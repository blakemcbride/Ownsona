# OwnSona Auto-Update Facility

OwnSona keeps its **database and stored data current with the deployed
code automatically, at server startup, with no manual SQL step**. A
deploy is just a WAR swap; the running server detects that the database
is behind the code and brings it forward itself.

This document describes that facility: what it is, the three stages it
runs, how each one behaves under success and failure, and how to add a
new auto-applied change.

> There is **no** facility that updates the *application code / WAR*
> itself (no phone-home, no self-downloading binary). "Auto-update" here
> means the server, after a normal WAR redeploy, automatically migrates
> the **schema and data** it finds to match the new code. Code updates
> are operator-driven deploys (see `Deploy.md` / `INSTALL.md`).

---

## 1. The big picture

When the `/mcp` servlet class loads, its static initializer runs three
startup walkers **in a fixed order**:

| # | Stage | Class | Granularity | Tracks | Gated by |
|---|---|---|---|---|---|
| 1 | Schema migration | `ai.ownsona.migrations.DbMigrator` | whole database | `db_version` table | always runs |
| 2 | Per-row upgrade | `ai.ownsona.memory.RecordMigrator` | each `memories` row | `record_version` column | always runs |
| 3 | Re-embedding | `ai.ownsona.embeddings.ReembedJob` | each `memories` row | `(embedding_provider, embedding_model)` | `REEMBED_ON_STARTUP=true` |

The order is **not** arbitrary:

1. **Schema first** — migrations may create the very columns
   (`record_version`, `salience`, …) that the later stages read or
   write.
2. **Per-row upgrade next** — fills new columns on existing rows from
   data those rows already hold.
3. **Re-embedding last** — only meaningful once the schema and row shape
   are current; it is also the only optional / cost-bearing stage.

### What triggers it

```java
// MCPServer.java
@WebServlet(urlPatterns = "/mcp", loadOnStartup = 1)
public class MCPServer extends MCPServerBase {
    static {
        Configurator.setLevel("ai.ownsona", Level.INFO);   // verbose during init
        ...
        try {
            DbMigrator.runOnStartup();                      // stage 1
            new RecordMigrator(repo).runOnStartup();        // stage 2
            new ReembedJob(repo, provider).runOnStartup();  // stage 3
        } finally {
            Configurator.setLevel("ai.ownsona", Level.ERROR); // quiet afterward
        }
    }
}
```

`loadOnStartup = 1` forces the static initializer (and therefore the
whole auto-update sequence) to run **at Tomcat startup**, not lazily on
the first `/mcp` request. The point is that a failed migration surfaces
in `catalina.out` / `journalctl -u ownsona.service` **at deploy time**,
not when a real client hits the endpoint.

`ai.ownsona` logging is held at **INFO** for the duration of the static
initializer so the startup banner and every migrator line are visible in
the deploy log, then dropped to **ERROR** before the initializer returns
(in a `finally`, so the drop happens even if a stage threw). WARN/ERROR
always appear.

### Two independent version namespaces

Don't conflate them:

- **`db_version`** — an integer for the **whole database** (one row per
  applied migration in the `db_version` table). Owned by `DbMigrator`.
  Expected value = `MigrationRegistry.CURRENT_DB_VERSION` (currently
  **8**).
- **`record_version`** — an integer column **on each `memories` row**.
  Owned by `RecordMigrator`. Expected value =
  `RecordUpgraderRegistry.CURRENT_RECORD_VERSION` (currently **2**).

A schema change that adds a column is a **migration**; backfilling that
column on existing rows from data they already have is a **record
upgrader**. The two often ship together (e.g. `Migration006AddSalience`
adds the `salience` column; `SalienceSeedUpgrader` seeds it from
`importance`).

---

## 2. Stage 1 — `DbMigrator` (schema / `db_version`)

Brings the database from whatever version it is at up to
`CURRENT_DB_VERSION` by applying registered `Migration`s in order.

**Algorithm** (`DbMigrator.runOnStartup`):

1. **Validate the registry** (`MigrationRegistry.validate()`) before
   touching the DB — fail fast on programmer error.
