# OwnSona Rollout Plan

Companion to `OwnSona-enhancement.md`. This plan stages the suggested
enhancements so the running single-user Tomcat instance keeps working
between deploys, every schema change is additive and rollback-safe, and
each phase can sit in production for a while before the next.

Touchpoints are referenced as `file:line` against the current tree
(branch `main`).

---

## Implementation status

Updated in lockstep with commits. "Code-complete" = built and unit-tested
locally, committed on `main`. "Deployed" = the production Tomcat is
actually running this code.

| Phase | Status | Notes |
|---|---|---|
| 1A — capture_mode + session_id provenance | **deployed** (2026-05-16) | committed at e14da68 |
| 1B — tag normalization | **deployed** (2026-05-16) | committed at 540fe13 |
| 1C — max_chars budget on build_context_prompt | **deployed** (2026-05-16) | committed at 48ab206 |
| Log level: quiet per-request INFO, keep startup banner | **deployed** (2026-05-16) | committed at 0d5683f |
| 2 — Auto-migration framework | **deployed** (2026-05-16) | empty migration registry, `CURRENT_DB_VERSION = 1`; `migrator_prep.sql` was run once before this deploy |
| 3 — Per-record versioning + record_version | **deployed** (2026-05-16) | `CURRENT_DB_VERSION` bumped to 2 with `Migration002AddRecordVersion`; per-record upgrader framework shipped with empty `RecordUpgraderRegistry` (`CURRENT_RECORD_VERSION = 1`) |
| 4 — Dedup + freshness | not started | |
| 5 — Conflict surfacing + tombstones + decay | not started | |

When Phase 1 (the code-complete row above) gets deployed, all four
commits go in one WAR swap — they don't depend on each other and
none touches the DB. After that, Phase 2's deploy carries the first
real schema work (creating the `db_version` table on first start).

---

## The central asymmetry: code is cheap, data is not

**This is the design constraint that shapes every decision below.**

- **Code / WAR rollout is easy.** `./bld war` produces a single file.
  Swapping it is a few seconds of downtime. Rolling back is keeping
  the previous `.war` and putting it back. The blast radius is
  bounded by however long the wrong WAR is live.
- **Schema and data changes are hard.** A migration touches the
  authoritative store. A bad one corrupts data the user actually
  cares about (years of remembered facts), and "rollback" means
  restoring from a backup — losing every memory written between the
  backup and the rollback. Data loss is the failure mode that
  matters, not downtime.

Concrete consequences for this plan:

1. **Prefer code-only changes over schema changes.** When a feature
   can fit in the existing `metadata JSONB` column or be implemented
   in application logic, do it that way even if a dedicated column
   would be slightly cleaner.
2. **Never combine a schema change with a behavior change in one
   deploy.** Apply the migration first, on its own, and let it sit.
   Deploy the code that *uses* the new columns separately, after the
   schema has proven stable. If the WAR turns out to be wrong, you
   redeploy. If the migration turns out to be wrong, you have a much
   bigger problem — so don't entangle them.
