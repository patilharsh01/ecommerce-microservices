// Load test: customers placing orders at a constant rate, with a sample of sagas followed to
// completion to measure end-to-end saga latency (placement → CONFIRMED), not just the HTTP call.
//
// Run against the compose stack (results are written to load-test/results/):
//   docker run --rm -i --network ecommerce_backend -v "$PWD/load-test:/scripts" \
//     -e BASE_URL=http://api-gateway:8080 grafana/k6:1.2.0 run /scripts/place-orders.js
//
// Tunables: RATE (orders/s, default 30), DURATION (default 2m), CUSTOMERS (default 25).
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const RATE = Number(__ENV.RATE || 30);
const DURATION = __ENV.DURATION || '2m';
const CUSTOMERS = Number(__ENV.CUSTOMERS || 25);
const ADMIN_EMAIL = __ENV.ADMIN_EMAIL || 'admin@shop.local';
const ADMIN_PASSWORD = __ENV.ADMIN_PASSWORD || 'admin12345';

const sagaDuration = new Trend('saga_completion_ms', true);
const sagaOutcomes = new Counter('saga_outcomes');

export const options = {
  scenarios: {
    place_orders: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: Math.max(20, RATE * 2),
      maxVUs: RATE * 6,
    },
  },
  thresholds: {
    'http_req_failed{name:place-order}': ['rate<0.01'],
    'http_req_duration{name:place-order}': ['p(95)<500', 'p(99)<1000'],
    saga_completion_ms: ['p(95)<3000'],
    checks: ['rate>0.99'],
  },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

const json = (token) => ({
  headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
});

/** Right after `docker compose up`, the gateway needs ~30 s to discover services in Eureka. */
function waitForPlatform() {
  for (let attempt = 0; attempt < 60; attempt++) {
    const catalog = http.get(`${BASE_URL}/api/products?size=1`);
    const login = http.post(`${BASE_URL}/api/auth/login`, JSON.stringify({ email: ADMIN_EMAIL, password: ADMIN_PASSWORD }), json());
    if (catalog.status === 200 && login.status === 200) {
      return;
    }
    sleep(2);
  }
  throw new Error('platform did not become ready within 2 minutes');
}

export function setup() {
  waitForPlatform();
  const admin = http.post(`${BASE_URL}/api/auth/login`, JSON.stringify({ email: ADMIN_EMAIL, password: ADMIN_PASSWORD }), json())
    .json('accessToken');

  // Plenty of stock and the default card limit, so every saga should confirm: we are measuring
  // throughput and latency here, failure paths are covered by the e2e and integration tests.
  http.put(`${BASE_URL}/api/payments/simulation`, JSON.stringify({ mode: 'APPROVE_UNDER_LIMIT', approvalLimit: 50000, latencyMs: 0 }), json(admin));
  const products = http.get(`${BASE_URL}/api/products?size=100`).json('content')
    .filter((product) => product.active && product.price < 10000);
  for (const product of products) {
    http.post(`${BASE_URL}/api/products/${product.id}/stock-adjustments`, JSON.stringify({ delta: 1000000 - product.stockQuantity }), json(admin));
  }

  const run = Date.now();
  const tokens = [];
  for (let i = 0; i < CUSTOMERS; i++) {
    const response = http.post(`${BASE_URL}/api/auth/register`,
      JSON.stringify({ name: `Load ${i}`, email: `load-${run}-${i}@shop.test`, password: 'load-test-password' }), json());
    tokens.push(response.json('accessToken'));
    // Sign-up shares the per-IP limit of 5 requests/s with login; stay under it.
    sleep(0.25);
  }
  return { tokens, productIds: products.map((product) => product.id) };
}

export default function ({ tokens, productIds }) {
  const token = tokens[(__VU + __ITER) % tokens.length];
  const productId = productIds[__ITER % productIds.length];
  const params = json(token);
  params.headers['Idempotency-Key'] = `load-${__VU}-${__ITER}-${Date.now()}`;
  params.tags = { name: 'place-order' };

  const started = Date.now();
  const response = http.post(`${BASE_URL}/api/orders`, JSON.stringify({ items: [{ productId, quantity: 1 }] }), params);
  const accepted = check(response, { 'order accepted (201)': (r) => r.status === 201 });
  if (!accepted || __ITER % 10 !== 0) {
    return;
  }

  // Follow every tenth saga until it settles.
  const id = response.json('id');
  for (let attempt = 0; attempt < 100; attempt++) {
    sleep(0.1);
    const order = http.get(`${BASE_URL}/api/orders/${id}`, { ...json(token), tags: { name: 'poll-order' } });
    const status = order.status === 200 ? order.json('status') : null;
    if (status === 'CONFIRMED' || status === 'CANCELLED') {
      sagaDuration.add(Date.now() - started);
      sagaOutcomes.add(1, { outcome: status });
      check(order, { 'saga confirmed': () => status === 'CONFIRMED' });
      return;
    }
  }
  check(null, { 'saga finished within 10 s': () => false });
}

export function handleSummary(data) {
  const stamp = new Date().toISOString().replace(/[:.]/g, '-');
  return {
    stdout: textSummary(data),
    [`/scripts/results/summary-${stamp}.json`]: JSON.stringify(data, null, 2),
  };
}

function textSummary(data) {
  const metric = (name) => data.metrics[name]?.values ?? {};
  const placed = metric('http_req_duration{name:place-order}');
  const saga = metric('saga_completion_ms');
  const iterations = metric('iterations');
  const failed = metric('http_req_failed{name:place-order}');
  const fmt = (value) => (value === undefined ? 'n/a' : `${value.toFixed(1)} ms`);
  return [
    '',
    `orders placed           ${iterations.count ?? 0} (target ${RATE}/s for ${DURATION})`,
    `place-order failures    ${((failed.rate ?? 0) * 100).toFixed(2)}%`,
    `place-order latency     p50 ${fmt(placed.med)}  p95 ${fmt(placed['p(95)'])}  p99 ${fmt(placed['p(99)'])}`,
    `saga completion         p50 ${fmt(saga.med)}  p95 ${fmt(saga['p(95)'])}  p99 ${fmt(saga['p(99)'])}`,
    `thresholds              ${Object.values(data.metrics).some((m) => m.thresholds && Object.values(m.thresholds).some((t) => !t.ok)) ? 'FAILED' : 'passed'}`,
    '',
  ].join('\n');
}
