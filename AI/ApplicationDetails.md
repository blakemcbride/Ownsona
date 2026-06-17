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
        EmbeddingProvider.java           # embedding vendor seam (always used)
        OpenAIEmbeddingProvider.java
        MockEmbeddingProvider.java
    llm/
        GenerativeProvider.java          # optional generative seam (LLM_* config)
        OpenAIGenerativeProvider.java
        MockGenerativeProvider.java
        ConsolidationJob.java            # Tier 3 "sleep" job (cluster -> LLM merge -> supersede)
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
`src/main/backend/CronTasks/` holds the Kiss Cron files: `crontab`
(schedule) and `Consolidate.groovy` (a thin shim that calls
`ConsolidationJob.runScheduled()`). Kiss auto-starts Cron from
`MainServlet`; both files are hot-editable on a running server.

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

1. **Vendor neutrality, via two independent seams.** Vendor coupling is
   confined to two pluggable seams, configured separately:
   `EmbeddingProvider` (always used; `EMBEDDING_*` keys) and the
   **optional** `GenerativeProvider` (`ai.ownsona.llm`; `LLM_*` keys). A
   generative LLM **may** be used for background / maintenance work and
   best-effort assists through the `GenerativeProvider` seam — currently
   the Tier 3 consolidation job — but it must **NOT** sit on the
   synchronous recall / remember hot path in a way that makes a normal
   read or write fail when the model is down or unconfigured. When no
   `GenerativeProvider` is configured (no `LLM_API_KEY`), the server runs
   fully on embeddings + deterministic heuristics, exactly as before, and
   every generative feature stays off. MCP tool descriptions and schemas
   stay vendor-generic — no "Claude should …", no fields named after a
   specific vendor's API.

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

9. **`keep='Y'` is an immutable/undeletable lock, enforced in the
   service layer for every client.** `MemoryService.requireNotProtected`
   gates `update`, `forget`, `updateBatch`, and `forgetBatch`; a
   protected row throws `ServiceException.PROTECTED`. The lock covers
   text/tags/importance edits and deletion. It does NOT cover the
   freshness ping (`confirm`), salience reinforcement (`reinforce`, and
   the reinforcement `confirm` applies), internal re-embedding, or
   additive record upgraders — none of which change user-visible content.
   A `remember(..., supersedes=[id])` correction that would soft-delete a
   `keep='Y'` row instead skips it and reports it as `protected`. The `keep`
   flag itself is **always settable** (a protected row can be
   un-protected — no permanent lockout), but only through the CLI-only
   `set_keep` path. When adding a new mutation/deletion path, add the
   `requireNotProtected` guard.

10. **The `keep` flag changes only via `set_keep`, which is CLI-only.**
    Two layers keep LLM clients out: `set_keep` is omitted from
    `listTools()` (undiscoverable), and `doSetKeep` requires an
    `admin_secret` matching `Config.ADMIN_SECRET` (`OwnsonaAdminSecret`
    in `application.ini`), compared in constant time. It **fails closed**
    — if no secret is configured, every `set_keep` is rejected as
    "Unknown tool". Don't advertise `set_keep`, and don't drop the
    secret check. The CLI holds the same secret as `admin_secret` in its
    config.

