import { useEffect, useState } from 'react';
import { Link } from 'react-router';
import { ApiError } from '../../api/client';
import { api } from '../../api/endpoints';
import { useProducts, useSimulation, useUpdateSimulation } from '../../api/hooks';
import type { Order, PaymentSimulation, SimulationMode } from '../../api/types';
import { ErrorMessage, Money, PageHeader, Spinner, StatusBadge } from '../../components/ui';
import { formatDuration, newIdempotencyKey } from '../../lib/format';
import { isTerminal } from '../../lib/orders';

const MODES: { value: SimulationMode; label: string; effect: string }[] = [
  { value: 'APPROVE_UNDER_LIMIT', label: 'Card limit', effect: 'Charges above the limit are declined → stock is released, order cancelled.' },
  { value: 'APPROVE_ALL', label: 'Approve everything', effect: 'Every charge succeeds.' },
  { value: 'DECLINE_ALL', label: 'Decline everything', effect: 'Every order is compensated after stock was reserved.' },
];

export function FailureLabPage() {
  return (
    <>
      <PageHeader title="Failure lab"
                  subtitle="Break things on purpose and watch the saga compensate. Changes affect every customer immediately." />
      <div className="two-column">
        <PaymentSimulationCard />
        <StockRaceCard />
      </div>
    </>
  );
}

function PaymentSimulationCard() {
  const simulation = useSimulation();
  const update = useUpdateSimulation();
  const [draft, setDraft] = useState<PaymentSimulation | null>(null);

  useEffect(() => {
    if (simulation.data) setDraft(simulation.data);
  }, [simulation.data]);

  if (simulation.isPending || !draft) {
    return <section className="card"><Spinner label="Loading payment simulation" /></section>;
  }

  const dirty = JSON.stringify(draft) !== JSON.stringify(simulation.data);
  const slowerThanTimeout = draft.latencyMs >= 30_000;

  return (
    <section className="card">
      <h2>Payment gateway</h2>
      <p className="muted">The simulated card processor that payment-service calls.</p>
      <ErrorMessage error={simulation.error ?? update.error} title="Payment simulation" />

      <fieldset className="radio-group">
        <legend>Behaviour</legend>
        {MODES.map((mode) => (
          <label key={mode.value} className={`radio-card ${draft.mode === mode.value ? 'radio-card-selected' : ''}`}>
            <input type="radio" name="mode" value={mode.value} checked={draft.mode === mode.value}
                   onChange={() => setDraft({ ...draft, mode: mode.value })} />
            <span><strong>{mode.label}</strong><small className="muted">{mode.effect}</small></span>
          </label>
        ))}
      </fieldset>

      {draft.mode === 'APPROVE_UNDER_LIMIT' && (
        <label className="field">
          <span>Card limit (₹)</span>
          <input type="number" min={1} step={100} value={draft.approvalLimit}
                 onChange={(event) => setDraft({ ...draft, approvalLimit: Number(event.target.value) })} />
        </label>
      )}

      <label className="field">
        <span>Gateway latency: <strong>{formatDuration(draft.latencyMs)}</strong></span>
        <input type="range" min={0} max={60_000} step={500} value={draft.latencyMs}
               onChange={(event) => setDraft({ ...draft, latencyMs: Number(event.target.value) })} />
      </label>
      {slowerThanTimeout && (
        <p className="hint hint-warn">
          Slower than the 30 s saga step timeout: orders will time out, release their stock, and the charge that
          eventually succeeds will be refunded automatically. Watch the timeline of a new order.
        </p>
      )}

      <div className="button-row">
        <button type="button" className="button" disabled={!dirty || update.isPending} onClick={() => update.mutate(draft)}>
          {update.isPending ? 'Applying…' : 'Apply'}
        </button>
        <button type="button" className="button button-ghost"
                onClick={() => update.mutate({ mode: 'APPROVE_UNDER_LIMIT', approvalLimit: 50000, latencyMs: 0 })}>
          Reset to defaults
        </button>
      </div>
    </section>
  );
}

interface RaceResult {
  orders: Order[];
  failures: string[];
  elapsedMs: number;
}

