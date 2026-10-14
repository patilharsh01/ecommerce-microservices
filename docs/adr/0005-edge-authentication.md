# 5. Authentication at the edge with RS256

**Status:** accepted (replaces a shared HS256 secret)

## Context

v1 signed tokens with an HMAC secret shared by auth-service and the gateway, so anything that could verify a token
could also mint one. The gateway added `X-User-Id` from the token but didn't remove a client-supplied one on public
routes, and every service published its port, so the gateway could be bypassed with any `X-User-Id` you liked.
Registration accepted a `role` field, so anyone could sign up as `ADMIN`.

## Decision

* **auth-service signs RS256 tokens** (`iss`, `aud`, `sub`, `roles`, `jti`, 60 min expiry). The private key never
  leaves it; it is generated once and persisted to a volume. The public key is published as a JWK Set at
  `/.well-known/jwks.json`, with the RFC 7638 thumbprint as key id.
* **The gateway is an OAuth2 resource server.** It fetches the JWK Set through the load balancer and validates
  signature, expiry, issuer and audience. A token with an unknown key id triggers a refetch, so keys can rotate without
  restarting the gateway.
* **Identity is re-derived, never trusted.** A global filter removes `X-User-Id`, `X-User-Roles` and `Authorization`
  from every request, then sets the identity headers from the verified token. Services read them through
  `AuthenticatedUser`, which is resolved for them.
* **Services aren't reachable from outside.** Only the gateway, UI and tooling publish ports; actuator runs on a
  separate management port that isn't published.
* **Coarse rules at the edge, fine rules in the services.** The gateway decides "must be signed in". Services decide
  "admin only" or "your own orders only", next to the data. A resource owned by someone else returns 404, not 403.
* Self-registration ignores any role and always creates a customer; the first admin comes from configuration.

## Consequences

* A leaked gateway configuration can't be used to forge tokens.
* Services stay simple: no security filter chain, no key distribution. The trade-off is that they rely on network
  isolation, which a service mesh with mTLS would harden in production.
* Tokens stay valid until they expire (no revocation list). Short lifetimes plus refresh tokens would be the next step.