11. **The token audience is the bare origin, and the audience check
    tolerates a trailing slash.** `OAuthResourceIdentifier` is
    `https://ownsona.com` (the issuer / origin), **not** the `/mcp` URL.
    Real MCP clients disagree on the RFC 8707 `resource` value and a
    single exact-match setting cannot satisfy all of them:
    - **ChatGPT** always sends the bare origin `https://ownsona.com`
      (derived from the server origin; it ignores the `resource` the
      metadata advertises).
    - **Claude** discovers the metadata `resource` and treats it as a
      URI, RFC 3986-canonicalizing an empty path to a trailing slash, so
      it sends `https://ownsona.com/`.
    - **The OwnSona CLI** discovers and sends the metadata `resource`
      verbatim.

    A `/mcp` audience locks out ChatGPT (it never sends `/mcp`); a bare
    origin locks out Claude under an exact-match check (trailing slash).
    The resolution: keep the audience at the origin **and** patch the
    core validator to trim a trailing slash on both sides before
    comparing.

    ⚠️ **This required a patch to vendored Kiss core**
    (`src/main/core/org/kissweb/oauth/BearerTokenValidator.java`,
    `checkAudience()`) — normally off-limits, but unavoidable: the
    audience comparison lives only there, overriding `authenticate()` is
    forbidden (invariant 8), and no config value reconciles the clients.
    The patch mirrors the trailing-slash normalization `checkIssuer()`
    already applies, so it is app-neutral and suitable to **upstream to
    Kiss**. It will be **lost on a Kiss upgrade** — re-apply it (or
    confirm it landed upstream) after any framework bump.

    Because the audience is the bare origin, the canonical
    protected-resource metadata URL (RFC 9728: host +
    `/.well-known/oauth-protected-resource` + the resource's path, which
    is empty) is just the root — which the core
    `ProtectedResourceMetadataServlet` already serves, and which the
    `BearerTokenValidator` challenge already advertises. So **no
    app-level metadata servlet is needed**; don't reintroduce one (an
    earlier interim `ProtectedResourceMetadataMcpServlet` that served
    `/mcp`-relative metadata URLs was removed — it duplicated generic
    core logic and became unnecessary once the audience moved to the
    origin). The CLI (`cli/src/oauth.c`) discovers the resource from the
    root metadata rather than hardcoding `server_url`. After changing the
    audience, existing client connections must be reconnected — clients
    cache the discovered `resource` and reuse it on refresh.