2. **Bootstrap** the `db_version` table: `CREATE TABLE IF NOT EXISTS`,
   then seed version **1** (the `001_init.sql` baseline) with
   `INSERT ... ON CONFLICT (version) DO NOTHING`. Read back
   `MAX(version)` as `current`.
3. **Refuse if the DB is ahead of the code** (`current > target`) —
   throws, so an older WAR can't run against a newer schema (rollback
   guard). The message tells the operator to either deploy code with a
   high enough `CURRENT_DB_VERSION` or restore a snapshot.
4. **Nothing to do** if `current == target` — log and return.
5. **Apply** each registered migration whose `version() > current`, in
   order, advancing `current` after each.

**Per-migration transaction** (`applyOne`): each migration runs on its
**own fresh connection / transaction**. The migration's DDL **and** the
`INSERT INTO db_version` row that records it **commit together**. Any
failure rolls **both** back, so `db_version` is never bumped past a
migration that didn't fully apply — the next startup retries from the
same point.

**Failure = servlet refuses to load.** `runOnStartup` throws
`IllegalStateException` on any failure; `MCPServer.<clinit>` lets it
propagate, so the servlet fails to load and Tomcat surfaces the error.
We never serve traffic on a half-migrated DB.

**The `Migration` contract** (`migrations/Migration.java`):

- `version()` — the version this migration brings the DB **to**. Must be
  exactly one greater than the previous registered migration. Version 1
  is the `001_init.sql` baseline and is **never** a `Migration` object;
  the first registered migration is version **2**.
- `apply(Connection db)` — runs inside the `DbMigrator`-managed
  transaction; throw to abort.
