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
| Customer onboarding | Create customers, run a KYC decision. An unverified customer cannot open an account, and money cannot leave the accounts of one whose KYC lapses later; payments in still arrive. |
| Accounts | Savings and current accounts, opened at a zero balance. Current accounts may carry an overdraft. |
| Money movement | Deposits, withdrawals and internal transfers, each posted as two balanced ledger legs. |
| FX | Cross-currency transfers, posted as four legs through per-currency position accounts, with the book valued and marked to market. |
| Interest | Savings balances accrue daily at full scale and capitalise monthly as a real posting. |
| Velocity limits | A per-account daily debit ceiling and a per-posting ceiling, counted from the ledger. |
| Holds | Authorisation holds reserve money without posting it. Captured, released, or expired by a sweep. |
| Scheduled transfers | Standing instructions -- once, daily, weekly or monthly -- posted by a background runner, set up and cancelled from the account page. |
| Reversals | An admin can undo a posting. The correction is its own transaction with mirrored legs -- nothing is edited or erased. |
| Idempotency | Every money-moving `POST` requires an `Idempotency-Key`. Retries never post twice. |
| Statements | Paginated account history, newest first, signed from that account's point of view. |
| Security | Keycloak-issued JWTs, three realm roles. Customers can read only their own accounts. |
| Caching | Account detail reads go through Redis with a short TTL; a Redis outage just means no caching. |
| Events | Every posted transaction is published to Kafka once its database transaction commits. |
| Notifications | Each customer is told when money moves on their account -- once per posting, even when Kafka delivers the message twice. |
| Search | Bank-wide, cross-account transaction and customer search (OpenSearch), fed by the same Kafka events -- not the per-account statement or unfiltered customer list, both Postgres-backed. |
| gRPC | A read-only service-to-service surface (accounts, transactions, streamed statements) over the same service layer and the same Keycloak tokens as REST. |
| Spending insights | A separate Python service categorises each posting off the Kafka feed and serves per-customer spending summaries. Read-only: it never writes to the ledger. |
| Errors | RFC 7807 problem documents with a stable machine-readable `code` on every failure. |
| Frontend | A React SPA: customer onboarding and KYC, account opening, deposits/withdrawals/transfers, statements, standing instructions, notifications, and admin-only reversals. |
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
backend), to the Java API at `localhost:8080`, and to the insights service at `localhost:8000`
for the spending-insights widget and the categoriser tool; all three need to already be running.
See [frontend/.env.example](frontend/.env.example) if any of them is running somewhere else.

Staff (TELLER/ADMIN) get a **Search** page — bank-wide transaction and customer search, a
categoriser playground, and a click-through to a transaction's ledger legs. That transaction view
is also where an **ADMIN** can reverse a posting: the card states what a reversal does before
asking for the mandatory reason, and when a posting cannot be reversed it says which of the two
rules applies rather than hiding the control. A TELLER never sees it at all — the backend refuses
them regardless, so offering a disabled button would only imply the split is negotiable.

An account page also lists the **standing instructions** touching that account, in both
directions and signed from that account's side, since the same mandate is money leaving one
account and arriving in another. A customer sees their own; only staff can set one up or
cancel it. The list leads with what the API exposed but nothing previously showed: a mandate
that has started failing, with the backend's own reason attached, and one that has been
suspended after too many failures -- which is terminal, so the card says to set up a
replacement rather than leaving someone hunting for a resume button. Every customer view
(staff viewing one customer, or a CUSTOMER role viewing their own accounts) gets a **Spending
insights** section: category breakdown and recent categorised entries, pulled live from the
insights service.

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

Ledger entries are append-only, and reversal is what that buys. Undoing a posting does not
edit it and does not delete it: `POST /api/v1/transactions/{reference}/reversal` writes a new
transaction whose legs mirror the original's -- same accounts, same amounts, opposite
directions -- and marks the original `REVERSED`. Both stay on the statement, so the record
shows the money moving and then moving back, which is what an auditor needs to see.

It also means nothing downstream has to know reversal exists. Search, the spending insights
service, anything summing signed amounts: they all net out correctly, because the correcting
legs are ordinary legs.

Two rules are worth stating outright. A reversal **may** push an account past its overdraft
limit -- that limit exists to stop the bank lending money it never agreed to lend, and a
correction is not new lending; refusing one because the customer has since spent the money
would leave the ledger permanently wrong about a movement that should never have happened.
A reversal is **not** itself reversible: correcting a mistaken reversal means posting the
original movement again, not stacking a second correction on the first.

### Cross-currency transfers

`Money.BASE_CURRENCY` has said since Phase 1 that "multi-currency ledgers arrive with FX in a later
phase". Until now `Account.assertCurrency` refused any posting whose currency did not match the
account, which kept the ledger honest by keeping it monolingual.

**An FX transfer is four legs, not two**, and that is the whole shape of the change. The customer
pays in one currency and is paid in another, so no pair of entries balances: a two-leg posting would
have to claim those amounts are equal, which is a ledger recording that ₹10,000 *is* $119.40 rather
than that it was *exchanged for* it. Instead each currency balances against its own **FX position
account** — the source debited and the INR position credited, the USD position debited and the
destination credited. What the bank actually did, bought one currency and sold another, is then
visible in those two accounts.

**`assertBalanced` now balances per currency**, and it had to. Summed in total, those four legs
balance by pure coincidence — each amount appears once on each side, so *any* two numbers pass. The
old check would have accepted ₹1,000 becoming $1,000,000. Grouping by currency first is the only
sense in which a multi-currency posting can be said to balance; adding rupees to dollars is not
arithmetic.