12. **Memories are never forgotten for being old — only when explicitly
    invalidated.** This is a firm product decision (the learning-memory
    work deliberately diverges from the roadmap's decay-based pruning).
    There is **no time-based decay and no age-based pruning** anywhere.
    The `salience` weight (Tier 1) changes only on an explicit feedback
    *event* — `reinforce` or `confirm` — via the reward-modulated rule
    `salience ← clamp((1−λ)·salience + η·reward)` (`η`/`λ`/clamp live in
    `MemoryService` as `REINFORCE_*` / `SALIENCE_*`; the SQL update is in
    `MemoryRepository.reinforce`, mirrored by the pure
    `MemoryService.applyReinforcement` for tests). The `(1−λ)` factor is
    update smoothing, **not** a clock-driven decay. Deletion happens only
    through explicit `forget` or a `remember(..., supersedes=[…])`
    correction. If you're ever tempted to add a "prune stale rows" job,
    don't — surface the conflict instead and let the user/LLM decide.

13. **Recall ranks by salience but reports cosine.** `recall` /
    `search_memory` order results by
    `cosine · (1 + SALIENCE_RANK_WEIGHT · COALESCE(salience, importance,
    0.5))`, breaking ties by recency (`last_confirmed_at`, then
    `last_used_at`, then `created_at`) — recency is a *tiebreaker only*,
    never a decay. The blended score is used solely for ordering; the
    `score` field returned to clients stays the raw cosine similarity, so
    `min_score` and the client-side contradiction heuristic keep their
    original meaning. The ranking lives in `MemoryRepository.findRanked`
    (over-fetches by cosine via the HNSW index, then re-ranks); the
    dedup / conflict paths still use plain-cosine `findSimilar`, so don't
    point them at `findRanked`.

14. **Conflict handling is surfacing + explicit resolution, never an
    LLM judgement.** The server flags *potential* conflicts —
    semantically close AND tag-sharing — on write (`potential_conflicts`
    in the `remember` response) and on demand (`find_conflicts`), using
    pure embedding + tag-overlap math (the synchronous remember/recall path
    stays deterministic and LLM-free by design — invariant #1; the
    generative seam is for background work, not the hot path). It never
    decides on the write path whether two facts actually contradict.
    Resolution is always explicit, via two `remember` levers (Tier 2):
    - `supersedes=[id]` — the old fact is now **wrong**: soft-delete it
      and link `replaced_by_id`.
    - `downweights=[id]` — the old fact is merely **outdated**: apply a
      strong negative reinforcement (`CONFLICT_PENALTY`) so the new fact
      out-ranks it, but **keep it** active and recallable. This is the
      non-destructive "un-learn the stale answer" path and the default
      choice when the LLM isn't sure the old fact is flatly wrong.

    Both `remember` levers are *targeted overrides*, so both respect
    `keep='Y'` (the protected row wins; reported `protected`). This is
    deliberately different from raw `reinforce`, which is symmetric
    feedback and stays exempt from the keep lock (invariant #9). The
    distinction: a lever that names a specific memory to retire/demote in
    favor of another must not override protection; general thumbs-up/down
    feedback may. The user can also always resolve manually with `forget`.

15. **Consolidation (Tier 3) is a gated, recoverable, cost-bounded
    background job.** The "sleep" job (`ai.ownsona.llm.ConsolidationJob`,
    driven by Kiss Cron via `backend/CronTasks/Consolidate.groovy`)
    clusters near-duplicates, asks the `GenerativeProvider` to merge each
    cluster into one canonical fact, stores it, and supersedes the
    originals. Non-negotiable properties:
    - **Off by default, three ways:** the crontab line ships commented
      out, `CONSOLIDATION_ENABLED` defaults false, and it no-ops without
      `LLM_API_KEY`. Any one keeps it (and all LLM spend) off.
    - **Recoverable, never destructive:** originals are *superseded*
      (soft-delete + `replaced_by_id`), never hard-deleted.
    - **Respects `keep='Y'`:** a cluster containing any protected memory
      is skipped entirely — the job never touches anything around a
      locked memory.
    - **Cost-bounded:** at most `CONSOLIDATION_MAX_GROUPS` (default 25)
      LLM calls per run, and zero calls when no near-duplicate clusters
      exist. Threshold defaults high (0.95) so only near-identical rows
      merge. A garbled/declined model reply is treated as "do not merge"
      (never supersede on a bad reply).
    - **Auditable:** each merge and the run summary log at WARN (visible
      under the `ai.ownsona` ERROR floor) with the superseded ids, so any
      merge can be reviewed and undone.
    Don't move this onto the synchronous path or make it hard-delete.

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
- **Learning-memory enhancement roadmap** (salience/decay,
  feedback-driven reinforcement, contradiction supersession,
  consolidation — turning the static RAG store into a memory that
  *learns*): `OwnSona.md`. Distilled from the ArtificialIntelligence
  project's experiments; **read this first** when working on making
  OwnSona learn. Ordered by evidence, with concrete Postgres/pgvector
  schema and a `reinforce` MCP tool.
- **The full reasoning chain** behind that roadmap (backprop vs.
  Hebbian, why trained nets can't learn, bootstrapping from a
  pretrained model, the controller-vs-RAG head-to-head):
  `SystemAnalysis.md`.
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
- **`forget_batch` is soft-delete only by design.** A bulk hard
  delete has no tombstone trail, so the batch tool intentionally
  exposes no `hard_delete` flag.  Callers that need to erase a
  single row completely fall back to the single-row `forget` with
  `hard_delete=true`.  The other reason batching matters: cleanup
  workflows that issue N single `forget` calls have been observed
  to trip LLM-client safety filters that react to the *context* of
  short technical fragments (paths, jar names, exit codes) sitting
  in scope; a single `forget_batch` carrying a list of integers in
  its payload doesn't give the filter anything per-row to react to.

- **`set_keep` fails closed and is unlisted.** It does not appear in
  `tools/list`, and `doSetKeep` rejects every call whose `admin_secret`
  doesn't match `Config.ADMIN_SECRET` — including the case where the
  secret is unset (`OwnsonaAdminSecret` missing from `application.ini`).
  Symptom of a forgotten secret: the CLI's `enumerate` k/r actions (and
  any `set_keep` call) come back "Unknown tool: set_keep" even though
  the tool is wired up. Fix is to set `OwnsonaAdminSecret` on the server
  and the matching `admin_secret` in the CLI config — not to advertise
  or un-gate the tool.

- **`keep` is a `CHAR(1)` with a DB CHECK (`Y`/`N`/`U`).** `Record`
  reads it back as a one-character `String` ("U"), not a `char`. Compare
  with `"Y".equals(row.keep)`, and remember new rows default to `"U"`
  via the column default — `MemoryInsert.keep` defaults to `"U"` to
  match. The protection logic keys on the exact string `"Y"`.

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
