# Local development setup (Windows)

How to get CoreBank Lite running on a Windows machine, and how the local environment on this
machine is actually wired. The project [README](../README.md) covers what the system does;
this covers the plumbing underneath it.

There are three ways to run the application, in increasing order of how much they resemble
production. You do not need all of them.

| | Needs | Database | Use it for |
| --- | --- | --- | --- |
| `dev` profile | nothing for the DB; Keycloak still needed for auth | H2, in memory | Browsing the schema via Swagger/H2 console |
| Default profile | PostgreSQL on the host, Keycloak/Redis/Kafka reachable | PostgreSQL 17 on `5432` | Day-to-day development |
| Docker Compose | Docker Desktop | PostgreSQL 17 in a container | Checking the deployable artefact, the frontend, everything together |

Since Phase 2, Keycloak issues every token and there is no local login endpoint, so
**authenticated requests need Keycloak reachable no matter which of these you pick** for the
database. The cheapest way to get that without the whole stack:

```bash
docker compose up -d keycloak redis kafka
```

then run the application however you like from the table above.

---

## 1. The fastest path for the database — H2

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

H2 runs in memory in PostgreSQL compatibility mode, Flyway applies the same migrations, and a
seeded customer with two funded accounts is created on startup. Everything vanishes when you
stop the process.

This is also what `./mvnw test` uses, so the H2 path stays honest — the migrations are written
in portable SQL precisely so the same files run on both engines. The seeded customer is linked
to the fixed Keycloak subject the realm import gives the demo user "asha", so logging in as her
shows exactly this data — but that login still needs Keycloak running (`docker compose up -d
keycloak`); this profile only replaces the database, not authentication.

---

## 2. Against PostgreSQL on the host

### What is installed here

PostgreSQL 17.11 from EDB's **standalone binaries** — extracted, not installed by an MSI, so
it needed no administrator rights and touched no system settings.

| | |
| --- | --- |
| Location | `C:\Akshay\tools\pgsql` |
| Data directory | `C:\Akshay\tools\pgsql\data` |
| Server log | `C:\Akshay\tools\pgsql\server.log` |
| Listening on | `localhost:5432` only — not reachable from the network |
| Superuser | `postgres` / `postgres` |
| Project role and database | `corebank` / `corebank` |
| Autostart | Scheduled task `CoreBank PostgreSQL`, at logon |

`psql` and the other client tools are on the user `PATH`, so they work in any new terminal.

The role and database names match what [`application.yml`](../src/main/resources/application.yml)
already defaults to, which is why the default profile needs no overrides at all:

```bash
./mvnw spring-boot:run
```

### Managing the server

```bash
schtasks /Run /TN "CoreBank PostgreSQL"
```

```bash
C:\Akshay\tools\pgsql\stop-postgres.cmd
```

Check whether it is up:

```bash
netstat -ano | findstr :5432
```

### Why autostart is a scheduled task and not a Startup shortcut

This one cost an evening, so it is worth writing down.

The obvious approach — a `.cmd` in the Startup folder calling `pg_ctl start` — *appears* to
work. The server comes up at logon and then dies a minute or two later with:

```
background worker "logical replication launcher" was terminated by exception 0xC000013A
LOG:  terminating any other active server processes
LOG:  received fast shutdown request
```

`0xC000013A` is `STATUS_CONTROL_C_EXIT`. `pg_ctl start` leaves `postgres.exe` attached to the
console that launched it, so when that console window is closed Windows delivers a close event
to the whole process group and takes the database down with it.

A scheduled task runs with no console attached at all, which removes the problem entirely.
Note that `schtasks /Create /SC ONLOGON` requires administrator rights, but PowerShell's
`Register-ScheduledTask` in the current user's context does not:

```bash
powershell -Command "Get-ScheduledTaskInfo -TaskName 'CoreBank PostgreSQL'"
```

### Talking to the database directly

