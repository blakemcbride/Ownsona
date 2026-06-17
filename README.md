# Ownsona

> **Teach any LLM about you, once.** Ownsona is a personal-memory
> server that turns one knowledge base into a shared brain across
> ChatGPT, Claude, Gemini, Grok, and any other MCP-capable assistant —
> and it doesn't just store facts, it **learns**: it figures out which
> memories matter, resolves contradictions, consolidates duplicates, and
> builds a knowledge graph you can ask multi-hop questions of.

[![License: BSD 2-Clause](https://img.shields.io/badge/License-BSD_2--Clause-blue.svg)](LICENSE.txt)
[![Java: 17+](https://img.shields.io/badge/Java-17%2B-orange)](https://adoptium.net)
[![PostgreSQL: 16](https://img.shields.io/badge/PostgreSQL-16%20%2B%20pgvector-336791)](https://www.postgresql.org)
[![Built on: Kiss](https://img.shields.io/badge/Built%20on-Kiss-brightgreen)](https://kissweb.org)

The main home for this code is <https://github.com/blakemcbride/Ownsona>.

---

## One memory, every LLM

Today, teaching an LLM about your work, your family, your projects, or
your preferences is a per-provider chore. You explain who you are to
ChatGPT, then again to Claude, then again to Gemini — and each one
forgets between conversations. Switch providers, switch models,
upgrade to the next release, and the knowledge starts over from zero.

Ownsona (your OWN perSONA) breaks that cycle. It is a single durable memory store that
**every MCP-capable assistant connects to**. Tell ChatGPT once that
your dog's name is Mochi, that you prefer concise answers, that the
quarterly forecast lives in `~/work/q3/`, or that you're a Go developer
new to React — and the next session in Claude or Gemini already knows.
A fact written by one model is recallable by the next. Your context
follows you across providers, across models, across years.

Under the hood, memories are stored in PostgreSQL with `pgvector` and
served through the [Model Context Protocol](https://modelcontextprotocol.io),
the open standard for letting LLMs call external tools. Any
MCP-capable client can be pointed at it.

## Not just storage — a memory that learns

Most "AI memory" is a passive notes file: it stores what you write and
hands it back on a keyword or vector match. Ownsona goes further. It
treats your memory as something that **improves with use** — getting
better at surfacing the right fact, keeping itself consistent, and
understanding how your facts connect.

- **It learns what matters (reinforcement).** Every recalled memory can
  be given feedback — *this helped*, *this was wrong* — via the
  `reinforce` tool (and a `confirm` counts as positive). That feedback
  moves a learned **salience** weight, and recall ranks by it. Genuinely
  useful facts rise; noise sinks. There is **no time-based decay**:
  nothing is ever forgotten or down-ranked merely for being old — only
  feedback and explicit correction change a memory's standing.

- **It learns *context* (per-query ranking).** Beyond a single global
  weight, each memory learns *which kinds of questions* it helps answer.
  Reinforce a memory along with the query it answered, and Ownsona nudges
  that memory's learned context so the next similar question surfaces it
  first — even over facts that are merely text-similar.

- **It keeps itself consistent (conflict handling).** When you store a
  fact that looks like it contradicts an existing one (same topic, shared
  tags), Ownsona flags the conflict — on write and via an on-demand
  `find_conflicts` scan. You (or the assistant) resolve it explicitly:
  `supersedes` retires a now-wrong fact, `downweights` demotes a merely
  outdated one — the "un-learn the stale answer" behavior plain retrieval
  can't do.

- **It tidies itself up (consolidation).** A background "sleep" job
  clusters near-duplicate memories and merges each cluster into one clean
  canonical fact, superseding the redundant copies — and can resolve
  contradictions on its own when you let it.

- **It understands how facts connect (knowledge graph).** A background
  pass extracts `(subject, predicate, object)` relationships from your
  memories into a graph, and the `query_relations` tool traverses it to
  answer **multi-hop** questions ("who is my manager's spouse?") that a
  flat similarity search can't.

The always-on parts (salience, contextual ranking, conflict *surfacing*)
are pure math — no extra dependency, nothing to enable. The
generative-powered parts (automatic consolidation, automatic conflict
*resolution*, relation extraction) use a **separate, optional LLM**
configured independently of the embedding provider, run **in the
background** (never on the request path), and ship **off by default** —
turn them on when you want them. Everything destructive is recoverable
(soft-delete) and **respects the `keep` lock** described below.

## MCP Tools

| Tool | Purpose |
|---|---|
| `remember` | Store a fact, with optional tags and importance score |
| `remember_batch` | Bulk import (up to 200 items) in a single embedding round-trip |
| `recall` / `search_memory` | Vector-similarity search by natural-language query |
| `build_context_prompt` | Pack relevant memories into a prompt envelope |
| `list_memories` | Paginated listing with cleanup filters (untagged-only, char-length bounds, not-confirmed-since) |
| `update_memory` | Edit any subset of fields; re-embeds only when `text` is supplied |
| `update_memory_batch` | Bulk update (up to 200 items) in one call |
| `confirm` | Refresh `last_confirmed_at` (counts as positive reinforcement) without rebuilding the embedding |
| `reinforce` | Record feedback (helpful / unhelpful, optionally per-query) that adjusts a memory's learned salience and context ranking |
| `forget` | Soft delete (default) or hard delete; `dry_run` supported |
| `forget_batch` | Bulk soft-delete (up to 200 ids) in one call |
| `find_near_duplicates` | Cluster active memories by cosine similarity (cleanup diagnostic) |
| `find_conflicts` | Surface same-topic memories that may contradict each other (tag-gated) |
| `query_relations` | Multi-hop traversal of the extracted relationship graph |
| `text_search` | Trigram text match for known phrases |
| `get_memory` | Fetch a single memory by id (including tombstones) |
| `count_memories` | Cheap `COUNT(*)` with the same cleanup filters as `list_memories` |
| `memory_stats` | Aggregate counts, top tags, per-provider breakdown |
| `list_tags` | Distinct tags with their row counts |
| `export_memories` | Full JSON dump for backup or migration |

Every tool requires an OAuth 2.1 access token (the server bundles its
own authorization server, so no external IdP is required); secret-shaped
inputs (API keys, JWTs, PEM private-key markers, etc.) are rejected
before they hit the database.

Every memory carries a `keep` protection flag (`Y`/`N`/`U`, default
`U`): a `keep='Y'` memory cannot be changed or deleted by any client.
The flag is returned on every read tool's output, but it can only be
*changed* through the `ownsona` CLI — via a secret-gated, unadvertised
`set_keep` operation — so connected LLM clients can read protection
status yet never alter it.

## Command-line client

Ownsona ships with a small standalone CLI under [`cli/`](cli/) for
reading from and writing to the memory store from the terminal —
useful for scripting, ad-hoc edits, and bulk-loading facts into the
Ownsona MCP server's database.

Written in portable C; builds on Linux, macOS, and Windows (MSYS2
UCRT64) with one runtime dependency (libcurl) and a single Makefile.
It offers curation commands for managing the store plus thin wrappers
over individual MCP tools:

```bash
# Curation (id selectors: an id, a range 5-9, a list 5,7,9, or 5-9,12,$)
ownsona display [ids] [-k YNU]    # show id, keep, text, in id order
ownsona enumerate [ids] [-k YNU]  # walk + act on each memory interactively
ownsona change <id> "<text>"      # replace one memory's text
ownsona delete <ids>              # hard-delete the selected memories
ownsona add    "<text>"           # remember (aliases: remember, new)
ownsona stats                     # store overview: counts, keep breakdown, tags
ownsona dump   [file]             # write all memories as JSON (file or stdout)
ownsona restore <file>            # re-insert memories from a dump file

# Thin tool wrappers
ownsona query  "<question>"       # recall
ownsona search "<substring>"      # text_search
ownsona list                      # list_memories
ownsona update <id> "<text>"      # update_memory
ownsona confirm <id>              # confirm
ownsona reinforce <id>... [--query "..."]  # feedback: learned ranking
ownsona conflicts                 # find_conflicts
ownsona relations "<entity>"      # query_relations (multi-hop graph)
ownsona forget <id>               # forget
ownsona prompt "<user prompt>"    # build_context_prompt
ownsona import FILE               # remember_batch (JSON or lines)
ownsona teach  FILE               # extract facts from prose via an LLM,
                                  # then bulk-load them
```

The curation commands (`display` / `enumerate` / `change` / `delete`)
and the `keep` protection flag they manage are documented in
[`CLI.md`](CLI.md); changing `keep` needs an `admin_secret` in the CLI
config that matches the server's `OwnsonaAdminSecret`.

The `teach` subcommand is the headline feature: hand it a long-form
text (an autobiography draft, journal, project notes) and it uses an
OpenAI-compatible chat-completion API to extract durable third-person
facts and submit them via `remember_batch`.  Dry-run by default; pass
`--commit` to insert.  Vendor-neutral by configuration — point it at
OpenAI, OpenRouter, a local Ollama, or anything else that exposes the
OpenAI `/chat/completions` shape.

See [`CLI.md`](CLI.md) for build instructions per OS, the config-file
shape, every subcommand's flags, and the `teach`-from-prose workflow.

## Documentation

The **canonical long-form documentation is the manual**, published at
**<https://blakemcbride.github.io/Ownsona/user-manual/html/>**. The same
HTML output is also committed in this repo at
[`user-manual/html/index.html`](user-manual/html/index.html) for offline
browsing; both are generated from the GNU TexInfo sources under
[`user-manual/`](user-manual/) (rebuild with `cd user-manual && make`).

Short-form reference docs at the repo root (kept current alongside the
manual):

| Doc | What's inside |
|---|---|
| [Manual (published)](https://blakemcbride.github.io/Ownsona/user-manual/html/) | **Full manual** — install, configure, MCP tools, server internals, client integration, CLI, `teach`, embedding migration, tutorials, troubleshooting, reference. (Offline copy: [`user-manual/html/index.html`](user-manual/html/index.html).) |
| [`MCPServer.md`](MCPServer.md) | Architecture, configuration keys, tool surface, security, deploy & ops |
| [`INSTALL.md`](INSTALL.md) | Linux VPS install: PostgreSQL, Tomcat, systemd, HTTPS, `application.ini` |
| [`Deploy.md`](Deploy.md) | Step-by-step deploy procedure: server upgrade + connecting each LLM client over OAuth 2.1 |
| [`OpenAI.md`](OpenAI.md) | Wiring Ownsona into ChatGPT and the OpenAI Responses API |
| [`OWNSONA_SPEC.md`](OWNSONA_SPEC.md) | Functional spec, schema, security model |
| [`CLI.md`](CLI.md) | Portable command-line client: build, configure, every subcommand, and the LLM-driven `teach`-from-prose workflow |
| [`REEMBED.md`](REEMBED.md) | Switching the embedding model or provider: when/why, the same-dim flow, the different-dim flow, monitoring & recovery |

## Quick Start

Prerequisites: Java 17+, PostgreSQL 16 with `pgvector`, an OpenAI API
key, a Linux host with Tomcat 11.

```bash
# 1. Database: role + extensions + schema migration
sql/setup_db.sh "<postgres-password>"

# 2. Configuration: copy the template and populate secrets/endpoints.
#    application.ini is gitignored; only the .example template ships in the repo.
#    Required values at minimum:
#      - DatabasePassword
#      - EMBEDDING_API_KEY (your OpenAI key)
#      - OWNSONA_LOGIN_USERNAME / OWNSONA_LOGIN_PASSWORD (invent these;
#        they guard the OAuth consent page)
#      - OAuthAuthorizationServer = https://<your-host>
#      - OAuthAsEnabled           = true
#      - OAuthAsIniFile = /absolute/path/outside/the/webapp/oauth.ini
#        (recommended for production; without it, every WAR redeploy
#        rotates the AS signing key and re-prompts every LLM client)
cp src/main/backend/application.ini.example src/main/backend/application.ini
$EDITOR src/main/backend/application.ini

# 3. Build + deploy the WAR
./bld -v build && ./bld war
cp work/Kiss.war /home/ownsona/tomcat/webapps/ROOT.war

# 4. Smoke-test the live endpoint (token from the OAuth flow; see INSTALL.md §12)
OWNSONA_ACCESS_TOKEN=... sql/smoke_test.sh https://<your-host>/mcp

# 5. Connect an LLM client.  In Claude.ai / Claude Desktop / ChatGPT /
#    Grok / any OAuth-capable MCP client, paste the URL:
#        https://<your-host>/mcp
#    and pick OAuth as the auth mode.  The client opens a browser tab
#    to /oauth/authorize; enter the OWNSONA_LOGIN_USERNAME /
#    OWNSONA_LOGIN_PASSWORD from step 2, click Allow, and you're done.
#    See INSTALL.md §12 or the manual chapter "Connecting Claude" /
#    "Connecting OpenAI" for the per-client click-throughs.
```

See [`INSTALL.md`](INSTALL.md) for the complete walkthrough including
HTTPS certificates, the `ownsona.service` systemd unit, daily backups,
and the OAuth setup details.  See [`Deploy.md`](Deploy.md) when
upgrading an existing install.

## Built on Kiss

Ownsona is built on the open-source
[Kiss web development framework](https://kissweb.org) — a Java-based
full-stack framework that provides the servlet container, the
`MCPServerBase` JSON-RPC base class this server extends, the c3p0
connection pool, the `bld` build system, and the `application.ini`
configuration loader.

Documentation for Kiss itself (manual, JSDoc, JavaDoc, training
videos) lives at [kissweb.org](https://kissweb.org). Source:
<https://github.com/blakemcbride/Kiss>.

## License

Distributed under the BSD 2-Clause License — see [`LICENSE.txt`](LICENSE.txt)
for the full text. Copyright © 2018 Blake McBride.

## Contributing

See [`CONTRIBUTING.md`](CONTRIBUTING.md). Pull requests welcome.
