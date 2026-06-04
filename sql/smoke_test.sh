#!/usr/bin/env bash
#
# Smoke test for the deployed Ownsona MCP server.
#
# Usage:
#   OWNSONA_ACCESS_TOKEN=<jwt> sql/smoke_test.sh <base-url>
#
# `base-url` is REQUIRED: pass the full URL to your /mcp endpoint, e.g.
#   sql/smoke_test.sh https://your.host/mcp
#
# Earlier versions of this script defaulted to https://ownsona.com/mcp;
# the default was removed so a stray invocation never targets someone
# else's server.
#
# OAuth 2.1 note
# --------------
# Ownsona uses OAuth 2.1 (auth code + PKCE).  The embedded AS does not
# support the client_credentials grant, so this script cannot fetch a
# token on its own.  Obtain one once via the browser flow, then export
# it as OWNSONA_ACCESS_TOKEN.  See INSTALL.md for the procedure (run a
# throwaway curl-driven auth code dance, or copy the access_token your
# real MCP client already obtained from its local config).
#
# The token expires --- 1 hour by default (OAuthAccessTokenTtlSeconds).
# If the smoke test starts returning 401, refresh.

set -euo pipefail

BASE_URL="${1:-}"
TOKEN="${OWNSONA_ACCESS_TOKEN:-}"
ADMIN_SECRET="${OWNSONA_ADMIN_SECRET:-}"

if [[ -z "$BASE_URL" ]]; then
    echo "Usage: OWNSONA_ACCESS_TOKEN=<jwt> $0 <base-url>" >&2
    echo "Example: $0 https://your.host/mcp" >&2
    exit 1
fi
if [[ -z "$TOKEN" ]]; then
    echo "Set OWNSONA_ACCESS_TOKEN in the environment first." >&2
    echo "See INSTALL.md for how to obtain one via the OAuth auth code flow." >&2
    exit 1
fi

call() {
    local body="$1"
    curl -sS -X POST "$BASE_URL" \
        -H "Authorization: Bearer $TOKEN" \
        -H "Content-Type: application/json" \
        -d "$body"
    echo
}

echo "==> initialize"
call '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{}}}'

echo "==> tools/list"
call '{"jsonrpc":"2.0","id":2,"method":"tools/list"}'

echo "==> remember (smoke)"
call '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"remember","arguments":{"text":"Smoke-test fact: the user prefers concise answers.","tags":["preferences","testing"]}}}'

echo "==> recall (smoke)"
call '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"recall","arguments":{"query":"how does the user like answers","limit":3}}}'

echo "==> list_memories"
call '{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"list_memories","arguments":{"limit":5}}}'

echo "==> text_search"
call '{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"text_search","arguments":{"text":"smoke-test"}}}'

# forget_batch + dry_run smoke: insert two throwaway rows, dry-run a
# forget_batch, then commit it.  Caller can spot-check that the dry_run
# response carries already_deleted=false and the live run reports
# Forgotten without the rows reappearing in list_memories.
echo "==> remember (forget-batch fixture #1)"
call '{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"remember","arguments":{"text":"Smoke-test fixture A for forget_batch.","tags":["testing","fixture"]}}}'

echo "==> remember (forget-batch fixture #2)"
call '{"jsonrpc":"2.0","id":8,"method":"tools/call","params":{"name":"remember","arguments":{"text":"Smoke-test fixture B for forget_batch.","tags":["testing","fixture"]}}}'

echo "==> text_search (capture fixture ids)"
call '{"jsonrpc":"2.0","id":9,"method":"tools/call","params":{"name":"text_search","arguments":{"text":"forget_batch"}}}'

echo "==> find_near_duplicates (diagnostic)"
call '{"jsonrpc":"2.0","id":10,"method":"tools/call","params":{"name":"find_near_duplicates","arguments":{"threshold":0.85,"max_groups":5}}}'

# --- keep protection -------------------------------------------------
# 1. tools/list (above) must NOT contain "set_keep" --- it is CLI-only
#    and deliberately unadvertised.  Eyeball the id:2 output.
# 2. list_memories / recall results now carry a "keep" field (Y/N/U).
# 3. set_keep without the admin secret must fail closed.  An LLM client
#    has no secret, so it can never change a memory's keep flag.
echo "==> set_keep WITHOUT admin_secret (must be rejected: \"Unknown tool: set_keep\")"
call '{"jsonrpc":"2.0","id":11,"method":"tools/call","params":{"name":"set_keep","arguments":{"id":1,"keep":"Y"}}}'

if [[ -n "$ADMIN_SECRET" ]]; then
    echo "==> set_keep WITH a wrong admin_secret (must also be rejected)"
    call '{"jsonrpc":"2.0","id":12,"method":"tools/call","params":{"name":"set_keep","arguments":{"id":1,"keep":"Y","admin_secret":"definitely-not-the-secret"}}}'
    echo "    (a correct-secret set_keep is id-specific --- use the manual hint below"
    echo "     with a real id from list_memories to actually protect a row.)"
else
    echo "    (set OWNSONA_ADMIN_SECRET to also exercise the wrong-secret rejection.)"
fi

echo
echo "Manual cleanup: forget the smoke-test memory and fixtures by id from the list_memories / text_search output."
echo "  Single forget with dry_run:"
echo '       -d '"'"'{"jsonrpc":"2.0","id":97,"method":"tools/call","params":{"name":"forget","arguments":{"id":<ID>,"dry_run":true}}}'"'"
echo "  forget_batch with dry_run (preview):"
echo '       -d '"'"'{"jsonrpc":"2.0","id":98,"method":"tools/call","params":{"name":"forget_batch","arguments":{"ids":[<ID1>,<ID2>],"reason":"Smoke-test cleanup","dry_run":true}}}'"'"
echo "  forget_batch (live):"
echo '       -d '"'"'{"jsonrpc":"2.0","id":99,"method":"tools/call","params":{"name":"forget_batch","arguments":{"ids":[<ID1>,<ID2>],"reason":"Smoke-test cleanup"}}}'"'"
echo "  forget (hard delete a single row):"
echo '       -d '"'"'{"jsonrpc":"2.0","id":100,"method":"tools/call","params":{"name":"forget","arguments":{"id":<ID>,"hard_delete":true}}}'"'"
echo "  update_memory_batch with dry_run (preview tag normalization):"
echo '       -d '"'"'{"jsonrpc":"2.0","id":101,"method":"tools/call","params":{"name":"update_memory_batch","arguments":{"items":[{"id":<ID1>,"tags":["testing"]},{"id":<ID2>,"tags":["testing"]}],"dry_run":true}}}'"'"
echo "  set_keep WITH the real admin secret (protect a row; CLI-only tool):"
echo '       -d '"'"'{"jsonrpc":"2.0","id":102,"method":"tools/call","params":{"name":"set_keep","arguments":{"id":<ID>,"keep":"Y","admin_secret":"<OwnsonaAdminSecret>"}}}'"'"
echo "  Then confirm update_memory / forget on that <ID> are rejected with code PROTECTED."