```bash
psql -h localhost -p 5432 -U corebank -d corebank
```

The password is `corebank`. Two queries worth knowing — the ledger, and the invariant that
must always hold:

```sql
SELECT t.type, e.direction, e.amount, e.balance_after, a.account_number
  FROM ledger_entry e
  JOIN bank_transaction t ON t.id = e.transaction_id
  JOIN account a ON a.id = e.account_id
 ORDER BY e.posted_at, e.sequence_no;
```

```sql
SELECT SUM(CASE WHEN direction = 'DEBIT' THEN amount ELSE -amount END) FROM ledger_entry;
```

That second one must return exactly `0`. If it ever does not, a posting reached the ledger
unbalanced and something in `BankTransaction.assertBalanced` has been bypassed.

---

## 3. Against Docker

### What is installed here

Docker Desktop 4.87 (engine 29.7.2) on the WSL2 backend. Windows 11 Home has no Hyper-V
backend, so WSL2 is the only option; installing it enables the Virtual Machine Platform
Windows feature and **requires a reboot** before the engine will start.

```bash
docker compose up --build
```

First build takes several minutes — it pulls the JDK base image and resolves the whole Maven
dependency tree inside the container. Subsequent builds reuse the dependency layer unless
`pom.xml` changes, which is the reason the Dockerfile copies `pom.xml` before `src/`.

Stop it, and drop the database volume too:

```bash
docker compose down -v
```

### Ports

The compose stack publishes its database on **5433**, not 5432, so it coexists with the host
cluster. They are entirely separate databases with separate data.

| Port | What |
| --- | --- |
| 5432 | PostgreSQL on the host |
| 5433 | PostgreSQL inside the compose stack |
| 8080 | The application — whichever way you started it |
| 8081 | Keycloak |
| 8082 | Kafka UI |
| 6379 | Redis |
| 9200 | OpenSearch |
| 3000 | Grafana |
| 9090 | Prometheus |
| 3200 / 4318 | Tempo (API / OTLP receiver) |
| 9000 | SonarQube (optional overlay, not part of the default stack) |
| 5173 | The frontend dev server |

Override the database port with `POSTGRES_HOST_PORT` if 5433 is taken. The application
container always reaches the database at `postgres:5432` over the compose network, so the
published port only matters to a client on the host.

Only one thing can own 8080 at a time, so stop the local `spring-boot:run` before
`docker compose up`, or vice versa.

---

## 4. Keycloak, Redis, Kafka

All three come from `docker compose up`; there is no host-native install for any of them on
this machine. Bring up just what you need alongside a host-mode backend:

```bash
docker compose up -d keycloak redis kafka
```

| | Port | Notes |
| --- | --- | --- |
| Keycloak | `8081` | Admin console at `/`, `admin` / `admin`. Realm, roles, clients and demo users come from [keycloak/corebank-realm.json](../keycloak/corebank-realm.json), imported at container start. |
| Redis | `6379` | `docker exec corebank-redis-1 redis-cli KEYS "accounts*"` to see what's cached. |
| Kafka | not published to the host | Only the app and Kafka UI (inside the compose network) ever talk to it. Watch it via Kafka UI instead. |
| Kafka UI | `8082` | Browse the `corebank.transactions.posted` topic as postings happen. |

**Keycloak does not persist anything.** `start-dev` with no volume mounted means its database
lives in the container's own writable layer, gone on removal. That is deliberate — it keeps the
realm import (and any edits made through this file) the actual source of truth rather than
diverging admin-console state, but it also means:

- `docker compose restart keycloak` reuses the same container filesystem, so it **does not**
  re-run `--import-realm` if the realm already exists — a change to `corebank-realm.json` will
  look like it did nothing.
- To pick up a realm change, force a real recreate:
  ```bash
  docker compose up -d --force-recreate keycloak
  ```
  This cost real time once: after editing the realm file, `restart` kept silently serving the
  stale import while every fix looked like it had failed.

