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

echo
echo "Manual cleanup: forget the smoke-test memory by id from the list_memories output:"
echo '  curl -sS -X POST "'"$BASE_URL"'" \'
echo '       -H "Authorization: Bearer $OWNSONA_ACCESS_TOKEN" \'
echo '       -H "Content-Type: application/json" \'
echo '       -d '"'"'{"jsonrpc":"2.0","id":99,"method":"tools/call","params":{"name":"forget","arguments":{"id":<ID>,"hard_delete":true}}}'"'"
