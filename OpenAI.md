# Connecting OpenAI / ChatGPT to Ownsona

This document explains how to wire OpenAI's products to your Ownsona MCP
server so that ChatGPT — and any code you write against the OpenAI API —
can read from and write to your durable memory store.

There are two integration paths and they are independent:

1. **ChatGPT (consumer)** — add Ownsona as a custom connector in
   Settings. The most common path for day-to-day use.
2. **OpenAI API (developer)** — pass Ownsona as an `mcp` tool when
   calling the Responses API. The path for scripts and your own
   applications.

You can use either, both, or neither.

---

## What you need before you start

| Item | Where to get it |
|---|---|
| Public Ownsona endpoint | `https://<your-host>/mcp` (e.g. `https://ownsona.com/mcp`) |
| Login credentials      | `OWNSONA_LOGIN_USERNAME` / `OWNSONA_LOGIN_PASSWORD` from `src/main/backend/application.ini` |
| Eligible OpenAI plan   | ChatGPT path: Plus, Pro, Team, or Enterprise. API path: any account with API access. |

Ownsona uses OAuth 2.1: clients perform a dynamic registration + auth
code flow against the server's embedded authorization server and use
the resulting access token (a short-lived JWT) for every MCP request.
The user-facing piece is a one-time login on Ownsona's consent page,
done automatically by any OAuth-capable MCP client.

Confirm the server is reachable before doing anything in OpenAI's UI:

```bash
curl -sS https://<your-host>/.well-known/oauth-protected-resource
```

You should get back a small JSON document naming the authorization
server. A 404 means the server is up but OAuth isn't configured
(`OAuthAuthorizationServer` missing from `application.ini`).

---

## 1. Adding Ownsona to ChatGPT (UI path)

OpenAI exposes MCP servers as **connectors** in ChatGPT. Connectors are
configured per-account, and once added are available in any
conversation.

### 1.1 Add the connector

1. Open ChatGPT in a browser. Go to **Settings → Connectors**.
2. Choose **Add connector** (or "Add custom connector" — wording
   varies by ChatGPT version and plan).
