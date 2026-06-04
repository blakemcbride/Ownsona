# OwnSona CLI

A portable C command-line client for the OwnSona MCP memory server.

**Full documentation lives at [`../CLI.md`](../CLI.md)** — what it
does, build instructions per OS, configuration, every subcommand's
flags, and the `teach`-from-prose workflow.

## TL;DR

Build (one-time install of `libcurl` development headers; see
[`../CLI.md`](../CLI.md) for the per-platform one-liner):

```bash
make            # builds ./ownsona  (or ownsona.exe on Windows)
make test       # sanity-check the binary
```

Configure: copy [`config.ini.example`](config.ini.example) to the
OS-specific default location and fill in `server_url` (plus
`llm_api_key` if you'll use the `teach` subcommand):

| OS | Default config path |
|---|---|
| Linux / BSD | `~/.config/ownsona/config.ini` |
| macOS       | `~/Library/Application Support/ownsona/config.ini` |
| Windows     | `%LOCALAPPDATA%\ownsona\config.ini` |

Then authenticate once via OAuth (opens a browser):

```bash
ownsona auth login            # one-time OAuth bootstrap
```

After that:

```bash
ownsona --help
ownsona add "fact"            # store a memory (alias: remember, new)
ownsona query "question"      # semantic recall
ownsona display 5-9,12        # show id, keep, text for selected memories
ownsona enumerate -k U        # walk unspecified-keep memories and curate them
ownsona change 12 "new text"  # replace one memory's text
ownsona delete 5,7,9          # hard-delete selected memories
ownsona stats                 # store overview: counts, keep breakdown, tags
ownsona auth status           # check the cached credentials
```

The curation commands (`display`, `enumerate`, `change`, `delete`) and the
per-memory `keep` protection flag are documented in
[`../CLI.md`](../CLI.md).  Changing the `keep` flag requires `admin_secret`
in the config (matching the server's `OwnsonaAdminSecret`).

See [`../CLI.md`](../CLI.md) for the full subcommand reference and
examples.
