# Re-embedding: switching the embedding model or provider

OwnSona stores every memory's text alongside a vector embedding of that
text.  Recall, dedup-on-write, and conflict surfacing all rely on those
vectors.  Sooner or later you will want to switch to a different
embedding model or a different embedding provider, and the existing
vectors will need to be replaced — vectors from different models live
in different spaces, and cosine distance between them is noise.

This document explains **when and why** you would do that, and the
**exact mechanics** of doing it cleanly.

---

## Contents

1. [When and why you might switch](#when-and-why-you-might-switch)
2. [What is and isn't affected](#what-is-and-isnt-affected)
3. [How re-embedding works inside the server](#how-re-embedding-works-inside-the-server)
4. [Procedure A: same-dimension switch](#procedure-a-same-dimension-switch)
5. [Procedure B: different-dimension switch](#procedure-b-different-dimension-switch)
6. [Monitoring and recovery](#monitoring-and-recovery)
7. [Cost](#cost)
8. [Gotchas](#gotchas)

---

## When and why you might switch

A few realistic triggers:

- **Cost.** A new model from the same provider, or a different
  provider entirely, comes in at half the per-token price.  At
  thousands of memories this matters less than at millions, but it
  matters.
- **Quality.** A newer / larger model produces measurably better
  recall on your kind of facts.  Embedding-model leaderboards
  (e.g. MTEB) move every few months.
- **Provider deprecation.** Your current provider end-of-lifes the
  model you embedded under.  Migrating before they yank the API is
  cheaper than scrambling after.
- **Vendor diversity / data sovereignty.** You decide you no longer
  want your memory texts traveling to a third party for embedding.
  You move to a self-hosted model (Ollama, sentence-transformers,
  BGE, etc.).
- **Local privacy / air-gap.** Same as above: the entire pipeline
  becomes on-host.
- **You changed your mind about the dimension.**  You started with a
  1536-dim model because that was the default and now want to drop
  to 768 to save disk / index memory.

You are *not* changing the LLMs that use the memory store — Claude /
ChatGPT / Gemini connect over MCP and don't care which embedding model
produced the vectors they're searching.  The CLI's `teach` subcommand
uses an independent LLM for fact extraction (separate config, separate
endpoint, separate API key) and has no data-shape effect on the server.
Switching the server's embedding provider has no effect on the CLI's
teach LLM, and vice versa.

---

## What is and isn't affected

Every memory row carries `embedding`, `embedding_provider`,
`embedding_model`, and a stored `text`.  A re-embed pass:

- **Replaces** every row's `embedding` with a vector from the new
  provider/model.
- **Replaces** every row's `embedding_provider` and `embedding_model`
  to match the active config.
- **Leaves untouched**: `text`, `normalized_text`, `tags`,
  `importance`, `metadata`, `created_at`, `updated_at`, `deleted_at`,
  `expires_at`, `last_confirmed_at`, `forget_reason`, `replaced_by_id`,
  `record_version`, `user_id`.

Soft-deleted rows (tombstones) **are re-embedded**.  Tombstones
participate in dedup-on-write via `findSimilarTombstones`, so their
vectors have to move into the new model's space along with everything
else.  If the walker skipped them, the dedup-on-write check would
start returning gibberish for previously-corrected facts.

---

## How re-embedding works inside the server

The walker is `ReembedJob` (`src/main/precompiled/ai/ownsona/embeddings/ReembedJob.java`).
It runs from `MCPServer.<clinit>` after `DbMigrator` and
`RecordMigrator`, and only when `REEMBED_ON_STARTUP=true` in
`application.ini`.  On clean completion it flips that flag back to
`false` so a routine restart doesn't accidentally re-trigger it.

Order at startup:

```
1. DbMigrator        — applies any new schema migrations
2. RecordMigrator    — runs per-row upgraders
3. ReembedJob        — re-embeds stale rows (only if REEMBED_ON_STARTUP=true)
```

That ordering matters because a different-dimension switch ships a
**migration** that resizes the `embedding` column.  The migration
runs *first*, then the walker fills the now-resized column with new
vectors.

The walker:

- Selects rows where `embedding IS NULL` *or*
  `embedding_provider IS DISTINCT FROM <active>` *or*
  `embedding_model IS DISTINCT FROM <active>`, paginating by id.
- Reads each batch's `text` in a single query.
- Calls `EmbeddingProvider.embedBatch()` once per batch (default
  batch size 50).
- Writes new vector + new provider + new model back, one row at a
  time, in a single transaction per batch.

A crash mid-walk loses at most one batch.  The next restart re-runs
from exactly the rows still showing the old provider/model.

---

## Procedure A: same-dimension switch

Use when: the new model produces vectors of the same dimension as the
old one (e.g. you stay at 1536 and switch from
`text-embedding-3-small` to `text-embedding-3-large@1536` — OpenAI
supports a `dimensions` parameter that truncates output of the larger
model — or you swap to a self-hosted 1536-dim model).

**Code/config change:** none beyond editing `application.ini` on the
deployed server.

1. **Back up the database.**

       sudo systemctl stop ownsona.service
       pg_dump -h localhost -U ownsona ownsona > /var/backups/ownsona-pre-reembed-$(date +%F).sql
       sudo systemctl start ownsona.service

   (Or rely on your nightly backup — but a fresh dump immediately
   before re-embed is safer.)

2. **Edit the deployed `application.ini`** at
   `<tomcat>/webapps/ROOT/WEB-INF/backend/application.ini`:

   ```ini
   EMBEDDING_PROVIDER   = openai
   EMBEDDING_MODEL      = text-embedding-3-large
   EMBEDDING_DIMENSIONS = 1536
   EMBEDDING_API_KEY    = sk-...                # if it changed
   EMBEDDING_ENDPOINT   = https://api.openai.com/v1/embeddings

   REEMBED_ON_STARTUP   = true
   ```

3. **Restart the service.**

       sudo systemctl restart ownsona.service

4. **Watch the log.**

       journalctl -u ownsona.service -f

   You'll see, in order:

   - `migrator: db_version at N, target N, nothing to apply`
   - `record_migrator: done upgraded=0 failed=0` (or whatever)
   - `reembed: starting active_provider=... active_model=... dims=...`
   - `reembed: progress count=50`, `count=100`, … (one line per batch)
   - `reembed: done count=N`
   - `ApplicationIniWriter: set REEMBED_ON_STARTUP = false in <path>`

   The server is **serving requests during the walker's run**.  Recall
   on rows already updated uses new-model vectors; recall on rows not
   yet updated uses old-model vectors.  Within either cohort, results
   are correct; across cohorts, scores are not meaningfully
   comparable.  For Blake's single-user store this window is minutes,
   not hours.

5. **Verify and propagate.**

   ```sql
   SELECT embedding_provider, embedding_model, count(*)
   FROM memories
   GROUP BY 1, 2;
   ```

   Should show one row, with the new provider/model and a count
   matching `count(*)` overall.

   The walker auto-flips `REEMBED_ON_STARTUP=false` in the deployed
   `application.ini`, but the **source tree** still has it at `true`.
   Before your next WAR build, update
   `src/main/backend/application.ini` and
   `src/main/backend/application.ini.example` to match, so the next
   deploy doesn't accidentally re-trigger the walker.

---

## Procedure B: different-dimension switch

Use when: the new model's output dimension differs from the existing
column (e.g. moving from 1536 to 3072, or 1536 to 768).  The
`vector(N)` column type must be resized first — pgvector cannot store
a 3072-element vector in a `vector(1536)` column.

The schema change goes in an **additive migration** that runs *before*
the walker.  The walker then fills the now-resized column.

1. **Write a new migration class** at
   `src/main/precompiled/ai/ownsona/migrations/MigrationNNN_resize_embedding_to_N.java`:

   ```java
   package ai.ownsona.migrations;

   import org.kissweb.database.Connection;

   public final class Migration005ResizeEmbeddingTo3072 implements Migration {
       @Override public int version() { return 5; }
       @Override public String name() { return "resize embedding to vector(3072)"; }
       @Override public void apply(Connection db) throws Exception {
           // Relax NOT NULL so we can null out via the type change.
           db.execute("ALTER TABLE memories ALTER COLUMN embedding DROP NOT NULL");
           // Resize.  USING NULL = discard old vectors (the walker
           // rebuilds them from each row's text in the immediately
           // following ReembedJob pass).
           db.execute("ALTER TABLE memories ALTER COLUMN embedding TYPE vector(3072) USING NULL");
       }
   }
   ```

   **Why this is OK to ship as a migration despite the
   "additive-only" rule:** the embedding column is *derived data*.
   The texts that produced the old vectors are still in the `text`
   column.  Nulling out the embedding column and then re-filling it
   from the texts in the same startup sequence loses nothing the
   system can't rebuild.  This is a narrow, deliberate exception to
   guardrail #2 — dim-change migrations may clear the embedding
   column iff the same commit sets `REEMBED_ON_STARTUP=true`.

2. **Optional: drop the HNSW index first.**  If you have thousands of
   rows, dropping the index before the walker and recreating it after
   avoids paying incremental HNSW insertion cost on every batch.
   Add to the migration:

   ```java
   db.execute("DROP INDEX IF EXISTS memories_embedding_idx");
   ```

   The matching `CREATE INDEX` step at the end is one-time operator
   work (a follow-up `psql` command after the walker finishes); we
   don't bake it into the migration because it must run *after* the
   walker, not before it.  For small stores (a few hundred rows), you
   can skip this entirely.

3. **Register the migration and bump the version.**  In
   `src/main/precompiled/ai/ownsona/migrations/MigrationRegistry.java`:

   ```java
   public static final int CURRENT_DB_VERSION = 5;
   ...
   m.add(new Migration005ResizeEmbeddingTo3072());
   ```

4. **Update `application.ini`** (source tree, and on the server) with
   the new `EMBEDDING_MODEL`, `EMBEDDING_DIMENSIONS = 3072`,
   `EMBEDDING_API_KEY` (if it changed), and `REEMBED_ON_STARTUP =
   true`.

5. **Back up the database.**

       pg_dump -h localhost -U ownsona ownsona > /var/backups/ownsona-pre-reembed-$(date +%F).sql

6. **Build, deploy, restart.**  Normal WAR-swap deploy.

7. **Watch the log.**  In order:

   - `migrator: applied v=5 name="resize embedding to vector(3072)" ms=…`
   - `record_migrator: done …`
   - `reembed: starting active_provider=... active_model=... dims=3072`
   - `reembed: progress count=…` (repeats)
   - `reembed: done count=…`
   - `ApplicationIniWriter: set REEMBED_ON_STARTUP = false ...`

8. **If you dropped the HNSW index in step 2**, recreate it now that
   the walker has filled every row:

       sudo -u ownsona psql -d ownsona -c \
         "CREATE INDEX memories_embedding_idx ON memories USING hnsw (embedding vector_cosine_ops);"

9. **Optional cleanup: restore `NOT NULL`** on the embedding column
   in a follow-up migration (`Migration006`).  Strictly optional — the
   server always sets `embedding` on insert / update.  If you do,
   ship it in a later commit, after you've confirmed every row has
   a non-NULL embedding.

10. **Propagate** the source-tree `application.ini` flip (see step 5
    of Procedure A).

---

## Monitoring and recovery

### Status query

To see what state the store is currently in:

```sql
SELECT embedding_provider, embedding_model, count(*)
FROM memories
GROUP BY 1, 2
ORDER BY count(*) DESC;
```

Mid-walk this returns two rows (old and new), shifting as the walker
progresses.  After completion it returns one row.

To see how many rows still need re-embedding:

```sql
SELECT count(*) FROM memories
WHERE embedding IS NULL
   OR embedding_provider IS DISTINCT FROM 'openai'
   OR embedding_model    IS DISTINCT FROM 'text-embedding-3-large';
```

### If the walker is interrupted

The walker is resumable by design.  Kill the JVM mid-run, then
`systemctl restart ownsona.service`, and it picks up exactly the rows
still showing the old provider/model.  No state to clean up.

### If the walker fails

Look at the exception in `journalctl -u ownsona.service`.  Common
causes:

- **Embedding endpoint unreachable / API key wrong.**  Fix
  `application.ini` and restart.
- **Rate-limit / 429.**  The walker treats this as a fatal error and
  aborts the run; the next restart resumes.  For a one-off run on a
  small store you'll usually outrun the rate limit; for a large store
  you may want to lower `BATCH_SIZE` in `ReembedJob.java`.
- **Different-dimension migration ran but `EMBEDDING_DIMENSIONS` in
  config doesn't match the column type.**  The walker will try to
  write wrong-size vectors and fail at the database with a vector
  dimension mismatch error.  Fix the config and restart.

### Full rollback

If the new model is producing terrible recall and you want to revert:

1. Stop the service.
2. Restore from the pre-deploy backup:
       `sudo -u postgres psql ownsona < /var/backups/ownsona-pre-reembed-<date>.sql`
3. Revert `application.ini` to the old `EMBEDDING_MODEL` /
   `EMBEDDING_DIMENSIONS` / `EMBEDDING_API_KEY`.
4. If you also ran a dim-change migration, redeploy a WAR built from
   the pre-Phase-6-dim-change commit (i.e. one whose
   `CURRENT_DB_VERSION` is lower).  `DbMigrator` will refuse to start
   against a *higher*-version DB, so you must either restore the DB
   first or ship a code build matching the DB version.
5. Start the service.

This is the "data is not cheap" branch — the backup is the only thing
that gets you back to the pre-switch state cleanly.  Take backups
*before* the deploy, not after.

---

## Cost

Embedding APIs are priced per token.  For a single-user store of a few
thousand short memories, a full re-embed costs cents to single-digit
dollars.  Rough estimates (OpenAI list prices, may have changed):

| Memories × avg tokens | `text-embedding-3-small` | `text-embedding-3-large` |
|---|---|---|
| 1,000 × 40 tokens  | ~$0.001 | ~$0.005 |
| 10,000 × 40 tokens | ~$0.01  | ~$0.05  |
| 100,000 × 40 tokens | ~$0.10 | ~$0.50  |

Self-hosted models (Ollama, sentence-transformers) cost zero per
token but you pay in compute time on the host.

---

## Gotchas

- **The auto-flip writes to the deployed copy of `application.ini`,
  not the source tree.**  After a successful run, the file at
  `<tomcat>/webapps/ROOT/WEB-INF/backend/application.ini` has
  `REEMBED_ON_STARTUP = false`, but
  `src/main/backend/application.ini` (the source you build from)
  still has it at `true`.  Update the source-tree copy before your
  next WAR build, or the next deploy will silently kick off a no-op
  walker on startup (which is harmless but wastes a bit of time and
  log noise).
- **`application.ini` is gitignored**, so you won't catch a stale
  source copy via PR review.  The example file
  (`application.ini.example`) ships `REEMBED_ON_STARTUP = false` so
  fresh installs start in the right state.
- **`EMBEDDING_DIMENSIONS` must match the `vector(N)` column type.**
  If they disagree, every write fails at the database with a vector
  dim mismatch.  This is enforced by Postgres, not by Ownsona — the
  error surfaces at insert/update time, not at startup.
- **Vendor neutrality is preserved.**  The walker doesn't care
  whether the provider is OpenAI, Cohere, Voyage, BGE, Ollama, or
  anything else.  It only knows about the `EmbeddingProvider`
  interface.  To add a new provider, write a new
  `EmbeddingProvider` implementation and update the
  `OpenAIEmbeddingProvider` instantiation in `MCPServer.<clinit>` —
  the rest of the system (migrations, walker, repository, tools) is
  provider-agnostic.
- **Hard-deleted rows mid-walk don't break the walker.**  If a row
  is hard-deleted between the id scan and the per-batch text fetch,
  it silently drops out of the batch.  No re-embed, no error.
- **There's no MCP tool to trigger a re-embed.**  Deliberate: admin
  operations stay outside the MCP surface so any LLM holding the
  bearer can't initiate them.  The trigger is editing
  `application.ini` and restarting, which is an operator action.
