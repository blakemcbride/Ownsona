# OwnSona Rollout Plan

Companion to `OwnSona-enhancement.md`. This plan stages the suggested
enhancements so the running single-user Tomcat instance keeps working
between deploys, every schema change is additive and rollback-safe, and
each phase can sit in production for a while before the next.

Touchpoints are referenced as `file:line` against the current tree
(branch `main`).

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
   changes. They need a backup immediately before, a dry run on the
   test DB with prod-size data, and a written rollback plan.
4. **Avoid migrations entirely when you can.** Most of Phase 1 in
   this plan is code-only for exactly this reason.

---

## Guardrails (apply to every phase)

These constraints bound what the plan does and does not touch.

1. **Embedding model is frozen.** Changing `EMBEDDING_MODEL` or
   `EMBEDDING_DIMENSIONS` invalidates every stored vector — the
   `memories.embedding` column is `vector(1536)` and the index is HNSW
   over cosine distance (`sql/001_init.sql:20,52`). Re-embedding all
   rows is a separate, much bigger project; not in scope here.
2. **Migrations are additive only.** New file per change
   (`sql/002_*.sql`, `sql/003_*.sql`, …). No edits to `001_init.sql`,
   no `ALTER ... DROP`, no renames. An old WAR must keep working
   against the new schema so rollback is just redeploying the previous
   `.war`. **All new columns must be nullable or carry a default**, so
   the migration itself rewrites no existing data — it only adds
   structure.
3. **`metadata JSONB` is the cheap extension point.** The column
   already exists (`sql/001_init.sql:37`) and is unused. Anything that
   doesn't need its own index or its own SQL filter goes there with
   zero migration. **Default to JSONB**; only add a real column when
   you need to index it or filter on it in SQL.
4. **MCP tool signatures stay backward compatible.** New parameters
   are optional with safe defaults. Existing clients (Claude Code,
   claude.ai, ChatGPT connector) must keep working without changes.
5. **Migrations get their own deploy window, separate from code.**
   Apply the SQL, verify the schema, then *stop*. The code that
   reads/writes the new columns ships in a later deploy, after the
   schema has been observed stable for at least a few hours of
   normal traffic. Old WAR + new schema must keep working — that's
   the property that makes this safe.
6. **Run every migration on the test DB first**, against a snapshot
   restored from a recent prod backup so the rowcount and data
   shape match reality. The integration tests under
   `src/test/precompiled/ai/ownsona/` are gated on
   `OWNSONA_TEST_DATABASE_URL` (`sql/run_tests.sh`). Run `./bld -v
   test` and `sql/run_tests.sh` against the test DB *after*
   applying the migration, with the current (un-updated) code, to
   confirm the old code still works against the new schema.
7. **Take a fresh backup immediately before each prod migration**,
   not just relying on the scheduled `ownsona-backup.timer`. Trigger
   `ownsona-backup.service` manually and verify the backup file
   exists and is non-empty before running `psql -f sql/0NN_*.sql`.
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

- [ ] All three changes built, tested on test DB
- [ ] `./bld -v test` and `sql/run_tests.sh` green
- [ ] `sql/smoke_test.sh` green against a local dev server
- [ ] `ownsona-backup.service` triggered manually
- [ ] `./bld war` → deploy → tail `tomcat/logs/catalina.out` for clean startup
- [ ] One real `remember`/`recall` round-trip from a Claude Code session

---

## Phase 2 — First additive migration: dedup-on-write + freshness

**Goal:** Catch semantic duplicates before they fragment the store,
and let memories carry an optional expiration / last-confirmed date.

**Risk:** Migration is the highest-risk operation in the plan
(modifies the prod schema). Mitigated by splitting the schema change
from the code that uses it: ship the migration alone, let it sit,
ship the code later.

**Ordering.** Three sub-deploys, in this order, with soak time
between each:

1. **2A — Migration deploy (Procedure B).** Apply
   `sql/002_freshness.sql`. **No code change.** The deployed WAR
   ignores the new columns. Let this sit for a few hours of normal
   use. Verify `recall` and `remember` still work via Claude Code.
2. **2B — Dedup code deploy (Procedure A).** Pure code change. Uses
   only columns that have existed since `001_init.sql`. Could in
   principle have shipped without 2A; it's grouped here because the
   features pair well thematically.
3. **2C — Freshness code deploy (Procedure A).** Reads/writes the
   columns added in 2A. Optional input parameters; existing rows
   stay null.

### 2A. Migration `sql/002_freshness.sql`

```sql
-- Optional freshness signals.  All nullable; existing rows untouched.
ALTER TABLE memories
    ADD COLUMN IF NOT EXISTS expires_at         TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS last_confirmed_at  TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS memories_expires_at_idx
    ON memories(expires_at)
    WHERE expires_at IS NOT NULL;
```

Two columns instead of stuffing into `metadata` because we'll filter
on them at query time (`expires_at < now()` in `recall`); a JSONB
field can't be indexed as cheaply.

### 2B. Semantic dedup on write

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

