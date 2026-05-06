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
| Bearer token | `OWNSONA_API_TOKEN` from `src/main/backend/application.ini` |
| Eligible OpenAI plan | ChatGPT path: Plus, Pro, Team, or Enterprise. API path: any account with API access. |

The bearer token is what proves to Ownsona that the request came from
you. **Treat it like a password.** Anyone who has it can read and
overwrite your memories.

Confirm the server is reachable before doing anything in OpenAI's UI:

```bash
curl -sS -X POST https://<your-host>/mcp \
    -H "Authorization: Bearer $OWNSONA_API_TOKEN" \
    -H "Content-Type: application/json" \
    -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}'
```

You should get back JSON containing `"name":"ownsona-mcp"`. A 401 means
the token is wrong; anything else means the server isn't healthy and
you should fix that first (see `MCPServer.md`).

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

### 1.2 Authentication: the OpenAI ↔ Ownsona gap

OpenAI's connector form does **not** offer "API Key" or "Bearer
token" as an authentication mode. As of early 2026 the choices are:

| OpenAI mode | What it means | Works with Ownsona v1? |
|---|---|---|
| **No auth** | ChatGPT sends no `Authorization` header. | Only if you also drop server-side auth — see option C below. |
| **OAuth**   | ChatGPT performs an OAuth 2.1 + PKCE authorization-code flow against the MCP server. | No — Ownsona v1 has no OAuth endpoints. |
| **Mixed**   | Hybrid mode. Practical behavior is for ChatGPT to follow what the server signals via the 401 `WWW-Authenticate` header (and possibly to prompt you for a static credential). | Worth trying first — see option B below. |

That mismatch leaves four practical paths:

#### Option A — Skip the ChatGPT UI; use the OpenAI API only

