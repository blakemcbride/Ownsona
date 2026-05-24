# Ownsona MCP Server

A personal-memory MCP server built on the open-source
[Kiss web development framework](https://kissweb.org). Stores durable
memories in PostgreSQL with `pgvector` and exposes tools for remembering,
recalling, listing, updating, forgetting, and searching them. Any
MCP-capable LLM client can be pointed at it as a shared knowledge base.

The full specification is in `OWNSONA_SPEC.md` at the repo root.
Documentation for the Kiss framework itself (manual, JSDoc, JavaDoc)
lives at [kissweb.org](https://kissweb.org).

## Status

**Version 1 implemented.** All spec tools plus later extensions are
wired up. OAuth 2.1 authentication (resource server + embedded
authorization server), secret rejection, soft-delete, duplicate
detection, OpenAI embeddings, and pgvector cosine search are all in
place.

| Spec section | State |
|---|---|
| Core spec tools: `remember`, `recall`, `build_context_prompt`, `list_memories`, `update_memory`, `forget`, `text_search` | done |
| Post-spec extensions: `remember_batch`, `confirm`, `get_memory`, `count_memories`, `memory_stats`, `list_tags`, `export_memories` | done |
| OAuth 2.1 (RFC 6750 RS + RFC 7591/8414 AS) on every request | done |
| Secret rejection (OpenAI/AWS/GitHub/Slack/JWT/PEM) | done |
| Duplicate detection by normalized text + unique partial index | done |
| Soft delete by default; `hard_delete: true` opt-in | done |
| pgvector cosine similarity search | done |
| Embedding-provider abstraction (OpenAI + mock) | done |
| Per-user `user_id` plumbing | done |
| Unit + integration tests | 47 tests, all passing (`sql/run_tests.sh`) |
| Connection pool | shared with Kiss (`MainServlet.openNewConnection`); Kiss owns lifecycle |
| Vector index | done (HNSW, `vector_cosine_ops`); planner picks it once rows justify it |

## Project layout

```
src/main/precompiled/ai/ownsona/
    MCPServer.java                       # @WebServlet("/mcp"); MCP tool catalog (auth inherited from MCPServerBase via OAuth validator)
    Config.java                          # application.ini loader (via MainServlet.getEnvironment)
    SecretScanner.java                   # regex-based credential filter
    TextNormalizer.java                  # trim / lowercase for dup-detection key
    VectorFormat.java                    # float[] -> pgvector literal
    embeddings/
        EmbeddingProvider.java
        OpenAIEmbeddingProvider.java
        MockEmbeddingProvider.java
    memory/
        MemoryRepository.java            # SQL via Kiss Connection.execute/fetchAll
        MemoryService.java               # MCP tools' business logic
        MemoryRow.java                   # row POJO
        MemoryInsert.java                # insert parameter object
        RememberResult.java
        PromptFormatter.java             # exact spec envelope for build_context_prompt
        ServiceException.java

src/test/precompiled/ai/ownsona/
    SecretScannerTest.java
    TextNormalizerTest.java
    VectorFormatTest.java
    MockEmbeddingProviderTest.java
    PromptFormatterTest.java
    MemoryRepositoryIntegrationTest.java # PG-gated via OWNSONA_TEST_DATABASE_URL

sql/
    001_init.sql                         # schema migration (run also for the test DB)
    setup_db.sh                          # role + extension + migration runner (prod DB)
    setenv.example.sh                    # historical pointer (secrets now in application.ini)
    smoke_test.sh                        # end-to-end curl test of all tools
    run_tests.sh                         # JUnit runner for src/test/precompiled/

src/main/core/org/kissweb/MCPServerBase.java   # framework base class (do not modify)
```

## URL

```
https://<your-host>/mcp
```

The bundled Kiss example is mounted at `/sample-mcp` and is left untouched.

## Configuration

All settings live in `src/main/backend/application.ini` and are read at
servlet load via `MainServlet.getEnvironment()`. Missing required keys
fail fast and the servlet does not load.

The repo ships `application.ini.example` (a redacted template) and
gitignores `application.ini` itself, so live secrets stay out of git.
On a fresh clone, `cp src/main/backend/application.ini.example
src/main/backend/application.ini` and edit the copy.

Required keys:

| Key | Purpose |
|---|---|
| `DatabaseHost`, `DatabasePort`, `DatabaseName`, `DatabaseUser`, `DatabasePassword` | Kiss's standard PostgreSQL connection settings |
| `EMBEDDING_API_KEY`        | OpenAI key for the embeddings endpoint |
| `OWNSONA_LOGIN_USERNAME` | Username the OAuth AS consent page accepts |
| `OWNSONA_LOGIN_PASSWORD` | Password the OAuth AS consent page accepts (plaintext; file is chmod 600) |
| `OAuthAuthorizationServer` | AS issuer URL (`https://<your-host>`); turns on the resource server. Resource identifier, AS issuer, and JWKS URI all default from this single value, so no other OAuth key is required for a standard embedded-AS deployment. |
| `OAuthAsEnabled`           | `true` to enable the embedded authorization server |
| `EMBEDDING_ENDPOINT`       | Embeddings endpoint URL (e.g. `https://api.openai.com/v1/embeddings`) |
| `EMBEDDING_MODEL`       | Embedding model name (e.g. `text-embedding-3-small`) |
| `EMBEDDING_DIMENSIONS`  | Embedding vector dimensions; **must match** the `vector(N)` column type in `sql/001_init.sql` |

Optional with sensible defaults:

| Key | Default |
|---|---|
| `OWNSONA_USER_ID`       | `default` |
| `EMBEDDING_PROVIDER`    | `openai` |
| `DEFAULT_RECALL_LIMIT`  | `8` |
| `MAX_RECALL_LIMIT`      | `50` |
| `MAX_TEXT_CHARS`        | `16000` |
| `MAX_BATCH_SIZE`        | `200` |

The live `application.ini` lives in the source tree at
`src/main/backend/` (gitignored). The `bld` build copies it into the
WAR (`work/exploded/WEB-INF/backend/`) on every build, so editing the
source-tree copy is what populates the deployed copy.

## Database setup

Prerequisites: PostgreSQL 16, an empty `ownsona` database, and the
`postgresql-16-pgvector` OS package installed.

```bash
sudo apt install postgresql-16-pgvector

# Creates the ownsona role, sets its password, runs the migration.
# Set the same password as DatabasePassword in application.ini.
sql/setup_db.sh "<password>"
```

The migration creates `vector` and `pg_trgm` extensions, the `memories`
table with `vector(1536)` and `text[]` tags, the standard set of indexes
(skipping IVFFLAT until the table is large), a unique partial index on
`(user_id, normalized_text) WHERE deleted_at IS NULL` for duplicate
prevention, and an `updated_at` trigger.

## Build & deploy

```bash
./bld -v build      # compile precompiled/ classes; run on every code change
./bld war           # produce work/Kiss.war
```

Tomcat at `/home/ownsona/tomcat/` is configured with
`autoDeploy="true" unpackWARs="true"` on the `<Host>` (see the
"Deployment gotchas" section below for why both flags matter), so deploy
is one line:

```bash
cp work/Kiss.war /home/ownsona/tomcat/webapps/ROOT.war
```

The build emits `Kiss.war`; rename to `ROOT.war` at copy time so it
serves at the host root. Tomcat undeploys/redeploys in-place within ~10
seconds. **`application.ini` is read once at servlet load**, so any
edits there require a fresh build (so the new ini lands in the WAR)
followed by either a redeploy or a service restart:
`sudo systemctl restart ownsona.service`.

## Lifecycle (systemd)

Production Tomcat runs under `ownsona.service`. The unit lives at
`/etc/systemd/system/ownsona.service` and its source is committed at
`sql/ownsona.service`. Install or reinstall in one command:

```bash
sudo /home/ownsona/ownsona/sql/install_systemd.sh
```

The script stops any manually-launched Tomcat, removes any stale
`tomcat.service`, installs the unit, and `enable --now`s it.

Operate with the usual systemd verbs:

```bash
sudo systemctl status   ownsona.service
sudo systemctl restart  ownsona.service
sudo systemctl stop     ownsona.service
sudo systemctl start    ownsona.service
journalctl -u ownsona.service -f
```

The unit binds ports 80 and 443 via `AmbientCapabilities=CAP_NET_BIND_SERVICE`,
which **survives openjdk apt upgrades** --- unlike the older path that
relied on `setcap` on the JDK binary (which apt strips on every install).
Don't `startup.sh` manually while the service is up; both would try to
bind 443.

After deploy:

```bash
OWNSONA_ACCESS_TOKEN=<jwt> sql/smoke_test.sh https://<your-host>/mcp
```

The token is an OAuth 2.1 access token issued by the embedded AS — see
`INSTALL.md` §13 for the one-time setup that produces one (typically:
add OwnSona as an MCP server in any OAuth-capable client and copy the
token out of its local config).

This walks `initialize`, `tools/list`, `remember`, `recall`,
`list_memories`, and `text_search` against the live endpoint.

## Tests

```bash
sql/run_tests.sh                        # 36 unit tests, no setup needed
```

To enable the 11 integration tests, point at a separate test database
with the schema already applied:

```bash
createdb -U postgres ownsona_test
psql -U postgres -d ownsona_test -f sql/001_init.sql

OWNSONA_TEST_DATABASE_URL=postgresql://ownsona:<password>@localhost:5432/ownsona_test \
    sql/run_tests.sh
```

Integration tests `TRUNCATE memories` in `@BeforeEach`, so do **not** point
this at the production database. They are gated by JUnit's
`@EnabledIfEnvironmentVariable` and are silently skipped if the variable
is unset. The mock embedding provider keeps tests offline.

## What gets logged

Logging goes through Log4j 2 (`ai.ownsona.*`). The package's level is
forced to INFO at class-load via `Configurator.setLevel(...)`, working
around Kiss's bundled `log4j2.xml` which sets the root logger to
`ERROR`. Under the systemd unit, application stdout (the log4j2
console appender's destination) flows to **journald**, not
`catalina.out` --- inspect with `journalctl -u ownsona.service`.

### Log retention

| Log | Rotated by | Retention |
|---|---|---|
| application stdout (log4j2) | journald | journald defaults (Ubuntu: ~10% of `/var/log/journal`, capped at 4 GiB) |
| `tomcat/logs/catalina.YYYY-MM-DD.log`, `localhost.YYYY-MM-DD.log`, `manager.*`, `host-manager.*` | Tomcat juli (`AsyncFileHandler`) | `maxDays=90` in `tomcat/conf/logging.properties` |
| `tomcat/logs/localhost_access_log.YYYY-MM-DD.txt` | Tomcat `AccessLogValve` | `maxDays="90"` in `tomcat/conf/server.xml` (added 2026-05-05) |
| `tomcat/logs/catalina.out` | n/a | static; only written when Tomcat is started by hand via `startup.sh`, which is no longer the supported path |

Per request:
- Auth failures are logged by `BearerTokenValidator` at DEBUG with the
  validation reason (bad signature, expired, wrong audience, etc.); the
  raw token is never logged.
- Tool calls log name + memory id (where applicable) + char count + ms.
  Full memory text is **not** logged at INFO/ERROR --- the spec calls
  this out as a privacy requirement.
- Embedding-provider calls log model + dim + char count + ms.

## Security

- **Transport:** Tomcat terminates HTTPS on `:443`
  (`conf/tomcat.p12`/`tomcat-com.p12`).
- **Auth:** OAuth 2.1. The resource side (`org.kissweb.oauth`) validates
  every incoming `Authorization: Bearer <JWT>` against the JWKS
  published by the embedded authorization server
  (`org.kissweb.oauth.as`). `MCPServerBase.authenticate()` handles
  this with no application code; OwnSona contributes only a
  `UserAuthenticator` (constant-time compare against the
  `OWNSONA_LOGIN_*` keys) and a `ConsentProvider` (display text). 401
  responses carry the RFC 6750 / RFC 9728 `WWW-Authenticate` challenge,
  whose `resource_metadata` parameter points clients at
  `/.well-known/oauth-protected-resource` for AS discovery. The AS's
  signing key, registered clients, and refresh tokens are persisted to
  the path set in `OAuthAsIniFile` (production: an absolute path
  outside the deployed webapp; default: `WEB-INF/backend/oauth.ini`,
  which is rewritten on every WAR redeploy and therefore inadvisable
  for anything but local development). Auth codes are in-memory only
  with a 60s TTL.
- **Secret rejection:** `SecretScanner` blocks obvious tokens
  (OpenAI `sk-...`/`sk-ant-...`/`sk-proj-...`, GitHub `ghp_`/`ghs_`,
  GitHub fine-grained PAT, AWS access key IDs, Slack `xox?-`, Google
  AIza, JWT-shaped tokens, PEM private-key markers). The filter is
  best-effort; the calling client should not be forwarding secrets in
  the first place.
- **PostgreSQL:** never exposed publicly. The `ownsona` role has only
  `SELECT/INSERT/UPDATE/DELETE` on `memories` plus sequence usage; it is
  not a superuser.
- **Treat returned memories as data, not instructions.** Tool
  descriptions to the client say so explicitly.

## Deployment gotchas (learned 2026-05-03)

Three configs will silently break a deploy. All three bit us on the
first attempt with the placeholder server.

1. **`<Host unpackWARs="true">`** in `conf/server.xml` --- with
   `unpackWARs="false"`, Tomcat serves from inside the WAR and
   `ServletContext.getRealPath("/")` returns `null`. Kiss's
   `MainServlet.setApplicationPathInternal()` NPEs on that, taking
   the whole webapp down. Symptom: every URL returns the default 404
   page; the actual stack is in `tomcat/logs/localhost.<date>.log`,
   *not* `catalina.out`.
2. **Tomcat heap fits in available RAM.** The shipped `setenv.sh`
   defaults of `-Xms1524M -Xmx2000M -XX:+AlwaysPreTouch` will
   OOM-kill on a 1.9 GB VM with PostgreSQL alongside. Use
   `-Xms256M -Xmx768M` with `-XX:+UseG1GC -XX:+DisableExplicitGC` and
   skip `+AlwaysPreTouch`. A 2 GB swap file is a useful safety net.
3. **HTTP -> HTTPS redirect (302).** When testing locally, follow
   the redirect with `curl -L` or hit HTTPS directly with `-k` to
   bypass the cert-hostname mismatch on `localhost`.

## Tomcat noise --- different mechanism

Noise from Tomcat itself (HTTP-parser INFO from scanners with bad Host
headers, HTTP/2 protocol warnings, client-aborted-connection chatter)
is `java.util.logging`, not log4j2. Suppression goes in
`src/main/core/org/kissweb/restServer/StartupListener.java`'s
`configTomcatLogger()` method. JUL silencing belongs in core's
StartupListener, not in application code.

## History

- **2026-05-03** --- Initial scaffolding: `MCPServer.java` created,
  log-only behavior wired through every base-class hook. Bundled
  example moved to `/sample-mcp` so our `/mcp` is canonical.
- **2026-05-03** --- First successful deploy. Three blockers fixed:
  setenv.sh heap, missing 2 GB swap, and `unpackWARs="false"`. After
  that, `https://ownsona.ai/mcp` returned `serverInfo.name =
  "ownsona-mcp"`.
- **2026-05-03** --- Logging visibility fixed (Configurator override of
  the Kiss-default ERROR root level). Each hook logs both inputs and
  return values.
- **2026-05-03** --- Switched Tomcat to
  `autoDeploy="true" unpackWARs="true"`. Deploys are now `cp
  work/Kiss.war webapps/ROOT.war`, no restart, ~10s.
- **2026-05-03** --- Tool surface changed: removed `ping`, added a
  single generic `ask(question)` placeholder that returned `"The
  Answer."` while we observed real OpenAI prompts in the log.
- **2026-05-03** --- Suppressed noisy Tomcat HTTP-parser INFO via
  `StartupListener.configTomcatLogger`.
- **2026-05-05** --- Replaced placeholder `ask` with the real
  implementation per `OWNSONA_SPEC.md`: PostgreSQL + pgvector storage,
  OpenAI embeddings, all seven tools, bearer-token auth, secret
  rejection, soft-delete with `hard_delete` opt-in, duplicate
  detection, prompt-builder formatter. Embedding-provider abstraction
  with a mock for offline tests. Schema migration in `sql/001_init.sql`,
  one-shot DB setup in `sql/setup_db.sh`, env template in
  `sql/setenv.example.sh`, smoke test in `sql/smoke_test.sh`.
- **2026-05-05** --- Switched production from manual `startup.sh` to a
  `ownsona.service` systemd unit (`sql/ownsona.service`,
  `sql/install_systemd.sh`). Auto-starts on boot. Grants
  `CAP_NET_BIND_SERVICE` via systemd's `AmbientCapabilities`, which
  removes the dependency on `setcap` on the JDK (which apt strips on
  every openjdk upgrade). Removed the stale `tomcat.service` that
  pointed at `/home/stack360/tomcat/`. Application stdout now flows to
  journald instead of `catalina.out`, which has stopped growing.
- **2026-05-05** --- Bounded `localhost_access_log.*.txt` retention by
  adding `maxDays="90"` to the `AccessLogValve` in
  `tomcat/conf/server.xml`, matching the existing `maxDays=90` on the
  juli `AsyncFileHandler`s. This was the last unbounded log path.
- **2026-05-05** --- Added `remember_batch` MCP tool for bulk import.
  ChatGPT was importing prior memories one-at-a-time, ~2-5s per call
  dominated by the OpenAI embedding round-trip and ChatGPT's
  serialized tool-call loop. The new tool takes up to 200 items per
  call, embeds them all in a single OpenAI call, and inserts them on
  a single pooled JDBC connection. Per-item failures (secret-reject,
  validation) appear as `{ok:false, error:...}` entries in the
  results array; one bad item does not fail the rest. Verified
  end-to-end: 3-item batch in 1.85s (vs. 1 single remember at 1.4s),
  per-item rejection path works (one fake AWS key rejected, other
  two items succeeded). `EmbeddingProvider` got a default
  `embedBatch()` that falls back to per-item; OpenAI provider
  overrides with one batched HTTP call.
- **2026-05-05** --- **Connection-pool leak fix.** *(Class deleted on
  2026-05-06; MCP server now uses Kiss's main pool via
  `MainServlet.openNewConnection()`, which doesn't have this leak.)*
  Kiss's
  `Connection(java.sql.Connection)` constructor sets
  `externalConnection=true`, which makes its `close()` deliberately
  *not* close the wrapped JDBC handle. When the wrapped handle came
  from c3p0, that handle never returned to the pool. After 20 requests
  (= `maxPoolSize`) the pool was exhausted and the 21st request blocked
  forever; ChatGPT reported tool timeouts. Layered probe confirmed
  `ping` healthy, OpenAI embeddings healthy, but `list_memories`
  (DB-only) hung 258 s before getting a slot back, and PG
  `pg_stat_activity` showed 20 idle connections. Fix: in
  `ai.ownsona.Db.getConnection()`, subclass Kiss `Connection` with an
  override that closes the JDBC handle in addition to `super.close()`.
  Also changed `Db.shutdown()` to `POOL.close(true)` so an exhausted
  pool can still tear down. After deploy + restart: 30/30 stress
  requests succeed, PG connection count steady at 2 (= initial pool
  size). Saved memory in
  `feedback_kiss_connection_pool_leak.md`.
- **2026-05-05** --- Added a `?token=` query-parameter auth fallback
  in `MCPServer.authenticate()` so ChatGPT's connector UI (which
  exposes only OAuth / No auth / Mixed and lets the user supply no
  custom HTTP headers) can authenticate via the URL itself. The
  `AccessLogValve` `pattern` was changed from `%r` to `%m %U %H` in
  `tomcat/conf/server.xml` so the token is not written to the access
  log (and therefore not to the daily backups either). Verified after
  deploy: header path 200, URL-token path 200, no auth 401, wrong
  token 401, no `token=` in today's access log. Tradeoff documented
  in `OpenAI.md` §1.2 option E.
- **2026-05-05** --- Added the HNSW vector index
  (`memories_embedding_idx ON memories USING hnsw (embedding vector_cosine_ops)`)
  to both `ownsona` and `ownsona_test` and the migration. Picked HNSW
  over IVFFLAT because IVFFLAT's k-means seeding requires a populated
  table to be useful, while HNSW works on an empty table and gives
  better recall-for-the-speed at every scale. Defaults
  (`m=16`, `ef_construction=64`, `ef_search=40`) are fine for 1536-dim
  vectors; query-time recall/speed tunable via `SET hnsw.ef_search`.
  At v1's row counts (currently 0) the planner still prefers a sort,
  which is the right choice; it'll switch to HNSW automatically once
  the row count makes the index cheaper.
- **2026-05-05** --- Follow-ups: added `OwnsonaContextListener`
  (`@WebListener`) that closes the c3p0 pool on `contextDestroyed`,
  eliminating the thread-leak warnings on every redeploy.
  *(Class deleted on 2026-05-06 along with the private c3p0 pool;
  Kiss owns lifecycle now.)*
  Extracted
  `PromptFormatter` from `MemoryService.buildContextPrompt` so the spec
  envelope is unit-testable. Added 36 unit tests + 11 PG-gated
  integration tests under `src/test/precompiled/ai/ownsona/` with a
  custom `sql/run_tests.sh` runner (Kiss's `bld unitTests` only sees
  `src/test/core/`). Fixed a bug where `MemoryRepository.update`
  reported success on a soft-deleted row (the post-update SELECT
  returned the still-deleted row as evidence of "found"; now also
  checks `deletedAt == null`). Migration grants `TRUNCATE` to the
  app role for tests --- semantically equivalent to `DELETE` which it
  already had, so no production risk.
- **2026-05-06** --- Centralized configuration in
  `src/main/backend/application.ini`. All URLs, secrets, and tunables
  (`EMBEDDING_API_KEY`, `OWNSONA_API_TOKEN`, `EMBEDDING_ENDPOINT`,
  `OWNSONA_USER_ID`, `EMBEDDING_*`, `*_RECALL_LIMIT`,
  `MAX_TEXT_CHARS`, `MAX_BATCH_SIZE`) are now read via
  `MainServlet.getEnvironment()` instead of `System.getenv()`. The
  former Ownsona-private c3p0 pool (`ai.ownsona.Db`) was deleted; the
  MCP server now borrows from Kiss's main pool via
  `MainServlet.openNewConnection()`, which means each request runs in
  a real PostgreSQL transaction (autoCommit=false). `MemoryService.rememberBatch`
  now uses JDBC savepoints per item so a unique-violation in one item
  does not poison the surrounding transaction. The connection-leak
  workaround in `Db.getConnection()` is gone with the class — the leak
  was a side-effect of wrapping a c3p0-borrowed JDBC handle in Kiss's
  `Connection`, which Kiss's own `openNewConnection()` does not do.
  Kiss's lifecycle now owns pool teardown, so `OwnsonaContextListener`
  was deleted too.
