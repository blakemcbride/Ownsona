# OwnSona CLI

A small, single-binary command-line client for the OwnSona MCP memory
server.  Talks to the server over HTTPS using JSON-RPC.  Written in
portable C (C11) with one runtime dependency (libcurl), vendored cJSON
for JSON, and a Makefile that detects the platform.

Supports Linux, macOS, and Windows (MSYS2 UCRT64).

## Build

You need a C toolchain plus libcurl + OpenSSL + zlib development
packages.  One-line install per platform:

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
make
```

You get `./ownsona` (or `ownsona.exe` on Windows).  Run `make test`
for a sanity check that doesn't need the server.

The Makefile attempts to statically link libcurl and its transitive
dependencies via `curl-config --static-libs` so the resulting binary
is portable to other machines of the same OS.  On Linux distros that
don't ship static `.a` variants by default, the build falls back to a
dynamic link and prints a warning --- you'll need libcurl on the
target machine.

## Configuration

The CLI reads two values: the server URL and a bearer token.  Three
ways to provide them, in increasing priority:

1. **Config file.**  Default location is `~/.ownsona/config.ini` (or
   `%USERPROFILE%\.ownsona\config.ini`).  Override the path with
   `--config PATH` or `$OWNSONA_CONFIG`.

   ```ini
   # ~/.ownsona/config.ini
   server_url = https://your.host/mcp
   token      = <bearer-token>
   ```

   `#` and `;` start comments.  Quotes around the value are stripped.

2. **Environment.**  `OWNSONA_SERVER` and `OWNSONA_TOKEN`.

3. **Flags.**  `--server URL` and `--token TOKEN`.

Save the bearer token like you'd save an SSH private key --- anyone
holding it can read and write your memory store.

## Usage

```
ownsona add "<text>"                       store a new memory
ownsona query "<question>"                 recall by semantic similarity
ownsona search "<substring>"               plain text search
ownsona list                               most-recent first
ownsona update <id> "<new text>"           replace a memory
ownsona confirm <id>                       mark as still-current
ownsona forget <id>                        soft-delete (--hard to drop)
ownsona prompt "<user prompt>"             build an LLM-ready prompt
ownsona import FILE                        bulk-load (remember_batch)
```

Run `ownsona <command> --help` for per-command flags.

Global options come before the subcommand:

```
ownsona [--config PATH] [--server URL] [--token TOKEN] [--json] <cmd> ...
```

`--json` switches the default human-readable output to the raw JSON
returned by the server --- pipe into `jq` for scripting.

### Examples

Store a fact:

```
$ ownsona add "My dog's name is Mochi." --tags family,pets
Ok (id=42)
```

Recall:

```
$ ownsona query "what is my dog's name"
1 match
  [42] score=0.913  My dog's name is Mochi.
    tags: family pets
    at:   2026-05-16T12:00:00Z
```

Build a prompt for an LLM:

```
$ ownsona prompt "What should I name my new dog?" --max-chars 1000 \
    | claude --no-mcp
```

Bulk import from a text file (one fact per line):

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

Bulk import from JSON (per-item metadata):

```
$ cat facts.json
[
  {"text": "I live in Texas.",  "tags": ["personal"],    "expires_at": "2030-01-01T00:00:00Z"},
  {"text": "I drive a Subaru.", "tags": ["personal"],    "importance": 0.3},
  {"text": "I'm building an MCP memory server.", "tags": ["software", "projects"]}
]

$ ownsona import facts.json --dedup skip_if_near
```

Correct a fact and leave a trail:

```
$ ownsona add "My dog's name is Mochi." --dedup skip_if_near
Ok (id=99)

$ ownsona forget 42 --reason "misremembered name" --replaced-by 99
Forgotten (id=42)
```

## Exit codes

| Code | Meaning                                    |
|------|--------------------------------------------|
| 0    | Success                                    |
| 1    | Server error, transport failure, or `ok:false` tool result |
| 2    | Usage error (bad flag, missing arg, etc.)  |

## Layout

```
cli/
├── Makefile              # portable per-OS build
├── README.md             # this file
├── src/
│   ├── cJSON.c           # vendored, MIT
│   ├── cJSON.h
│   ├── config.c          # INI loader + env/CLI overrides
│   ├── http.c            # libcurl POST wrapper
│   ├── mcp.c             # MCP JSON-RPC envelope + unwrap
│   ├── main.c            # argv parsing, subcommand dispatch, output
│   └── cmd_*.c           # one file per subcommand
└── include/
    └── ownsona.h         # shared declarations
```

`src/cJSON.{c,h}` are vendored from
<https://github.com/DaveGamble/cJSON> (MIT).  To update, replace those
two files with the upstream release.

## License

Same as the parent OwnSona project (BSD 2-Clause).  Vendored cJSON
retains its own MIT license (see headers in `src/cJSON.{c,h}`).
