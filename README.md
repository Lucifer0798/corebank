# CoreBank Lite

[![CI](https://github.com/Lucifer0798/corebank/actions/workflows/ci.yml/badge.svg)](https://github.com/Lucifer0798/corebank/actions/workflows/ci.yml)
[![CodeQL](https://github.com/Lucifer0798/corebank/actions/workflows/codeql.yml/badge.svg)](https://github.com/Lucifer0798/corebank/actions/workflows/codeql.yml)

A retail banking account and transaction platform, built the way a core banking backend
actually works: every rupee that moves is recorded as a balanced double-entry posting, money
movement is safe to retry, and nothing is ever edited after it has been booked.

Phase 1 shipped the backend. Phase 2 added Keycloak as the identity provider, Redis as a caching
layer, Kafka as an event feed of every posting, and a React frontend that drives the whole
system through a browser instead of curl. Phase 3 adds metrics and distributed tracing
(OpenTelemetry, Prometheus, Grafana), a CI pipeline (GitHub Actions, CodeQL), and static/image
security scanning (SonarQube, Trivy). Phase 4 adds a real-infrastructure test suite
(Testcontainers, REST Assured), a k6 load test for money movement, and a Kubernetes deployment
verified against a local `kind` cluster. Phase 5 adds bank-wide search: OpenSearch, fed by the
same Kafka events Phase 2 already publishes, behind two new endpoints under `/api/v1/search`.

---

## What it does

| Capability | Detail |
| --- | --- |
| Customer onboarding | Create customers, run a KYC decision. An unverified customer cannot hold an account. |
| Accounts | Savings and current accounts, opened at a zero balance. Current accounts may carry an overdraft. |
| Money movement | Deposits, withdrawals and internal transfers, each posted as two balanced ledger legs. |
| Idempotency | Every money-moving `POST` requires an `Idempotency-Key`. Retries never post twice. |
| Statements | Paginated account history, newest first, signed from that account's point of view. |
| Security | Keycloak-issued JWTs, three realm roles. Customers can read only their own accounts. |
| Caching | Account detail reads go through Redis with a short TTL; a Redis outage just means no caching. |
| Events | Every posted transaction is published to Kafka once its database transaction commits. |
| Search | Bank-wide, cross-account transaction and customer search (OpenSearch), fed by the same Kafka events -- not the per-account statement or unfiltered customer list, both Postgres-backed. |
| Errors | RFC 7807 problem documents with a stable machine-readable `code` on every failure. |
| Frontend | A React SPA: customer onboarding and KYC, account opening, deposits/withdrawals/transfers, statements. |
| Observability | Every request traced end to end (OpenTelemetry/Tempo); business and platform metrics in Grafana. |
| CI | Every push builds and tests the backend and frontend, scans the Docker image with Trivy, and runs CodeQL. |
| Docs | Swagger UI at `/swagger-ui.html`, OpenAPI JSON at `/v3/api-docs`. |

---

## Running it

### The full stack, via Docker

```bash
docker compose up --build
```

Brings up PostgreSQL, Keycloak, Redis, Kafka, Kafka UI, OpenSearch, Prometheus, Tempo, Grafana,
and the application together. First build takes a few minutes. Once it's up:

| | |
| --- | --- |
| API | <http://localhost:8080>, Swagger UI at `/swagger-ui.html` |
| Keycloak admin console | <http://localhost:8081> (`admin` / `admin`) |
| Kafka UI | <http://localhost:8082> — watch `corebank.transactions.posted` fill up as you post transactions |
| OpenSearch | <http://localhost:9200> — `curl localhost:9200/corebank-transactions/_search` to see the raw documents |
| Grafana | <http://localhost:3000> (no login needed) — the **CoreBank Overview** dashboard, and every request's trace under Explore → Tempo |
| Prometheus | <http://localhost:9090> |
| PostgreSQL | `localhost:5433` (not 5432 — see below) |
| Redis | `localhost:6379` |

The database is published on **5433**, not 5432, so the stack does not collide with a
PostgreSQL already installed on the host. Override it with `POSTGRES_HOST_PORT` if you prefer
another port; the application container reaches the database at `postgres:5432` over the
compose network either way.

### The frontend

```bash
cd frontend
npm install
npm run dev
```

Opens on <http://localhost:5173>. It talks to Keycloak directly for login (never through the
backend) and to the API at `localhost:8080`; both need to already be running. See
[frontend/.env.example](frontend/.env.example) if either is running somewhere else.

### The backend alone, against a host PostgreSQL

```bash
./mvnw spring-boot:run
```

Authenticated endpoints still need Keycloak, Redis and Kafka reachable — start just those three
from Compose (`docker compose up -d keycloak redis kafka`) alongside a host PostgreSQL. See
[docs/LOCAL_SETUP.md](docs/LOCAL_SETUP.md) for installing PostgreSQL without administrator
rights, the full port layout, and troubleshooting.

### Running the tests

```bash
./mvnw test
```

55 tests: unit tests for the balance and double-entry rules, unit tests for the Keycloak role
mapping, unit tests for a defensive validation in the sequence-number generator, and a full
end-to-end journey through the real HTTP stack against a real database. No live Keycloak, Redis
or Kafka is required — each request injects a fake authenticated principal directly (see
`CoreBankApiIntegrationTest`), and Redis/Kafka being unreachable degrades to "no caching" and
"events not published" rather than failing anything.

### Against real infrastructure

```bash
./mvnw test -Dtest=CoreBankTestcontainersIT
```

`CoreBankTestcontainersIT` is `CoreBankApiIntegrationTest`'s counterpart: real PostgreSQL,
Keycloak, Redis and Kafka via Testcontainers, driven with REST Assured, instead of a fake JWT
and no broker. It's slow (containers dominate the runtime — budget ~90 seconds with warm
images) and excluded from the default `mvn test`/`mvn verify` run by Surefire's `*Test`-only
naming convention, so it stays out of the everyday loop; CI runs it explicitly as its own step
on every push instead. It exists because four real bugs during Phase 2–4 — a Keycloak access
token missing `sub`, Redis's serializer throwing on a cross-caller cache read, Kafka's producer
blocking a request thread for 60s, and (during this suite's own development) the producer and
consumer silently reverting to `StringSerializer` under test — were only ever visible against
the genuinely running stack.

### Load testing money movement

```bash
docker compose up -d
MSYS_NO_PATHCONV=1 docker run --rm -i --network corebank_default -v "${PWD}/k6:/scripts" \
  -e BASE_URL=http://app:8080 -e KEYCLOAK_URL=http://keycloak:8080 \
  grafana/k6 run /scripts/money-movement.js
```

Ramps up to 20 virtual users driving deposits, withdrawals and transfers against a pool of
pre-funded accounts, authenticating against the real Keycloak realm exactly like the frontend
does. See [k6/money-movement.js](k6/money-movement.js) for the full set of `-e` overrides
(`VUS`, `DURATION`, `ACCOUNT_POOL_SIZE`, ...). Drop `MSYS_NO_PATHCONV=1` outside Git Bash.

### Static analysis, locally

```bash
docker compose -f compose.yaml -f compose.sonar.yml up -d sonarqube
./mvnw verify sonar:sonar -Dsonar.host.url=http://localhost:9000 -Dsonar.token=<token from the SonarQube UI>
```

Self-hosted SonarQube Community Edition, entirely local — no account needed. It's a separate,
optional overlay rather than part of the default stack: heavy (a bundled Elasticsearch, a
couple of GB, a slow first start) and most projects would use SonarCloud in CI instead, which
this repo's CI workflow supports too if you add a `SONAR_TOKEN` secret pointing at your own
SonarCloud account. See [docs/LOCAL_SETUP.md](docs/LOCAL_SETUP.md) for first-login details.

### Kubernetes, locally

```bash
kind create cluster --name corebank
docker compose build app
bash k8s/deploy.sh
```

Deploys the same application image to a local, single-node `kind` cluster: Postgres, Redis,
Kafka and Keycloak each as a Deployment + Service, the app wired to them the same way
`compose.yaml` wires it, with init containers gating the app's startup on its dependencies
(plain Kubernetes has no equivalent of `depends_on: condition: service_healthy`, so without them
the app crash-loops until a dependency happens to be ready in time). See
[k8s/deploy.sh](k8s/deploy.sh) for what it does and [k8s/kafka.yaml](k8s/kafka.yaml) for the two
non-obvious fixes a real cluster forced: a single-node Kafka broker registering its own
controller through the `kafka` Service deadlocks (a Service only routes to pods that already
pass readiness, and this pod can't become ready until it registers), and the readiness probe's
Kubernetes-default 1-second timeout is too short for a script that boots a fresh JVM per check.

```bash
kubectl port-forward svc/app -n corebank 8080:8080
kubectl port-forward svc/keycloak -n corebank 8081:8080
```

Reach it exactly like the Docker Compose stack — same ports, same demo logins — once both are
running.

---

## Trying it from the command line

Since Keycloak owns login, a token comes directly from its token endpoint rather than from the
API. The `corebank-web` client has direct-access-grants enabled for exactly this kind of
scripting (the frontend itself uses Authorization Code + PKCE instead):

```bash
TOKEN=$(curl -s -X POST http://localhost:8081/realms/corebank/protocol/openid-connect/token \
  -d grant_type=password -d client_id=corebank-web \
  -d username=teller1 -d password='Teller#2025' | jq -r .access_token)
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

Demo logins (see [keycloak/corebank-realm.json](keycloak/corebank-realm.json)):

| Username | Password | Role |
| --- | --- | --- |
| `admin` | `ChangeMe#2025!` | ADMIN |
| `teller1` | `Teller#2025` | TELLER |
| `asha` | `Customer#2025` | CUSTOMER |

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

Keycloak is the identity provider. It issues the tokens; the application only validates them
and maps `realm_access.roles` onto Spring Security's `ROLE_` authorities (Keycloak nests realm
roles under that claim, so the framework's built-in flat-claim converter doesn't understand it
— see `SecurityConfig.RealmRoleConverter`). There is no local login endpoint: a client obtains a
token directly from Keycloak and presents it as a bearer token.

| Role | May |
| --- | --- |
| `CUSTOMER` | Read their own accounts and statements |
| `TELLER` | Onboard customers, open accounts, move money |
| `ADMIN` | Everything, plus KYC decisions, closing accounts and linking identities |

A CUSTOMER token's ownership is resolved by looking up `customer.keycloak_subject` against the
token's `sub` claim — the one claim Keycloak always issues and never lets drift out of sync with
an application-managed attribute — rather than by trusting a customer id embedded in the token
itself. Staff link a Keycloak identity to a customer via `PATCH /customers/{id}/identity`; a
customer resolves their own record via `GET /customers/me`.

The `issuer-uri` and `jwk-set-uri` resource-server properties are both set deliberately: with
only `issuer-uri`, Spring performs an eager discovery-document fetch at application startup,
which would fail if Keycloak isn't up yet. With both set, Spring validates the `iss` claim as a
plain string comparison and fetches signing keys lazily — so the application starts fine even
before Keycloak does.

### Caching

Redis sits in front of `GET /accounts/{id}` with a 30-second TTL. Every posting that touches an
account evicts its cache entry immediately after commit, so the TTL is a safety net for a
missed eviction, not the primary freshness mechanism — an eviction bug would show up as
staleness for at most 30 seconds, not indefinitely.

Redis is a read-through accelerator, never a source of truth: every cached value also lives in
PostgreSQL. `CacheConfig` implements `CachingConfigurer` and installs an error handler that logs
and swallows cache failures instead of the framework's default of rethrowing them — a Redis
outage degrades to "no caching," never to a 500.

### Events

Every posted transaction is published to the `corebank.transactions.posted` Kafka topic once
its database transaction actually commits — a `@TransactionalEventListener(phase = AFTER_COMMIT)`
listener does the send, so a transaction that rolls back (an optimistic-lock failure, a
constraint violation) never reaches Kafka at all. A demo `@KafkaListener` logs what it consumes;
Phase 5's OpenSearch indexer is the second, real consumer of the same topic — see Search below.
A customer create or KYC/status change publishes the same way, to `corebank.customers.changed`.

Publishing is fire-and-forget: the ledger is the single source of truth, this topic is a
downstream feed of it, and a broker outage should never fail an HTTP request that already
succeeded and committed. The producer's `max.block.ms` is set to 3 seconds in
`KafkaProducerConfig` — Kafka's client default is 60 seconds, which would otherwise hold the
request thread hostage on every posting during a broker outage.

### Search

`GET /api/v1/search/transactions` and `GET /api/v1/search/customers` are bank-wide and
cross-account — the gap the Postgres-backed statement endpoint (scoped to one account) and
customer list (unfiltered) deliberately don't cover. Each is a Kafka consumer indexing into
OpenSearch, not a query against the ledger: `TransactionSearchIndexer` and
`CustomerSearchIndexer` consume the same two topics Events already publishes, in their own
consumer group so they never interfere with the app's other listeners on those topics.

OpenSearch is a downstream read projection, the same status Kafka and Redis already have here —
never the source of truth for anything, and nothing else in the system depends on it being
reachable. Building the client never itself talks to OpenSearch (lazy, like `KafkaTemplate`), so
an outage at startup doesn't fail the application; a failed index attempt is logged and dropped,
not retried, so a transient outage leaves a gap in the index rather than catching up
automatically; and a failed search request comes back as a clean `503 SEARCH_UNAVAILABLE`
instead of an unhandled `500`.

### Observability

Every request is traced end to end with OpenTelemetry (via Micrometer Tracing's OTel bridge,
Spring Boot's native integration rather than a javaagent) and exported over OTLP to Tempo, which
Grafana queries directly — open a trace from Grafana's Explore view and it shows the full path
through Spring MVC, the ledger write, and the Kafka publish, one span per hop.

Metrics are pulled, not pushed: Prometheus scrapes `/actuator/prometheus` every 5 seconds. Two
custom counters sit alongside the usual JVM/HTTP/HikariCP metrics —
`corebank.transactions.posted` (by type and currency) and `corebank.idempotency.replayed` (by
scope) — because "how many deposits happened" and "how often are clients retrying" are the two
numbers a banking platform's own dashboard should answer first, not just infrastructure health.
The **CoreBank Overview** Grafana dashboard is provisioned automatically; no manual setup.

`/actuator/prometheus` is deliberately public (no bearer token) alongside `/actuator/health`
and `/actuator/info` — Prometheus has no Keycloak token to present, and none of the three expose
customer or account data.

### CI and security scanning

Every push and pull request against `main` runs three GitHub Actions workflows: backend build
and test (with JaCoCo coverage), frontend build and typecheck, and a Docker image build scanned
with Trivy. A separate CodeQL workflow analyses both the Java backend and the TypeScript
frontend, plus a weekly scheduled run so newly published advisories get caught against code that
hasn't changed. The Trivy scan reports CRITICAL/HIGH findings without failing the build — most
of what it finds at that severity lives in base-image OS packages outside this project's direct
control, so treating it as a hard gate would block merges over CVEs nobody here can fix; see the
workflow file for exactly where that line is drawn.

SonarQube analysis (bugs, code smells, coverage, security hotspots) runs locally against a
self-hosted instance — see [Running it](#static-analysis-locally) above — and optionally in CI
against SonarCloud if a `SONAR_TOKEN` secret is configured; the CI step is skipped, not failed,
when that secret is absent, so this workflow stays green on a fork with no SonarCloud account.

---

## API

| Method | Path | Role | Purpose |
| --- | --- | --- | --- |
| `POST` | `/api/v1/customers` | TELLER, ADMIN | Onboard a customer |
| `GET` | `/api/v1/customers` | TELLER, ADMIN | List customers |
| `GET` | `/api/v1/customers/{id}` | TELLER, ADMIN | Fetch one customer |
| `GET` | `/api/v1/customers/me` | CUSTOMER | Resolve the caller's own customer record |
| `PATCH` | `/api/v1/customers/{id}/kyc` | ADMIN | Record a KYC decision |
| `PATCH` | `/api/v1/customers/{id}/identity` | TELLER, ADMIN | Link a Keycloak identity to this customer |
| `POST` | `/api/v1/accounts` | TELLER, ADMIN | Open an account |
| `GET` | `/api/v1/accounts/{id}` | owner, staff | Fetch one account (cached) |
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
| `GET` | `/api/v1/search/transactions` | TELLER, ADMIN | Bank-wide search: `q`, `type`, `minAmount`/`maxAmount`, `from`/`to` |
| `GET` | `/api/v1/search/customers` | TELLER, ADMIN | Search by name, email or customer number: `q` |

### Error codes

| Code | Status | Meaning |
| --- | --- | --- |
| `VALIDATION_FAILED` | 400 | Request body failed validation; see `errors` |
| `MISSING_HEADER` | 400 | A required header, usually `Idempotency-Key`, was absent |
| `UNAUTHENTICATED` | 401 | No valid bearer token |
| `ACCESS_DENIED` | 403 | Authenticated, but not allowed |
| `RESOURCE_NOT_FOUND` | 404 | No such customer, account or transaction |
| `IDEMPOTENCY_KEY_REUSED` | 409 | Same key, different body |
| `REQUEST_IN_PROGRESS` | 409 | An identical request is still being processed |
| `CONCURRENT_MODIFICATION` | 409 | Optimistic lock lost; retry |
| `EMAIL_TAKEN` | 409 | A customer with that email already exists |
| `IDENTITY_ALREADY_LINKED` | 409 | That Keycloak identity is linked to a different customer |
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
corebank/
├── src/main/java/com/corebank/
│   ├── account/       Accounts, balances, ownership checks
│   ├── customer/      Onboarding, KYC, Keycloak identity linking
│   ├── transaction/   Postings, the ledger, statements, Kafka publishing
│   ├── idempotency/   Replay protection for money movement
│   ├── search/        OpenSearch indexers (Kafka-fed) and the /search API
│   ├── common/        Money, audit columns, errors, sequences
│   └── config/        Security, caching, Kafka, OpenSearch, OpenAPI, properties
├── src/main/resources/db/migration/   Flyway migrations
├── keycloak/corebank-realm.json       Realm, roles, clients and demo users
├── observability/                     Prometheus scrape config, Tempo config, Grafana provisioning
├── src/test/java/.../testcontainers/  CoreBankTestcontainersIT: real infra, not mocks
├── k6/                                Load test for deposit/withdraw/transfer
├── k8s/                               Kubernetes manifests + deploy.sh for a local kind cluster
├── .github/workflows/                 CI (build/test/scan) and CodeQL
├── frontend/                          React + TypeScript SPA
└── docs/LOCAL_SETUP.md                Standing up the environment on Windows
```

Each backend slice keeps its own `domain`, `repository`, `service`, `web` and `dto` packages, so
a feature is one directory rather than a trail through five technical layers.

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
| `COREBANK_OIDC_ISSUER_URI` | `http://localhost:8081/realms/corebank` | Compared against every token's `iss` claim |
| `COREBANK_OIDC_JWK_SET_URI` | `http://localhost:8081/realms/.../certs` | Where signing keys are actually fetched from |
| `COREBANK_REDIS_HOST` / `_PORT` | `localhost` / `6379` | |
| `COREBANK_KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | |
| `COREBANK_ALLOWED_ORIGINS` | `http://localhost:5173` | CORS; comma-separated for more than one |
| `COREBANK_OTLP_TRACING_ENDPOINT` | `http://localhost:4318/v1/traces` | Where spans are exported to (Tempo, or any OTLP/HTTP collector) |
| `COREBANK_OPENSEARCH_URI` | `http://localhost:9200` | Search index; an outage degrades `/api/v1/search/**` to `503`, nothing else |
| `SERVER_PORT` | `8080` | |

The defaults exist so the project starts on a laptop with no setup. None of them are
appropriate anywhere else — in particular, `COREBANK_OIDC_ISSUER_URI` and `_JWK_SET_URI` need
real values pointing at wherever Keycloak actually runs in any environment beyond a laptop.

---

## Scope, and what is deliberately not here

Built across Phase 1–5: Java 21 · Spring Boot · Spring Security · Hibernate · PostgreSQL · REST ·
OpenAPI/Swagger · Docker · JUnit · Git · Keycloak/OIDC · Redis · Kafka · React + TypeScript ·
OpenTelemetry · Prometheus · Grafana · GitHub Actions · CodeQL · Trivy · SonarQube ·
Testcontainers · REST Assured · k6 · Kubernetes (`kind`, locally) · OpenSearch.

Not yet, left for later phases by design: gRPC, Terraform, AWS, and a Python/FastAPI/MLflow
data-AI tier. The seams they will attach to already exist — a stateless
application already proven to run under Kubernetes for whatever a Terraform-provisioned cloud
cluster comes next, an append-only ledger already feeding Kafka for whatever downstream
analytics or ML pipeline comes next, and a CI pipeline a cloud deploy step would slot into rather
than replace.