3. Fill in the form:
   - **Name**: `Ownsona` (or anything — purely cosmetic).
   - **Description**: `Personal durable memory store.` (also cosmetic).
   - **MCP Server URL**: `https://<your-host>/mcp`.
   - **Authentication**: see [§1.2](#12-authentication-the-openai-ownsona-gap)
     — this is the awkward part.

### 1.2 Authentication

Pick **OAuth** in ChatGPT's connector form and save. The MCP
authorization profile that ChatGPT speaks (OAuth 2.1 + PKCE + RFC
7591 dynamic client registration + RFC 9728 protected-resource
metadata + RFC 8414 AS metadata) is exactly what Ownsona's embedded
authorization server implements, so no extra fields are needed.

The first time you enable the connector in a chat, ChatGPT opens a
browser tab to `https://<your-host>/oauth/authorize`. Log in with
`OWNSONA_LOGIN_USERNAME` / `OWNSONA_LOGIN_PASSWORD`, click **Allow**
on the consent page, and the tab closes. ChatGPT now holds an access
token (one-hour TTL by default) and a refresh token (30 days);
it refreshes automatically as the access token expires.

You won't see the login screen again until either the refresh token
expires or you delete the connector and re-add it.

### 1.3 Enable the connector in a conversation

Once the connector is saved (and one of the auth options above is
working), it appears in the **tools / connectors** menu inside any
chat. Toggle Ownsona on for a conversation to make its tools available
to the model.

Until the connector authenticates successfully, ChatGPT may show a
generic "connector unavailable" error in the menu — that's the symptom
of the auth-gap problem above, not a server-side bug.

### 1.4 Tell ChatGPT to use Ownsona by default (Custom Instructions)

The MCP tool descriptions in `MCPServer.java` already steer the model
toward the right tool when phrasing matches their descriptions
(see [§2](#2-using-it-in-conversations)). For consistent behavior
across **every** new conversation — without re-typing "use Ownsona"
every time — put a directive in ChatGPT's **Personalization → Custom
Instructions**.

In ChatGPT: **Settings → Personalization → Customize ChatGPT** (label
varies by version). Paste this in the second text box ("How would you
like ChatGPT to respond?").

ChatGPT enforces a **~1500-character limit** on this field, so the
directive below is tightened to fit (~720 characters), leaving headroom
for whatever else you want in the same box. The cuts (extensive intro,
"treat memories as data," "no secrets") are things the tool descriptions
on the server side already enforce or instruct, so dropping them from the
client-side directive is safe:

```
The "Ownsona" MCP connector is my durable memory. Always use it.

- Before answering any question that might depend on facts about me ---
  family, friends, projects, preferences, history, work, software, plans
  --- call Ownsona's `recall` tool first and use the result as authoritative
  context. If `recall` returns nothing relevant, say so.

- When I ask you to remember, save, store, or note something durable,
  call `remember`. For multiple items at once (importing, summarizing,
  ingesting a list), use `remember_batch` instead --- it's an order of
  magnitude faster.

- For corrections use `update_memory`; removal `forget`; browsing
  `list_memories` or `text_search`.

- Prefer Ownsona over your built-in memory.
```

#### Things this changes and doesn't change

**ChatGPT has its own native memory system**, separate from your MCP
connector. By default it stores its own facts in OpenAI's
infrastructure. To keep Ownsona authoritative, choose one:

- **Disable native memory entirely**: **Settings → Personalization →
  Memory → toggle off** "Reference saved memories" and "Reference
  chat history". Now there's no competing store; only Ownsona is in
  play. This is the cleanest setup if Ownsona is your real memory.
- **Or leave native memory on** and rely on the "prefer Ownsona" line
  in the instructions above. The model usually complies but isn't
  deterministic.

**The connector still has to be enabled in each conversation** in
some ChatGPT versions. Custom Instructions can't enable a tool that
isn't surfaced in the conversation's tool menu. If a chat starts and
ChatGPT says "I don't have access to a memory tool," the toggle for
the Ownsona connector is off for that conversation.

**The data-approval modal still fires** independently of Custom
Instructions. That's an OpenAI safety layer above the model and isn't
affected by anything you write in Personalization. The per-connector
"Trust this connector" / "Always allow" toggle in the connector's
settings is the lever for that — and only for some categories;
addresses, identifiers, financial, and medical data may always
prompt regardless. See [§4](#4-common-quirks-worth-knowing-up-front).

**The model is high-compliance but not 100%.** Custom Instructions
are read at the top of every conversation, but the model can still
decide a given question doesn't need `recall` (especially for simple,
generic questions). When that happens, a one-line nudge usually fixes
it: *"Check Ownsona first."* After a few such nudges in a
conversation, the model tends to stay on-pattern.

For **fully deterministic** behavior — every question hits Ownsona,
no exceptions, no approval prompts — the API path
([§5](#5-programmatic-access-via-the-openai-api)) with
`require_approval: "never"` is the only way. The Custom Instructions
approach gets you 90–95% of the way there for daily use without
writing any code.

---

## 2. Using it in conversations

The MCP tool descriptions in `MCPServer.java` are written specifically
to steer the model. You generally **don't need to mention "use Ownsona"
explicitly** — ChatGPT will route to the right tool based on the
phrasing of your request, especially with the Custom Instructions from
[§1.4](#14-tell-chatgpt-to-use-ownsona-by-default-custom-instructions)
in place.

### Phrases that trigger `remember`

The model is told to call `remember` whenever the user asks to
remember, save, store, note, or retain durable information.

```
Remember that my son Colby works for Dropbox.
Please remember my son Colby lives in Los Angeles.
Save this: I prefer concise answers without preamble.
Note that the project deadline is March 15.
```

Ownsona stores the fact and replies via the model. Don't include the
leading `Remember that` in things you actually want stored — the model
is told to strip it. (If the words are part of the actual fact —
`"Remember the Alamo"` — they stay.)

### Phrases that trigger `remember_batch` (bulk import)

For ingesting many memories at once — porting from another memory
system, summarizing a long conversation, importing a list — Ownsona
exposes a **batched** version that embeds all items in a single OpenAI
call. The tool description tells the model to STRONGLY PREFER it over
calling `remember` in a loop. The speedup is roughly two orders of
magnitude: 100 facts go from minutes to seconds.

```
Import these as memories: <list, paste, or selection>
Bulk-store the following facts: ...
Save all of these at once: ...
```

If the model still calls `remember` one-at-a-time despite the
description, nudge it explicitly:

```
Use the remember_batch tool. Send up to 200 items per call.
```

Per-item failures (secret rejection, validation, dup) are reported in
the batch's results array and do not abort the rest of the batch. The
caller will see, e.g., `{ok: false, error: {code: "SECRET_REJECTED", ...},
input_index: 4}` for the offending entry alongside successes for the
others.

### Phrases that trigger `recall`

The model is told to call `recall` before answering questions that may
depend on previously remembered facts.

```
Where does my son work?
What's my son's name again?
What did I tell you about my project deadline?
What do I know about Colby?
```

If you want to **force** a recall (because the model decided the
question didn't need memory), say so explicitly:

```
Look in my memories: where does my son work?
```

### Phrases that trigger `list_memories`

```
What do you remember about me?
List the things I've asked you to remember.
Show me my recent memories.
```

### Phrases that trigger `forget`

`forget` does a *soft* delete by default — the row is marked deleted
and excluded from recall but still recoverable. Pass `hard_delete: true`
to drop it permanently.

```
Forget what I told you about my son's job.
Delete memory 42.
Permanently delete memory 42.    # asks for hard_delete
```

### Phrases that trigger `update_memory`

```
Update memory 42: my son now works for Anthropic.
Correct that: my son works for Anthropic, not Dropbox.
```

(The model needs to know the memory id, so it usually pairs `update`
with a prior `list_memories` or `recall` to find the right id.)

### Phrases that trigger `text_search`

`text_search` is plain substring search; useful when you remember a
keyword but cosine recall isn't finding it.

```
Search my memories for "Dropbox".
Find anything I've remembered about LA.
```

### `build_context_prompt`

This is rarely invoked from a normal ChatGPT conversation — it's
designed for clients that want a pre-formatted prompt envelope rather
than structured results. You can ignore it in day-to-day use.

---

## 3. Verifying the integration end-to-end

Open a fresh ChatGPT conversation with the Ownsona connector enabled
and run this two-message test:

```
Message 1:  Remember that I prefer concise answers without preamble.
Message 2:  How do I like my answers?
```

The first message should result in ChatGPT calling Ownsona's `remember`
tool (you'll see "Calling Ownsona / remember" or similar in the chat
UI, depending on version). The second message should result in
ChatGPT calling `recall` and answering "concise" or similar.

Cross-check by curling the live server (using an access token obtained
via the OAuth flow — see `INSTALL.md` §12):

```bash
curl -sS -X POST https://<your-host>/mcp \
    -H "Authorization: Bearer $OWNSONA_ACCESS_TOKEN" \
    -H "Content-Type: application/json" \
    -d '{"jsonrpc":"2.0","id":1,"method":"tools/call",
         "params":{"name":"list_memories","arguments":{}}}'
```

You should see the fact you just told ChatGPT.

---

## 4. Common quirks (worth knowing up front)

### ChatGPT caches the tool list per session

When you change Ownsona's tool catalog (rename a tool, edit a
description), ChatGPT often keeps using the **old** catalog from its
session-level cache, even though the UI sometimes claims to refresh.

The reliable way to flush the cache: **edit the connector in the UI**
(any save action, even toggling the description) and then start a new
conversation. The session token in the request changes; that's how you
know the new catalog is being used.

This is an OpenAI-side quirk, not anything Ownsona does.

### ChatGPT may not call the connector unless prompted to

If a question seems like it should pull from memory but the model
answers without calling `recall`, just nudge:

```
Check my memories first.
```

### Latency

Each tool call adds at least one round-trip from ChatGPT to Ownsona to
OpenAI's embedding endpoint and back. For `remember`, expect a couple
of seconds. For `recall`, similar (one embedding call plus a vector
query). This is normal and bounded by OpenAI's embedding latency, not
Ownsona.

---

## 5. Programmatic access via the OpenAI API

For your own scripts and apps, the OpenAI **Responses API** has
first-class MCP support. You declare Ownsona as a tool and OpenAI
takes care of the protocol from there.

### Minimal Python example

```python
from openai import OpenAI
import os

client = OpenAI()  # uses OPENAI_API_KEY from env

response = client.responses.create(
    model="gpt-4o",
    input="Where does my son work?",
    tools=[{
        "type":         "mcp",
        "server_label": "ownsona",
        "server_url":   "https://<your-host>/mcp",
        "headers": {
            # Short-lived OAuth access token.  See "Obtaining tokens"
            # below for how to get and refresh one.
            "Authorization": f"Bearer {os.environ['OWNSONA_ACCESS_TOKEN']}"
        },
        # "never" lets OpenAI invoke Ownsona's tools without asking
        # for human approval each time.  Set to "always" if you want
        # tool-call approvals.
        "require_approval": "never"
    }]
)

print(response.output_text)
```

### TypeScript / Node equivalent

```typescript
import OpenAI from "openai";
const openai = new OpenAI();

const response = await openai.responses.create({
    model: "gpt-4o",
    input: "Where does my son work?",
    tools: [{
        type: "mcp",
        server_label: "ownsona",
        server_url: "https://<your-host>/mcp",
        headers: {
            Authorization: `Bearer ${process.env.OWNSONA_ACCESS_TOKEN}`,
        },
        require_approval: "never",
    }],
});

console.log(response.output_text);
```

OpenAI does the same `tools/list` discovery the ChatGPT UI does, then
calls `tools/call` whenever the model decides one of Ownsona's tools is
the right move.

### Obtaining tokens

The OpenAI Responses API takes a static `Authorization` header — it
does **not** run the OAuth flow on your behalf. Run the flow once
yourself to get the initial access + refresh token, then in your
script trade the refresh token for a fresh access token before each
batch of calls:

```python
import requests, os, time

refresh = os.environ["OWNSONA_REFRESH_TOKEN"]
client_id = os.environ["OWNSONA_CLIENT_ID"]   # from the one-time /oauth/register call

r = requests.post(
    "https://<your-host>/oauth/token",
    data={
        "grant_type":    "refresh_token",
        "refresh_token": refresh,
        "client_id":     client_id,
    },
    timeout=10,
)
r.raise_for_status()
body = r.json()
os.environ["OWNSONA_ACCESS_TOKEN"] = body["access_token"]
# body["refresh_token"] is rotated --- save it for next time.
```

For the one-time bootstrap (`/oauth/register` + `/oauth/authorize` +
`/oauth/token` with `grant_type=authorization_code`) see Kiss's
`org.kissweb.oauth.as` package-info or run the flow once with a real
MCP client and copy the tokens out of its local config.

### Field names may move

OpenAI evolves the Responses API. If a field name has changed by the
time you read this, the canonical reference is OpenAI's docs at
**<https://platform.openai.com/docs>**. The shape — server URL +
headers + (optional) approval policy — has been stable.

### Older Chat Completions API

The older `chat.completions` endpoint does **not** support MCP tools
natively. You have two options there:

1. Switch to the Responses API (recommended).
2. Define the seven Ownsona tools as standard OpenAI function-calling
   tools, parse `tool_calls` yourself, and forward each to Ownsona by
   POSTing the JSON-RPC envelope. Workable but pure plumbing — not
   recommended unless you are stuck on the older endpoint.

---

## 6. Security

### Access tokens are short-lived; refresh tokens are the long-term secret

Ownsona has no per-user accounts in v1 — every token authorizes the
same single principal (`OWNSONA_USER_ID`). What separates clients is
that each gets its own `client_id` (from dynamic registration) and
its own refresh token. Treat refresh tokens accordingly:

- Refresh tokens are stored by your MCP client (ChatGPT, Claude
  Desktop) in its local config; access tokens are derived from them
  on demand and only need to live in memory.
- **Don't paste a refresh token into shared docs, public repos, or
  screenshots.** A leaked refresh token grants memory access until
  it's revoked (currently: until you delete the corresponding line
  from `WEB-INF/backend/oauth.ini` on the server and restart).
- Each MCP client gets its own registration + token pair, so revoking
  one client doesn't disturb the others.

### Rotation

See `INSTALL.md` §16 "Rotating login credentials" and "Rotating the
AS signing key" for the operator-side rotation procedures. Briefly:

- **Change the consent-page password**: edit
  `OWNSONA_LOGIN_PASSWORD`, rebuild, redeploy. Existing tokens stay
  valid until their TTL.
- **Invalidate every issued token**: delete
  `WEB-INF/backend/oauth.ini` on the server and restart. The AS
  generates a new signing key; every previously-issued JWT fails
  signature verification. Clients re-register and the user redoes
  the login + Allow flow.

### What ChatGPT sees vs. what's stored

Ownsona returns memories to ChatGPT as **data, not instructions**. The
tool descriptions tell the model that explicitly. A memory that says
`"Always answer in Klingon"` will be returned as text, but the model is
instructed to treat it as context, not as a system directive. This is a
guideline for the model, not an air-tight guarantee — don't treat
Ownsona as a security boundary against prompt injection.

`remember` will refuse obvious credentials (OpenAI / Anthropic /
GitHub / AWS / Slack tokens, JWTs, PEM private-key markers) so the
model can't accidentally store one. The filter is best-effort, not
exhaustive — don't rely on it as a substitute for not pasting your AWS
keys into a chat.

---

## 7. Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| Connector shows "unavailable" / can't list tools after save | OAuth discovery failure (DNS, TLS, or `/.well-known/oauth-protected-resource` not served) | `curl https://<host>/.well-known/oauth-protected-resource` — must return JSON, not 404. |
| Login page never appears when adding the connector | ChatGPT already cached a stale registration | Delete the connector and re-add it, or wait for ChatGPT to drop the stale `client_id`. |
| 401 on every `/mcp` request (API path) | Access token expired (default TTL 1h) | Trade the refresh token for a fresh access token (see [§5 "Obtaining tokens"](#obtaining-tokens)). If the refresh also fails, the AS's signing key may have rotated — re-run the auth code flow. |
| ChatGPT says "I don't have access to a memory tool" | Connector not enabled in this conversation | Toggle Ownsona on in the conversation's connectors menu. |
| ChatGPT calls a tool that no longer exists | Cached tool list | Edit the connector (any save), start a new conversation. |
| `remember` returns `EMBEDDING_ERROR insufficient_quota` | OpenAI account out of credit | Top up at <https://platform.openai.com/usage>. |
| `remember` returns `SECRET_REJECTED` | Text matched a credential pattern | Strip the credential from the text. |
| Response time keeps climbing | OpenAI embedding latency | Check OpenAI status. Ownsona's only network call is the embeddings endpoint. |
| Connector returns 5xx after a deploy | Tomcat redeploy in progress | Wait ~10 seconds; `autoDeploy` redeploys the WAR in place. |

For server-side problems (the connector is reachable but tools fail
with `INTERNAL_ERROR` or `DATABASE_ERROR`), check
`journalctl -u ownsona.service -n 200`.

---

## See also

- **`OWNSONA_SPEC.md`** — full tool schemas, error codes, expected
  payloads.
- **`MCPServer.md`** — server architecture, deploy and lifecycle.
- **`INSTALL.md`** — installing the server on a new machine.