**The spread stays with the position accounts.** The bank credits itself everything received and
debits itself slightly less than the mid-rate equivalent paid out, so the positions net to the
margin at market — nothing separate books the profit. The mid rate and the spread are stored apart,
because a rate already net of spread cannot be audited against any published source afterwards, and
`GET /api/v1/fx/quote` returns both.

#### Valuing the book

V10 built the position accounts and nothing read them — a bank holding offsetting amounts in four
currencies and never valuing them does not know its own exposure. `GET /api/v1/fx/position` answers
that: each position in its own currency, valued into the reporting currency, plus what the
revaluation account currently carries the book at. The gap between the two is what a close would
recognise.

Positions are valued at the **directly quoted** rate into the reporting currency (`USD/INR`), never
at the inverse of the opposite quote (`1 / INR/USD`). Those disagree in this book by 0.10% to 0.64%,
and the directly quoted one is the rate the bank could actually transact at. They are valued at
**mid**, not at the bank's own spread — marking a book at the price you would charge to close it
reports a profit you have not made.

A close posts the **delta, not the mark**. The revaluation account carries the mark as its balance,
so a run only moves it to where the market now says it should be: running twice in a row is
harmless, and a missed close is caught up by the next one rather than lost. Posting the mark itself
would double the book on every run.

Both legs of a revaluation are in the reporting currency, and that is forced rather than chosen —
the per-currency balance rule means a posting mixing INR with USD cannot balance at all. So the
foreign positions are never touched by a close, which is precisely what makes the gain
**unrealised**: nothing has happened to the dollars, the bank has only restated what holding them
is worth.

Revaluation is an explicit admin action rather than a scheduled job. A close is an operational
decision about a point in time, not something that should happen on a timer.

The seeded cross-rates are not mutually consistent, which is tolerable only while the spread covers
it — otherwise a customer could mint money cycling between their own accounts. A test asserts that
every round trip *and* every triangle loses money, so an edited rate that broke that would fail the
build rather than reach production.

Rates are seeded indicative values; a real deployment replaces them from a feed. Quoting does not
reserve — a transfer is converted at whatever is quoted when it is submitted, and records that rate
on the posting at eight decimal places, because a rate is not money and rounding it to the money
scale would make a large conversion irreproducible from its own audit trail.

### Internal accounts exist per currency

Every posting that touches an internal account looks it up by **type and currency** —
`AccountService.internalAccount(type, currency)` — rather than by account number. Cash, interest
expense and the FX positions each exist once per currency the bank deals in.

That rule came from a bug. When the ledger started balancing per currency (FX, V10), cash and
interest expense had only ever existed in rupees, and were reached by number. From then on any
posting pairing a dollar, euro or sterling account with either could not balance: such an account
could take no cash deposit and no withdrawal, and interest on one accrued every day and was never
paid. The per-currency rule refused those postings loudly, which is what it is for — before it, a
dollar deposit would have balanced against rupee cash in silence. V12 adds the missing accounts.

Opening an account in a currency the bank holds no cash account in is refused with
`CURRENCY_NOT_SUPPORTED`. It used to check only the ISO shape, so a JPY account would open and then
be dead for good: nothing could be deposited into it and nothing converted into it.

### What a reversal tells everything else

A reversal has always posted correctly; the balances were never wrong. What used to go stale was
every record that pointed at the reversed posting and was never told about it:

- **A hold whose capture was reversed** stayed `CAPTURED`, still naming the posting that had been
  undone — so it went on claiming the merchant had been paid. It now becomes `CAPTURE_REVERSED`,
  keeping the captured reference (the capture really happened) and adding the reversal that undid
  it. Not `RELEASED`: the customer ends up in the same place, but a release means nothing moved
  and a reversed capture means it moved and came back, and reconciling against the merchant
  depends on knowing which.
- **Search** kept showing the original as a live transaction. The original is now re-published
  carrying status `REVERSED`, under the same reference. The indexer upserts on that key, so the
  existing document is overwritten rather than joined by a second, contradictory hit, and search
  results show a status column.

Both travel from one place. `TransactionService.reverse` publishes the original again for
downstream consumers, and an in-process `TransactionReversedEvent` for records that point at it by
reference — so the transaction side never needs to know holds exist, and the next derived record
does not need an edit there to stay truthful. The hold is updated inside the reversal's own
transaction, so the two change together or not at all.

`TransactionPostedEvent` gains a `status` field — the first change to that event since it was
introduced. It is additive: the insights service reads fields by name and ignores extras, and it
upserts on `(reference, account)` with legs that have not changed, so the re-published original is
a no-op for it. A message from **before** the field existed deserializes with it null and is read
as `POSTED` — nothing sent a status until now, and every earlier message described a transaction at
the moment it was posted. Reading it any other way would let one old message fail an entire
indexing batch.

> **After deploying:** search documents written before this change carry no status, so a
> transaction reversed earlier still reads as `POSTED`. Run the existing replay once —
> `POST /api/v1/admin/outbox/replay/transactions` over the period in question — and each
> transaction is re-published as it now stands.

### What can be reversed

Reversal is for undoing what a customer or teller did — deposits, withdrawals and transfers — and
that is an **allow-list** in `BankTransaction`. It used to be a single block on reversing a
reversal, which left interest and FX revaluation reversible by default simply because nobody had
forbidden them. Both were wrong to reverse: reversing interest destroyed it outright (capitalising
moves it out of `accrued_interest` and into the balance; the reversal took it back out of the
balance and restored nothing), and reversing a revaluation broke the invariant that the
revaluation account's balance is the mark. System postings are corrected by the process that
produced them. As an allow-list, the next posting type anyone adds is non-reversible until somebody
decides otherwise, rather than reversible until somebody notices.

### Interest

