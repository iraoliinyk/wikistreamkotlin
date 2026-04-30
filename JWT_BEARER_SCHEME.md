# JWT & Bearer Token — Authentication Scheme

## Overview

This project uses **stateless JWT (JSON Web Tokens)** signed with **HMAC-SHA256 (HS256)** as the
authentication mechanism, delivered via the HTTP **Bearer** scheme.  
A supplementary **token revocation** layer is implemented on top using **Apache Cassandra**.

---

## 1. What Are These Two Things?

| Concept | Definition |
|---|---|
| **JWT** | A self-contained, cryptographically signed token that encodes claims (who you are, when the token expires, what you can do). No server-side session is needed. |
| **Bearer token** | An HTTP authorization scheme (`Authorization: Bearer <token>`). The server trusts *whoever bears* (carries) the token. JWT is merely the *format* of that token here. |

---

## 2. Token Structure (JWT)

A JWT is three Base64-URL-encoded parts separated by `.`:

```
eyJhbGciOiJIUzI1NiJ9   ←  Header  (algorithm: HS256)
.
eyJpc3MiOiJ3aWtpc3RyZWFta290bGluIiwic3ViIjoidXNlckBleGFtcGxlLmNvbSIsImp0aSI6InV1aWQiLCJzY29wZSI6InN0YXRzOnJlYWQiLCJleHAiOjE3MTQzMDAwMDB9
                        ←  Payload (claims below)
.
<HMAC-SHA256 signature> ←  Signature (server-side secret, ≥32 bytes)
```

### Claims stored in this project

| Claim | Value | Purpose |
|---|---|---|
| `iss` | `wikistreamkotlin` | Issuer — validated on every request |
| `sub` | `user@example.com` | Subject (the authenticated user) |
| `jti` | Random UUID | **Unique token ID** — used for revocation |
| `scope` | `stats:read` | Granted permission |
| `iat` | Unix timestamp | Issued-at time |
| `exp` | `iat + TTL (3600 s)` | Expiry — Spring rejects expired tokens automatically |

---

## 3. Auth Endpoints (public — no token required)

```
POST /v1/auth/register   →  Create account   (email + password)
POST /v1/auth/login      →  Obtain JWT        (returns access_token + expires_in)
GET  /v1/auth/email-exists → Check email availability
GET  /v1/status          →  Health check
```

Every other endpoint requires `Authorization: Bearer <jwt>`.

---

## 4. Full Request Lifecycle

### 4a. Login → get a JWT

```
Client                         AuthController / AuthService         Cassandra
  │                                     │                               │
  │──POST /v1/auth/login ──────────────►│                               │
  │  { email, password }                │                               │
  │                                     │── findById(email) ───────────►│
  │                                     │◄── UserAccount ───────────────│
  │                                     │                               │
  │                                     │  BCrypt.matches(password, hash)
  │                                     │                               │
  │                                     │  JwtTokenService.createAccessToken(email)
  │                                     │  ┌─────────────────────────────────────┐
  │                                     │  │ Header:  { alg: HS256 }             │
  │                                     │  │ Payload: { iss, sub, jti(UUID),     │
  │                                     │  │           scope, iat, exp }          │
  │                                     │  │ Signature: HMAC-SHA256(secret)      │
  │                                     │  └─────────────────────────────────────┘
  │◄── 200 { access_token, expires_in } │
```

### 4b. Authenticated request

```
Client                    Spring Security                 RevokedTokenWebFilter   Cassandra
  │                            │                                  │                   │
  │── GET /v1/... ────────────►│                                  │                   │
  │  Authorization:            │                                  │                   │
  │  Bearer <jwt>              │                                  │                   │
  │                            │  1. NimbusReactiveJwtDecoder      │                   │
  │                            │     • Verify HS256 signature      │                   │
  │                            │     • Validate exp / iss          │                   │
  │                            │     • Build JwtAuthenticationToken│                   │
  │                            │                                  │                   │
  │                            │  2. addFilterAfter(AUTHENTICATION)│                   │
  │                            │────────────────────────────────► │                   │
  │                            │                                  │── findById(jti) ──►│
  │                            │                                  │◄── RevokedToken? ──│
  │                            │                                  │                   │
  │                            │          [token IS revoked]      │                   │
  │◄── 401 Unauthorized ───────│◄─────────────────────────────────│                   │
  │                            │                                  │                   │
  │                            │          [token NOT revoked]     │                   │
  │                            │◄─────────────────────────────────│                   │
  │                            │  3. Proceed to controller         │                   │
  │◄── 200 + response ─────────│                                  │                   │
```