/**
 * Fires many orders for the same product at once. With stock S, exactly S succeed and the
 * rest are rejected by inventory — the database row lock and CHECK constraint mean the
 * last unit can't be sold twice however the requests interleave.
 */
function StockRaceCard() {
  const products = useProducts('', false);
  const [productId, setProductId] = useState<number | null>(null);
  const [concurrency, setConcurrency] = useState(20);
  const [running, setRunning] = useState(false);
  const [result, setResult] = useState<RaceResult | null>(null);
  const [error, setError] = useState<unknown>(null);
  const selected = products.data?.content.find((product) => product.id === productId) ?? products.data?.content[0];

  async function race() {
    if (!selected) return;
    setRunning(true);
    setError(null);
    setResult(null);
    const started = performance.now();
    const attempts = await Promise.allSettled(Array.from({ length: concurrency }, () =>
      api.placeOrder([{ productId: selected.id, quantity: 1 }], newIdempotencyKey())));
    const placed = attempts.flatMap((attempt) => attempt.status === 'fulfilled' ? [attempt.value.order] : []);
    const failures = attempts.flatMap((attempt) => attempt.status === 'rejected'
      ? [attempt.reason instanceof ApiError ? `${attempt.reason.status} ${attempt.reason.message}` : String(attempt.reason)] : []);
    setResult({ orders: placed, failures, elapsedMs: performance.now() - started });
    try {
      // Follow the sagas to their outcome.
      for (let round = 0; round < 60; round++) {
        const latest = await Promise.all(placed.map((order) => api.order(order.id)));
        setResult({ orders: latest, failures, elapsedMs: performance.now() - started });
        if (latest.every((order) => isTerminal(order.status))) break;
        await new Promise((resolve) => setTimeout(resolve, 1000));
      }
    } catch (caught) {
      setError(caught);
    } finally {
      setRunning(false);
    }
  }

  const confirmed = result?.orders.filter((order) => order.status === 'CONFIRMED').length ?? 0;
  const outOfStock = result?.orders.filter((order) => order.cancellationCause === 'OUT_OF_STOCK').length ?? 0;

  return (
    <section className="card">
      <h2>Stock race</h2>
      <p className="muted">Many customers try to buy the last units at the same instant.</p>
      {products.isPending && <Spinner label="Loading products" />}
      {selected && (
        <>
          <div className="form-grid">
            <label className="field">
              <span>Product</span>
              <select value={selected.id} onChange={(event) => setProductId(Number(event.target.value))}>
                {products.data?.content.map((product) => (
                  <option key={product.id} value={product.id}>{product.name} — {product.stockQuantity} in stock</option>
                ))}
              </select>
            </label>
            <label className="field">
              <span>Concurrent orders</span>
              <input type="number" min={2} max={50} value={concurrency}
                     onChange={(event) => setConcurrency(Math.min(50, Math.max(2, Number(event.target.value))))} />
            </label>
          </div>
          <p className="hint">
            {selected.stockQuantity} unit(s) of {selected.name} at <Money amount={selected.price} />: expect exactly{' '}
            {Math.min(selected.stockQuantity, concurrency)} confirmed and {Math.max(0, concurrency - selected.stockQuantity)} rejected.
          </p>
          <button type="button" className="button" disabled={running} onClick={race}>
            {running ? 'Racing…' : `Fire ${concurrency} orders at once`}
          </button>
        </>
      )}
      <ErrorMessage error={error} title="Race failed" />
      {result && (
        <div className="race-result">
          <p>
            <strong>{confirmed}</strong> confirmed · <strong>{outOfStock}</strong> rejected for stock ·{' '}
            {result.orders.length - confirmed - outOfStock} other · {result.failures.length} request error(s) ·{' '}
            {formatDuration(result.elapsedMs)}
          </p>
          <ul className="race-orders">
            {result.orders.map((order) => (
              <li key={order.id}><Link to={`/orders/${order.id}`}>#{order.id}</Link> <StatusBadge status={order.status} /></li>
            ))}
          </ul>
          {result.failures.length > 0 && (
            <details><summary>Request errors</summary><ul>{result.failures.map((failure, index) => <li key={index}>{failure}</li>)}</ul></details>
          )}
        </div>
      )}
    </section>
  );
}