### Logins

Keycloak owns every login now; there is no local login endpoint. Demo users, from the realm
import:

| Username | Password | Role |
| --- | --- | --- |
| `admin` | `ChangeMe#2025!` | ADMIN |
| `teller1` | `Teller#2025` | TELLER |
| `asha` | `Customer#2025` | CUSTOMER |

These are development defaults and are fine on a laptop. They are not fine anywhere else — see
the configuration table in the [README](../README.md#configuration) for the variables to set.

---

## 5. The frontend

```bash
cd frontend
npm install
npm run dev
```

Node 22 / npm 11 on this machine, nothing further to install. Opens on
<http://localhost:5173>, which is already the origin `corebank-realm.json`'s client redirects to
and the CORS origin the backend allows by default. It needs Keycloak and the API already
running — neither is optional, since login goes straight to Keycloak and every other call goes
straight to the API.

---

## 6. Observability -- Prometheus, Tempo, Grafana

All three come from `docker compose up`; there is no host-native install. Config lives in
[observability/](../observability/) -- Prometheus's scrape target, Tempo's receiver config, and
Grafana's provisioned datasources and dashboard.

| | Port | Notes |
| --- | --- | --- |
| Grafana | `3000` | No login (anonymous access, admin role, for local convenience only). **CoreBank Overview** dashboard is provisioned automatically. |
| Prometheus | `9090` | Scrapes `app:8080/actuator/prometheus` every 5s. |
| Tempo | `3200` (API), `4318` (OTLP/HTTP receiver) | Grafana queries it over the compose network; both ports are also published to the host for poking at directly. |

Traces show up under Grafana's Explore view with the Tempo datasource selected, or via
`curl "http://localhost:3200/api/search?limit=10"` directly.

**Config file changes here need a real recreate, not a restart** -- same lesson as Keycloak's
realm import in section 4: `docker compose up -d tempo` reuses the running container and never
re-reads a changed `tempo.yaml`. Use `docker compose up -d --force-recreate tempo`.

**Tempo's config schema changed in 3.x.** The classic single-binary `ingester:`/`compactor:`
blocks from most getting-started guides fail to parse against Tempo 3.0.3 with `field ingester
not found in type app.Config`. The working minimal config only needs `server`,
`distributor.receivers.otlp` and `storage.trace` -- see [observability/tempo.yaml](../observability/tempo.yaml).
Also set the OTLP receiver's `endpoint` explicitly to `0.0.0.0:4318`/`0.0.0.0:4317`: left
blank, Tempo binds `127.0.0.1` only, which is unreachable from any other container including
the app.

**A second, unconfigured OTLP metrics exporter fires every minute regardless of Prometheus
scraping.** `spring-boot-starter-opentelemetry` auto-enables push-based OTLP metrics export in
addition to whatever else is configured; without `management.otlp.metrics.export.enabled:
false`, it logs a connection failure to `localhost:4318` every minute since nothing is listening
there for metrics (only Tempo's tracing receiver is). Harmless but noisy -- already disabled in
`application.yml` since this project uses Prometheus scraping for metrics instead.

---

## 7. Static analysis -- SonarQube (optional, local only)

```bash
docker compose -f compose.yaml -f compose.sonar.yml up -d sonarqube
```

Heavy (bundled Elasticsearch, a couple of GB, a slow first start -- give it a minute or two).
Once `curl http://localhost:9000/api/system/status` reports `"status":"UP"`:

1. Open <http://localhost:9000>, log in as `admin` / `admin`.
2. Generate a token: My Account → Security, or `curl -u admin:admin -X POST
   "http://localhost:9000/api/user_tokens/generate" -d "name=corebank-local"`.
3. Run the analysis:
   ```bash
   ./mvnw verify sonar:sonar -Dsonar.host.url=http://localhost:9000 -Dsonar.token=<token>
   ```

Coverage comes from the JaCoCo XML report the `test` phase already produces at
`target/site/jacoco/jacoco.xml` (configured via `sonar.coverage.jacoco.xmlReportPaths` in
`pom.xml`), so `verify` (which runs `test`) has to run before `sonar:sonar`, not the other way
round.

`docker compose -f compose.yaml -f compose.sonar.yml stop sonarqube` when done -- it is not part
of the default stack and has no reason to stay running between sessions. Its data persists in
named volumes (`sonarqube-data`, `sonarqube-extensions`) across stop/start, just not across
`down -v`.

---

## 8. Testcontainers, REST Assured, k6

**Testcontainers** (`CoreBankTestcontainersIT`) needs only Docker Desktop running — it starts
and stops its own Postgres/Redis/Kafka/Keycloak containers per run, so nothing from section 4
needs to be up first. Run it with `./mvnw test -Dtest=CoreBankTestcontainersIT`; see the
[README](../README.md#against-real-infrastructure) for why it's excluded from the default
`mvn test` run.

**k6** has no standalone install on this machine — it runs from the `grafana/k6` Docker image
instead, joined to the compose network so it can reach the app and Keycloak by their container
DNS names (`app`, `keycloak`) rather than needing `--network host`, which Docker Desktop doesn't
support the way native Linux does. See the [README](../README.md#load-testing-money-movement)
for the exact command. **On Windows Git Bash, prefix it with `MSYS_NO_PATHCONV=1`** — without
that, Git Bash rewrites the container path `/scripts` into a Windows host path
(`C:/Program Files/Git/scripts`) before Docker ever sees it, and k6 fails to find the script.

**REST Assured** is a test-scoped Maven dependency only (`io.rest-assured:rest-assured`) — it's
the HTTP client `CoreBankTestcontainersIT` drives requests with, not a separate tool to install
or run.

---

## 9. Kubernetes -- kind, locally

### What is installed here

`kind` v0.31.0 as a **standalone binary** at `C:\Akshay\tools\kind\kind.exe`, on the user `PATH`
— same pattern as PostgreSQL in section 2, no administrator rights needed. `kubectl` v1.36.1
comes bundled with Docker Desktop already, at
`C:\Program Files\Docker\Docker\resources\bin\kubectl`. `helm` is not installed: this phase's
manifests are plain YAML plus a `kustomization.yaml`, since Helm wasn't part of what this phase
actually needed.

```bash
kind create cluster --name corebank
```

Creates a single-node cluster (control-plane doubles as worker) using Docker Desktop's existing
engine — no separate VM, no cloud account. `kubectl cluster-info --context kind-corebank` and
`kubectl get nodes` confirm it's up.

### Deploying

```bash
docker compose build app
bash k8s/deploy.sh
```

`deploy.sh` loads the locally built image straight into the kind node
(`kind load docker-image corebank-app:latest --name corebank` — no registry involved), creates
the one ConfigMap `kustomize` can't generate itself (the Keycloak realm import — see the comment
in [k8s/kustomization.yaml](../k8s/kustomization.yaml) for why), applies everything else via
`kubectl apply -k k8s/`, and waits for the app Deployment to roll out.

```bash
kubectl port-forward svc/app -n corebank 8080:8080
kubectl port-forward svc/keycloak -n corebank 8081:8080
```

Same ports, same demo logins as the Compose stack. Tear the cluster down entirely with
`kind delete cluster --name corebank` when done — it holds no state worth keeping between
sessions.

### Two bugs a real cluster found that compose.yaml never could

**A single-node Kafka broker deadlocks registering its own controller through its own Service.**
KRaft's `KAFKA_CONTROLLER_QUORUM_VOTERS` pointed at the `kafka` Service name, matching
compose.yaml's pattern exactly — except a Kubernetes Service only routes to pods that already
pass their readiness probe, and this pod can't pass readiness until it finishes registering with
itself as controller. Confirmed against this real cluster: the pod crash-looped repeatedly on
`unable to register with the controller quorum` until `KAFKA_CONTROLLER_QUORUM_VOTERS` pointed
at `localhost` instead — correct anyway, since controller and broker are the same process in a
single-node setup, not a workaround. See [k8s/kafka.yaml](../k8s/kafka.yaml).

**Kubernetes' exec-probe default timeout is too short for a probe that boots a JVM.** Kafka's
readiness probe runs `kafka-broker-api-versions.sh`, which launches a fresh JVM per invocation.
compose.yaml's equivalent healthcheck already set `timeout: 5s` for exactly this reason; the K8s
manifest didn't repeat it, so Kubernetes' own 1-second default applied instead — confirmed
against this real cluster: 63 consecutive readiness failures logged as `command timed out after
1s` while the broker itself had already started cleanly. Fixed with an explicit
`timeoutSeconds: 10`.

Neither of these has a compose.yaml equivalent to have caught them by comparison — compose has
no concept of a Service routing only to ready pods, and Docker Compose healthchecks don't share
Kubernetes' exec-probe timeout default. Both were only findable by actually standing up the
cluster.

**Plain Deployments have no `depends_on: condition: service_healthy`.** Without gating, the app
container starts immediately alongside Postgres/Kafka/Keycloak, fails fast on the still-
unreachable database (Spring Boot doesn't retry a failed datasource connection at startup), and
crash-loops until Kubernetes' restart backoff happens to land after the dependency is ready.
Confirmed against this real cluster: the app pod restarted 3 times on `Connection refused`
before init containers (`k8s/app.yaml`) were added to gate its start on Postgres, Kafka and
Keycloak all being reachable first.

---

## 10. OpenSearch (search)

Comes from `docker compose up`, same as Keycloak/Redis/Kafka -- no host-native install. Unlike
those three, the app deliberately does **not** wait for it at startup (no `wait-for-opensearch`
init container in `k8s/app.yaml` either): search is a downstream projection, and the app is
designed to start and serve every other endpoint whether or not OpenSearch is up yet. Confirmed
against both the real compose stack and the real `kind` cluster -- the app pod comes up healthy
with 0 restarts even when OpenSearch is still initializing.

```bash
curl http://localhost:9200/corebank-transactions/_search
curl http://localhost:9200/corebank-customers/_search
```

to see the raw indexed documents. Both indices are created automatically on startup
(`SearchIndexInitializer`) if they don't already exist.

**Two real bugs this integration found, neither catchable without a real Kafka listener
container actually starting:**

- **Supplying both a `spring.kafka.consumer.value-deserializer` property and a deserializer
  *instance* to a `ConsumerFactory` is a combination Spring Kafka rejects outright** with
  `IllegalStateException: JsonDeserializer must be configured with property setters, or via
  configuration properties; not both`. This only surfaces when a listener container actually
  tries to start a real consumer -- the mocked-JWT test suite never does, so it stayed invisible
  until `docker compose up` actually booted the app. Fixed in `KafkaConsumerConfig` by stripping
  `key/value.deserializer` out of `kafkaProperties.buildConsumerProperties()` before handing the
  map to `DefaultKafkaConsumerFactory`, since every listener here supplies its own typed
  deserializer instance instead of relying on that YAML property.
- **The OpenSearch Java client's Jackson-based response deserializer fails hard on any indexed
  field a DTO doesn't declare** (`UnrecognizedPropertyException`), the opposite of Spring's own
  `@JsonIgnoreProperties`-free default elsewhere in this codebase. `CustomerSearchHit` doesn't
  expose the index document's `changedAt` field, and every single search request 500'd until
  `@JsonIgnoreProperties(ignoreUnknown = true)` was added to both search-result DTOs.

---

## Troubleshooting

**Port 5432 already in use.** Something else is bound to it. `netstat -ano | findstr :5432`
gives the PID; `tasklist | findstr <pid>` names it. If it is a stale `postgres.exe`, stop it
with `stop-postgres.cmd` rather than killing it.

**PostgreSQL will not start, and the log ends mid-sentence.** Check for a stale
`C:\Akshay\tools\pgsql\data\postmaster.pid` left behind by a hard kill. If no `postgres.exe` is
running, deleting that file is safe; if one *is* running, do not.

**PostgreSQL starts and then dies a minute later.** See the console-attachment explanation
above. Something is starting it from a console window that later closes.

**`docker` command works but nothing runs.** The CLI is installed but the engine is not up.
Launch Docker Desktop and wait for the whale to stop animating, then `docker info` should
report a `ServerVersion`. Straight after installing WSL2 this will keep failing until you
reboot.

**Flyway reports a checksum mismatch.** A migration that has already been applied was edited.
For a development database the fix is to throw it away — `docker compose down -v`, or
`DROP DATABASE corebank` on the host — and let the migrations run clean.

**Hibernate fails at startup with a schema validation error.** An entity mapping and the
migrations have drifted apart. This is deliberate: `ddl-auto: validate` means Hibernate never
silently reshapes the database, so the fix is a new migration, never a change to the entity
alone.

**A Keycloak-issued token is missing the `sub` claim, and `/customers/me` 500s instead of
404ing.** `sub` in the *access* token (not the ID token, which always has it) turned out to
depend on the client's granted scopes in a way that was not obvious: even with `openid` and
`basic` requested, it stayed absent until the client carried an explicit `oidc-sub-mapper`
protocol mapper — see the `protocolMappers` block on `corebank-web` in the realm file. If a
custom client is added without copying that mapper, expect the same symptom.

**Editing `corebank-realm.json` seems to do nothing.** You almost certainly restarted Keycloak
instead of recreating it — see the note in [section 4](#4-keycloak-redis-kafka) above.

**A cached `GET /accounts/{id}` throws `ClassCastException: LinkedHashMap cannot be cast to
AccountResponse`.** This one only shows up when two different callers hit the same cache key
(the first request that populates the entry always "succeeds" since it deserializes what it
just wrote in the same JVM run). The fix already in `CacheConfig` is to serialize with
`JacksonJsonRedisSerializer<AccountResponse>`, bound to the one type this cache actually holds,
rather than the generic polymorphic serializer — that one needs `@class` type metadata written
into every entry to know what to deserialize back into, which reusing the application's plain
`ObjectMapper` does not produce. Worth remembering before adding a second cached type.

---

## Rebuilding this environment from scratch

PostgreSQL, without administrator rights:

```bash
curl -L -o pgsql.zip https://get.enterprisedb.com/postgresql/postgresql-17.11-1-windows-x64-binaries.zip
```

Extract to `C:\Akshay\tools`, then initialise the cluster and create the project's role and
database:

```bash
C:\Akshay\tools\pgsql\bin\initdb.exe -D C:\Akshay\tools\pgsql\data -U postgres -A scram-sha-256 --pwfile=pw.txt -E UTF8 --locale=C
```

```bash
psql -h localhost -U postgres -c "CREATE ROLE corebank LOGIN PASSWORD 'corebank'; CREATE DATABASE corebank OWNER corebank;"
```

Docker Desktop, which does need administrator rights and a reboot:

```bash
wsl --install --no-distribution
```

```bash
winget install --id Docker.DockerDesktop --exact --silent --accept-package-agreements --accept-source-agreements
```

`kind`, without administrator rights:

```bash
mkdir -p /c/Akshay/tools/kind
curl -fsSL -o /c/Akshay/tools/kind/kind.exe https://kind.sigs.k8s.io/dl/v0.31.0/kind-windows-amd64
```

Then add `C:\Akshay\tools\kind` to the user `PATH` (`[Environment]::SetEnvironmentVariable('Path',
"$([Environment]::GetEnvironmentVariable('Path','User'));C:\Akshay\tools\kind", 'User')` in
PowerShell).