### 2C. Freshness fields on `remember` / `update_memory`

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

**Decay scoring (deferred to phase 3).** Just the storage + hard
expiration in this phase. A soft score decay (`score * exp(-age/τ)`)
is a follow-up; it's behavior change worth its own deploy.

### Phase 2 ship checklist

**Deploy 2A — migration alone (Procedure B):**
- [ ] Prod backup restored to test DB
- [ ] `sql/002_freshness.sql` applied to test DB
- [ ] `./bld -v test` and `sql/run_tests.sh` green against new schema
      with the *currently deployed* WAR code (proves
      old-WAR + new-schema works)
- [ ] Pre-written rollback SQL (`ALTER TABLE ... DROP COLUMN`) on
      standby
- [ ] Fresh prod backup taken and verified on disk
- [ ] `psql -U postgres -d ownsona -f sql/002_freshness.sql` in prod
- [ ] Smoke test green; tail catalina.out clean
- [ ] **Stop. Soak for at least a few hours of normal use.**

**Deploy 2B — dedup code (Procedure A):**
- [ ] Code change reviewed, tests added for dedup candidates
- [ ] Local smoke test green
- [ ] WAR deploy; verify

**Deploy 2C — freshness code (Procedure A):**
- [ ] Tests added: `recall` excludes expired rows, `confirm` updates
      timestamp, schema reads/writes work end-to-end
- [ ] Smoke test extended to cover new fields
- [ ] WAR deploy; verify
- [ ] One week soak before phase 3

---

## Phase 3 — Conflict surfacing + tombstones + decay

**Goal:** Make stale facts less likely to mislead. Make corrections
durable across sessions.

**Risk:** Moderate. Touches recall output shape and `forget`
semantics. Still schema-additive.

**Ordering.** Same migration-then-code split as Phase 2:

1. **3B-migration first (Procedure B).** Apply `sql/003_tombstones.sql`
   alone. Let it soak.
2. **3A — conflict surfacing code (Procedure A).** No schema
   dependency; could go before or after the migration. Cheap and
   low-risk, so ship it whenever.
3. **3B-code (Procedure A).** Reads/writes the new columns.
4. **3C — decay (Procedure A).** Pure code; behind a config flag,
   default off.

### 3A. Conflict surfacing (cheap version)

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

### 3B. Tombstones

**What.** Soft delete already exists (`deleted_at` is set). Add
`forget_reason TEXT` and `replaced_by_id BIGINT` columns so a
correction trail survives. Exclude tombstones from normal `recall`,
but consult them in dedup-on-write so the system doesn't reaccept a
previously-corrected fact.

**Migration `sql/003_tombstones.sql`.**

```sql
ALTER TABLE memories
    ADD COLUMN IF NOT EXISTS forget_reason    TEXT,
    ADD COLUMN IF NOT EXISTS replaced_by_id   BIGINT REFERENCES memories(id);
```

**Touchpoints.**
- `MemoryRepository.java:203` (`softDelete`) — accept optional reason
  / replacement id.
- `MemoryService.java:351` (`forget`) — accept `reason` and
  `replaced_by` parameters.
- Dedup-on-write (2B) — when a candidate hit's tombstone matches the
  new text, return a louder signal (`"previously corrected"`).
- `MCPServer.java` — extend `forget` schema; add optional fields.

### 3C. Soft decay in recall (optional, behind a flag)

**What.** Multiply similarity score by `exp(-age_days / τ)` for rows
where `expires_at IS NULL AND last_confirmed_at IS NULL`. Durable
facts (those the user re-confirmed, or that have an explicit
`expires_at`) are exempt.

**Default off.** Add a `decay_half_life_days` config knob (off if
unset). Turn it on after watching real recall traffic.

**Touchpoint.** `MemoryRepository.findSimilar` SQL — compute the
adjusted score in the SQL or in Java. The latter is simpler and easy
to A/B against the raw score.

### Phase 3 ship checklist

**3A — conflict surfacing code (Procedure A):**
- [ ] `recall` description and result schema updated; tests updated
- [ ] WAR deploy; verify

**3B-migration — schema alone (Procedure B):**
- [ ] Prod backup restored to test DB
- [ ] `sql/003_tombstones.sql` applied to test DB
- [ ] Old WAR + new schema verified
- [ ] Pre-written rollback SQL on standby
- [ ] Fresh prod backup taken and verified
- [ ] Apply in prod; smoke test; soak

**3B-code — tombstones code (Procedure A):**
- [ ] Tests cover: tombstone excluded from recall, surfaced in dedup
- [ ] WAR deploy; verify

**3C — decay (Procedure A):**
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

Two distinct procedures because the risk profile is different.
**Migrations and code deploys are separate operations and run on
separate days.** Don't bundle them.

### Procedure A: Code-only deploy (cheap, ~10 min, easy rollback)

