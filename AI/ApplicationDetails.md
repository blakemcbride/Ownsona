# OwnSona Application Details

OwnSona-specific operational knowledge. The Kiss framework reference is in
`AI/KnowledgeBase.md`; consult both.

**This file describes the *current* state of the application. Do not
record history here** — past deploys, commit hashes, and per-phase
timelines belong in `git log` and `OwnSona-rollout-plan.md`, not in this
file.

---

## What OwnSona is

A single-user MCP (Model Context Protocol) memory server. It stores
durable facts about the user in PostgreSQL with pgvector embeddings and
exposes them as MCP tools so any cloud LLM client (Claude, ChatGPT,
Gemini, …) can write to and read from the same memory store.

Deployed on a small Linux VPS, supervised by systemd, behind Tomcat
terminating TLS directly on :443. Single user (Blake).

Read `OWNSONA_SPEC.md` for the protocol and per-tool wire format. Read
`MCPServer.md` for server design notes. Read `INSTALL.md` for fresh
installs and existing-install upgrades.

---

## Tech stack

- Java 17+ application code.
- [Kiss framework](https://kissweb.org) — provides the servlet
  container glue, JSON-RPC plumbing, c3p0 connection pool, the
  `MainServlet` / `MCPServerBase` base classes, and the custom `bld`
  build script. **Read `AI/KnowledgeBase.md` for the framework
  reference; do not modify `src/main/core/`.**
- Apache Tomcat 11 (Jakarta EE 11, Servlet 6.1) — embedded.
- PostgreSQL 16+ with `pgvector` and `pg_trgm` extensions.
- OpenAI `text-embedding-3-small` (1536 dims) via a swappable
  `EmbeddingProvider` abstraction.
- JUnit 5 for tests, driven by a custom shell runner (no surefire).

---

## Repo layout (the parts that matter)

```
src/main/precompiled/ai/ownsona/
    MCPServer.java                       # @WebServlet(/mcp), MCP tool catalog
    Config.java                          # application.ini loader
    SecretScanner.java
    TextNormalizer.java
    TagNormalizer.java                   # synonym → canonical tag map
    VectorFormat.java
    oauth/
        OwnsonaUserAuthenticator.java    # AS login: checks OWNSONA_LOGIN_USERNAME/PASSWORD
        OwnsonaConsentProvider.java      # AS consent page text + display name
    embeddings/
        EmbeddingProvider.java           # the only vendor-coupled seam
        OpenAIEmbeddingProvider.java
        MockEmbeddingProvider.java
    memory/
        MemoryService.java               # the MCP tools' business logic
        MemoryRepository.java            # SQL layer
        MemoryRow.java / MemoryInsert.java
        BatchRememberItem.java / BatchRememberResult.java
        RememberResult.java
        PromptFormatter.java
        ServiceException.java
        RecordUpgrader.java              # per-row upgrader interface
        RecordUpgraderRegistry.java      # CURRENT_RECORD_VERSION + ordered list
        RecordMigrator.java              # the per-row walker, runs at startup
    migrations/
        Migration.java                   # DB-version migration interface
        MigrationRegistry.java           # CURRENT_DB_VERSION + ordered list
        DbMigrator.java                  # creates db_version, applies missing migrations
        MigrationNNN_*.java              # one per applied schema/data change
src/test/precompiled/ai/ownsona/
    *Test.java                           # unit tests (no DB needed)
    memory/*Test.java                    # unit tests in the memory package
    MemoryRepositoryIntegrationTest.java # gated on OWNSONA_TEST_DATABASE_URL
sql/
    001_init.sql                         # bootstrap schema (fresh installs)
    setup_db.sh                          # role + extensions + 001_init.sql
    migrator_prep.sql                    # one-time prep for existing installs
    run_tests.sh                         # JUnit runner
    smoke_test.sh                        # curl drive of every MCP tool
    ownsona.service / ownsona-backup.*   # systemd units
```

`src/main/core/` is the Kiss framework — never modify it.
`src/main/frontend/` is the bundled example UI — generally not touched.

---

## Build & test commands

| Command | What it does |
|---|---|
| `./bld -v build` | Compile core + precompiled into `work/exploded/` |
| `./bld war` | Produce `work/Kiss.war` |
| `./bld develop` | Local dev server (frontend + backend) |
| `sql/run_tests.sh` | Run JUnit tests. Unit tests always run; integration tests under `MemoryRepositoryIntegrationTest` (and equivalents) silently skip without `OWNSONA_TEST_DATABASE_URL` |
| `sql/smoke_test.sh [url]` | End-to-end curl drive of every MCP tool against a live server |

**There is no `./bld test` target.** Use `sql/run_tests.sh`.

---

## Design invariants (don't violate these)

1. **Vendor neutrality.** Generative-LLM calls (chat / completion /
   classification APIs) must NOT appear in the request or write path.
   The only vendor-coupled seam is `EmbeddingProvider`. MCP tool
   descriptions and schemas stay vendor-generic — no "Claude should …",
   no fields named after a specific vendor's API.

2. **Migrations are additive only.** Every `MigrationNNN_*.java` adds
   columns / indexes / nullable fields. Never `DROP`, never `RENAME`,
   never rewrite existing values in a migration. Destructive data
   rewrites are one-time manual operations, not migrations.

3. **Per-record upgraders are additive only and idempotent.** A
   `RecordUpgrader` may fill in a new field on an existing row. It must
   NOT overwrite, transform, or delete existing values. It must produce
   the same outcome when re-run on the same row. Per-row failures are
   logged but never block startup.

4. **Auto-migration framework owns all DB changes after the baseline.**
   After Phase 2, no schema change ships as a loose `psql -f` step.
   Every change is a `Migration` class applied by `DbMigrator` at
   startup.

5. **`CURRENT_DB_VERSION` and the migration registry stay in
   lockstep.** Same for `CURRENT_RECORD_VERSION` and
   `RecordUpgraderRegistry`. The framework fails fast at startup on a
   mismatch, but catch it at code-review time. These three changes
   (new `MigrationNNN_*.java` class, registration in the registry,
   constant bump) always ship in the same commit.

6. **Code conventions over framework conventions when they differ.**
   Use Kiss's `Connection`/`Record` API, not raw JDBC. Use the
   `MainServlet.openNewConnection()` + `closeConnection(db, success)`
   pattern. Use `org.kissweb.json.{JSONObject, JSONArray}`, not
   external JSON libraries.

7. **No new `.sql` files for migrations.** Bootstrap (`001_init.sql`)
   and one-time ops scripts (`migrator_prep.sql`) are the only SQL
   files in `sql/`. Schema migrations are Java classes.

8. **OAuth 2.1 is the only authentication path.** `MCPServer` does NOT
   override `authenticate()` — the base class's OAuth validator handles
   every request. Don't re-introduce a static bearer-token shortcut,
   a `?token=` URL fallback, or a `return true` debug bypass. The
   embedded AS lives at `/oauth/*` and is configured by
   `OAuthAsEnabled` + the registered `UserAuthenticator` /
   `ConsentProvider`. The user-facing credentials are
   `OWNSONA_LOGIN_USERNAME` / `OWNSONA_LOGIN_PASSWORD` in
   `application.ini`.

---

## Operational model

- **Single user.** No staging server, no rehearsal pass, no separate
  test DB by default. Live exercise on prod is the verification path.
- **Backup-first deploy.** Take a fresh backup before any deploy that
  bumps `CURRENT_DB_VERSION`; the backup is the safety net.
- **Procedure A / Procedure B** (terminology used in
  `OwnSona-rollout-plan.md`):
  - **A**: deploy with no migration. Pure WAR swap, swap back to roll
    back.
  - **B**: deploy with a migration. Backup first; `DROP COLUMN`
    rollback SQL pre-written.
- **Auto-migrator runs at servlet load** (`loadOnStartup = 1`).
  Failures refuse to load the servlet, surfaced in
  `journalctl -u ownsona.service`.
- **Log level**: `ai.ownsona` is INFO during the static initializer
  (banner + migrator output) and ERROR afterward (per-request INFO
  silenced). WARN/ERROR always appear.

---

## How to add a schema change

1. Create `src/main/precompiled/ai/ownsona/migrations/MigrationNNN_descriptive_name.java`
   implementing `Migration`. Its `apply(db)` does the additive DDL.
2. Add `m.add(new MigrationNNN_DescriptiveName());` to the static
   list in `MigrationRegistry`.
3. Bump `CURRENT_DB_VERSION` in `MigrationRegistry` to `NNN`.
4. Commit all three changes together. The deploy is a normal WAR
   swap; the migrator applies the change at next startup.

Rollback SQL goes in the rollout plan's ship checklist for that
phase (not in the migration class).

---

## How to add a per-record upgrader

1. Create a `RecordUpgrader` implementation in
   `src/main/precompiled/ai/ownsona/memory/`. Implement `upgrade(db, row)`
   to fill in new fields ONLY (additive + idempotent). Set
   `fromVersion()` to the row's current version and `toVersion()` to
   `fromVersion() + 1`.
2. Add to `RecordUpgraderRegistry`'s static list, in order.
3. Bump `CURRENT_RECORD_VERSION` to match.
4. Commit all three together. The walker picks up old rows on next
   startup.

---

## What lives where (when looking something up)

- **Current rollout state / phase status**: `OwnSona-rollout-plan.md`
  has an Implementation Status table near the top, kept current.
- **Past activity / commit history**: `git log`. Don't try to recall
  what was done when from memory or from this file.
- **Original enhancement suggestions**: `OwnSona-enhancement.md`
  (frozen, do not edit).
- **Fresh-install walkthrough**: `INSTALL.md`.
- **Upgrade walkthrough for existing installs**: `INSTALL.md`
  section 15 ("Upgrading an existing install").
- **Per-tool wire format**: `OWNSONA_SPEC.md`.
- **Server design notes**: `MCPServer.md`.
- **Kiss framework reference**: `AI/KnowledgeBase.md`.

---

## Things that bit me before, watch out

- **`Record.getInt()` returns boxed `Integer`** (nullable). Check for
  null before unboxing.
- **`MemoryRow` is a transport object** with public fields. Don't add
  invariants there; constraints belong in the service layer.
- **Kiss connections have `autoCommit = false`**. `closeConnection(db,
  success=true)` commits; `success=false` rolls back. Forgetting the
  `success` flag means everything rolls back.
- **`Configurator.setLevel("ai.ownsona", Level.INFO)` is set briefly
  in `MCPServer.<clinit>`** so the startup banner and migrator output
  appear, then dropped to ERROR. To debug per-request behavior, change
  the trailing setLevel to `Level.INFO` (or remove it).
- **`ownsona` Postgres role needs `CREATE ON SCHEMA public` and
  ownership of `memories`**. Fresh installs get this via `001_init.sql`;
  existing installs need `sql/migrator_prep.sql` once.
- **The AS persists state to the path set in `OAuthAsIniFile`**.
  Production deployments set this to an absolute path outside the
  Tomcat webapps tree (e.g. `/home/ownsona/oauth.ini`) so WAR
  redeploys can't touch it. The containing directory must be writable
  by the JVM user (`ownsona` under systemd). If `OAuthAsIniFile` is
  unset, the AS falls back to `WEB-INF/backend/oauth.ini`, which is
  rewritten on every redeploy — never leave a production install in
  that state. Symptom of either misconfig: every `/oauth/token`
  exchange fails after restart (state-file write error) or every LLM
  client gets 401 after every redeploy (signing key rotated under
  them).
- **OAuth access tokens have a 1-hour default TTL**. A long-running
  smoke-test or curl session against `/mcp` starts returning 401
  mid-stream when the token expires; refresh via `/oauth/token` with
  `grant_type=refresh_token`. Bump `OAuthAccessTokenTtlSeconds` only
  if you understand the tradeoff (longer-lived tokens harder to
  revoke).
- **Compiled tests run against the *currently-compiled* classes in
  `work/exploded/`**. If you change a `private` to package-private for
  testability, run `./bld -v build` before `sql/run_tests.sh` or you'll
  get stale-class compile errors.
- **Soft-deleted rows are dual-purpose.** They're hidden from recall
  (`deleted_at IS NULL` filter in `findSimilar` / `listRecent` /
  `textSearch`) AND consulted by the dedup-on-write check
  (`findSimilarTombstones`).  A "forgotten" row therefore continues to
  influence behavior --- it prevents a previously-corrected fact from
  silently re-entering the store.  Hard delete is the only way to
  truly remove a memory's influence.
- **`forget(hard_delete=true, reason=...)` is rejected.** A hard
  delete drops the row entirely, so there's nowhere to record the
  tombstone metadata.  The combination is treated as `INVALID_INPUT`
  so callers don't think they recorded a reason when they didn't.

---

## Maintenance

**Keep this file current.** When an invariant changes, a new convention
emerges, or operational practice shifts, update this file in the same
commit. Stale guidance here misleads future Claude sessions more than
no guidance would.

Do NOT add to this file:
- Per-phase commit logs or dates (use `git log`).
- The Implementation Status table from `OwnSona-rollout-plan.md`
  (already lives there).
- One-off debugging notes that aren't durable (those belong in commit
  messages or PR descriptions, not here).