Savings balances earn interest, accrued daily and capitalised monthly. It is the feature this
schema's `NUMERIC(19,4)` storage scale was chosen for, and `account.accrued_interest` is the column
that needs it.

**Accrual is not a posting.** Nothing has moved — the bank owes slightly more than yesterday, but no
money has changed hands — so writing a ledger entry every night would put 365 lines a year on a
statement for something the customer cannot yet spend. The amount accumulates on the account and
becomes a single posting when capitalised.

**The two halves round differently, and that is the whole correctness argument.** Accrual keeps four
decimal places: a day's interest on ₹10,000 at 3.5% is ₹0.9589, and on ₹100 it is under a paisa. At
two decimal places the first over-pays by 4% every day and the second rounds to zero forever — a
small balance would earn nothing at all. Capitalisation rounds **down** to two places and carries
the remainder, because paying a rounded-up fraction is the bank inventing money and zeroing the
field is the bank keeping the customer's.

Capitalising is an ordinary balanced posting: the customer is credited and `GL0000000003`, the
interest-expense account, is debited — an expense with a `DEBIT` normal balance, exactly as cash is.
The money comes from somewhere, which is what makes it a posting rather than an invention.

Accrual is idempotent per day. `interest_accrued_through` means a crashed run, a restart, or a
second replica cannot pay the same day twice, which is also why the runner can tick hourly: the
extra ticks pick up accounts opened since the last one and finish oversized batches, and a
once-a-day schedule would let a badly timed restart cost a day.

Because capitalised interest joins the balance, the product **compounds** — a year of daily accrual
on ₹10,000 pays about ₹355.67 rather than the ₹350.00 of simple interest. The test bounds it from
both sides using arithmetic rather than the implementation: strictly more than simple interest, and
strictly less than continuous compounding at the same nominal rate (₹356.20), which no compounding
frequency can exceed.

### Velocity limits

Two ceilings, per account: one on a single posting, one on a UTC calendar day's total debits.
Both are configured in rupees, and a debit on an account in another currency is converted at the
mid rate before it is compared — a dollar account used to get roughly 83 times a rupee account's
allowance, because the limits were written as bare numbers when every account was in rupees. They
apply to withdrawals and outgoing transfers — not to deposits or incoming transfers, since money
arriving is not what a velocity control is about, and applying it there would refuse a customer
their own salary.

`TRANSACTION_LIMIT_EXCEEDED` and `DAILY_LIMIT_EXCEEDED` are separate codes, and separate from
`INSUFFICIENT_FUNDS`. "You do not have the money" and "you have the money but not today" are
opposite problems, and a customer told the first when the second is true goes and checks a balance
that was never wrong. The daily refusal states the remaining allowance, because that is the only
number in it the caller can act on.

**The day's total is summed from the ledger, not kept in a counter** — the opposite choice from
`account.held_amount`, and for a specific reason. A counter would need decrementing whenever a
withdrawal was reversed, and a bug there costs a customer allowance for a posting the bank itself
undid. Two predicates in one query handle it instead: `status = POSTED` drops a withdrawal that was
later reversed, and `type <> REVERSAL` drops the correcting legs, so that reversing a mistaken
*deposit* — which debits the customer — is not counted as the customer spending. The existing
`idx_entry_account_posted` index serves the read, inside a transaction that already holds the
account's row lock.

**Holds are checked at authorisation, not at capture.** Capturing a hold is exempt: the whole value
of a hold is that the reserved money cannot be taken away in between, and a guarantee a later limit
can revoke is not a guarantee. That exemption would be a way round the ceiling if placing a hold
went unchecked, so the check moves there, counting today's outstanding holds alongside today's
settled debits. One case is left open deliberately — a hold placed on one day and captured on the
next consumes the capture day's allowance without having been checked against it. The alternatives
are refusing captures or reserving allowance across days, and both are worse.

### Authorisation holds

`availableBalance` used to be `balance + overdraft`, which a bank can only quote honestly if
nothing is ever pending. A card authorised at hotel check-in reserved nothing, so the same money
could be spent again before the hotel captured it — and the posting finally refused would be the
one the bank had already guaranteed.

A hold is **not a ledger entry**. Nothing has happened to the bank's position — the customer still
owns the money and the bank still owes it — so posting one would record a movement that never
occurred. It reserves the amount against `availableBalance` instead, and only a capture becomes a
transaction. Holds compound, so a second authorisation sees the first one's money as already gone.

The reserved total is denormalised onto `account.held_amount` rather than summed from the hold
table on every read. Every path that moves money already row-locks the account, so keeping the
running total there is atomic for free; summing instead would put a second query in the hot
posting path. `AccountHoldRepository.sumOutstandingFor` exists purely so a test can prove the
denormalised figure never drifted from the holds behind it.

**Capture frees the reservation before it posts**, and the order is the point. A capture for the
held amount or less is then guaranteed to succeed, because the money it needs is exactly the money
reserved for it — nothing that happened in between can make it fail. A capture for *more* (a tip
added after the pre-authorisation) finds only the excess competing with the ordinary available
balance, and since the whole thing is one transaction, a refusal rolls the release back and leaves
the hold intact.

Expiry is checked against the clock, not against a status column. The sweep that marks holds
`EXPIRED` runs on an interval, so there is always a window where a hold is over but still `ACTIVE`
in the database; a capture arriving then is refused on the clock rather than honoured because a
background job happened not to have run. The sweep only gives the customer their available balance
back, which is why it can afford to be slow.

### KYC after opening

KYC used to be checked once, when an account was opened, and never again. An admin could reject a
customer after a failed re-check or a sanctions hit, and every account they already held kept
working: withdrawals, transfers, holds and standing orders alike. A throwaway test confirmed it
before anything changed.

