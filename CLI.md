# OwnSona CLI

A small, self-contained command-line client for the OwnSona MCP
memory server.  Lets you read from and write to your memory store from
the terminal — handy for scripting, bulk operations, and feeding
remembered facts into another LLM.

The sources live under [`cli/`](cli/).

---

## Contents

1. [What it does](#what-it-does)
2. [Building](#building)
3. [Configuration](#configuration)
   - [Default config file location](#default-config-file-location)
   - [Resolution order](#resolution-order)
   - [Config file format](#config-file-format)
4. [Subcommands](#subcommands)
5. [Examples](#examples)
6. [Teaching from a long-form text](#teaching-from-a-long-form-text)
7. [Exit codes](#exit-codes)
8. [Source layout](#source-layout)

---

## What it does

Talks to the OwnSona MCP server over HTTPS using JSON-RPC.  Maps each
of the server's MCP tools to a subcommand:

| Subcommand | MCP tool | Purpose |
|---|---|---|
| `add`      | `remember`             | Store a new memory |
| `query`    | `recall`               | Find memories by semantic similarity |
| `search`   | `text_search`          | Plain substring search |
| `list`     | `list_memories`        | List most-recent memories |
| `update`   | `update_memory`        | Replace a memory's text/tags/etc |
| `confirm`  | `confirm`              | Mark a memory as still-current |
| `forget`   | `forget`               | Soft- or hard-delete a memory |
| `prompt`   | `build_context_prompt` | Build an LLM prompt with relevant facts |
| `import`   | `remember_batch`       | Bulk-load facts from a file |
| `teach`    | (LLM + `remember_batch`) | Extract facts from prose via an LLM, then bulk-load |

`teach` is the only subcommand that calls a generative LLM directly
(see [Teaching from a long-form text](#teaching-from-a-long-form-text)).
The others stay vendor-neutral — they just talk to OwnSona.

Output is human-readable by default; pass `--json` (a global flag) for
the raw server response, suitable for piping into `jq`.

---

## Building

Written in portable C11.  One runtime dependency: libcurl (statically
linked where the platform supports it, dynamic otherwise).  cJSON is
vendored in `cli/src/`.

One-line install of build dependencies per platform:

**Linux (Fedora):**
```bash
sudo dnf install gcc make libcurl-devel openssl-devel zlib-devel
```

**Linux (Debian / Ubuntu):**
```bash
sudo apt install build-essential libcurl4-openssl-dev libssl-dev zlib1g-dev
```

**macOS (Homebrew):**
```bash
brew install curl openssl@3 pkg-config
```

**Windows (MSYS2 UCRT64 shell):**
```bash
pacman -S mingw-w64-ucrt-x86_64-toolchain \
          mingw-w64-ucrt-x86_64-curl     \
          mingw-w64-ucrt-x86_64-openssl
```

Then:

```bash
cd cli
make            # builds ./ownsona  (or ownsona.exe on Windows)
make test       # sanity-check the binary --- doesn't hit the server
```

The Makefile detects the platform via `uname -s` and asks
`curl-config --static-libs` for static linker flags.  When those flags
yield a true single-file binary (typical on macOS Homebrew and on
MSYS2 UCRT64), you can copy `ownsona` to another machine of the same
OS and run it as-is.  When the platform's libcurl package doesn't
ship the static `.a` (typical on Fedora and Debian/Ubuntu), the build
falls back to dynamic linking and prints a warning at link time —
you'll need libcurl on the target machine.

The binary is small (~80–100 KB dynamically linked, ~3-5 MB statically).

---

## Configuration

The CLI reads two kinds of settings:

1. **MCP server credentials** — always needed.
2. **LLM credentials** — only needed by the `teach` subcommand.

### Default config file location

If you do not pass `--config PATH` and do not set `$OWNSONA_CONFIG`,
the CLI looks for its config file at an OS-specific default path:

| OS | Default config path |
|---|---|
| Linux / BSD | `~/.config/ownsona/config.ini` |
| macOS       | `~/Library/Application Support/ownsona/config.ini` |
| Windows     | `%LOCALAPPDATA%\ownsona\config.ini` |

Create the parent directory if it doesn't already exist:

```bash
# Linux / BSD
mkdir -p ~/.config/ownsona

# macOS
mkdir -p ~/"Library/Application Support/ownsona"
```

```cmd
:: Windows (cmd.exe)
mkdir "%LOCALAPPDATA%\ownsona"
```

If the default file is missing, the CLI continues silently — it will
only complain when something it needs (server URL, token) is still
unset after the environment and CLI overrides are applied.

### Resolution order

For every setting, highest priority wins:

1. CLI flag (`--config`, `--server`, `--token`, `--model`, `--subject`, …).
2. Environment variable (`OWNSONA_SERVER`, `OWNSONA_TOKEN`,
   `OWNSONA_LLM_API_KEY`, `OWNSONA_LLM_MODEL`, `OWNSONA_LLM_BASE_URL`,
   `OWNSONA_SUBJECT`, `OWNSONA_CONFIG`).
3. Config file (at `$OWNSONA_CONFIG` if set, otherwise the OS-specific
   default above).
4. Built-in defaults (only for the LLM-side keys: `model = gpt-4o`,
   `base_url = https://api.openai.com/v1`, `subject_name = the user`).

### Config file format

INI-style.  `#` and `;` start comments.  `[sections]` are parsed but
ignored (forward-compatibility placeholder).  Quotes around a value
are stripped.

A template is shipped at [`cli/config.ini.example`](cli/config.ini.example);
copy it to the OS-specific default path (see the
[Default config file location](#default-config-file-location) table)
and fill in your values.

```ini
# --- MCP server (required for every subcommand) ---
server_url = https://your.host/mcp
token      = <bearer-token>

# --- LLM (used only by `teach`) ---
# Required only when running `teach`.
llm_api_key  = sk-...
llm_model    = gpt-4o              # default; --model NAME overrides per-run
llm_base_url = https://api.openai.com/v1
subject_name = Blake               # how `teach` refers to you in facts
```

The token is the same bearer token any other MCP client uses against
your OwnSona server.  Treat it as a secret — anyone holding it can
read and write your memory store.

The `llm_api_key` can be the same OpenAI key the server uses for
embeddings, or a separate key if you want to track CLI costs
independently.

---

## Subcommands

Global options come **before** the subcommand name; subcommand options
come after.

```
ownsona [--config PATH] [--server URL] [--token TOKEN] [--json] <command> [...]
```

Run `ownsona --help` for the top-level help, and
`ownsona <command> --help` for per-command help.

### Quick reference

```
ownsona add "<text>"                       store a memory
ownsona query "<question>"                 semantic recall
ownsona search "<substring>"               substring search
ownsona list                               recent memories
ownsona update <id> "<new text>"           replace a memory
ownsona confirm <id>                       refresh last_confirmed_at
ownsona forget <id>                        soft-delete (--hard to drop)
ownsona prompt "<user prompt>"             build an LLM-ready prompt
ownsona import FILE                        bulk-load JSON or one-per-line
ownsona teach FILE                         extract facts from prose via LLM
```

### Common flags across subcommands

- `--tags T1,T2,...` — comma-separated tags.  Used by `add`, `update`,
  `query` (as a filter), `import` (default for line-format files),
  `teach`.
- `--limit N` — cap on returned rows.  Used by `query`, `search`,
  `list`, `prompt`.
- `--dedup POLICY` — one of `insert`, `skip_if_near`, `ask` (default
  `ask` for `add`, `skip_if_near` for `teach`).
- `--expires-at`, `--last-confirmed-at` — ISO 8601 timestamps on
  `add` and `update`.

---

## Examples

### Store a fact

```
$ ownsona add "My dog's name is Mochi." --tags family,pets
Ok (id=42)
```

### Recall

```
$ ownsona query "what is my dog's name"
1 match
  [42] score=0.913  My dog's name is Mochi.
    tags: family pets
    at:   2026-05-16T12:00:00Z
```

### Build a prompt for another LLM

```
$ ownsona prompt "What should I name my new dog?" --max-chars 1000 \
    | claude --no-mcp
```

### Bulk import from a one-fact-per-line file

```
$ cat facts.txt
# preferences
I prefer concise answers.
I work in Pacific Time.
My favorite editor is Emacs.

$ ownsona import facts.txt --tags preferences --dedup skip_if_near
inserted=3  duplicates=0  errors=0  (total=3)
  [0] id=43  Ok
  [1] id=44  Ok
  [2] id=45  Ok
```

### Bulk import from JSON (per-item metadata)

```
$ cat facts.json
[
  {"text": "I live in Texas.",  "tags": ["personal"], "expires_at": "2030-01-01T00:00:00Z"},
  {"text": "I drive a Subaru.", "tags": ["personal"], "importance": 0.3}
]

$ ownsona import facts.json --dedup skip_if_near
```

### Correct a fact, leaving a trail

```
$ ownsona add "My dog's name is Mochi." --dedup skip_if_near
Ok (id=99)

$ ownsona forget 42 --reason "misremembered name" --replaced-by 99
Forgotten (id=42)
```

A future `add` of "Coco is my dog's name" will now show up with a
`previously_corrected` block, warning that this fact was already
forgotten.

---

## Teaching from a long-form text

`ownsona teach` reads a plain-text file (autobiography draft, journal,
notes, anything narrative) and uses an LLM to extract durable
third-person facts about you, then submits them as a batch of
memories.

### How it works

1. Reads the file and splits it into chunks (default 4000 characters,
   broken at paragraph then sentence boundaries).
2. For each chunk, POSTs to your configured chat-completion endpoint
   (`{llm_base_url}/chat/completions`) with a system prompt that
   instructs the model to extract durable facts as a JSON array.
3. Collects all returned facts.
4. **Default behavior: dry-run.**  Prints the JSON to stdout (or
   `--output FILE`) so you can eyeball before committing.
5. Pass `--commit` to actually submit the facts via `remember_batch`,
   in 200-at-a-time chunks, with `dedup_policy=skip_if_near` by
   default so re-runs after manuscript edits don't double up.

### Requirements

`teach` needs the LLM config keys filled in.  Set `llm_api_key` either
in your config file (see [Configuration](#configuration) for the
OS-specific default path) or via `$OWNSONA_LLM_API_KEY`.

### Flags

| Flag | Purpose |
|---|---|
| `--subject NAME`     | How to refer to you in extracted facts (default `the user`; recommend setting to your name) |
| `--model NAME`       | Override the LLM model just for this run |
| `--chunk-size N`     | Characters per LLM call (default 4000; valid 500–32000) |
| `--tags T1,T2`       | Apply these tags to every extracted fact |
| `--dedup POLICY`     | `dedup_policy` for the resulting `remember_batch` calls (default `skip_if_near`) |
| `--max-facts N`      | Sanity cap on total extracted facts (default 10000) |
| `--commit`           | Actually insert the facts (default is dry-run) |
| `--yes`              | With `--commit`, skip the y/N confirmation prompt |
| `--output FILE`      | In dry-run, write JSON here instead of stdout |

### Recommended workflow

```bash
# 1) Extract to a file for review.
ownsona teach my-life.txt --subject Blake --output review.json

# 2) Open review.json, edit if you want, drop or rephrase anything.

# 3) Submit the reviewed file via `import` (no second LLM call).
ownsona import review.json --dedup skip_if_near
```

This two-step `teach --output` → human review → `import` avoids
spending a second LLM call if you decided to edit the result.  You
can also just `ownsona teach my-life.txt --commit` if you trust the
extraction.

### Cost

A 100K-word autobiography is about 150 chunks of 4000 chars each.
Roughly:

| Model | Approx. cost |
|---|---|
| `gpt-4o-mini` | $0.05 – $0.15 |
| `gpt-4o`      | $1 – $3 |

Numbers vary with chunk size and how dense the extraction is.
Dry-runs cost the same as commits — the LLM call is the expense, the
`remember_batch` insertion is free.

### Vendor neutrality

The `--model` flag and `llm_base_url` config key let you point
`teach` at any service that exposes OpenAI's `/chat/completions`
shape: OpenAI itself, OpenRouter, Anthropic via a compat proxy, a
local Ollama server, etc.  Default is OpenAI's endpoint.

---

## Exit codes

| Code | Meaning |
|------|---------|
| 0    | Success |
| 1    | Server error, transport failure, or `ok:false` tool result |
| 2    | Usage error (bad flag, missing arg, etc.) |

---

## Source layout

```
cli/
├── Makefile              portable per-OS build
├── README.md             short pointer to this file
├── config.ini.example    config template
├── src/
│   ├── cJSON.{c,h}       vendored, MIT
│   ├── config.c          INI loader + env/CLI overrides
│   ├── http.c            libcurl wrapper for the MCP server
│   ├── llm.c             libcurl wrapper for the chat-completion endpoint
│   ├── mcp.c             MCP JSON-RPC envelope + unwrap
│   ├── main.c            argv parsing, subcommand dispatch, output
│   └── cmd_*.c           one file per subcommand
└── include/
    └── ownsona.h         shared declarations
```

`cJSON` is vendored from <https://github.com/DaveGamble/cJSON> (MIT
licensed; retains its own license header).  To update, replace
`cli/src/cJSON.c` and `cli/src/cJSON.h` with the upstream release.

The rest of the CLI is BSD 2-Clause, same as the OwnSona server.
