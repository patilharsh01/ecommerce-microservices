#!/usr/bin/env bash
# End-to-end smoke test against a running stack (docker compose up -d --wait).
# Exercises every saga path through the real gateway, services, Kafka and Postgres.
#
#   ./scripts/e2e-smoke.sh                      # against http://localhost:8080
#   BASE_URL=http://gateway:8080 ./scripts/e2e-smoke.sh
#
# Needs curl and jq.
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
ADMIN_EMAIL="${ADMIN_EMAIL:-admin@shop.local}"
ADMIN_PASSWORD="${ADMIN_PASSWORD:-admin12345}"
SAGA_TIMEOUT_SECONDS="${SAGA_TIMEOUT_SECONDS:-60}"

pass=0
step() { printf '\n\033[1m▶ %s\033[0m\n' "$*"; }
ok() { pass=$((pass + 1)); printf '  \033[32m✔\033[0m %s\n' "$*"; }
fail() { printf '  \033[31m✘ %s\033[0m\n' "$*" >&2; exit 1; }

# api METHOD PATH [TOKEN] [BODY] [EXTRA_CURL_ARGS...] -> prints "<status>\n<body>"
api() {
  local method=$1 path=$2 token=${3:-} body=${4:-}
  shift 4 2>/dev/null || shift $#
  local args=(-s -o /tmp/e2e-body -w '%{http_code}' -X "$method" "$BASE_URL$path" -H 'Accept: application/json')
  [[ -n "$token" ]] && args+=(-H "Authorization: Bearer $token")
  [[ -n "$body" ]] && args+=(-H 'Content-Type: application/json' -d "$body")
  local status
  status=$(curl "${args[@]}" "$@")
  printf '%s\n' "$status"
  cat /tmp/e2e-body
}
status_of() { head -n1 <<<"$1"; }
body_of() { tail -n +2 <<<"$1"; }
expect_status() { [[ "$(status_of "$1")" == "$2" ]] || fail "$3: expected HTTP $2, got $(status_of "$1"): $(body_of "$1")"; ok "$3"; }

await_terminal() {
  local order_id=$1 token=$2 deadline=$((SECONDS + SAGA_TIMEOUT_SECONDS)) status
  while (( SECONDS < deadline )); do
    status=$(body_of "$(api GET "/api/orders/$order_id" "$token")" | jq -r .status)
    if [[ "$status" == "CONFIRMED" || "$status" == "CANCELLED" ]]; then
      echo "$status"
      return
    fi
    sleep 1
  done
  fail "order $order_id did not finish within ${SAGA_TIMEOUT_SECONDS}s (last status: $status)"
}

stock_of() { body_of "$(api GET "/api/products/$1")" | jq -r .stockQuantity; }

step "Waiting for the gateway and catalog"
for _ in $(seq 1 60); do
  [[ "$(curl -s -o /dev/null -w '%{http_code}' "$BASE_URL/api/products")" == "200" ]] && break
  sleep 2
done
expect_status "$(api GET /api/products)" 200 "anonymous catalog browsing"

