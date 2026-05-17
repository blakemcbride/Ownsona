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

Configure: copy [`config.ini.example`](config.ini.example) to
`~/.ownsona/config.ini` and fill in `server_url` and `token` (plus
`llm_api_key` if you'll use the `teach` subcommand).

Then:

```bash
ownsona --help
ownsona add "fact"            # store a memory
ownsona query "question"      # semantic recall
```

See [`../CLI.md`](../CLI.md) for the full subcommand reference and
examples.