Now **money cannot leave** the accounts of a customer who is not `VERIFIED`, whether they were
rejected or sent back to `PENDING` for a re-review. Every path money leaves by is covered:
withdrawals (which includes capturing a hold), the paying side of a transfer (which includes a
standing order's occurrences), placing a hold, and setting up or resuming a standing order. All
of them refuse with `CUSTOMER_NOT_VERIFIED`. The rule lives in one place, `Account.assertCanSendMoney`.

- **Payments in still arrive:** deposits, incoming transfers, interest. This is a policy choice.
  The customer is restricted rather than cut off, and nobody paying them has a payment bounce for
  a reason that is none of their business.
- **Reversals are exempt**, as they are from velocity limits. A correction the bank owes must never
  be refused, even when it takes money back out.
- **It is read live**, not recorded on each account, so verifying the customer again lifts it at
  once. There is no list of accounts to unfreeze, and no state that can drift from the customer's.
- **A hold placed before the restriction cannot be captured during it.** A capture is a
  withdrawal. This is the same policy a freeze has, and both are pinned in tests so that changing
  either is a deliberate act.
- **An existing standing order fails** like any other refused debit, and the payer is notified. The
  reason shown is the generic "could not be processed", because the instruction is visible to the
  payee as well and the payer's KYC status is not theirs to learn.

**Every KYC decision is kept** in `kyc_decision`, with the status it moved from and to, who made
it and why. Before this, a decision overwrote the customer's status and left no other trace. A
customer asking why their money was blocked, or an auditor asking who rejected them, got no
answer.

- **Who decided comes from the caller's token**: its `sub` claim and username. The request body
  has no field for it, so there is nothing to claim to be someone else. A non-human decider, such
  as the dev-data seeder, is named `system:<process>`.
- **A reason is required for anything but VERIFIED**, because that is the decision that stops
  money leaving the customer's accounts. Whitespace doesn't count. `ck_kyc_decision_reason` backs
  the rule up in the schema.
- **It is append-only.** The history row is written in the same transaction as the status change.
  The entity is immutable, and the repository extends Spring Data's bare `Repository`, so no delete
  or update method exists. A test fails if one is ever declared, or if the repository moves to
  `CrudRepository`.
- **History starts when this table does.** Earlier decisions were never recorded and cannot be
  reconstructed, and the customer page says so where the history would be.

The customer page's KYC control used to appear only while a customer was PENDING, so a verified
customer could never be sent back for review or rejected from the UI. It is now a decision form
available to admins in every state, with the history underneath it for all staff.

### Freezing and closing: who and why

Every freeze, unfreeze and closure is kept in `account_status_change` (V18), with the status
before and after, who made the change, the reason and the time. None of it used to be recorded.
The account row was overwritten in place: a closure kept `closed_at`, and a freeze kept nothing,
not even when it happened. A freeze stops all money movement, in as well as out, and usually
follows a fraud report or a legal order, so "who froze this, and on what grounds" needs an answer.

- **Who made the change comes from `Actors.current()`**, the same mechanism that attributes
  postings, so a freeze is attributed exactly as a deposit is.
- **Freezing and closing need a reason** (`STATUS_REASON_REQUIRED`); unfreezing doesn't.
  `ck_status_change_reason` backs this up in the schema. The three endpoints take an optional
  `{"reason": …}` body. This is a breaking change for any client that froze or closed without one.
- **A change to the status the account already has is refused** (`STATUS_UNCHANGED`) rather than
  recorded. A history full of non-events hides the changes that mattered.
- **A refused change records nothing.** The row is written only after every check passes, so a
  closure turned away for its balance never appears to have happened.
- **Append-only by construction, as the KYC history is**, and **staff only**: the reason for a
  freeze is not the account holder's to read. History starts here.

The account page's freeze and close buttons became an `AccountStatusCard`: a reason field,
required for freeze and close, with the history underneath. Close is now offered only to admins.
Before, tellers saw the button too, though the endpoint is admin-only and refused them with a 403.

### Closing an account

Closing used to check only for a zero balance, which is not enough. Two things could still be
attached to an account at zero, and closing under either broke a promise someone else relied on:

- **An active hold.** A current account can sit at zero inside its overdraft while carrying an
  authorisation. It closed anyway, and the merchant's capture was then refused because the
  account was closed. That is exactly what the hold existed to prevent.
- **A standing instruction, on either side.** The runner keeps firing at an account whatever its
  status, so every occurrence was refused and, since notifications, announced to the payer as a
  failure until the instruction suspended. When the closed account is the payee, that payer is
  another customer.

Closure now refuses with `CLOSURE_BLOCKED` and lists everything outstanding at once ("still has 1
outstanding hold and 2 standing instructions"). Staff then clear it all in one pass, rather than
finding each item through a separate refusal. It **refuses rather than cancelling**: quietly
stopping another customer's instruction is worse than asking staff to deal with it on purpose.

Each feature that owns such an obligation reports it through an `AccountClosureCheck`, so the
account package does not depend on every package that depends on it. The checks run with the
account's row locked. Placing a hold already takes that lock, and creating or resuming a standing
instruction now does too, locking both accounts in the order transfers use. Without that,
setting up an instruction could see the account still open, slip in after closure's check, and
leave a live instruction on a closed account. A test holds the lock to prove that creation waits
and is then refused.

### Scheduled transfers

A standing instruction is the only thing here that moves money with no request behind it, and
almost every design decision follows from that.

`ScheduledTransferRunner` polls for mandates that have come due and advances **one occurrence per
mandate per tick**. After an outage a daily instruction is several occurrences behind; it catches
up over successive ticks rather than posting the backlog in one burst, so each payment stays a
separate, individually idempotent unit.

Every due date is measured from the mandate's start date rather than from the previous one. Adding
a month to the last run would let an instruction drift: starting 31 January it would clamp to 28
February, then take 28 March as the next base, and quietly move a customer's rent three days
earlier for good. Anchored to the start it clamps only in the short months and returns to the 31st
in the long ones.

**The idempotency key is derived, not random** — `sched:<mandate id>:<due date>` — and that single
fact is what makes the runner safe. Posting the money and recording that it was posted are
necessarily two separate transactions (wrapping them in one would invert the order in which
`IdempotencyService` commits a key's completion, so a rollback could leave a key marked done with
no posting behind it). Crash in between and the occurrence is simply due again next tick: the
transfer replays from the stored response instead of paying twice, and the bookkeeping completes.
The `SKIP LOCKED` row claim stops two replicas doing redundant work; it is not what stops a double
payment.

A refused occurrence — nearly always insufficient funds — is **skipped rather than retried in
place**, because the poll interval would otherwise hammer a short account all day. Three
consecutive refusals suspend the mandate: enough to survive an ordinary run of bad luck, few
enough that an instruction nobody can honour ends up in front of a human instead of retrying
forever. A mandate that runs out of occurrences after a failure is `SUSPENDED`, not `COMPLETED` —
`COMPLETED` means the money moved.

**The payer is told about every refusal** — see Notifications. Only the payer: why a payment did
not arrive is the payer's business, not the payee's.

**Staff can resume a suspended mandate** (`POST /scheduled-transfers/{id}/resume`) once the cause
is fixed. It picks up from its next occurrence on the original timetable. Recreating it instead
would restart the timetable from whatever day that happened to be, so rent anchored to the 31st
would move to the 5th. Three rules, each with a test that fails without it:

- Occurrences missed while it was stopped are **not paid retroactively**. The customer was told
  about each one at the time, and taking several payments the moment they top up the account
  would take money they meant for one. This is the same reason a mandate cannot start in the past.
- The occurrence that failed is **never retried**, even when the mandate is resumed on the same
  day. The final failure does not advance the timetable, so without this rule the first date on or
  after today could be the one just refused.
- Both accounts are **re-checked** as for a new mandate. Whatever stopped it may have been an
  account being frozen or closed.

A one-off, or a mandate past its end date, has nothing left to run, so resuming it is refused.
A suspended mandate can also be **cancelled**. Because it could be resumed, cancelling is how it is
retired for good, and an account it names cannot close until that happens.

**One broken mandate cannot stall the rest.** The runner works oldest first. Anything that threw
out of the loop for one mandate (claiming its row, or recording the outcome) used to abort the
whole tick, and the mandate stayed due. So it sat at the front of every later batch, and no
standing order behind it ever ran. Each mandate is now isolated: such a failure is logged and
counted (`corebank.scheduled.transfers{outcome="error"}`), and the batch carries on.

**Why a payment failed is shown in words safe for either side.** The refusal's own message is
written for debugging. Insufficient funds reads "Account 100100000001 has 212.40 available but
750.00 was requested". The mandate is listed on the payee's account too, and `lastError` used to
carry that message verbatim, so a payee could read the payer's full account number and available
balance. The stable error code is now stored beside the message (V15), and every response shows a
reason derived from it: "There was not enough money in the account (750.00 INR was due)." The
original message stays in the database and the log, where it belongs.

### Who made a posting

Every posting records who made it, in `initiated_by_subject` and `initiated_by_name` (V17). Nothing
used to. Which teller took a cash deposit or paid out a withdrawal is what a till is reconciled
against, and the first question when cash is disputed. A reversal kept its reason but not the
admin who unwound the money.

- **Set in one place**, the single posting path every deposit, withdrawal, transfer, reversal,
  capture and system posting takes. No path can post without saying who.
- **A person is identified by their token:** its `sub` claim and username. The actor is read from
  the request's security context, not passed down through every posting method. Threading an
  actor parameter through would touch every caller and could be filled in wrongly just as easily.
- **Jobs name themselves** with `Actors.runAs`: the standing-order runner, the interest runner and
  the dev seeder are recorded as `system:<job>`. A job's identity takes precedence over any token
  on the thread, because nobody signed in made a standing order's payment.
- **A posting with nobody behind it is refused**, not recorded as unknown. In production every
  posting comes from an authenticated request or a declared job, so anything else is a bug, and
  an attribution that can quietly say "unknown" is not one anybody can rely on.
- **Tests attribute to a named test actor** through a fallback that only tests set, via a
  `TestExecutionListener`. A default token in the security context was tried first, but it leaked
  into MockMvc and made requests sent without a token authenticated; a test of anonymous access
  caught it. A test fails if production code ever sets the fallback.
- **Postings from before V17 show as not recorded.** They cannot be attributed after the fact.

The transaction page shows it as "Posted by", and a job reads as one ("System:
interest-runner"). Customers never see it: the transaction endpoint is staff-only, and the
statement lines a customer reads do not carry it.

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
create or KYC/identity change ends up on `corebank.customers.changed`. The consumers are customer
notifications, the OpenSearch indexers and the Python insights service — see Notifications, Search
and Spending insights below.

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

### Notifications

A customer is told when money moves on one of their accounts: "500.00 INR credited to account
XXXX0001". `NotificationConsumer` reads `corebank.transactions.posted` and writes one row per
customer account a posting touched; the bank's own GL legs are nobody's to be told about. The
customer reads theirs at `GET /api/v1/customers/me/notifications`, and staff read any customer's —
the first thing to check when someone says they were never told about a payment. Both pages in the
frontend show the list.

**Each one is written once.** Kafka delivers at least once and the outbox relay retries a send it
is unsure of, so the same message can arrive twice. The table carries a unique key on
`(transaction reference, account, status)`, and the consumer checks it before writing, so a
redelivery is a quiet no-op. Without the check, it would be a constraint violation: the listener
fails, retries, logs an error and skips the record. That is a false alarm on every redelivery. Messages are keyed by reference, so every copy of one lands on the same
partition and is handled by the same thread, never two at once.

**A reversal is announced once.** Reversing a posting publishes three events: the original, the
correcting `REVERSAL` posting, and the original again carrying `REVERSED`. Announcing all three
would tell the customer "500.00 INR debited" and "your credit was reversed" about one correction.
The `REVERSAL` posting is skipped, and the re-published original becomes "A credit of 500.00 INR to
account XXXX0001 was reversed". Its status is part of the unique key, so it sits beside the original
notification rather than colliding with it.

**Each side of an FX transfer hears its own currency.** The transaction's currency is the
sender's, so a dollar account told it received rupees would be told nonsense. Each notification
takes its account's currency and its own leg's amount.

Account numbers are masked to the last four digits. The message is rendered once, when the row is
written, and stored. It is the record of what the customer was told, so it cannot later be
re-derived into something different.

The consumer took over the `corebank-app` consumer group from the demo listener it replaced, which
only logged what it read. A fresh group starts from the earliest offset (`auto-offset-reset:
earliest`), and would have replayed the topic's whole retention as a burst of alerts about old
postings. Keeping the group means it resumes where the logger stopped.

**Missed standing-order payments are notifications too.** A refused scheduled transfer posts
nothing, so a feature built only on postings never heard of it. The payer was not told their rent
had not gone out, nor that the instruction had stopped after three refusals. The schedule side now
writes the notification itself, in the same transaction that records the failure. There is no
Kafka hop: the failure is a local fact with no event to consume, and writing both together means
the notification exists exactly when the recorded failure does. It sits inside the runner's guard
that lets an occurrence be recorded only once, so a second replica or a crash re-run announces
nothing twice. `uk_notification_schedule_once` backs that up. The message gives the amount, the
masked account, the due date, the reason, and what happens next: the next date, or that it has
stopped and how to restart it.

A notification now has a `kind`. A posting's reference, status and direction are present only on
a `TRANSACTION`, and the schedule and due date only on the other two. `ck_notification_shape`
enforces that, since relaxing V14's `NOT NULL`s would otherwise have allowed a transaction
notification with no transaction.

**Interest and FX revaluation now reach Kafka.** Building this showed they never had. Both saved
their posting straight to the repository instead of through `TransactionService`, so neither
published an event. Interest never reached search, the insights service or a notification. The
cache for the interest expense account was never evicted. Neither showed in the posting metrics.
Both now go through the same path as every other posting. Earlier ones can be backfilled into
search and insights with the existing replay endpoint, and that does not notify — see below.

**A replay announces nothing.** The admin replay and search rebuilding a lost index both re-publish
history through this same topic — the rebuild from the beginning of time. Treated as live, a wiped
OpenSearch volume would alert every customer about every posting they had ever made. Replayed
events carry `replayed: true` and the consumer skips them. Like `status`, the field is additive: a
message from before it existed has no value, which reads as live, and every such message was.

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
automatically; and a failed search request — a transport failure or a server-side error such as
querying an index that doesn't exist yet — comes back as a clean `503 SEARCH_UNAVAILABLE`
instead of an unhandled `500`.

**Losing OpenSearch's data doesn't lose search permanently.** `SearchIndexInitializer` backfills
an index the instant it creates one, re-deriving every customer and transaction from the ledger
through the same outbox path the admin replay endpoints use (see Events above) — so "the index
did not exist yet" is treated as exactly what it is: a fresh start that needs its history rebuilt,
not a routine restart that should leave existing data alone. Without this, a wiped OpenSearch
volume, a fresh environment, or an index dropped by hand would leave search silently and
permanently empty for every record that predates the loss, since nothing else in the application
ever re-sends an event for something that already exists.

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

Its dependencies are **pinned in a lock file**, which is the one place this service differs in
kind from the rest of the repo. The Java side gets reproducible builds from the Spring Boot BOM
and the frontend from `package-lock.json`; `insights/` had neither, just floors like
`mlflow>=2.20`, so every image build resolved afresh against whatever PyPI was serving. It had
already drifted a full major version (mlflow 3.x against pins written for 2.x) with no change to
any file in the repo. Now `requirements.txt` declares bounded ranges and
`requirements.lock` pins all 99 packages, transitive ones included; the Dockerfile and CI both
install from the lock. Regenerate it with the command in that file's header — inside
`python:3.13-slim`, so the pins match the platform the service actually runs on. CI re-resolves
the declared ranges with the lock as a constraint set, so a lock that drifts outside its own
declared bounds fails the build instead of going unnoticed.

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
| `PATCH` | `/api/v1/customers/{id}/kyc` | ADMIN | Record a KYC decision: `kycStatus`, and a `reason` for anything but VERIFIED |
| `GET` | `/api/v1/customers/{id}/kyc-decisions` | TELLER, ADMIN | Every KYC decision, who made it and why, newest first |
| `GET` | `/api/v1/customers/me/notifications` | CUSTOMER | What the caller has been told, newest first |
| `GET` | `/api/v1/customers/{id}/notifications` | TELLER, ADMIN | What a customer has been told, newest first |
| `PATCH` | `/api/v1/customers/{id}/identity` | TELLER, ADMIN | Link a Keycloak identity to this customer |
| `POST` | `/api/v1/accounts` | TELLER, ADMIN | Open an account |
| `GET` | `/api/v1/accounts/{id}` | owner, staff | Fetch one account (cached) |
| `GET` | `/api/v1/accounts/{id}/balance` | owner, staff | Current and available balance |
| `GET` | `/api/v1/customers/{id}/accounts` | owner, staff | Accounts a customer holds |
| `POST` | `/api/v1/accounts/{id}/freeze` | TELLER, ADMIN | Freeze an account; needs a `reason` |
| `POST` | `/api/v1/accounts/{id}/unfreeze` | TELLER, ADMIN | Return it to service; `reason` optional |
| `POST` | `/api/v1/accounts/{id}/close` | ADMIN | Close an account: zero balance, no active holds, no live or suspended standing instructions, and a `reason` |
| `GET` | `/api/v1/accounts/{id}/status-changes` | TELLER, ADMIN | Every freeze, unfreeze and closure, who made it and why, newest first |
| `POST` | `/api/v1/accounts/{id}/deposits` | TELLER, ADMIN | Deposit — needs `Idempotency-Key` |
| `POST` | `/api/v1/accounts/{id}/withdrawals` | TELLER, ADMIN | Withdraw — needs `Idempotency-Key` |
| `POST` | `/api/v1/transfers` | TELLER, ADMIN | Transfer — needs `Idempotency-Key` |
| `POST` | `/api/v1/accounts/{id}/holds` | TELLER, ADMIN | Place an authorisation hold — needs `Idempotency-Key` |
| `POST` | `/api/v1/holds/{reference}/capture` | TELLER, ADMIN | Turn it into a posting — needs `Idempotency-Key` |
| `POST` | `/api/v1/holds/{reference}/release` | TELLER, ADMIN | Give up the reservation |
| `GET` | `/api/v1/holds/{reference}` | TELLER, ADMIN | One hold and what became of it |
| `GET` | `/api/v1/accounts/{id}/holds` | owner, staff | Holds against one account, newest first |
| `POST` | `/api/v1/scheduled-transfers` | TELLER, ADMIN | Set up a standing instruction |
| `GET` | `/api/v1/scheduled-transfers/{id}` | TELLER, ADMIN | One instruction, and how it has fared |
| `GET` | `/api/v1/accounts/{id}/scheduled-transfers` | owner, staff | Instructions against one account, both directions |
| `POST` | `/api/v1/scheduled-transfers/{id}/cancel` | TELLER, ADMIN | Stop one |
| `POST` | `/api/v1/scheduled-transfers/{id}/resume` | TELLER, ADMIN | Restart a suspended one from its next occurrence |
| `POST` | `/api/v1/transactions/{reference}/reversal` | ADMIN | Reverse a posting — needs `Idempotency-Key` and a `reason` |
| `GET` | `/api/v1/accounts/{id}/transactions` | owner, staff | Statement, newest first |
| `GET` | `/api/v1/transactions/{reference}` | TELLER, ADMIN | One transaction, its legs, and who made it (`initiatedBy`) |
| `GET` | `/api/v1/fx/quote` | any | What an amount converts to: `from`, `to`, `amount` |
| `GET` | `/api/v1/fx/position` | ADMIN | The bank's own currency exposure, valued into the reporting currency |
| `POST` | `/api/v1/fx/revalue` | ADMIN | Mark the book to market, posting the change since the last close |
| `GET` | `/api/v1/search/transactions` | TELLER, ADMIN | Bank-wide search: `q`, `type`, `minAmount`/`maxAmount`, `from`/`to`; each hit carries its `status` |
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
| `ALREADY_REVERSED` | 409 | That transaction has already been reversed |
| `HOLD_NOT_ACTIVE` | 409 | That hold was already captured, released or expired |
| `INSUFFICIENT_FUNDS` | 422 | Available balance, including overdraft, is too low |
| `TRANSACTION_LIMIT_EXCEEDED` | 422 | One posting exceeded the per-transaction ceiling |
| `DAILY_LIMIT_EXCEEDED` | 422 | The account's daily debit allowance is spent; the message says how much remains |
| `ACCOUNT_FROZEN` / `ACCOUNT_CLOSED` | 422 | The account cannot take postings |
| `CUSTOMER_NOT_ELIGIBLE` | 422 | Not active, or KYC not verified |
| `STATUS_REASON_REQUIRED` | 422 | A freeze or closure was requested without saying why |
| `STATUS_UNCHANGED` | 422 | The account already has the status requested |
| `KYC_REASON_REQUIRED` | 422 | A KYC decision other than VERIFIED was made without saying why |
| `CUSTOMER_NOT_VERIFIED` | 422 | Money cannot leave this account: its owner's KYC is no longer verified. Payments in still arrive |
| `CURRENCY_MISMATCH` | 422 | The account is held in another currency |
| `SAME_ACCOUNT_TRANSFER` | 422 | Source and destination are the same account |
| `FX_RATE_UNAVAILABLE` | 422 | The bank does not quote that currency pair |
| `CURRENCY_NOT_SUPPORTED` | 422 | The bank holds no accounts in that currency |
| `NOT_REVERSIBLE` | 422 | A system posting (interest, FX revaluation) is corrected by the process that produced it, not by reversal |
| `FX_AMOUNT_TOO_SMALL` | 422 | The amount converts to less than the smallest unit of the target currency |
| `SCHEDULE_STARTS_IN_PAST` | 422 | A standing instruction cannot be backdated |
| `SCHEDULE_NEVER_RUNS` | 422 | The window contains no occurrence |
| `SCHEDULE_NOT_ACTIVE` | 422 | That instruction has already stopped |
| `OVERDRAFT_NOT_ALLOWED` | 422 | Savings accounts cannot carry an overdraft |
| `BALANCE_NOT_ZERO` | 422 | An account must be emptied before it is closed |
| `INTERNAL_ACCOUNT` | 422 | General-ledger accounts are not addressable here |
| `REVERSAL_NOT_REVERSIBLE` | 422 | A reversal cannot itself be reversed |
| `HOLD_EXPIRED` | 422 | The hold is past its expiry and can no longer be captured |
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
│   ├── notification/  Customer notifications, written once per posting off Kafka
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
| `COREBANK_DB_POOL_SIZE` | `20` | HikariCP `maximum-pool-size`; size to the deployment's own concurrency and Postgres's `max_connections`, not a value to guess once |
| `COREBANK_OIDC_ISSUER_URI` | `http://localhost:8081/realms/corebank` | Compared against every token's `iss` claim |
| `COREBANK_OIDC_JWK_SET_URI` | `http://localhost:8081/realms/.../certs` | Where signing keys are actually fetched from |
| `COREBANK_REDIS_HOST` / `_PORT` | `localhost` / `6379` | |
| `COREBANK_KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | |
| `COREBANK_ALLOWED_ORIGINS` | `http://localhost:5173` | CORS; comma-separated for more than one |
| `COREBANK_OTLP_TRACING_ENDPOINT` | `http://localhost:4318/v1/traces` | Where spans are exported to (Tempo, or any OTLP/HTTP collector) |
| `COREBANK_OPENSEARCH_URI` | `http://localhost:9200` | Search index; an outage degrades `/api/v1/search/**` to `503`, nothing else |
| `COREBANK_GRPC_PORT` | `9091` | gRPC listener; 9091 rather than 9090, which Prometheus owns |
| `COREBANK_SAVINGS_ANNUAL_RATE` | `0.0350` | Annual rate on savings balances, as a fraction |
| `COREBANK_DAY_COUNT_BASIS` | `365` | Denominator the daily rate is derived from; a policy choice, not a fact |
| `COREBANK_INTEREST_ENABLED` | `true` | Set `false` to keep the accrual runner out of a replica |
| `COREBANK_DAILY_DEBIT_LIMIT` | `200000.00` | Per account, per UTC calendar day, across withdrawals and outgoing transfers |
| `COREBANK_SINGLE_TRANSACTION_LIMIT` | `100000.00` | One posting's ceiling |
| `COREBANK_HOLD_SWEEP_ENABLED` | `true` | Set `false` to keep the hold-expiry sweep out of a replica. Housekeeping only — a capture past the expiry instant is refused whether or not the sweep has run |
| `COREBANK_SCHEDULED_TRANSFERS_ENABLED` | `true` | Set `false` to keep the standing-instruction runner out of a replica entirely. Safe on any number of replicas when left on -- the claim is `SKIP LOCKED` and each occurrence carries a derived idempotency key |
| `SERVER_PORT` | `8080` | |

The insights service is configured separately, with an `INSIGHTS_` prefix:

| Variable | Default | Notes |
| --- | --- | --- |
| `INSIGHTS_DATABASE_URL` | `postgresql://corebank:corebank@localhost:5432/insights` | Its own database; created on first start if absent |
| `INSIGHTS_KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | |
| `INSIGHTS_COREBANK_API_URL` | `http://localhost:8080` | Only used to resolve a customer's account numbers |
| `INSIGHTS_ALLOWED_ORIGINS` | `http://localhost:5173` | CORS; the frontend calls this service directly from the browser, so it needs its own grant separate from `COREBANK_ALLOWED_ORIGINS` |
| `INSIGHTS_OIDC_ISSUER` / `_JWKS_URL` | Keycloak realm | Same split as the backend's issuer/JWK-set pair |
| `INSIGHTS_MLFLOW_TRACKING_URI` | `sqlite:////var/lib/insights/mlflow.db` | SQLite, not `file:` — see LOCAL_SETUP |

The defaults exist so the project starts on a laptop with no setup. None of them are
appropriate anywhere else — in particular, `COREBANK_OIDC_ISSUER_URI` and `_JWK_SET_URI` need
real values pointing at wherever Keycloak actually runs in any environment beyond a laptop.

---

## Working on it

`main` is protected: no direct pushes — including from repo admins — and the required CI checks
(`Backend build and test`, `Frontend build and typecheck`,
`Spending insights (Python) build and test`, `Docker image build and scan`) must pass on a branch
that's up to date with `main` before it can merge. CodeQL runs on every change too but is
deliberately not required — its findings belong in the Security tab, not blocking a merge on a
scanner's opinion.

```bash
git checkout -b feat/my-change
# ... make changes, then:
git push -u origin feat/my-change
gh pr create --base main
```

If you ever genuinely need to bypass this, turn protection off in **Settings → Branches**, do
what you need, and turn it back on.

---

## Scope, and what is deliberately not here

Built across Phase 1–7: Java 21 · Spring Boot · Spring Security · Hibernate · PostgreSQL · REST ·
OpenAPI/Swagger · Docker · JUnit · Git · Keycloak/OIDC · Redis · Kafka · React + TypeScript ·
OpenTelemetry · Prometheus · Grafana · GitHub Actions · CodeQL · Trivy · SonarQube ·
Testcontainers · REST Assured · k6 · Kubernetes (`kind`, locally) · OpenSearch · gRPC/protobuf ·
Terraform (local `kind` only) · Python · FastAPI · scikit-learn · MLflow.

Deliberately not here: AWS, and the cloud half of Terraform — this project stops at a local `kind`
cluster by design, not as a placeholder for a phase still to come. The seam for a cloud provider
is already there if it's ever needed — a stateless application proven under Kubernetes and already
provisioned by Terraform, so a cloud cluster would be a provider change rather than a rewrite —
but it isn't on this project's roadmap.