Use Ownsona from your own scripts and apps via the OpenAI Responses
API ([§5](#5-programmatic-access-via-the-openai-api)), where you can
attach `Authorization: Bearer <OWNSONA_API_TOKEN>` directly in the
tool spec. Don't add a ChatGPT connector at all. **This is the
lowest-friction option that keeps the bearer-token security model
intact** and is the recommended path until OAuth lands on Ownsona.

#### Option B — Try **Mixed** first

Pick **Mixed** in the connector form and save. Ownsona returns
`401 Unauthorized` with `WWW-Authenticate: Bearer realm="ownsona"`
on every unauthenticated request, which is the standard signal for
"this resource needs a Bearer token." Some ChatGPT versions follow
that signal and prompt you for a token; if that prompt appears,
paste `OWNSONA_API_TOKEN` and it works. If instead ChatGPT reports
an OAuth discovery failure (because Ownsona has no
`/.well-known/oauth-authorization-server` endpoint), fall back to
options C or D.

This is unverified for any specific ChatGPT version — the UI
evolves. It's a five-minute test, so try it before committing to the
heavier paths.

#### Option C — Run with **No auth** (least secure)

Pick **No auth** in the connector form and relax server-side auth in
Ownsona so unauthenticated requests are allowed:

```java
// MCPServer.authenticate(...)
@Override
protected boolean authenticate(HttpServletRequest req, HttpServletResponse resp) {
    return true;     // anyone who can reach /mcp is allowed
}
```

**Anyone who discovers the URL can read and overwrite your
memories.** Acceptable for short-lived experimentation, not for
production. If you go this route, at minimum:

- Remap the servlet to a hard-to-guess path
  (`@WebServlet(urlPatterns = "/mcp-<random>")`).
- Tail `localhost_access_log` and treat any 200 from an unfamiliar
  IP as a leak.
- Plan to revert to authenticated mode promptly.

#### Option D — Add OAuth to Ownsona (proper fix, future work)

Implement an OAuth 2.1 authorization server endpoint set on Ownsona,
or front Ownsona with an OAuth-capable proxy (Cloudflare Access,
oauth2-proxy, Pomerium). ChatGPT does the OAuth dance with the
proxy / Ownsona, the resulting Bearer token flows through, and
nothing else changes. This is the spec-compliant path; it just isn't
done in v1.

The MCP authorization profile is OAuth 2.1 with PKCE plus optional
dynamic client registration (RFC 7591) and authorization-server
metadata (RFC 8414). A proxy is significantly less work than
implementing those endpoints in Ownsona itself.

#### Option E — "No auth" + token in URL query parameter (recommended for single-user)

Pick **No auth** in the connector form, but include the bearer token
as a `?token=` query parameter in the connector URL:

```
https://ownsona.com/mcp?token=<OWNSONA_API_TOKEN>
```

ChatGPT sends no `Authorization` header (it doesn't expose one), but
the URL with the query string travels on every request. Ownsona's
`MCPServer.authenticate()` looks for the token in the
`Authorization: Bearer …` header **and** falls back to the `token`
query parameter, so this works alongside the API path without
breaking anything.

**Security tradeoff.** A token in a query string is logged by web
servers in places a header is not — by default Tomcat's
`AccessLogValve` writes the full request line including the query.
We mitigate this in `tomcat/conf/server.xml` by changing the
`AccessLogValve` `pattern` to `%m %U %H` (method, URI without query,
protocol) instead of `%r` (full request line). Verify after deploy
that `localhost_access_log.<today>.txt` shows lines like
`POST /mcp HTTP/2.0` with no `token=` substring anywhere.

**The remaining exposure** is human factors: the connector URL
itself is now a secret. Don't paste it into Slack, screenshots, or
shared docs. If you ever do, rotate the token (see
[§6 Security](#6-security)) and update every client.

**Don't choose this path if** you plan to share Ownsona with another
person or let it accept multiple distinct users. There is no per-user
token; everyone authenticates as the same single principal. That's
the trigger for Option D.

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

Cross-check by curling the live server:

```bash
curl -sS -X POST https://<your-host>/mcp \
    -H "Authorization: Bearer $OWNSONA_API_TOKEN" \
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

client = OpenAI()  # uses EMBEDDING_API_KEY from env

response = client.responses.create(
    model="gpt-4o",
    input="Where does my son work?",
    tools=[{
        "type":         "mcp",
        "server_label": "ownsona",
        "server_url":   "https://ownsona.com/mcp",
        "headers": {
            "Authorization": f"Bearer {os.environ['OWNSONA_API_TOKEN']}"
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
        server_url: "https://ownsona.com/mcp",
        headers: {
            Authorization: `Bearer ${process.env.OWNSONA_API_TOKEN}`,
        },
        require_approval: "never",
    }],
});

console.log(response.output_text);
```

OpenAI does the same `tools/list` discovery the ChatGPT UI does, then
calls `tools/call` whenever the model decides one of Ownsona's tools is
the right move.

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

### The bearer token is the whole authentication story

Ownsona has no per-user accounts in v1; the bearer token alone grants
full read/write access to your memory store. Treat it accordingly:

- **Don't paste it into shared docs, public repos, or screenshots.**
  ChatGPT's connector UI and the OpenAI API both transmit it over
  HTTPS; that's fine. Pasting it into a Slack channel is not.
- **Use a different token** for the ChatGPT UI vs. your API scripts if
  you want the option to revoke one without affecting the other.
  v1 supports only one token at a time, but that may change.

### Rotation

To rotate the token:

1. Generate a new value: `openssl rand -hex 32`.
2. Update `OWNSONA_API_TOKEN` in `src/main/backend/application.ini`.
3. Rebuild and redeploy: `./bld -v build && ./bld war && cp work/Kiss.war /home/ownsona/tomcat/webapps/ROOT.war`
   (or restart the service if you prefer).
4. Update every client (the ChatGPT connector, every API script) with
   the new token.

There is no dual-token grace window — clients will see `401
AUTH_FAILED` until they're updated.

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
| Connector shows "unavailable" / can't list tools after save | Auth-mode mismatch (see [§1.2](#12-authentication-the-openai-ownsona-gap)) | Use the API path (Option A), or pick "Mixed" / "No auth" per options B/C. |
| `AUTH_FAILED` on every call (API path) | Token mismatch | Re-check the `Authorization: Bearer ...` header in your tool spec. Cross-check `OWNSONA_API_TOKEN` in `application.ini` (and that the WAR has been rebuilt since the last edit). |
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