step "Authentication"
admin=$(body_of "$(api POST /api/auth/login '' "{\"email\":\"$ADMIN_EMAIL\",\"password\":\"$ADMIN_PASSWORD\"}")" | jq -r .accessToken)
[[ "$admin" != "null" && -n "$admin" ]] || fail "admin login"
ok "admin signs in"
email="e2e-$(date +%s)-$RANDOM@shop.test"
registered=$(api POST /api/auth/register '' "{\"name\":\"E2E Customer\",\"email\":\"$email\",\"password\":\"e2e-password-1\",\"role\":\"ADMIN\"}")
expect_status "$registered" 201 "customer registers"
[[ "$(body_of "$registered" | jq -r .user.role)" == "CUSTOMER" ]] || fail "self-registration must never grant ADMIN"
ok "a role in the registration body is ignored"
customer=$(body_of "$registered" | jq -r .accessToken)

step "Edge security"
expect_status "$(api POST /api/orders '' '{"items":[{"productId":1,"quantity":1}]}')" 401 "placing an order needs a token"
expect_status "$(api GET /api/orders '' '' -H 'X-User-Id: 1' -H 'X-User-Roles: ADMIN')" 401 "spoofed identity headers are not a token"
expect_status "$(api GET /api/orders/stats "$customer")" 403 "customers can't read admin stats"
expect_status "$(api POST /api/orders bogus.token.value '{"items":[{"productId":1,"quantity":1}]}')" 401 "forged tokens are rejected"

step "Happy path: reserve → charge → confirm"
expect_status "$(api PUT /api/payments/simulation "$admin" '{"mode":"APPROVE_UNDER_LIMIT","approvalLimit":50000,"latencyMs":0}')" 200 "payment simulation reset"
product=$(body_of "$(api GET '/api/products?size=100')" | jq -r '[.content[] | select(.active and .stockQuantity >= 5 and .price < 20000)][0].id')
[[ "$product" != "null" ]] || fail "no affordable product with stock to order"
before=$(stock_of "$product")
key="e2e-$(date +%s)-$RANDOM"
# Right after a cold start order-service may not have discovered product-service yet and
# answers 503. Retrying with the same Idempotency-Key is safe, and what a real client does.
for _ in $(seq 1 30); do
  placed=$(api POST /api/orders "$customer" "{\"items\":[{\"productId\":$product,\"quantity\":2}]}" -H "Idempotency-Key: $key")
  [[ "$(status_of "$placed")" == "503" ]] || break
  sleep 2
done
expect_status "$placed" 201 "order accepted"
order=$(body_of "$placed" | jq -r .id)
[[ "$(await_terminal "$order" "$customer")" == "CONFIRMED" ]] || fail "order $order should be CONFIRMED"
ok "order $order confirmed"
[[ "$(stock_of "$product")" == "$((before - 2))" ]] || fail "stock should drop by 2"
ok "stock reserved ($before → $((before - 2)))"
[[ "$(body_of "$(api GET "/api/payments/orders/$order" "$customer")" | jq -r .status)" == "COMPLETED" ]] || fail "payment should be COMPLETED"
ok "payment captured"

step "Idempotency"
replay=$(curl -s -D - -o /tmp/e2e-replay -X POST "$BASE_URL/api/orders" -H "Authorization: Bearer $customer" \
  -H 'Content-Type: application/json' -H "Idempotency-Key: $key" -d "{\"items\":[{\"productId\":$product,\"quantity\":2}]}")
grep -qi '^idempotent-replayed: true' <<<"$replay" || fail "retry should be flagged Idempotent-Replayed"
[[ "$(jq -r .id /tmp/e2e-replay)" == "$order" ]] || fail "retry should return the original order"
ok "retry with the same key returns order $order instead of a duplicate"
expect_status "$(api POST /api/orders "$customer" "{\"items\":[{\"productId\":$product,\"quantity\":3}]}" -H "Idempotency-Key: $key")" 422 "reusing a key for a different order is rejected"

step "Compensation: payment declined → stock released"
expect_status "$(api PUT /api/payments/simulation "$admin" '{"mode":"DECLINE_ALL","approvalLimit":50000,"latencyMs":0}')" 200 "payments now decline"
before=$(stock_of "$product")
declined=$(body_of "$(api POST /api/orders "$customer" "{\"items\":[{\"productId\":$product,\"quantity\":1}]}")" | jq -r .id)
[[ "$(await_terminal "$declined" "$customer")" == "CANCELLED" ]] || fail "declined order should be CANCELLED"
detail=$(body_of "$(api GET "/api/orders/$declined" "$customer")")
[[ "$(jq -r .cancellationCause <<<"$detail")" == "PAYMENT_DECLINED" ]] || fail "cause should be PAYMENT_DECLINED"
jq -e '[.timeline[].step] | index("STOCK_RELEASED")' <<<"$detail" >/dev/null || fail "timeline should show STOCK_RELEASED"
[[ "$(stock_of "$product")" == "$before" ]] || fail "stock should be back to $before"
ok "order $declined cancelled and its stock returned ($before)"
expect_status "$(api PUT /api/payments/simulation "$admin" '{"mode":"APPROVE_UNDER_LIMIT","approvalLimit":50000,"latencyMs":0}')" 200 "payment simulation restored"

step "Rejection: not enough stock"
scarce=$(body_of "$(api POST /api/products "$admin" "{\"sku\":\"E2E-$RANDOM$RANDOM\",\"name\":\"E2E scarce item\",\"price\":10,\"stockQuantity\":1}")" | jq -r .id)
short=$(body_of "$(api POST /api/orders "$customer" "{\"items\":[{\"productId\":$scarce,\"quantity\":2}]}")" | jq -r .id)
[[ "$(await_terminal "$short" "$customer")" == "CANCELLED" ]] || fail "order for missing stock should be CANCELLED"
[[ "$(body_of "$(api GET "/api/orders/$short" "$customer")" | jq -r .cancellationCause)" == "OUT_OF_STOCK" ]] || fail "cause should be OUT_OF_STOCK"
[[ "$(stock_of "$scarce")" == "1" ]] || fail "nothing should have been reserved"
ok "order $short rejected without touching stock"

step "Customer cancellation while payment is slow"
expect_status "$(api PUT /api/payments/simulation "$admin" '{"mode":"APPROVE_ALL","approvalLimit":50000,"latencyMs":8000}')" 200 "payments now take 8 s"
slow=$(body_of "$(api POST /api/orders "$customer" "{\"items\":[{\"productId\":$product,\"quantity\":1}]}")" | jq -r .id)
for _ in $(seq 1 20); do
  [[ "$(body_of "$(api GET "/api/orders/$slow" "$customer")" | jq -r .status)" == "AWAITING_PAYMENT" ]] && break
  sleep 0.5
done
expect_status "$(api POST "/api/orders/$slow/cancel" "$customer")" 200 "cancel accepted"
[[ "$(await_terminal "$slow" "$customer")" == "CANCELLED" ]] || fail "cancelled order should end CANCELLED"
payment=$(body_of "$(api GET "/api/payments/orders/$slow" "$customer")" | jq -r .status)
[[ "$payment" == "REFUNDED" || "$payment" == "VOIDED" ]] || fail "late charge should be refunded or voided, was $payment"
ok "order $slow cancelled; its payment ended $payment"
expect_status "$(api PUT /api/payments/simulation "$admin" '{"mode":"APPROVE_UNDER_LIMIT","approvalLimit":50000,"latencyMs":0}')" 200 "payment simulation restored"

step "Ownership and notifications"
other=$(body_of "$(api POST /api/auth/register '' "{\"name\":\"Other\",\"email\":\"other-$RANDOM$RANDOM@shop.test\",\"password\":\"e2e-password-2\"}")" | jq -r .accessToken)
expect_status "$(api GET "/api/orders/$order" "$other")" 404 "another customer's order looks like it doesn't exist"
for _ in $(seq 1 20); do
  count=$(body_of "$(api GET "/api/notifications?orderId=$order" "$customer")" | jq '.content | length')
  (( count >= 2 )) && break
  sleep 0.5
done
(( count >= 2 )) || fail "expected placed + confirmed notifications, got $count"
ok "customer was notified ($count emails for order $order)"

printf '\n\033[32m%d checks passed\033[0m\n' "$pass"
