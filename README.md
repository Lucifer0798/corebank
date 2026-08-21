# CoreBank Lite

A retail banking account and transaction platform, built the way a core banking backend
actually works: every rupee that moves is recorded as a balanced double-entry posting, money
movement is safe to retry, and nothing is ever edited after it has been booked.

Phase 1 is the backend: Java 21, Spring Boot, PostgreSQL, Spring Security, Docker, JUnit and
an OpenAPI document you can drive the whole system from.

---

## What it does

| Capability | Detail |
| --- | --- |
| Customer onboarding | Create customers, run a KYC decision. An unverified customer cannot hold an account. |
| Accounts | Savings and current accounts, opened at a zero balance. Current accounts may carry an overdraft. |
| Money movement | Deposits, withdrawals and internal transfers, each posted as two balanced ledger legs. |
| Idempotency | Every money-moving `POST` requires an `Idempotency-Key`. Retries never post twice. |
| Statements | Paginated account history, newest first, signed from that account's point of view. |
| Security | Stateless JWT auth with three roles. Customers can read only their own accounts. |
| Errors | RFC 7807 problem documents with a stable machine-readable `code` on every failure. |
| Docs | Swagger UI at `/swagger-ui.html`, OpenAPI JSON at `/v3/api-docs`. |

---

## Running it

### The fastest path — no database needed

The `dev` profile runs against an in-memory H2 database in PostgreSQL compatibility mode and
seeds a walkable example on startup.

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

Then open <http://localhost:8080/swagger-ui.html>.

Seeded logins:

| Username | Password | Role | Sees |
| --- | --- | --- | --- |
| `admin` | `ChangeMe#2025!` | ADMIN | Everything, including KYC decisions and user creation |
| `teller1` | `Teller#2025` | TELLER | Every customer and account; moves money |
| `asha` | `Customer#2025` | CUSTOMER | Only their own accounts |

### With PostgreSQL, via Docker

```bash
docker compose up --build
```

This starts PostgreSQL and the application together, waits for the database to report ready,
and applies the Flyway migrations on startup. The API is on <http://localhost:8080>.

The database is published on host port **5433**, not 5432, so the stack does not collide with
a PostgreSQL already installed on the machine. Override it with `POSTGRES_HOST_PORT` if you
prefer another port; the application container reaches the database at `postgres:5432` over
the compose network either way.

For the local environment underneath all of this — installing PostgreSQL without
administrator rights, the Docker/WSL2 prerequisites, the port layout and troubleshooting —
see [docs/LOCAL_SETUP.md](docs/LOCAL_SETUP.md).

### Running the tests

```bash
./mvnw test
```

47 tests: unit tests for the balance and double-entry rules, and a full end-to-end journey
through the real HTTP stack against a real database.

---

## Trying it from the command line

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"teller1","password":"Teller#2025"}' | jq -r .accessToken)
```

```bash
curl -s -X POST http://localhost:8080/api/v1/accounts/$ACCOUNT_ID/deposits \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Idempotency-Key: deposit-0001' \
  -H 'Content-Type: application/json' \
  -d '{"amount":2500.00,"currency":"INR","description":"Branch counter deposit"}'