- **Additive only** (invariant #2): add columns, add indexes, insert
  seed rows. Never `DROP`, `RENAME`, or rewrite existing values. (A
  destructive one-time data rewrite is a manual operation under
  "Procedure B," not a migration.)
- **Idempotent**: every migration uses `IF NOT EXISTS` /
  `ADD COLUMN IF NOT EXISTS` so a retry after a partial failure is safe.
- **Permanent**: once shipped, a migration class stays in the codebase
  forever (some old database somewhere may still need it). Don't edit a
  shipped migration — write a new one.

**The registry** (`MigrationRegistry`): an ordered, unmodifiable list
plus `CURRENT_DB_VERSION`. `validate()` enforces that the registered
versions are **exactly** `2, 3, …, CURRENT_DB_VERSION` — contiguous, in
order, no gaps, no duplicates, no empty names — and that the count is
`CURRENT_DB_VERSION - 1`. The current chain:

```
v2  Migration002AddRecordVersion   (adds the record_version column itself)
v3  Migration003AddFreshness
v4  Migration004AddTombstones
v5  Migration005AddKeep
v6  Migration006AddSalience
v7  Migration007AddContextVector
v8  Migration008AddRelations
```

**Bootstrap privilege note:** the first deploy with the auto-migrator
needs the application role to be able to `CREATE TABLE` on schema
`public`. On Postgres 15+ that privilege isn't granted by default;
`DbMigrator.bootstrap()` wraps the failure with a hint pointing at
`GRANT CREATE ON SCHEMA public TO <role>` (fresh installs get this via
`001_init.sql`; existing installs run `sql/migrator_prep.sql` once).

---

## 3. Stage 2 — `RecordMigrator` (per-row / `record_version`)

Walks every `memories` row whose `record_version` is below
`CURRENT_RECORD_VERSION` and applies the registered `RecordUpgrader`
chain to bring it forward.

**Algorithm** (`RecordMigrator.runOnStartup`):

1. `RecordUpgraderRegistry.validate()` — fail fast on misconfiguration.
2. If the registry is empty (target 1), no row can be below 1 — log and
   return.
3. **Paginate by ascending id**, `CHUNK_SIZE = 500`
   (`repo.findIdsBelowVersion`). A partial chunk means end-of-table.
4. For each id, upgrade that row's chain. **Per-row failures are caught,
   logged, and counted (`failed++`) — they never throw and never block
   startup.**

**Per-row transaction** (`upgradeOne`): each row is upgraded on its own
connection / transaction. The row is loaded by id; then while
`current < target`, the upgrader registered for `current` is looked up
and applied, advancing `current` to its `toVersion()`. After the chain
succeeds, `repo.bumpVersion` sets the row's `record_version` to the
target — **in the same transaction**, so the data change and the version
bump commit together. A row hard-deleted between the scan and the
upgrade is treated as already done.

**The `RecordUpgrader` contract** (`memory/RecordUpgrader.java`):

- `fromVersion()` / `toVersion()` — the upgrader at registry index `N-1`
  takes a row from version `N` to `N+1`. Multi-version rows advance by
  chaining upgraders.
- **Strictly additive** (invariant #3): fill a new column / metadata key
  from existing data, set a default, enrich a row. **Never** overwrite,
  transform, or delete existing values — original text, tags,
  embeddings, and timestamps are immutable from an upgrader's view.
- **Idempotent**: re-running on the same row must produce the same
  outcome. (`SalienceSeedUpgrader` only fills `salience` where it
  `IS NULL`, so it can never clobber a value the user's feedback already
  moved.)
- **Per-row isolation**: a thrown upgrade rolls back just that row.
- **Permanent**: don't edit a shipped upgrader; write a new one.
- An upgrader must **not** bump `record_version` itself — the migrator
  does that after the chain completes.

**The registry** (`RecordUpgraderRegistry`): ordered list plus
`CURRENT_RECORD_VERSION` (**2**). `validate()` enforces a contiguous
`fromVersion` sequence `1, 2, …, target-1` with each `toVersion =
fromVersion + 1`. Current chain:

```
v1 -> v2   SalienceSeedUpgrader   (seed salience from importance)
```

---

## 4. Stage 3 — `ReembedJob` (embedding refresh, optional)

Re-embeds rows whose stored `(embedding_provider, embedding_model)`
doesn't match the active config, or whose `embedding` is NULL (which
happens after an embedding-dimension column resize). This is how a
provider/model switch is rolled across the existing store.

**Gated and self-disabling:**

- Runs **only** when `REEMBED_ON_STARTUP=true` in `application.ini`;
  otherwise it logs "skipping" and returns 0.
- On **clean completion**, it flips `REEMBED_ON_STARTUP` back to
  `false` (via `ApplicationIniWriter.setKey`, an atomic
  stage-temp-then-`ATOMIC_MOVE` write) so a later restart doesn't re-run
  a no-op walker. If it can't write the file, it warns rather than
  failing.

**Algorithm** (`ReembedJob.runOnStartup`):

1. Read the active provider/model/dims.
2. Page the table (`BATCH_SIZE = 50`) via
   `repo.findStaleEmbeddingIds` — rows still showing the *old*
   provider/model.
3. Per batch: read the texts, embed them **all in one provider call**,
   write each new vector + provider + model back. Each batch is its own
   connection / transaction, so a crash loses at most the current batch.
4. **Resumable by SELECT filter:** the loop terminates only when a page
   comes back empty. Kill the JVM mid-run and the next startup picks up
   exactly the rows still showing the old provider/model.

**Failure policy differs from stage 2 on purpose:** a per-batch failure
**aborts the whole job** (and, being thrown, refuses the servlet load).
A partial run that silently skipped broken rows would leave the store in
a mixed-model state with no operator visibility. See `REEMBED.md` for
the full operational runbook.

> Operator caveat: `ApplicationIniWriter` writes the **unpacked
> deployment's** `application.ini`, not the source tree. A future WAR
> that ships `REEMBED_ON_STARTUP=true` will re-enable the walker on next
> restart — update the source-tree copy separately before the next
> build.

---

## 5. Failure & recovery summary

| Stage | Per-unit failure | Effect on startup | Recovery |
|---|---|---|---|
| `DbMigrator` | a migration throws | **servlet refuses to load**; `db_version` not bumped | fix + redeploy; next startup retries from same version |
| `DbMigrator` | DB ahead of code | **servlet refuses to load** | deploy newer code or restore snapshot |
| `RecordMigrator` | one row throws | logged + counted; **startup continues** | next startup retries just the rows still below target |
| `ReembedJob` | one batch throws | **whole job aborts** → servlet refuses to load | fix provider/connectivity; next startup resumes via SELECT filter |

Common threads across all three:
- Each unit of work (migration / row / batch) is its own
  connection/transaction; the data change and its version/marker bump
  commit atomically.
- Kiss connections are `autoCommit = false`; the framework's
  `closeConnection(db, ok)` commits on `true`, rolls back on `false`.
- All three walkers are **idempotent and resumable** — re-running after
  a crash never double-applies and never skips.
- **Operational stance** (single-user prod): take a fresh backup before
  any deploy that bumps `CURRENT_DB_VERSION` ("Procedure B"); the backup
  is the rollback net. A no-migration deploy ("Procedure A") is a pure
  WAR swap.

---

## 6. How to add an auto-applied change

### A schema change (new migration)

1. Create
   `src/main/precompiled/ai/ownsona/migrations/MigrationNNN_descriptive_name.java`
   implementing `Migration`; do the **additive** DDL in `apply(db)` with
   `IF NOT EXISTS`.
2. Register it: `m.add(new MigrationNNN_...());` in `MigrationRegistry`.
3. Bump `CURRENT_DB_VERSION` to `NNN`.
4. **Commit all three together.** (Pre-write the `DROP COLUMN` rollback
   SQL in the rollout-plan ship checklist, not in the class.)

### A per-row backfill (new upgrader)

1. Create a `RecordUpgrader` in
   `src/main/precompiled/ai/ownsona/memory/`; `upgrade(db, row)` fills
   new fields **only** (additive + idempotent). Set `fromVersion()` to
   the row's current version and `toVersion() = fromVersion() + 1`.
2. Register it in `RecordUpgraderRegistry`'s list, in order.
3. Bump `CURRENT_RECORD_VERSION` to match.
4. **Commit all three together.**

> **Invariant #5 — lockstep.** `CURRENT_DB_VERSION` ↔ migration registry
> and `CURRENT_RECORD_VERSION` ↔ upgrader registry must change in the
> **same commit**. Both `validate()` methods fail fast at startup on a
> mismatch, but the right place to catch it is code review.

### A store-wide re-embed

No code change — set `REEMBED_ON_STARTUP=true` in the deployed
`application.ini` and restart. The job runs once and disables itself.
See `REEMBED.md`.

---

## 7. Where things live

```
src/main/precompiled/ai/ownsona/
    MCPServer.java                          # <clinit> drives all three stages (loadOnStartup=1)
    migrations/
        Migration.java                      # interface + contract
        MigrationRegistry.java              # ordered list + CURRENT_DB_VERSION + validate()
        DbMigrator.java                     # stage 1: schema walker
        Migration002..008*.java             # the applied schema changes
    memory/
        RecordUpgrader.java                 # interface + contract
        RecordUpgraderRegistry.java         # ordered list + CURRENT_RECORD_VERSION + validate()
        RecordMigrator.java                 # stage 2: per-row walker
        SalienceSeedUpgrader.java           # v1 -> v2 backfill
        MemoryRepository.java               # findIdsBelowVersion / bumpVersion / findStaleEmbeddingIds
    embeddings/
        ReembedJob.java                     # stage 3: embedding refresh walker
    ApplicationIniWriter.java               # atomic single-key application.ini rewrite (flag flip)
sql/
    001_init.sql                            # the version-1 baseline (fresh installs)
    migrator_prep.sql                       # one-time CREATE-privilege grant for existing installs
```

Related reading: `AI/ApplicationDetails.md` (design invariants #2–#5,
the "How to add a schema change / per-record upgrader" recipes),
`OwnSona-rollout-plan.md` (auto-migration framework rationale and the
Procedure A/B deploy model), `REEMBED.md` (re-embed runbook),
`INSTALL.md` §15 (upgrading an existing install).
