#!/usr/bin/env bash
#
# Smoke test for the deployed Ownsona MCP server.
#
# Usage:
#   OWNSONA_API_TOKEN=... sql/smoke_test.sh [base-url]
#
# Default base URL is https://ownsona.com/mcp.  Provide OWNSONA_API_TOKEN
# in the environment so it does not appear in shell history.

set -euo pipefail

BASE_URL="${1:-https://ownsona.com/mcp}"
TOKEN="${OWNSONA_API_TOKEN:-}"

if [[ -z "$TOKEN" ]]; then
    echo "Set OWNSONA_API_TOKEN in the environment first." >&2
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
echo '       -H "Authorization: Bearer $OWNSONA_API_TOKEN" \'
echo '       -H "Content-Type: application/json" \'
echo '       -d '"'"'{"jsonrpc":"2.0","id":99,"method":"tools/call","params":{"name":"forget","arguments":{"id":<ID>,"hard_delete":true}}}'"'"