```

Send that second request again with the same key and the same body: you get the original
transaction back and the header `Idempotency-Replayed: true`. The balance does not move.
Send it with the same key but a different body and you get `409 IDEMPOTENCY_KEY_REUSED`.

---

## How it is built

### Double-entry, not a balance column

A balance is not a number the API edits. It is the consequence of a posting.

Every transaction writes at least two `ledger_entry` rows whose debits and credits sum to
zero, and the posting is rejected before it reaches the database if they do not. Accounts
carry a `normal_balance`, which is what makes one direction mean "more" on one account and
"less" on another:

- A **customer account** is a liability — the bank owes the customer — so its normal balance
  is `CREDIT`. Money in credits it.
- The **cash general ledger account** (`GL0000000001`) is an asset, so its normal balance is
  `DEBIT`. Money in debits it.

So a deposit debits cash and credits the customer. A withdrawal does the reverse. A transfer
between two customer accounts never touches cash at all. That single rule — in
`Account.applyEntry` — is why the arithmetic stays right without special cases per operation.

Ledger entries are append-only. A correction is a new reversing transaction, never an update.

### Idempotency

`Idempotency-Key` is required on deposits, withdrawals and transfers.

The first request inserts a claim row in its **own committed transaction**. A unique
constraint on `(scope, key)` is what actually serialises concurrent duplicates — the second
insert loses and reads the winner's outcome rather than posting again. On success the
response is stored and replayed verbatim; on failure the claim is released, so a genuine
retry after an insufficient-funds error still works.

Reusing a key with a different body returns `409` rather than quietly doing something the
caller did not ask for.

### Concurrency

Money movement loads its accounts with `SELECT … FOR UPDATE`, so two postings against the
same account serialise at the database rather than racing on a stale in-memory balance.
Transfers take both row locks in a fixed order, so a simultaneous transfer in the opposite
direction waits instead of deadlocking. Every mutable entity also carries a `@Version`
column, so a concurrent overwrite fails loudly as `409 CONCURRENT_MODIFICATION`.

### Money

`BigDecimal` throughout, stored as `NUMERIC(19,4)` and presented at a scale of 2. The extra
storage scale is headroom for interest and fee calculations in a later phase. Binary floating
point never touches an amount.

### Errors

Every failure — validation, business rule, authorisation, or unexpected — comes back as an
RFC 7807 problem document with the same shape and a stable `code`:

```json
{
  "type": "https://corebank.example/problems/insufficient-funds",
  "title": "Unprocessable Entity",
  "status": 422,
  "detail": "Account 100100000003 has 3800.00 available but 99999.00 was requested",
  "code": "INSUFFICIENT_FUNDS",
  "timestamp": "2025-04-17T10:15:30Z"
}
```

Rejections from the security filter chain never reach a controller, so they are formatted the
same way by a dedicated handler rather than falling back to a differently shaped body.

### Security

Stateless JWT. Login exchanges credentials for a short-lived HS256 token carrying the user's
roles and, for self-service logins, the customer they belong to. Passwords are BCrypt hashes.

| Role | May |
| --- | --- |
| `CUSTOMER` | Read their own accounts and statements |
| `TELLER` | Onboard customers, open accounts, move money |
| `ADMIN` | Everything, plus KYC decisions, closing accounts and creating logins |

Ownership is enforced with `@PreAuthorize` against a bean that compares the account's owner
to the `customerId` claim, so a customer reading someone else's account gets `403`, not data.

The token is validated as an OAuth2 resource server. Moving to Keycloak or Cognito in a later
phase means replacing two beans with an issuer URI — the authorisation rules and the role
mapping stay exactly as they are.

---

## API

| Method | Path | Role | Purpose |
| --- | --- | --- | --- |
| `POST` | `/api/v1/auth/login` | — | Exchange credentials for a bearer token |
| `POST` | `/api/v1/auth/users` | ADMIN | Create a staff or self-service login |
| `GET` | `/api/v1/auth/me` | any | Describe the caller behind the current token |
| `POST` | `/api/v1/customers` | TELLER, ADMIN | Onboard a customer |
| `GET` | `/api/v1/customers` | TELLER, ADMIN | List customers |
| `GET` | `/api/v1/customers/{id}` | TELLER, ADMIN | Fetch one customer |
| `PATCH` | `/api/v1/customers/{id}/kyc` | ADMIN | Record a KYC decision |
| `POST` | `/api/v1/accounts` | TELLER, ADMIN | Open an account |
| `GET` | `/api/v1/accounts/{id}` | owner, staff | Fetch one account |
| `GET` | `/api/v1/accounts/{id}/balance` | owner, staff | Current and available balance |
| `GET` | `/api/v1/customers/{id}/accounts` | owner, staff | Accounts a customer holds |
| `POST` | `/api/v1/accounts/{id}/freeze` | TELLER, ADMIN | Freeze an account |
| `POST` | `/api/v1/accounts/{id}/unfreeze` | TELLER, ADMIN | Return it to service |
| `POST` | `/api/v1/accounts/{id}/close` | ADMIN | Close a zero-balance account |
| `POST` | `/api/v1/accounts/{id}/deposits` | TELLER, ADMIN | Deposit — needs `Idempotency-Key` |
| `POST` | `/api/v1/accounts/{id}/withdrawals` | TELLER, ADMIN | Withdraw — needs `Idempotency-Key` |
| `POST` | `/api/v1/transfers` | TELLER, ADMIN | Transfer — needs `Idempotency-Key` |
| `GET` | `/api/v1/accounts/{id}/transactions` | owner, staff | Statement, newest first |
| `GET` | `/api/v1/transactions/{reference}` | TELLER, ADMIN | One transaction and both legs |

### Error codes

| Code | Status | Meaning |
| --- | --- | --- |
| `VALIDATION_FAILED` | 400 | Request body failed validation; see `errors` |
| `MISSING_HEADER` | 400 | A required header, usually `Idempotency-Key`, was absent |
| `UNAUTHENTICATED` | 401 | No valid bearer token |
| `AUTHENTICATION_FAILED` | 401 | Wrong username or password |
| `ACCESS_DENIED` | 403 | Authenticated, but not allowed |
| `RESOURCE_NOT_FOUND` | 404 | No such customer, account or transaction |
| `IDEMPOTENCY_KEY_REUSED` | 409 | Same key, different body |
| `REQUEST_IN_PROGRESS` | 409 | An identical request is still running |
| `CONCURRENT_MODIFICATION` | 409 | Optimistic lock lost; retry |
| `USERNAME_TAKEN` / `EMAIL_TAKEN` | 409 | Already in use |
| `INSUFFICIENT_FUNDS` | 422 | Available balance, including overdraft, is too low |
| `ACCOUNT_FROZEN` / `ACCOUNT_CLOSED` | 422 | The account cannot take postings |
| `CUSTOMER_NOT_ELIGIBLE` | 422 | Not active, or KYC not verified |
| `CURRENCY_MISMATCH` | 422 | The account is held in another currency |
| `SAME_ACCOUNT_TRANSFER` | 422 | Source and destination are the same account |
| `OVERDRAFT_NOT_ALLOWED` | 422 | Savings accounts cannot carry an overdraft |
| `BALANCE_NOT_ZERO` | 422 | An account must be emptied before it is closed |
| `INTERNAL_ACCOUNT` | 422 | General-ledger accounts are not addressable here |

---

## Layout

```
src/main/java/com/corebank/
├── auth/          Login, JWT issuing, users and roles
├── account/       Accounts, balances, ownership checks
├── customer/      Onboarding and KYC
├── transaction/   Postings, the ledger, statements
├── idempotency/   Replay protection for money movement
├── common/        Money, audit columns, errors, sequences
└── config/        Security, OpenAPI, properties, bootstrap data
```

Each slice keeps its own `domain`, `repository`, `service`, `web` and `dto` packages, so a
feature is one directory rather than a trail through five technical layers.

The schema lives in `src/main/resources/db/migration` as Flyway migrations, written in
portable SQL so the same files run on PostgreSQL and on H2 for tests. Hibernate is set to
`validate`, so a mapping that drifts from the schema fails at startup rather than at runtime.

---

## Configuration

| Variable | Default | Notes |
| --- | --- | --- |
| `COREBANK_DB_URL` | `jdbc:postgresql://localhost:5432/corebank` | |
| `COREBANK_DB_USER` | `corebank` | |
| `COREBANK_DB_PASSWORD` | `corebank` | |
| `COREBANK_JWT_SECRET` | a development value | **Set this.** At least 32 bytes; HS256 signing key |
| `COREBANK_ADMIN_PASSWORD` | `ChangeMe#2025!` | Only used to create the first `admin` login when the user table is empty |
| `SERVER_PORT` | `8080` | |

The defaults exist so the project starts on a laptop with no setup. None of them are
appropriate anywhere else.

---

## Phase 1 scope, and what is deliberately not here

Built: Java 21 · Spring Boot · Spring Security · Hibernate · PostgreSQL · REST ·
OpenAPI/Swagger · Docker · JUnit · Git.

Not yet, and left for later phases by design: Redis, Kafka, gRPC, Keycloak/OIDC, Kubernetes,
Terraform, AWS, OpenTelemetry/Prometheus/Grafana, Testcontainers, k6, and the frontend. The
seams they will attach to already exist — resource-server security for OIDC, an append-only
ledger for event publishing, and a stateless application for horizontal scaling.