### 4c. Logout → revoke the JWT

```
Client                    AuthController / AuthService         Cassandra
  │                                │                               │
  │──POST /v1/auth/logout ────────►│                               │
  │  Authorization: Bearer <jwt>   │                               │
  │                                │  extract jti from JWT         │
  │                                │── save RevokedToken ─────────►│
  │                                │   { jti, email,               │
  │                                │     expires_at, revoked_at }  │
  │◄── 200 OK ─────────────────────│                               │
```

After logout, any request that re-uses the same token is blocked by
`RevokedTokenWebFilter` — even though the JWT signature is still valid and not yet expired.

---

## 5. Component Map

```
┌──────────────────────────────────────────────────────────────────┐
│  SecurityConfig                                                  │
│  ┌──────────────────────┐   ┌───────────────────────────────┐   │
│  │  JwtEncoder          │   │  ReactiveJwtDecoder            │   │
│  │  (NimbusJwtEncoder)  │   │  (NimbusReactiveJwtDecoder)    │   │
│  │  HS256 + secret      │   │  HS256 + issuer validation     │   │
│  └──────────┬───────────┘   └────────────────┬──────────────┘   │
│             │                                │                   │
│  ┌──────────▼───────────┐   ┌────────────────▼──────────────┐   │
│  │  JwtTokenService     │   │  oauth2ResourceServer (DSL)    │   │
│  │  createAccessToken() │   │  → JwtAuthenticationConverter  │   │
│  │  extractJti()        │   └───────────────────────────────┘   │
│  └──────────────────────┘                                        │
│                                                                  │
│  ┌───────────────────────────────────────────────────────────┐   │
│  │  RevokedTokenWebFilter  (runs AFTER authentication)        │   │
│  │  • reads jti from JwtAuthenticationToken                   │   │
│  │  • queries Cassandra revoked_tokens table                  │   │
│  │  • returns 401 if found with future expires_at             │   │
│  └───────────────────────────────────────────────────────────┘   │
└──────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────┐
│  Cassandra Tables                                                │
│  ┌──────────────────────┐   ┌───────────────────────────────┐   │
│  │  user_accounts        │   │  revoked_tokens               │   │
│  │  PK: email            │   │  PK: jti (UUID)               │   │
│  │  password_hash        │   │  email                        │   │
│  │  active               │   │  expires_at                   │   │
│  │  created_at           │   │  revoked_at                   │   │
│  └──────────────────────┘   └───────────────────────────────┘   │
└──────────────────────────────────────────────────────────────────┘
```

---

## 6. Security Properties (`application.properties`)

```
app.security.jwt.issuer              = wikistreamkotlin
app.security.jwt.secret              = <≥32-byte random string>
app.security.jwt.access-token-ttl-seconds = 3600   # 1 hour
```

> **Important:** The secret is symmetric (same key signs and verifies).  
> It must never be committed to source control — use `config/auth-secrets.properties.template`
> and supply real values via environment variables or a secrets manager.

---

## 7. Why a Revocation Table?

JWTs are stateless by design — once issued, the server cannot "cancel" them without extra state.
The `revoked_tokens` Cassandra table solves this:

| Scenario | Behaviour |
|---|---|
| Normal request (token valid, not revoked) | Passes through, reaches controller |
| Expired token | Rejected by Spring's `ReactiveJwtDecoder` before the filter runs |
| Logged-out token (valid signature, not expired) | Blocked by `RevokedTokenWebFilter` (401) |
| Cassandra unavailable (`repository == null`) | Filter is bypassed gracefully — no crash |

Rows in `revoked_tokens` only need to live until `expires_at`; at that point the JWT would
be rejected by expiry anyway, so old rows can be pruned by a TTL policy in Cassandra.

