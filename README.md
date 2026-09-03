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
Phase 6 adds a gRPC read surface over the same service layer, and moves the local Kubernetes
deployment from a shell script to Terraform. Phase 7 adds a Python/FastAPI spending-insights
tier that categorises transactions off the same Kafka feed, with the model tracked in MLflow.

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
| gRPC | A read-only service-to-service surface (accounts, transactions, streamed statements) over the same service layer and the same Keycloak tokens as REST. |
| Spending insights | A separate Python service categorises each posting off the Kafka feed and serves per-customer spending summaries. Read-only: it never writes to the ledger. |
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
the spending-insights service and the application together. First build takes a few minutes.
Once it's up:

| | |
| --- | --- |
| API | <http://localhost:8080>, Swagger UI at `/swagger-ui.html` |
| gRPC | `localhost:9091`, plaintext with reflection on — `grpcurl -plaintext localhost:9091 list` |
| Spending insights | <http://localhost:8000>, OpenAPI docs at `/docs` |
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

The gRPC read path has its own script. Note the mount is the **repo root**, not `k6/`, because
the client loads `corebank.proto` from `src/main/proto`:

```bash
MSYS_NO_PATHCONV=1 docker run --rm -i --network corebank_default -v "${PWD}:/repo" \
  -e BASE_URL=http://app:8080 -e KEYCLOAK_URL=http://keycloak:8080 -e GRPC_ADDR=app:9091 \
  grafana/k6 run /repo/k6/grpc-reads.js
```

Fixtures are seeded over REST so only the read path is measured. Observed p95 is around 15ms
against ~800ms for REST writes, which is the gap the binary surface exists for. See
[k6/grpc-reads.js](k6/grpc-reads.js) for why the server-streaming RPC is covered by
`CoreBankTestcontainersIT` rather than here.

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

### Kubernetes, locally — via Terraform

```bash
docker compose build app
cd terraform && terraform init && terraform apply
```

Creates the `kind` cluster, loads the locally built image into it, applies the manifests and
waits for the rollout — everything [k8s/deploy.sh](k8s/deploy.sh) did, but with the cluster
itself (node image, topology, Kubernetes version) as a checked-in description `terraform plan`
can diff against reality rather than as flags someone has to remember. `terraform destroy` tears
the whole cluster down.

The manifests stay a kustomization applied by `kubectl apply -k`, deliberately, rather than being
re-expressed as typed Terraform resources: that would create a second copy of every Deployment
and Service, free to drift from the ones `k8s/` still holds. The trade-off is explicit —
Terraform tracks *that* the manifests are applied, not the state of each object inside them;
`kubectl diff -k k8s/` remains the tool for that. See [terraform/main.tf](terraform/main.tf),
where each of these choices is argued at the resource it affects.

`bash k8s/deploy.sh` still works and is the shorter path if the cluster already exists.

