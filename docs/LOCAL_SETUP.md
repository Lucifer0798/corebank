# Local development setup (Windows)

How to get CoreBank Lite running on a Windows machine, and how the local environment on this
machine is actually wired. The project [README](../README.md) covers what the system does;
this covers the plumbing underneath it.

There are three ways to run the application, in increasing order of how much they resemble
production. You do not need all of them.

| | Needs | Database | Use it for |
| --- | --- | --- | --- |
| `dev` profile | nothing | H2, in memory | Clicking through Swagger in 30 seconds |
| Default profile | PostgreSQL on the host | PostgreSQL 17 on `5432` | Day-to-day development |
| Docker Compose | Docker Desktop | PostgreSQL 17 in a container | Checking the deployable artefact |

---

## 1. The fastest path — no database at all

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

H2 runs in memory in PostgreSQL compatibility mode, Flyway applies the same migrations, and a
seeded customer with two funded accounts is created on startup. Swagger UI is at
<http://localhost:8080/swagger-ui.html>. Everything vanishes when you stop the process.

This is also what `./mvnw test` uses, so the H2 path stays honest — the migrations are written
in portable SQL precisely so the same files run on both engines.

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

Override the database port with `POSTGRES_HOST_PORT` if 5433 is taken. The application
container always reaches the database at `postgres:5432` over the compose network, so the
published port only matters to a client on the host.

Only one thing can own 8080 at a time, so stop the local `spring-boot:run` before
`docker compose up`, or vice versa.

---

## Logins

The application creates an `admin` login the first time it finds an empty user table, using
`COREBANK_ADMIN_PASSWORD` (default `ChangeMe#2025!`). Every other login is created through
`POST /api/v1/auth/users` as that administrator.

Under the `dev` profile you also get `teller1` / `Teller#2025` and `asha` / `Customer#2025`.

These are development defaults and are fine on a laptop. They are not fine anywhere else — see
the configuration table in the [README](../README.md#configuration) for the variables to set.

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