3. **Treat one-time data rewrites (UPDATE/DELETE on existing rows)
   with the same caution as migrations**, not as casual code
   changes. They need a backup immediately before and a written
   rollback plan. (Since there is no test DB in this operational
   model, the way to "rehearse" a destructive rewrite is: take
   the backup, run the rewrite, exercise the system, and restore
   from backup if you don't like what you see.)
4. **Avoid migrations entirely when you can.** Most of Phase 1 in
   this plan is code-only for exactly this reason.

---

## Auto-migration framework

**This is the foundation for every schema and data change after
Phase 1.** Once it is in place, no schema or data migration is ever
applied manually with `psql -f` again. The server does it itself on
startup.

The pattern:

- A `db_version` table in the database stores the version the
  database is currently at (as a history of every version applied,
  with the latest being the current state).
- A `CURRENT_DB_VERSION` constant in the application code says what
  version the running code expects.
- On startup, the server compares the two:
  - **DB version == code version** → nothing to do.
  - **DB version < code version** → apply each missing migration
    in sequence (version N+1, N+2, … up to code version), each in
    its own transaction, recording the new version after each
    success. If any step fails, the transaction rolls back, the
    version is not bumped, the server refuses to start, and the
    operator inspects the log.
  - **DB version > code version** → refuse to start. The database
    is on a newer schema than this code understands. The operator
    must either deploy newer code or restore an older database.
- A database that is several versions behind catches up by running
  each step in order. There is no "skip from 1 to 5" — every
  intermediate step runs, because each one's correctness depends on
  the prior ones.

Concretely, a migration is a small Java class implementing a
`Migration` interface. Each class knows its version number, its
name (for logging), and how to apply itself — usually a few
`db.execute("ALTER TABLE …")` calls, occasionally with Java logic
mixed in. Migrations are registered in an ordered list in the
migrator. Adding a new schema change in a future feature is
mechanical: write a new `MigrationNNN_descriptive_name.java`,
register it, bump `CURRENT_DB_VERSION`, deploy the WAR. The server
applies it on startup.

The existing `sql/001_init.sql` continues to exist as the bootstrap
schema for a fresh install. After `001_init.sql` runs, the database
is at version 1 — the migrator establishes that baseline on first
run and proceeds from there.

**Why this is safe even though it routinely rewrites the
database:**

- Each migration runs in a single transaction. Either it fully
  applies and `db_version` is bumped, or nothing changed.
- The migrator refuses to continue if any step fails. The operator
  notices.
- Migrations stay **additive** (per guardrail #2). The system never
  silently destroys data on startup.
- A backup is still taken before deploying a WAR with new
  migrations. If something corrupt slips through, restore is the
  fallback.

The infrastructure is built in **Phase 2** below. Everything from
Phase 3 onward registers its schema work as a migration in this
framework rather than touching the database directly.

---

## Per-record data versioning

Several upcoming features extend the *shape* of a memory record:
capture_mode (Phase 1A), freshness fields (Phase 4), tombstone fields
(Phase 5). Each one risks leaving older rows in a half-populated state
relative to newer ones. The plan handles this with **per-record
versioning**: every memory carries an integer `record_version`, and
each server startup runs a registered set of **upgraders** that walks
rows whose version is below the current target and brings each one up.

This is a deliberate exception to the "data rewrites are hard"
asymmetry above — it makes some data rewrites a routine, automatic
operation. The exception is safe only because of the **strict
constraints on what an upgrader is allowed to do**:

1. **Strictly additive.** An upgrader may *fill in* a new field
   (compute it from existing data, set a default), but it MUST NOT
   overwrite, transform, or delete existing values. The original
   memory text, tags, embeddings, and timestamps are immutable from
   an upgrader's point of view.
2. **Idempotent.** Running the same upgrader on the same row a second
   time must produce identical output. This protects against
   half-finished startup passes and lets the upgrader retry safely.
3. **Per-row failures are isolated.** If one row fails to upgrade, it
   stays at its old version, the error is logged, and the pass
   continues. Startup is never blocked.
4. **Upgraders are permanent.** Once shipped, an upgrader stays in
   the code forever. Some old row out there may still need it. You
   never delete an upgrader; you only add new ones.
5. **Destructive rewrites stay manual.** Anything that would replace
   existing data — for example, normalizing existing tags through a
   synonym map, or re-embedding rows under a new model — is NOT an
   upgrader. It's a one-time data migration that follows Procedure B
   (separate deploy, backup-first, rollback plan in hand).

The point of these rules: a `record_version` bump means "more
information is known about this row," never "this row's old
information has been rewritten." That keeps automatic startup
upgrades reversible in the only sense that matters — rolling back to
the previous WAR continues to work, because nothing was lost.

The infrastructure is built in Phase 3 below (after the
auto-migration framework lands in Phase 2). Phase 1A intentionally
does NOT use it: capture_mode being absent on an old row is the
correct, honest state ("we don't know") and doesn't need to be
backfilled.

---

## Guardrails (apply to every phase)

These constraints bound what the plan does and does not touch.

1. **Embedding model is frozen.** Changing `EMBEDDING_MODEL` or
   `EMBEDDING_DIMENSIONS` invalidates every stored vector — the
   `memories.embedding` column is `vector(1536)` and the index is HNSW
   over cosine distance (`sql/001_init.sql:20,52`). Re-embedding all
   rows is a separate, much bigger project; not in scope here.
2. **Migrations are additive only.** After Phase 2 ships, every
   migration is a `MigrationNNN_*.java` class registered in the
   auto-migrator's ordered list and applied at server startup
   (see "Auto-migration framework" above). No edits to
   `001_init.sql`, no `ALTER ... DROP`, no renames. An old WAR
   must keep working against the new schema so rollback is just
   redeploying the previous `.war`. **All new columns must be
   nullable or carry a default**, so the migration itself rewrites
   no existing data — it only adds structure.
3. **`metadata JSONB` is the cheap extension point.** The column
   already exists (`sql/001_init.sql:37`) and is unused. Anything that
   doesn't need its own index or its own SQL filter goes there with
   zero migration. **Default to JSONB**; only add a real column when
   you need to index it or filter on it in SQL.
4. **MCP tool signatures stay backward compatible.** New parameters
   are optional with safe defaults. Existing clients (Claude Code,
   claude.ai, ChatGPT connector) must keep working without changes.
5. **Migrations ship together with the code that uses them and
   apply automatically on startup.** After Phase 2 lands, the
   deploy pattern for any schema or data change is: backup, swap
   WAR, restart, watch the auto-migrator apply the missing
   migrations in order. No separate manual `psql -f` step. No
   "ship migration alone, soak, then ship code" separation —
   they're inherently combined because the new code carries the
   migration that brings the DB to the version it needs.
6. **No test DB.** There is no rehearsal server. Local unit tests
   (`sql/run_tests.sh`) cover what they cover (validators,
   normalizers, prompt formatting). Integration tests are present
   in the suite and skip silently without `OWNSONA_TEST_DATABASE_URL`;
   they're available if you ever want to set up a test DB later but
   not required for routine deploys. Live exercise on prod is the
   verification path.
7. **Take a fresh backup immediately before every deploy that
   bumps `CURRENT_DB_VERSION`**, not just relying on the scheduled
   `ownsona-backup.timer`. Trigger `ownsona-backup.service`
   manually and verify the backup file exists and is non-empty
   before swapping the WAR (the auto-migrator will apply the
   migration on startup). This backup IS the safety net; the
   procedure depends on it.
8. **One deploy = one WAR.** Build with `./bld war`, stop tomcat
   (`./kill-tomcat`), swap the WAR, start. Expected downtime: a few
   seconds. MCP clients retry on connection error, so this is
   acceptable for single-user use; do it outside active work windows.
9. **LLM vendor neutrality is a core invariant.** OwnSona must never
   hardcode a dependency on a specific generative-LLM vendor in its
   request path. The MCP design (`OWNSONA_SPEC.md` §5) is explicit
   that Ownsona returns structured data to *whichever* cloud LLM the
   user happens to be using — OpenAI, Anthropic, Gemini, Grok, or a
   future one. Concretely:
   - The only external dependency today is the **embedding
     provider**, already abstracted behind
     `ai.ownsona.embeddings.EmbeddingProvider` and configured per
     deployment via `application.ini`. That abstraction must remain
     the only seam where a vendor name appears.
   - **No new feature in this plan may add a call to a generative
     LLM** (a chat/completion/classification API) in the
     read or write path. If a future feature needs one (e.g. an
     LLM-driven tag classifier), it must go behind a new
     `LLMProvider`-style interface with a mock implementation for
     tests, and it must be optional — the server must continue to
     function correctly with no LLM provider configured.
   - **MCP tool descriptions and schemas stay generic.** No "use
     this if you're Claude" wording, no fields named after a
     specific vendor's API, no return shapes tailored to one
     provider's parsing.
   - **Recorded provider metadata is descriptive, not prescriptive.**
     `source_provider` records which client wrote a memory; it must
     never gate what `recall` returns or change behavior.
10. **Upgraders are strictly additive (see "Per-record data
    versioning" above).** Startup-time record upgraders may fill in
    new fields but must never overwrite, transform, or delete
    existing data. They must be idempotent. Per-row failures are
    isolated and don't block startup. Destructive rewrites are NOT
    upgraders — they're manual one-time data migrations under
    Procedure B.
11. **`CURRENT_DB_VERSION` in code and the registered migration
    list are kept in lockstep.** This is the load-bearing invariant
    of the auto-migration framework (see "Auto-migration framework"
    above). Every time a code change introduces a schema or data
    migration, the developer (or AI assistant) MUST:
    - Add a new `MigrationNNN_*.java` class implementing the
      `Migration` interface, with `version()` returning the next
      integer in the sequence.
    - Register that class in the migrator's ordered list.
    - Bump the `CURRENT_DB_VERSION` constant to match the new
      highest registered version.
    These three changes ship together in the same commit. The
    framework fails fast at startup if there's a gap (registered
    versions don't form a contiguous sequence from 1 to
    `CURRENT_DB_VERSION`) or if `CURRENT_DB_VERSION` is less than
    the highest registered version, but the right time to notice
    is at code-review time, not at server-start time. Never edit
    an already-shipped migration — write a new one to undo or
    refine it.

---

## Phase 1 — Zero-migration code-only changes

**Goal:** Add provenance, normalize tags, and budget the prompt
builder. No DB changes, no client-visible changes for old clients.

**Risk:** Very low. Each item is a single-file change (or close to it).
Rollback = redeploy previous WAR.

### 1A. Provenance — `capture_mode` and richer source metadata

**What.** Record whether a memory was saved because the user
explicitly asked (`"explicit"`) or because the model inferred it was
save-worthy (`"inferred"`). Surface it in `recall` so clients can
down-weight inferred facts if they want.

**Where the data lives.** `memories.metadata` JSONB. No column added.
Convention: `metadata = {"capture_mode": "explicit"|"inferred", "session_id": "..."}`.

**Touchpoints.**
- `src/main/precompiled/ai/ownsona/memory/MemoryInsert.java` — add a
  `metadata` (JSON string) field.
- `src/main/precompiled/ai/ownsona/memory/MemoryRepository.java:42-65`
  — extend the `INSERT` to write `metadata` (already in column list
  semantically; add explicit param). `SELECT_COLUMNS`
  (`MemoryRepository.java:27`) — add `metadata::text AS metadata_json`.
- `src/main/precompiled/ai/ownsona/memory/MemoryRow.java` — add
  `public String metadataJson`.
- `src/main/precompiled/ai/ownsona/memory/MemoryService.java:49` —
  add optional `captureMode` and `sessionId` parameters to
  `remember()` and `rememberBatch()`; build the metadata JSON.
- `src/main/precompiled/ai/ownsona/MCPServer.java` — extend the
  `remember` / `remember_batch` tool schemas (optional fields,
  defaulting `capture_mode = "explicit"` since the user *did* say
  "remember"). Surface `capture_mode` in `recall` output.

**Backward compatibility.** Old clients omit the new fields; old rows
have `metadata = '{}'`. Both render as `capture_mode = null` (or
"explicit" default depending on which side picks it). MCP tool result
gains a field; clients ignore unknown fields.

**Vendor neutrality (guardrail #9).** `capture_mode` is a generic
distinction (user-stated vs model-inferred) that applies to every
LLM provider. `source_provider` continues to be descriptive only —
it labels who wrote the row, but `recall` ranks identically
regardless of value. `session_id` is an opaque string; OwnSona
doesn't parse it or call back to the provider that issued it.

**Tests.** Unit tests in `src/test/precompiled/ai/ownsona/` covering:
- omitted `capture_mode` → defaults correctly,
- explicit value round-trips through DB,
- `recall` returns the value.

### 1B. Tag vocabulary + normalization

**What.** A canonical synonym map (`tech → software`, `bio → personal`,
etc.). Applied on every `remember` / `remember_batch` / `update_memory`
call. Existing tags untouched until updated.

**Where the data lives.** A bundled resource
(`src/main/precompiled/ai/ownsona/tags.json` or inlined). No DB
change.

**Touchpoints.**
- New file: `src/main/precompiled/ai/ownsona/TagNormalizer.java` —
  loads the synonym map once, exposes `normalize(String[]) → String[]`.
- `src/main/precompiled/ai/ownsona/memory/MemoryService.java:484`
  (`cleanTags`) — call `TagNormalizer.normalize` after
  trim/dedup/limit checks.
- New test: `TagNormalizerTest.java`.

**Backward compatibility.** Pure additive — existing tag arrays still
work; novel tags pass through unchanged.

**One-time cleanup (optional, do later — treat as a hard
operation).** Rewriting existing rows' `tags` column through the
synonym map is a data-modifying operation, not a code change. It
needs the full Procedure B treatment: fresh backup, dry run on a
restored copy of prod, written rollback plan (restore from backup —
the rewrite is lossy, the old tag values are gone after the
`UPDATE`). Defer this until the tag normalization code has been
running for long enough that the legacy tag values are a minority of
the store.

### 1C. Token budget on `build_context_prompt`

**What.** New optional `max_chars` parameter. Stops adding facts once
the running total would exceed the budget. (Use char count as a cheap
proxy for tokens; ~4 chars/token is a fine approximation for English.)

**Touchpoints.**
- `src/main/precompiled/ai/ownsona/memory/MemoryService.java:279`
  (`buildContextPrompt`) — accept `Integer maxChars`, trim the `facts`
  list before passing to `PromptFormatter.build`.
- `src/main/precompiled/ai/ownsona/memory/PromptFormatter.java` — no
  change needed if the trimming happens upstream.
- `src/main/precompiled/ai/ownsona/MCPServer.java` — add to
  `build_context_prompt` tool schema (optional, no default → unbounded
  if omitted = current behavior).

**Backward compatibility.** Omitting `max_chars` reproduces current
behavior exactly.

### Phase 1 ship checklist

- [ ] All three changes built; `./bld -v build` clean
- [ ] `sql/run_tests.sh` green (unit tests; integration tests will
      skip without `OWNSONA_TEST_DATABASE_URL`)
- [ ] `./bld war` → deploy (no migration; no backup strictly needed,
      but trigger `ownsona-backup.service` anyway since it's cheap)
- [ ] Tail `tomcat/logs/catalina.out` for clean startup
- [ ] One real `remember`/`recall` round-trip from a Claude Code session

---

## Phase 2 — Auto-migration framework

**Goal:** Stand up the auto-migrator described in the
"Auto-migration framework" section above. Establish the
`db_version` table, the `Migration` interface, the migrator
registry, and the startup hook. No actual migration runs on this
deploy — the registry is empty; `CURRENT_DB_VERSION` is 1, which
is the baseline established by `sql/001_init.sql`. Phase 3 onward
adds real migrations.

**Risk:** Lowest of any infrastructure deploy. With no migrations
registered, the framework's startup hook just verifies that
`db_version = CURRENT_DB_VERSION = 1` and proceeds. The framework
code is well-bounded, transaction-wrapped, and has unit-test
coverage of its corner cases.

### What ships in Phase 2

A new `ai.ownsona.migrations` package with:

```
src/main/precompiled/ai/ownsona/migrations/
    Migration.java          # the interface
    DbMigrator.java         # the startup hook + version table maintenance
    MigrationRegistry.java  # ordered list, sanity checks
```

Sketch of the interface:

```java
package ai.ownsona.migrations;

import org.kissweb.database.Connection;

public interface Migration {
    /** Version number this migration brings the DB to (= prior + 1). */
    int version();

    /** Short human-readable name; appears in logs. */
    String name();

    /**
     * Apply the migration. Runs inside a transaction managed by
     * DbMigrator. Throw on failure; the transaction will roll back
     * and startup will abort.
     */
    void apply(Connection db) throws Exception;
}
```

`DbMigrator.runOnStartup(db)`:

1. `CREATE TABLE IF NOT EXISTS db_version (...)` — idempotent.
2. If `db_version` is empty, insert `version = 1, applied_at =
   now(), note = 'baseline (001_init.sql)'`. This handles both
   fresh installs and the first Phase 2 deploy against the
   existing prod DB.
3. Read the current version as `SELECT MAX(version) FROM db_version`.
4. Validate the registry: registered versions must form a
   contiguous sequence from 2 up to `CURRENT_DB_VERSION`. Fail fast
   if not — that's a programmer error in guardrail #11 territory.
5. If current version > `CURRENT_DB_VERSION`, refuse to start with
   a clear error.
6. If current version < `CURRENT_DB_VERSION`, for each missing
   version in order:
   - Lock the version row (`SELECT … FOR UPDATE` against an
     advisory lock, or `SELECT pg_try_advisory_lock(…)`) to prevent
     concurrent migrators. Single-user system, but cheap insurance.
   - Begin a transaction.
   - Call `migration.apply(db)`.
   - Insert a new row into `db_version` with the new version.
   - Commit.
   - Log: `migrator: applied v={version} name={name} ms={elapsed}`.
7. After the loop, the database is at `CURRENT_DB_VERSION`.

The `db_version` table:

```sql
-- Created by the migrator itself on first run; not in 001_init.sql
CREATE TABLE IF NOT EXISTS db_version (
    version    INT PRIMARY KEY,
    applied_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    note       TEXT
);
```

A single row per applied version gives an audit trail. The current
version is always `MAX(version)`.

### Hook into `MCPServer.<clinit>`

The startup hook is called once from `MCPServer`'s static
initializer, after the `MemoryService` is constructed, before any
HTTP request can be served:

```java
static {
    Configurator.setLevel("ai.ownsona", Level.INFO);

    SERVICE = new MemoryService(
            new MemoryRepository(),
            new OpenAIEmbeddingProvider(...));

    // New in Phase 2:
    try (Connection db = MainServlet.openNewConnection()) {
        DbMigrator.runOnStartup(db);
    } catch (Exception e) {
        logger.error("DbMigrator failed; refusing to serve", e);
        throw new IllegalStateException("DbMigrator failed", e);
    }
    // (Phase 3 adds the per-record migrator call here.)
}
```

If the migrator throws, the servlet refuses to load — Tomcat will
log the failure and not route traffic to it. That's the correct
behavior: we don't want a half-migrated database to start serving
requests with the new code.

### Tests

Unit tests for the framework (no DB required for most):

- Registry validation rejects gaps in the version sequence.
- Registry validation rejects duplicate versions.
- Registry validation rejects `CURRENT_DB_VERSION` < max registered.

Integration tests (need `OWNSONA_TEST_DATABASE_URL`):

- First-run path: empty DB → migrator creates `db_version` and
  inserts version 1.
- No-op path: DB at version = `CURRENT_DB_VERSION` → migrator
  makes no changes.
- Forward path: DB at version < target → each missing migration
  applies in order; `db_version` reflects each.
- Refusal path: DB at version > target → migrator throws.
- Failure path: a migration that throws leaves `db_version`
  unchanged; subsequent startup retries from the same point.

### Ship checklist

Single deploy. With no migrations registered, this is functionally a
Procedure A code-only deploy, but the migrator creates the
`db_version` table on first run, so the application role needs
privileges it doesn't have on the existing prod DB. One-time prep
SQL fixes this.

**Existing prod DB needs a one-time prep before this deploy:**

```
sudo -u postgres psql -d ownsona -f sql/migrator_prep.sql
```

This grants `CREATE ON SCHEMA public` to the `ownsona` role (so the
migrator can create `db_version`) and transfers ownership of the
`memories` table and its sequence to `ownsona` (so future migrations
can ALTER TABLE without superuser). The script is idempotent — safe
to re-run. Fresh installs done via `sql/setup_db.sh +
sql/001_init.sql` already include these grants.

- [ ] `sql/migrator_prep.sql` applied to prod (verify the script's
      trailing query shows `can_create_in_public = t` and
      `memories_owner = ownsona`)
- [ ] `Migration`, `DbMigrator`, `MigrationRegistry` unit tests green
      (`sql/run_tests.sh`)
- [ ] `MCPServer.<clinit>` calls `DbMigrator.runOnStartup()` after
      service construction; `@WebServlet(loadOnStartup = 1)` ensures
      the migrator runs at Tomcat startup, not on first request
- [ ] Fresh prod backup taken and verified
- [ ] WAR deploy (`./bld war`, swap, restart)
- [ ] First startup log shows
      `migrator: db_version baseline established (version=1)` and
      `migrator: db_version at 1, target 1, nothing to apply`
- [ ] On a subsequent restart, log shows only the second line above
      (table now exists)
- [ ] One real `remember`/`recall` round-trip — nothing else should
      have changed

**Rollback.** Because the migrator created its own table, the rollback
WAR (a previous version) won't know about `db_version` but also won't
care — it never queries that table. Restoring `Kiss.war.prev` is
sufficient. If you also want to remove the `db_version` table itself
(unlikely needed): `DROP TABLE db_version;` as the postgres user.

---

## Phase 3 — Per-record versioning infrastructure

**Goal:** Add `record_version` to every memory and stand up the
on-startup record upgrader framework. Ship empty: no upgraders
registered yet. Future phases (and post-deploy follow-ups for
already-shipped phases) register their upgraders against this
scaffold.

**Risk:** Schema migration is low-risk (one nullable-defaulted INT
column) and now applies automatically via the Phase 2 framework.
The record-upgrader infrastructure code is non-trivial but has
zero effect on running behavior until an upgrader is registered.

**Ordering.** A single deploy. The schema change is registered as
`Migration002AddRecordVersion`; bumping `CURRENT_DB_VERSION` to 2
causes the auto-migrator to apply it on next startup. The record
upgrader framework code ships in the same WAR. Optionally a second
deploy adds a no-op proof-upgrader.

1. **3A — Schema + framework deploy.** Register
   `Migration002AddRecordVersion`. Bump `CURRENT_DB_VERSION` to 2.
   Add `RecordUpgrader`, `RecordUpgraderRegistry`, and
   `RecordMigrator` classes. Hook the record migrator into
   `MCPServer.<clinit>` after the DbMigrator call. Registry is
   empty. WAR deploy.
2. **3B — Trivial proof-upgrader (optional).** Register a no-op
   `RecordUpgrader` that bumps `record_version` 1 → 2 without
   touching any other field. Confirms the per-row walker works
   against real data. Skip if 3A's logging already shows the
   walker visited expected counts.

### 3A. Migration: `Migration002AddRecordVersion`

A Java class registered with the Phase 2 framework:

```java
package ai.ownsona.migrations;

import org.kissweb.database.Connection;

public final class Migration002AddRecordVersion implements Migration {
    @Override public int    version() { return 2; }
    @Override public String name()    { return "add record_version column"; }

    @Override
    public void apply(Connection db) throws Exception {
        db.execute(
            "ALTER TABLE memories " +
            "ADD COLUMN IF NOT EXISTS record_version INT NOT NULL DEFAULT 1");

        // Partial index for the per-record walker.  The constant 100 is
        // an arbitrary high water mark; queries use record_version < N
        // where N is the current target.  Partial form keeps the index
        // small once most rows are upgraded.
        db.execute(
            "CREATE INDEX IF NOT EXISTS memories_record_version_idx " +
            "ON memories(record_version) " +
            "WHERE record_version < 100");
    }
}
```

Register in the migration list; bump `CURRENT_DB_VERSION` to 2.
Per guardrail #11, these changes ship in the same commit.

### 3B. Record-upgrader infrastructure (code)

**Design.**

- New constant `MemoryService.CURRENT_RECORD_VERSION`. Bumped when an
  upgrader is added.
- New interface (sketch):
  ```java
  package ai.ownsona.memory;
  public interface RecordUpgrader {
      int fromVersion();           // upgrades rows whose record_version == this
      int toVersion();             // ... to this (typically fromVersion()+1)
      String name();               // for logging
      void upgrade(Connection db, MemoryRow row) throws Exception;
  }
  ```
- New class `RecordUpgraderRegistry` (singleton; distinct from the
  Phase 2 `MigrationRegistry`) collecting upgraders by
  `fromVersion`. Upgraders register in a static initializer.
- New servlet-init hook `RecordMigrator.runOnStartup(db)` — called
  from `MCPServer.<clinit>` *after* `DbMigrator.runOnStartup(db)`
  so the `record_version` column is guaranteed to exist. Paginate
  over `SELECT id FROM memories WHERE record_version < $1 ORDER BY id`
  in chunks of ~500. For each row, look up the upgrader chain
  needed to reach `CURRENT_RECORD_VERSION`, apply each in sequence
  inside a per-row transaction. On success, bump
  `record_version`. On failure, log and continue.
- **Logging:** at startup, log
  `record_migrator: starting, target_version=N`, then per-chunk
  progress, then a final summary
  `record_migrator: done, upgraded=X, skipped=Y, failed=Z`.
- **Repository changes:** `MemoryInsert` gains `recordVersion`; the
  INSERT writes it explicitly (= `CURRENT_RECORD_VERSION` for any
  new row). `MemoryRow` exposes it (for diagnostics; clients ignore).

**Constraints baked into the interface.**

- `upgrade()` receives a `MemoryRow`. It returns nothing — the
  expected pattern is to UPDATE just the new fields the upgrader is
  responsible for, plus `record_version`. Wrapping it in a savepoint
  in the call site means a thrown exception rolls back that row only.
- The framework refuses to register two upgraders with the same
  `fromVersion` (collision → fail-fast at startup).
- The framework refuses to start if any registered upgrader has a
  `toVersion > CURRENT_RECORD_VERSION` (someone forgot to bump the
  constant).

**Failure behavior.**

- A row that fails an upgrade stays at its old version. The next
  startup will retry. Operator can inspect the row, fix the
  upgrader, and redeploy.
- A failed upgrader is logged with the row id, upgrader name, and
  the exception. No data is rewritten.
- Startup completes regardless of how many rows fail. The MCP
  servlet begins serving traffic.

**Touchpoints.**

- New: `src/main/precompiled/ai/ownsona/memory/RecordUpgrader.java`.
- New: `src/main/precompiled/ai/ownsona/memory/RecordUpgraderRegistry.java`.
- New: `src/main/precompiled/ai/ownsona/memory/RecordMigrator.java`
  (the per-row walker).
- New: `src/main/precompiled/ai/ownsona/migrations/Migration002AddRecordVersion.java`.
- `MemoryInsert.java`, `MemoryRow.java` — add `recordVersion`.
- `MemoryRepository.java` — INSERT writes `record_version`; SELECT
  includes it; new methods `findIdsBelowVersion(db, version, lastId, limit)`
  and `bumpVersion(db, id, newVersion)`.
- `MemoryService.java` — set `ins.recordVersion =
  RecordUpgraderRegistry.CURRENT_RECORD_VERSION` on every insert
  path (both `remember()` and the batch's `processBatchItem`).
- `MigrationRegistry` (Phase 2) — register
  `Migration002AddRecordVersion`; bump `CURRENT_DB_VERSION` to 2.
- `MCPServer.java` — `<clinit>` calls `RecordMigrator.runOnStartup()`
  once after `DbMigrator.runOnStartup()`.

### 3B (continued). Trivial proof-upgrader (optional)

After 3A is live, optionally register a no-op upgrader:
`from=1, to=2, name="noop-v1-to-v2"`. Its `upgrade()` does nothing
but log. Bump `CURRENT_RECORD_VERSION` to 2. Confirms the walker
visits every row on a real database before a meaningful upgrader
is shipped. Easy to back out by reverting the WAR.

### Interaction with already-deployed Phase 1A

Phase 1A's `capture_mode` is deliberately *not* backfilled by an
upgrader. Old rows have no capture_mode and that's the correct,
honest state. If a future deploy decides to backfill (e.g. "every
row that lacks capture_mode is assumed explicit because that's how
remember was used before this feature shipped"), it'd register a
v2→v3 upgrader that adds `metadata.capture_mode = "explicit"` only
to rows where the key is currently absent. Strictly additive,
idempotent, isolated per row — fits the rules.

### Phase 3 ship checklist

Single deploy. The auto-migrator (Phase 2) applies the schema
change on startup; the record-upgrader framework is in the same
WAR.

- [ ] `Migration002AddRecordVersion` registered with the Phase 2
      `MigrationRegistry`; `CURRENT_DB_VERSION` bumped to 2
- [ ] `RecordUpgrader` / `RecordUpgraderRegistry` /
      `RecordMigrator` unit tests pass (registry collision
      detection, version-chain composition, per-row savepoint
      isolation)
- [ ] Pre-written rollback SQL on standby (only needed if the
      auto-migrator itself misbehaves):
      `ALTER TABLE memories DROP COLUMN record_version;`
      `DELETE FROM db_version WHERE version = 2;`
- [ ] Fresh prod backup taken and verified
- [ ] WAR deploy
- [ ] Startup log shows the auto-migrator applying v2:
      `migrator: applied v=2 name="add record_version column"`
- [ ] Startup log shows the record-migrator banner with empty
      registry: `record_migrator: done, upgraded=0, skipped=N,
      failed=0` where N is the prod row count

Optional follow-up deploy with proof-upgrader:
- [ ] WAR deploy with the noop upgrader registered;
      `CURRENT_RECORD_VERSION` bumped to 2
- [ ] First startup: log shows `record_migrator: ... upgraded=N`
      matching the prod row count
- [ ] Second startup: log shows `record_migrator: ... upgraded=0`
      (idempotency check)

---

## Phase 4 — Dedup-on-write + freshness

**Goal:** Catch semantic duplicates before they fragment the store,
and let memories carry an optional expiration / last-confirmed date.

**Risk:** Schema change is two nullable TIMESTAMPTZ columns plus a
partial index — applied automatically by the auto-migrator.

**Ordering.** One or two deploys, your choice:

- **Combined (recommended):** register `Migration003AddFreshness`,
  bump `CURRENT_DB_VERSION` to 3, and ship the dedup + freshness
  code in the same WAR. Auto-migrator handles the schema; the new
  code starts using the columns immediately.
- **Split:** ship the migration alone first (register the migration,
  bump `CURRENT_DB_VERSION`, leave the code that uses the new
  columns for the next deploy). Useful if you want to observe the
  auto-migrator's behavior on a real migration before relying on
  the new columns in code.

Sub-features described below are independent:

1. **4A — Schema migration (`Migration003AddFreshness`).**
2. **4B — Dedup code.** Pure code change. Uses only columns that
   have existed since `001_init.sql`; could in principle ship
   without 4A. Grouped here because the features pair well
   thematically.
3. **4C — Freshness code.** Reads/writes the columns added in 4A.

### 4A. Migration: `Migration003AddFreshness`

```java
package ai.ownsona.migrations;

import org.kissweb.database.Connection;

public final class Migration003AddFreshness implements Migration {
    @Override public int    version() { return 3; }
    @Override public String name()    { return "add expires_at, last_confirmed_at"; }

    @Override
    public void apply(Connection db) throws Exception {
        // Optional freshness signals.  All nullable; existing rows untouched.
        db.execute(
            "ALTER TABLE memories " +
            "ADD COLUMN IF NOT EXISTS expires_at        TIMESTAMPTZ, " +
            "ADD COLUMN IF NOT EXISTS last_confirmed_at TIMESTAMPTZ");

        // Two real columns (not metadata JSONB) because recall filters
        // on `expires_at < now()` and a JSONB key can't be indexed as
        // cheaply.
        db.execute(
            "CREATE INDEX IF NOT EXISTS memories_expires_at_idx " +
            "ON memories(expires_at) " +
            "WHERE expires_at IS NOT NULL");
    }
}
```

Register; bump `CURRENT_DB_VERSION` to 3.

### 4B. Semantic dedup on write

**What.** Before inserting a new memory, embed it (we have to anyway),
then run a top-K similarity query at `min_score ≥ 0.90`. If hits come
back, return them in the `remember` response as `candidates`. Client
decides what to do (insert anyway, update existing, abort).

**Touchpoints.**
- `src/main/precompiled/ai/ownsona/memory/MemoryService.java:49`
  (`remember`) — after the exact-match dedup check
  (line 62), before insert (line 84), run
  `repo.findSimilar(db, userId, vec, 5, null)` filtered to
  `score >= 0.90`. Add a new field
  `RememberResult.candidates` (list of MemoryRow).
- `src/main/precompiled/ai/ownsona/memory/RememberResult.java` — add
  `candidates`.
- `src/main/precompiled/ai/ownsona/MCPServer.java` — surface in the
  `remember` result. Add a `dedup_policy` input
  (`"insert" | "skip_if_near" | "ask"`, default `"ask"` = current
  insert behavior + return candidates).
- Batch path (`MemoryService.java:128`, `rememberBatch`) — same
  treatment, but be careful: the batch already does one embedding
  call for the whole valid set. Run a single query per item after
  embedding, or batch the similarity check too.

**Backward compatibility.** Default policy = `"ask"` (insert + return
candidates) preserves current behavior; clients that don't read
`candidates` see no difference. `"skip_if_near"` opt-in.

**Threshold.** 0.90 is a starting guess. After a week of telemetry
(log every candidate hit with its score), tune.

### 4C. Freshness fields on `remember` / `update_memory`

**What.** Optional `expires_at` and `last_confirmed_at` parameters.
`recall` excludes rows where `expires_at < now()`. New `confirm`
tool (one SQL update) refreshes `last_confirmed_at` without bumping
embedding.

**Touchpoints.**
- `MemoryInsert.java` — `expiresAt`, `lastConfirmedAt`.
- `MemoryRepository.java:42` (`insert`) and `:167` (`update`) — write
  the new columns; null-safe.
- `MemoryRepository.java:116` (`findSimilar`) and `:91` (`listRecent`)
  — `AND (expires_at IS NULL OR expires_at > now())` in the
  `deleted_at IS NULL` clause (and the same in `textSearch`,
  line 150). `include_deleted = true` should also include expired
  rows, for diagnostics.
- `MemoryService.java` — plumb through; clamp `expires_at` to a
  reasonable bound (e.g. ≤ 100 years future) to catch fat-finger.
- `MemoryRow.java` — `expiresAt`, `lastConfirmedAt`.
- `MCPServer.java` — schemas + new `confirm` tool (takes `id`, sets
  `last_confirmed_at = now()`).

**Backward compatibility.** All-null on existing rows. `recall`
behavior for those rows is unchanged. Old clients can't set the
fields and won't see them in results unless they look.

**Decay scoring (deferred to phase 5).** Just the storage + hard
expiration in this phase. A soft score decay (`score * exp(-age/τ)`)
is a follow-up; it's behavior change worth its own deploy.

### Phase 4 ship checklist (combined deploy)

- [ ] `Migration003AddFreshness` registered;
      `CURRENT_DB_VERSION` bumped to 3
- [ ] Dedup code + freshness code with tests
- [ ] Pre-written rollback SQL on standby (if auto-migrator
      misbehaves):
      `ALTER TABLE memories DROP COLUMN expires_at, DROP COLUMN last_confirmed_at;`
      `DELETE FROM db_version WHERE version = 3;`
- [ ] Fresh prod backup taken and verified
- [ ] WAR deploy
- [ ] Startup log shows
      `migrator: applied v=3 name="add expires_at, last_confirmed_at"`
- [ ] Live exercise: `remember` returns dedup candidates when
      near-duplicate text submitted; `confirm` updates timestamp;
      `recall` excludes expired rows

---

## Phase 5 — Conflict surfacing + tombstones + decay

**Goal:** Make stale facts less likely to mislead. Make corrections
durable across sessions.

**Risk:** Moderate. Touches recall output shape and `forget`
semantics. Schema change applied automatically by the auto-migrator.

**Ordering.** As with Phase 4, you can combine the migration with
the code that uses it (single deploy) or split them. Sub-features
are:

1. **5A — Conflict surfacing code.** No schema dependency. Can
   ship before or independently of the migration.
2. **5B — Tombstones.** Combines `Migration004AddTombstones` with
   the code that reads/writes the new columns.
3. **5C — Decay.** Pure code; behind a config flag, default off.

### 5A. Conflict surfacing (cheap version)

**What.** No new fields. Just make `recall` results include
`created_at` / `updated_at` / `last_confirmed_at` more prominently,
and have the MCP tool description tell clients to inspect them when
multiple near-duplicate hits come back.

**Touchpoints.**
- `MCPServer.java` — the `recall` tool description text and result
  schema. No new code paths.

**Vendor neutrality (guardrail #9).** The updated tool description
must stay generic: "if multiple hits look like they could
contradict, prefer the more recently confirmed one" rather than
"Claude should…" or "if your model supports X…". The description is
read by every MCP-capable client.

The "expensive" version (contradiction detection during indexing) is
deferred. It's a separate research project — and if it ever involves
an LLM call, it falls under the LLMProvider-interface rule in
guardrail #9.

### 5B. Tombstones

**What.** Soft delete already exists (`deleted_at` is set). Add
`forget_reason TEXT` and `replaced_by_id BIGINT` columns so a
correction trail survives. Exclude tombstones from normal `recall`,
but consult them in dedup-on-write so the system doesn't reaccept a
previously-corrected fact.

**Migration: `Migration004AddTombstones`**

```java
package ai.ownsona.migrations;

import org.kissweb.database.Connection;

public final class Migration004AddTombstones implements Migration {
    @Override public int    version() { return 4; }
    @Override public String name()    { return "add forget_reason, replaced_by_id"; }

    @Override
    public void apply(Connection db) throws Exception {
        db.execute(
            "ALTER TABLE memories " +
            "ADD COLUMN IF NOT EXISTS forget_reason  TEXT, " +
            "ADD COLUMN IF NOT EXISTS replaced_by_id BIGINT REFERENCES memories(id)");
    }
}
```

Register; bump `CURRENT_DB_VERSION` to 4.

**Touchpoints.**
- `MemoryRepository.java:203` (`softDelete`) — accept optional reason
  / replacement id.
- `MemoryService.java:351` (`forget`) — accept `reason` and
  `replaced_by` parameters.
- Dedup-on-write (4B) — when a candidate hit's tombstone matches the
  new text, return a louder signal (`"previously corrected"`).
- `MCPServer.java` — extend `forget` schema; add optional fields.

### 5C. Soft decay in recall (optional, behind a flag)

**What.** Multiply similarity score by `exp(-age_days / τ)` for rows
where `expires_at IS NULL AND last_confirmed_at IS NULL`. Durable
facts (those the user re-confirmed, or that have an explicit
`expires_at`) are exempt.

**Default off.** Add a `decay_half_life_days` config knob (off if
unset). Turn it on after watching real recall traffic.

**Touchpoint.** `MemoryRepository.findSimilar` SQL — compute the
adjusted score in the SQL or in Java. The latter is simpler and easy
to A/B against the raw score.

### Phase 5 ship checklist

**5A — conflict surfacing (no schema change):**
- [ ] `recall` description and result schema updated; tests updated
- [ ] WAR deploy; verify

**5B — tombstones (migration + code, combined deploy):**
- [ ] `Migration004AddTombstones` registered;
      `CURRENT_DB_VERSION` bumped to 4
- [ ] Tests cover: tombstone excluded from recall, surfaced in
      dedup, forget_reason persisted
- [ ] Pre-written rollback SQL on standby (if auto-migrator
      misbehaves):
      `ALTER TABLE memories DROP CONSTRAINT memories_replaced_by_id_fkey;`
      `ALTER TABLE memories DROP COLUMN forget_reason, DROP COLUMN replaced_by_id;`
      `DELETE FROM db_version WHERE version = 4;`
- [ ] Fresh prod backup taken and verified
- [ ] WAR deploy
- [ ] Startup log shows
      `migrator: applied v=4 name="add forget_reason, replaced_by_id"`
- [ ] Live exercise: `forget id=… reason="…"` round-trip

**5C — decay (code-only):**
- [ ] Decay off by default; on-flag tested separately
- [ ] WAR deploy with flag off
- [ ] Optional: enable flag in `application.ini`, redeploy, observe

---

## Deferred (not in this plan)

These suggestions from `OwnSona-enhancement.md` are deliberately
*not* staged here. Revisit only if a concrete problem motivates them.

- **#1 server-side auto-tagging on write** — Adds LLM latency and
  cost to every write. The right form is a periodic background pass
  over untagged rows, not on-write. If we want it, build it as a
  separate cron-driven job (`bld`-buildable JAR + systemd timer)
  reading rows where `tags = '{}'`. **Vendor neutrality constraint
  (guardrail #9):** prefer an embedding-nearest-tag approach (uses
  the existing `EmbeddingProvider` abstraction) over an LLM
  classification call. If LLM classification is needed, it must go
  behind a new `LLMProvider` interface with a mock implementation,
  and the feature must be optional — disabling it leaves OwnSona
  fully functional. Never hardcode a specific vendor's chat API.
- **#8 structured fields for common fact types** — Most invasive
  change in the original list (new `kind`/`payload`, query routing).
  Free-text + vectors handle Blake-scale fine. Defer until there's
  evidence the current model is missing facts.
- **#10 periodic compaction** — Build after #2 (dedup), #5 (decay),
  and #6 (tombstones) have lived in production; compaction's main
  jobs are folding near-dups, demoting unused rows, and surfacing a
  review queue, all of which depend on the earlier features.

---

## Per-deploy procedures

**Operational stance (single-user, owner-tested).** Blake is the only
user. There is no separate test server and no rehearsal phase. The
working pattern is: build locally, take a fresh backup of the prod
database, deploy directly to prod, exercise the system live, deal
with anything that breaks. Backups are the safety net; rollback is
"restore the WAR" or "restore the database" if it comes to that.

**Once Phase 2 is in production**, all migrations are applied
automatically at startup by the auto-migrator. There's no manual
`psql -f` step. The procedure below is unified — same steps
regardless of whether the deploy carries new migrations or not.

### Shorthand used elsewhere in this plan

- **Procedure A** = a deploy that doesn't bump
  `CURRENT_DB_VERSION`. No schema change. Pure WAR swap.
- **Procedure B** = a deploy that bumps `CURRENT_DB_VERSION` (i.e.
  registers a new migration). Auto-migrator will run it on startup.
  Backup is taken first; rollback SQL is staged in case the
  auto-migrator fails or the new migration is wrong.

Both follow the same steps below; the only difference is whether
new migrations will run on startup.

### The procedure

```
# 1. Pre-flight (local)
cd ~/GitHub.blakemcbride/Ownsona
git status                         # clean tree
./bld -v build                     # compiles cleanly
sql/run_tests.sh                   # unit tests pass (integration ones skip
                                   # without OWNSONA_TEST_DATABASE_URL)

# 2. If this deploy registers a new Migration, write the rollback
#    SQL for it (matching the DROP COLUMN listed in the phase
#    checklist).  Keep it in a scratch file next to the terminal
#    you'll deploy from.

# 3. Backup prod database (always, when a migration is included)
sudo systemctl start ownsona-backup.service
sudo journalctl -u ownsona-backup.service --since "5 minutes ago"
ls -lh /path/to/backups/ | tail -3   # confirm fresh, non-zero-byte file

# 4. Build the WAR
./bld war

# 5. Stage rollback for the WAR (so step 7 is fast if needed)
sudo cp /path/to/tomcat/webapps/Kiss.war /path/to/tomcat/webapps/Kiss.war.prev

# 6. Swap the WAR.  The auto-migrator runs as part of startup ---
#    no manual psql step.
sudo ./kill-tomcat
sudo cp work/Kiss.war /path/to/tomcat/webapps/
sudo systemctl start ownsona.service
sudo tail -f /path/to/tomcat/logs/catalina.out
# Look for:
#   migrator: db_version at X, target Y
#   migrator: applied v=Y name="..." ms=...
#   record_migrator: done, upgraded=A, skipped=B, failed=0
# If the migrator throws, the servlet refuses to load: investigate
# before doing anything else.

# 7. Live exercise --- drive remember/recall from a real client and
#    check the new behavior.
```

### Rollback

- **Code went bad.** Stop Tomcat, `mv Kiss.war.prev Kiss.war`, start.
  The auto-migrator in the previous WAR has `CURRENT_DB_VERSION` ≤
  the current `db_version`, so it skips the new migration on
  start and serves traffic. No data implications because every
  migration in this plan is additive.
- **Auto-migrator failed.** Symptom: catalina.out shows
  `DbMigrator failed; refusing to serve`. The transaction wrapping
  the failing migration has rolled back, so `db_version` is
  unchanged. Options:
  - Fix the migration class, build a new WAR, redeploy.
  - If urgent and the migration is wrong: rollback the WAR to
    `Kiss.war.prev`, accept that the new feature is offline,
    investigate calmly.
- **Auto-migrator succeeded but the new migration was the wrong
  shape.** Run the rollback SQL staged in step 2 (`ALTER TABLE …
  DROP COLUMN …; DELETE FROM db_version WHERE version = N;`).
  Roll back the WAR. No data loss because additive migrations
  don't touch existing columns.
- **Something destructive slipped through and corrupted data.**
  Stop Tomcat, restore the database from the step-3 backup.
  Memories written between the backup and the rollback are lost.
  This is the scenario the guardrails are designed to keep
  hypothetical.

### What this procedure deliberately drops

- **No test DB.** Single user. There is no rehearsal staging area.
- **No manual `psql -f`.** After Phase 2, the auto-migrator does it.
- **No "soak between migration and code".** They're inherently
  combined — the migration is in the WAR.
- **No long verification window before declaring success.** Live
  use *is* the verification.

---

## Order of operations summary

Each row is one production deploy. After Phase 2 lands, every
"migration" row's schema change is applied automatically by the
auto-migrator at startup — there's no manual SQL step. Rollback
for every row is either `mv Kiss.war.prev Kiss.war` (WAR) or the
explicit `DROP COLUMN` + `DELETE FROM db_version` listed in the
phase checklist.

| # | Deploy | `CURRENT_DB_VERSION` | Migration class | Code surface | Rollback |
|---|---|---|---|---|---|
| 1 | 1A provenance | — | (none) | `MemoryService`, `MemoryRepository`, `MCPServer` | swap WAR |
| 2 | 1B tag normalization | — | (none) | new `TagNormalizer`, `MemoryService.cleanTags` | swap WAR |
| 3 | 1C prompt budget | — | (none) | `MemoryService.buildContextPrompt` | swap WAR |
| 4 | 2 auto-migrator framework | 1 (baseline) | (none; framework creates `db_version` table) | new `Migration` interface, `MigrationRegistry`, `DbMigrator`; `MCPServer.<clinit>` calls migrator | swap WAR |
| 5 | 3 record_version + record-upgrader framework | 1 → 2 | `Migration002AddRecordVersion` | new `RecordUpgrader`, `RecordUpgraderRegistry`, `RecordMigrator`; INSERT writes `record_version` | swap WAR; if needed: `ALTER TABLE memories DROP COLUMN record_version; DELETE FROM db_version WHERE version=2;` |
| 6 | 3 proof-upgrader (optional) | (no DB bump) | (none) | one no-op `RecordUpgrader` registered; `CURRENT_RECORD_VERSION` → 2 | swap WAR |
| 7 | 4 freshness + dedup | 2 → 3 | `Migration003AddFreshness` | dedup + freshness + new `confirm` tool | swap WAR; if needed: `ALTER TABLE memories DROP COLUMN expires_at, DROP COLUMN last_confirmed_at; DELETE FROM db_version WHERE version=3;` |
| 8 | 5A conflict surfacing | — | (none) | `MCPServer` recall description | swap WAR |
| 9 | 5B tombstones | 3 → 4 | `Migration004AddTombstones` | `forget`, dedup integration | swap WAR; if needed: drop FK then `DROP COLUMN forget_reason, replaced_by_id; DELETE FROM db_version WHERE version=4;` |
| 10 | 5C decay | — | (none) | `MemoryRepository.findSimilar` | swap WAR (flag default = off) |

Three migrations across the whole plan (post Phase 2). Every one
of them is a `MigrationNNN_*.java` class registered with the Phase
2 framework. Every `CURRENT_DB_VERSION` bump ships in the same
commit as the migration class that justifies it (guardrail #11).