```
# 1. Pre-flight (on dev box)
cd ~/GitHub.blakemcbride/Ownsona
git status                         # clean tree
./bld -v build                     # compiles cleanly
./bld -v test                      # unit tests pass
OWNSONA_TEST_DATABASE_URL=... sql/run_tests.sh   # integration tests

# 2. Local smoke test
./bld develop &                    # local Tomcat
OWNSONA_API_TOKEN=... sql/smoke_test.sh http://localhost:8080/mcp

# 3. Production deploy
./bld war
sudo cp /path/to/tomcat/webapps/Kiss.war /path/to/tomcat/webapps/Kiss.war.prev
sudo ./kill-tomcat
sudo cp work/Kiss.war /path/to/tomcat/webapps/
sudo systemctl start ownsona.service
sudo tail -f /path/to/tomcat/logs/catalina.out   # watch for clean boot

# 4. Verification
OWNSONA_API_TOKEN=... sql/smoke_test.sh https://your.host/mcp
```

**Rollback (code).** Stop Tomcat, `mv Kiss.war.prev Kiss.war`, start.
Done. No data implications.

### Procedure B: Schema migration (expensive, run alone)

A migration is *its own deploy*. No code change ships with it. The
WAR running before the migration must keep working after the
migration — that's the property to verify.

```
# 1. Restore a recent prod backup onto the test DB and apply the migration there.
#    The point is to catch shape/rowcount surprises that a synthetic test DB hides.
pg_restore --clean -d "$OWNSONA_TEST_DATABASE_URL" /path/to/latest-prod-backup.dump
psql "$OWNSONA_TEST_DATABASE_URL" -f sql/0NN_*.sql

# 2. With the test DB now on the new schema, run the *currently deployed* code's
#    tests against it. This verifies old-WAR + new-schema is fine. If they fail,
#    the migration is not backward compatible — stop and revise.
OWNSONA_TEST_DATABASE_URL=... sql/run_tests.sh

# 3. Production: explicit backup, verified before touching the DB.
sudo systemctl start ownsona-backup.service
sudo journalctl -u ownsona-backup.service --since "5 minutes ago"
ls -lh /path/to/backups/ | tail -3   # confirm a fresh, non-zero-byte file landed

# 4. Production migration (during a quiet window).
sudo -u postgres psql -d ownsona -f /path/to/sql/0NN_*.sql

# 5. Sanity check: tail catalina.out for errors, run a normal remember/recall
#    via the existing client. The point is to prove the deployed WAR still works
#    against the new schema before any new code goes in.
OWNSONA_API_TOKEN=... sql/smoke_test.sh https://your.host/mcp
sudo tail -n 100 /path/to/tomcat/logs/catalina.out

# 6. Stop. Walk away. Let it sit for at least a few hours of normal use
#    before deploying any code that depends on the new columns (procedure A,
#    later).
```

**Rollback (schema).** This is the painful one — plan it before
running step 4, not after.

- **If the migration was purely `ADD COLUMN` with nullable / defaulted
  columns** (which every migration in this plan is), then "rollback"
  is just `ALTER TABLE memories DROP COLUMN ...`. Write that
  `DROP COLUMN` statement before applying the migration and keep it
  on standby. No data is lost because no existing column was changed.
- **If the migration also rewrote existing data** (a one-time
  `UPDATE`, e.g. the optional tag-normalization sweep): rollback is
  restore from backup. Every memory written between the backup and
  the rollback is gone. This is why this plan keeps data rewrites out
  of migrations.
- **If the migration was botched (partial apply, half-committed
  state):** restore from backup. The single-user nature means the
  loss window is small (writes per day are low), but it is still loss.

---

## Order of operations summary

Each row is one production deploy. `B` deploys (schema migrations)
ship alone, then soak, before any `A` deploy that depends on them.
The `A` deploys are cheap and easily reversed.

| # | Deploy | Type | DB change | Code surface | Reversibility |
|---|---|---|---|---|---|
| 1 | 1A provenance | A code | none (uses existing `metadata`) | `MemoryService`, `MemoryRepository`, `MCPServer` | swap WAR |
| 2 | 1B tag normalization | A code | none | new `TagNormalizer`, `MemoryService.cleanTags` | swap WAR |
| 3 | 1C prompt budget | A code | none | `MemoryService.buildContextPrompt` | swap WAR |
| 4 | 2A migration | **B schema** | `sql/002_freshness.sql` | none | `DROP COLUMN` (no data lost; columns are new) |
| 5 | 2B dedup code | A code | none | `remember`, `rememberBatch`, `RememberResult` | swap WAR |
| 6 | 2C freshness code | A code | none (uses 2A columns) | insert/update/select paths, new `confirm` tool | swap WAR |
| 7 | 3A conflict surfacing | A code | none | `MCPServer` recall description | swap WAR |
| 8 | 3B migration | **B schema** | `sql/003_tombstones.sql` | none | `DROP COLUMN` (the FK constraint must be dropped first) |
| 9 | 3B tombstones code | A code | none (uses 3B columns) | `forget`, dedup | swap WAR |
| 10 | 3C decay | A code | none | `MemoryRepository.findSimilar` | swap WAR (flag default = off) |

Two `B` deploys total across the whole plan. Everything else is a
code swap.
