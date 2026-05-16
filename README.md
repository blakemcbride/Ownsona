# Ownsona

> **Teach any LLM about you, once.** Ownsona is a personal-memory
> server that turns one knowledge base into a shared brain across
> ChatGPT, Claude, Gemini, Grok, and any other MCP-capable assistant.

[![License: BSD 2-Clause](https://img.shields.io/badge/License-BSD_2--Clause-blue.svg)](LICENSE.txt)
[![Java: 17+](https://img.shields.io/badge/Java-17%2B-orange)](https://adoptium.net)
[![PostgreSQL: 16](https://img.shields.io/badge/PostgreSQL-16%20%2B%20pgvector-336791)](https://www.postgresql.org)
[![Built on: Kiss](https://img.shields.io/badge/Built%20on-Kiss-brightgreen)](https://kissweb.org)

The main home for this code is <https://github.com/blakemcbride/Ownsona>.

---

> ### ⚠️ Existing installs: one-time database prep required
>
> If your Ownsona database was created before the auto-migration
> framework landed, you must run a one-time privilege-fixup script
> **once, as the postgres superuser**, before deploying any release
> that includes the auto-migrator. The server will refuse to start
> without it.
>
> ```bash
> cat sql/migrator_prep.sql | sudo -u postgres psql -d ownsona
> ```
>
> The script grants the `ownsona` application role `CREATE` on
> schema `public` and transfers ownership of `memories` to it, so
> the auto-migrator can manage future schema changes without
> needing the postgres superuser at runtime. It is idempotent —
> safe to re-run.
>
> **Fresh installs do not need this**: `sql/setup_db.sh` already
> includes everything `migrator_prep.sql` does. See
> [INSTALL.md](INSTALL.md) section 15 for the full walkthrough.

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

## MCP Tools

| Tool | Purpose |
|---|---|
| `remember` | Store a fact, with optional tags and importance score |
| `remember_batch` | Bulk import (up to 200 items) in a single embedding round-trip |
| `recall` | Vector-similarity search by natural-language query |
| `build_context_prompt` | Pack relevant memories into a prompt envelope |
| `list_memories` | Paginated listing for review/audit |
| `update_memory` | Edit text + re-embed |
| `forget` | Soft delete (default) or hard delete |
| `text_search` | Trigram text match for known phrases |

Every tool requires a bearer token; secret-shaped inputs (API keys,
JWTs, PEM private-key markers, etc.) are rejected before they hit the
database.

## Documentation

| Doc | What's inside |
|---|---|
| [`MCPServer.md`](MCPServer.md) | Architecture, configuration keys, tool surface, security, deploy & ops |
| [`INSTALL.md`](INSTALL.md) | Linux VPS install: PostgreSQL, Tomcat, systemd, HTTPS, `application.ini` |
| [`OpenAI.md`](OpenAI.md) | Wiring Ownsona into ChatGPT and the OpenAI Responses API |
| [`OWNSONA_SPEC.md`](OWNSONA_SPEC.md) | Functional spec, schema, security model |

## Quick Start

Prerequisites: Java 17+, PostgreSQL 16 with `pgvector`, an OpenAI API
key, a Linux host with Tomcat 11.

```bash
# 1. Database: role + extensions + schema migration
sql/setup_db.sh "<postgres-password>"

# 2. Configuration: copy the template and populate secrets/endpoints.
#    application.ini is gitignored; only the .example template ships in the repo.
cp src/main/backend/application.ini.example src/main/backend/application.ini
$EDITOR src/main/backend/application.ini

# 3. Build + deploy the WAR
./bld -v build && ./bld war
cp work/Kiss.war /home/ownsona/tomcat/webapps/ROOT.war

# 4. Smoke-test the live endpoint
OWNSONA_API_TOKEN=... sql/smoke_test.sh https://<your-host>/mcp
```

See [`INSTALL.md`](INSTALL.md) for the complete walkthrough including
HTTPS certificates, the `ownsona.service` systemd unit, and daily
backups.

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