Either way the deployed stack is the same: Postgres, Redis, Kafka, Keycloak and OpenSearch each
as a Deployment + Service, the app wired to them the way `compose.yaml` wires it, with init
containers gating the app's startup on its dependencies (plain Kubernetes has no equivalent of
`depends_on: condition: service_healthy`, so without them the app crash-loops until a dependency
happens to be ready in time). See [k8s/kafka.yaml](k8s/kafka.yaml) for the two non-obvious fixes
a real cluster forced: a single-node Kafka broker registering its own controller through the
`kafka` Service deadlocks (a Service only routes to pods that already pass readiness, and this
pod can't become ready until it registers), and the readiness probe's Kubernetes-default
1-second timeout is too short for a script that boots a fresh JVM per check.

```bash
kubectl port-forward svc/app -n corebank 8080:8080
kubectl port-forward svc/app -n corebank 9091:9091
kubectl port-forward svc/keycloak -n corebank 8081:8080
```

Reach it exactly like the Docker Compose stack — same ports, same demo logins — once these are
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

Every posted transaction ends up on the `corebank.transactions.posted` Kafka topic; a customer
create or KYC/identity change ends up on `corebank.customers.changed`. A demo `@KafkaListener`
logs what it consumes; Phase 5's OpenSearch indexer and the Python insights service are the real
consumers — see Search and Spending insights below.

Getting an event onto Kafka is a two-step, transactional-outbox handoff, not a direct send.
`TransactionEventPublisher`/`CustomerEventPublisher` listen for the domain event with a plain
`@EventListener` — not `@TransactionalEventListener(AFTER_COMMIT)` — so `OutboxEventWriter` writes
an `outbox_event` row in the **same** database transaction as the ledger or customer change it
describes: both commit together, or an exception rolls both back together. `OutboxRelay`, a
`@Scheduled` poller (`corebank.outbox.relay-interval`, default 2s), is the only thing that ever
actually talks to Kafka: it claims a batch of unpublished rows with `SELECT ... FOR UPDATE SKIP
LOCKED`, sends each one, and leaves a failed send's row unpublished for the next tick to retry.

What this replaced was fire-and-forget straight to Kafka from an `AFTER_COMMIT` listener:
correct as far as never publishing a rolled-back posting, but if the broker happened to be
unreachable at the exact moment of that single send attempt, the event was gone permanently —
silently leaving a hole in the OpenSearch index and the spending-insights projection with no way
to detect or repair it. The outbox row is a durable, retriable record of "this still needs
sending," so a broker outage now delays delivery instead of losing the event: nothing is
acknowledged to Kafka before it is safely on disk in the same transaction as the change itself.

Two ADMIN-only endpoints repair a gap after the fact — a window predating the outbox, or one this
application's own monitoring missed for some other reason — by re-deriving the event from the
ledger/customer tables and writing it through the exact same outbox path a live request uses:
`POST /api/v1/admin/outbox/replay/transactions?since=&until=` and
`.../replay/customers?since=&until=` (see API below). Both are safe to run more than once over
the same window, since every downstream consumer already upserts by the event's key rather than
appending.

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

### Spending insights

A separate Python service ([insights/](insights/)) — the only part of the system not written in
Java. It consumes the same `corebank.transactions.posted` topic in its own consumer group,
categorises each posting's description with a scikit-learn model, and stores one row per ledger
leg in its own database. `GET /api/v1/insights/customers/{id}/summary` then aggregates that into
spending by category.

**It never writes to CoreBank.** The single call in that direction resolves which account numbers
a customer holds, and it forwards the caller's own bearer token, so CoreBank applies exactly the
authorization it always would — a CUSTOMER token that does not own the customer gets CoreBank's
own 403 and never reaches the aggregate. That lookup exists because the published event carries
`accountNumber` but no customer id; widening CoreBank's event to suit a downstream consumer would
have inverted the dependency this phase is built to respect.

Only outgoing legs count as spending, and general-ledger legs are dropped — every deposit has a
`GL…` contra leg, and counting it would both double-count the transaction and attribute the
bank's own cash movements to a customer.

The categoriser is trained on a **synthetic, hand-written seed set**
([insights/app/model.py](insights/app/model.py)), because CoreBank has no real merchant feed. It
is a genuine TF-IDF + logistic-regression pipeline tracked in MLflow, not a lookup table, but its
confidence scores are low in absolute terms — with nine classes and a seed set this small, treat
them as a ranking signal rather than a calibrated probability. Training happens on first start if
no model exists, so a fresh `docker compose up` needs no separate step.

### gRPC

A second, **read-only** surface on port `9091`, defined by
[src/main/proto/corebank.proto](src/main/proto/corebank.proto): fetch an account, list a
customer's accounts, fetch a transaction, and stream a statement. Money movement stays on REST
on purpose — it needs the `Idempotency-Key` contract and the RFC 7807 error bodies the HTTP API
already defines, and a second implementation of the one thing in this system that must never
post twice would be a liability, not a feature.

Both surfaces are views of one service layer. The gRPC services call the same `AccountService`
and `TransactionService` beans the controllers do, so caching, ledger rules and ownership checks
live in one place; `GrpcSecurityConfig` authenticates with Spring's own gRPC JWT support, handed
the very `JwtAuthenticationConverter` bean `SecurityConfig` builds for the HTTP filter chain, so
a token cannot grant different authorities depending on which port it arrives on. That sharing
is load-bearing rather than tidy: the framework's default converter ignores Keycloak's nested
`realm_access.roles`, so a staff token would authenticate and then arrive with no roles at all.

`StreamStatement` is server-streaming rather than paged — a statement is unbounded in principle,
and a caller can start work on the first lines before the last are read. Amounts cross the wire
as strings, never doubles: proto3 has no decimal type, and a double would reintroduce exactly the
binary floating-point error the ledger is built to avoid. `GrpcExceptionInterceptor` maps the
application's own `ApiException` hierarchy onto gRPC statuses and puts the same stable `code` the
JSON API returns into `corebank-code` trailing metadata, so a gRPC client branches on the same
tokens an HTTP client does.

Phase 3's observability covers this surface too, without extra wiring: `/actuator/prometheus`
carries `grpc_server_*` timers and counters labelled by `rpc_service`, `rpc_method` and
`grpc_status_code`, and each call produces its own Tempo trace named for the RPC (for example
`corebank.v1.AccountQueryService/GetAccount`) — both confirmed against the running stack rather
than assumed from the starter's documentation.

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
| `POST` | `/api/v1/admin/outbox/replay/transactions` | ADMIN | Re-enqueue transaction-posted events for `since`/`until` |
| `POST` | `/api/v1/admin/outbox/replay/customers` | ADMIN | Re-enqueue customer-changed events for `since`/`until` |

Served by the separate insights service on port `8000`, not by the Java API:

| Method | Path | Role | Purpose |
| --- | --- | --- | --- |
| `GET` | `/api/v1/insights/customers/{id}/summary` | owner, staff | Spending by category; optional `since`/`until` |
| `GET` | `/api/v1/insights/customers/{id}/entries` | owner, staff | The categorised entries behind the summary |
| `GET` | `/api/v1/insights/categorise` | TELLER, ADMIN | Run the categoriser over an arbitrary `description` |

### gRPC (port 9091)

Read-only; see [src/main/proto/corebank.proto](src/main/proto/corebank.proto). Same bearer token
as REST, passed as `authorization` metadata.

| Service | RPC | Role |
| --- | --- | --- |
| `AccountQueryService` | `GetAccount` | owner, staff |
| `AccountQueryService` | `ListCustomerAccounts` | owner, staff |
| `TransactionQueryService` | `GetTransaction` | TELLER, ADMIN |
| `TransactionQueryService` | `StreamStatement` (server-streaming) | owner, staff |

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
| `INVALID_REPLAY_WINDOW` | 422 | An outbox replay's `until` is not after its `since` |

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
│   ├── grpc/          gRPC services, auth and error interceptors, proto mapping
│   ├── common/        Money, audit columns, errors, sequences
│   └── config/        Security, caching, Kafka, OpenSearch, OpenAPI, properties
├── src/main/proto/corebank.proto      The gRPC contract
├── src/main/resources/db/migration/   Flyway migrations
├── keycloak/corebank-realm.json       Realm, roles, clients and demo users
├── observability/                     Prometheus scrape config, Tempo config, Grafana provisioning
├── src/test/java/.../testcontainers/  CoreBankTestcontainersIT: real infra, not mocks
├── insights/                          Python/FastAPI spending-insights service (Kafka + MLflow)
├── k6/                                Load test for deposit/withdraw/transfer
├── k8s/                               Kubernetes manifests + deploy.sh for a local kind cluster
├── terraform/                         Provisions that kind cluster and applies k8s/ to it
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
| `COREBANK_GRPC_PORT` | `9091` | gRPC listener; 9091 rather than 9090, which Prometheus owns |
| `SERVER_PORT` | `8080` | |

The insights service is configured separately, with an `INSIGHTS_` prefix:

| Variable | Default | Notes |
| --- | --- | --- |
| `INSIGHTS_DATABASE_URL` | `postgresql://corebank:corebank@localhost:5432/insights` | Its own database; created on first start if absent |
| `INSIGHTS_KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | |
| `INSIGHTS_COREBANK_API_URL` | `http://localhost:8080` | Only used to resolve a customer's account numbers |
| `INSIGHTS_OIDC_ISSUER` / `_JWKS_URL` | Keycloak realm | Same split as the backend's issuer/JWK-set pair |
| `INSIGHTS_MLFLOW_TRACKING_URI` | `sqlite:////var/lib/insights/mlflow.db` | SQLite, not `file:` — see LOCAL_SETUP |

The defaults exist so the project starts on a laptop with no setup. None of them are
appropriate anywhere else — in particular, `COREBANK_OIDC_ISSUER_URI` and `_JWK_SET_URI` need
real values pointing at wherever Keycloak actually runs in any environment beyond a laptop.

---

## Scope, and what is deliberately not here

Built across Phase 1–7: Java 21 · Spring Boot · Spring Security · Hibernate · PostgreSQL · REST ·
OpenAPI/Swagger · Docker · JUnit · Git · Keycloak/OIDC · Redis · Kafka · React + TypeScript ·
OpenTelemetry · Prometheus · Grafana · GitHub Actions · CodeQL · Trivy · SonarQube ·
Testcontainers · REST Assured · k6 · Kubernetes (`kind`, locally) · OpenSearch · gRPC/protobuf ·
Terraform (local `kind` only) · Python · FastAPI · scikit-learn · MLflow.

Not yet, left for later phases by design: AWS, and the cloud half of Terraform. The seam is
already there — a stateless application proven under Kubernetes and already provisioned by
Terraform, so a cloud cluster is a provider change rather than a rewrite, and a CI pipeline a
deploy step would slot into rather than replace.
