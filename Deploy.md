# Deploying the OAuth 2.1 Build of OwnSona

This document is a focused step-by-step guide for the **one-time migration**
from the old bearer-token build of OwnSona to the new OAuth 2.1 build, on
both the cloud server and the LLM clients that consume it.

It assumes:

- OwnSona is already running on a cloud VPS under systemd at
  `/home/ownsona/ownsona`, with Tomcat at `/home/ownsona/tomcat`, exactly
  as `INSTALL.md` describes.
- You have shell access to that VPS as a user with `sudo`.
- You know how to remove a custom MCP connector from each LLM client you
  use (Claude.ai, Claude Desktop, ChatGPT, Grok, etc.).
- You have local clones of the OwnSona repo where you do code work,
  plus the deployed working copy on the server.

It does *not* cover fresh installs; for those, follow `INSTALL.md`.

---

## Contents

1. [What is changing](#1-what-is-changing)
2. [Pre-flight checklist](#2-pre-flight-checklist)
3. [Server upgrade](#3-server-upgrade)
   - [3.1 Take a backup](#31-take-a-backup)
   - [3.2 Pull the new code](#32-pull-the-new-code)
   - [3.3 Apply the migrator prep (one-time, if not already done)](#33-apply-the-migrator-prep-one-time-if-not-already-done)
   - [3.4 Edit `application.ini`](#34-edit-applicationini)
   - [3.5 Build and deploy the new WAR](#35-build-and-deploy-the-new-war)
   - [3.6 Verify the server came up clean](#36-verify-the-server-came-up-clean)
   - [3.7 Confirm OAuth discovery + auth wall](#37-confirm-oauth-discovery--auth-wall)
4. [LLM client redeployment](#4-llm-client-redeployment)
   - [4.1 Remove the old connector](#41-remove-the-old-connector)
   - [4.2 Add the new OAuth connector](#42-add-the-new-oauth-connector)
   - [4.3 First-time login flow](#43-first-time-login-flow)
   - [4.4 Per-client notes](#44-per-client-notes)
5. [End-to-end verification](#5-end-to-end-verification)
6. [Rollback](#6-rollback)
7. [What to expect afterwards](#7-what-to-expect-afterwards)

---

## 1. What is changing

| Aspect | Old build | New build |
|---|---|---|
| Authentication | Static bearer token (`OWNSONA_API_TOKEN` in `application.ini`, sent as `Authorization: Bearer <token>` or via `?token=` URL param) | OAuth 2.1 auth code + PKCE, JWT access tokens, refresh tokens, dynamic client registration |
| Server role | Resource server only | Resource server **and** embedded authorization server, both in one WAR |
| LLM client config | Paste the static token into each client | Paste the `/mcp` URL into each client; OAuth flow handles the rest |
| Token rotation | Manual (regenerate, re-paste everywhere) | Automatic via refresh tokens |
| DB schema | Whatever your current version is | Strictly additive migrations applied automatically at startup |
| Stored memories | preserved | preserved |
| `EMBEDDING_*` keys | unchanged | unchanged |
| Database, Tomcat, TLS cert, systemd service | unchanged | unchanged |

The migration is therefore mostly: change the auth config, rebuild, swap
the WAR, redo each client's connector.

---

## 2. Pre-flight checklist

Confirm each item before touching the server.

- **You can reach the VPS over SSH** as a sudoer.
- **You know the public host** the LLM clients dial out to
  (e.g. `https://ownsona.example.com`). This becomes
  `OAuthAuthorizationServer`. It must be the **exact same URL** the
  clients use — token `iss` and `aud` claims are checked against it.
- **The TLS cert at that host is valid.** OAuth clients refuse to follow
  redirects to invalid HTTPS; certbot must be running and the cert not
  expired.
- **You have decided on a login username and password** for the AS
  consent page. These go in `application.ini` as
  `OWNSONA_LOGIN_USERNAME` / `OWNSONA_LOGIN_PASSWORD`. They are:
  - invented by you, not tied to any external account (not OpenAI, not
    Anthropic, not your Unix login);
  - entered only in the browser tab that an LLM client opens during the
    first OAuth flow;
  - rotatable later by editing the ini and redeploying.
- **You can decrypt the OpenAI embeddings key from your local
  `application.ini`** so you can transcribe it into the new one if
  needed. (It is unchanged from the old build; you only need it if your
  current server's `application.ini` got lost.)
- **You have an LLM client at hand** (Claude.ai, Claude Desktop,
  ChatGPT, etc.) to drive the first OAuth login through after the new
  WAR is up — that is the simplest way to obtain a working access
  token.

---

## 3. Server upgrade

All steps run as the `ownsona` user on the VPS unless prefixed with
`sudo`.

```bash
ssh <vps-host>
sudo -iu ownsona
cd ~/ownsona              # the deployed working copy of the repo
```

### 3.1 Take a backup

If `ownsona-backup.timer` is installed and you trust its last run, run
it once on demand to capture a fresh snapshot just before the change:

```bash
sudo systemctl start ownsona-backup.service
sudo tail -n 50 /var/log/ownsona-backup.log
```

If you do *not* have the timer wired up, take a quick manual dump
(uses your `application.ini` DB credentials):

```bash
DB_PW=$(awk -F'= *' '/^DatabasePassword/ {print $2}' src/main/backend/application.ini)
PGPASSWORD="$DB_PW" pg_dump -h localhost -U ownsona ownsona \
    | gzip > ~/ownsona-pre-oauth-$(date +%Y%m%d-%H%M%S).sql.gz
ls -lh ~/ownsona-pre-oauth-*.sql.gz
```

Also stash the *current* deployed WAR and the current `application.ini`
so you have a known-good rollback artifact:

```bash
cp /home/ownsona/tomcat/webapps/ROOT.war ~/ROOT.war.pre-oauth
cp src/main/backend/application.ini      ~/application.ini.pre-oauth
chmod 600 ~/application.ini.pre-oauth
```

### 3.2 Pull the new code

```bash
cd /home/ownsona/ownsona
git fetch --all --tags
git status                # confirm clean working tree on the server
git pull --ff-only        # bring main up to the OAuth release commit
git log --oneline -5      # spot-check the latest commits
```

The commit history should show, near the top, the four OAuth-related
commits (`OAuth 2.1: drop static token, ...`,
`oauth: discover JWKS via RFC 8414 metadata...`,
`docs: simplify OAuth config to a single OAuthAuthorizationServer entry`,
and `Upgrade Kiss`). If they are not there, you have the wrong remote
or branch — stop and fix that before going further.

### 3.3 Apply the migrator prep (one-time, if not already done)

If your database was created **before** the auto-migration framework
landed (rollout Phase 2), the application role needs two grants the
original `001_init.sql` did not include. The auto-migrator will fail at
startup without them.

To check whether you have already done this:

```bash
sudo -u postgres psql -d ownsona -c "
SELECT has_schema_privilege('ownsona', 'public', 'CREATE') AS can_create,
       pg_get_userbyid(relowner)                          AS memories_owner
  FROM pg_class WHERE relname = 'memories';"
```

If `can_create = t` *and* `memories_owner = ownsona`, skip this step.

Otherwise:

```bash
cd /home/ownsona/ownsona
cat sql/migrator_prep.sql | sudo -u postgres psql -d ownsona
```

The script is idempotent and prints a verification block at the end.

### 3.4 Edit `application.ini`

The single biggest config change. Open the deployed copy:

```bash
${EDITOR:-vi} /home/ownsona/ownsona/src/main/backend/application.ini
```

Do all of the following:

1. **Remove any line beginning with `OWNSONA_API_TOKEN`.** This is the
   old static bearer token. The new server will refuse to start if
   the line is left in place *only* if you put it under a misspelling
   that matches a required key — but the cleanest thing is to delete
   it outright. Same for any `MCP_BEARER_TOKEN` or commented
   `?token=` reference if you customized those.

2. **Add the AS consent-page login credentials** (invent these — they
   are unrelated to any external account):

   ```ini
   OWNSONA_LOGIN_USERNAME = <pick a username>
   OWNSONA_LOGIN_PASSWORD = <pick a strong password>
   ```

3. **Add the OAuth section.** A standard single-host deployment needs
   exactly three keys:

   ```ini
   # OAuth 2.1 (resource server + embedded authorization server).
   # OAuthAuthorizationServer is the single URL that drives everything:
   # resource identifier, AS issuer, and JWKS URI all derive from it.
   OAuthAuthorizationServer = https://<your-host>
   OAuthAsEnabled           = true

   # Persist the AS signing key + registered clients + refresh tokens
   # OUTSIDE the Tomcat webapps tree.  Without this line, the file
   # defaults to WEB-INF/backend/oauth.ini --- which is rewritten on
   # every WAR redeploy, silently rotating the AS signing key and
   # forcing every LLM client through the browser OAuth flow again.
   # Pick any absolute path the service user can write to (see step
   # 3.4a below).
   OAuthAsIniFile = /home/ownsona/oauth.ini
   ```

   Replace `<your-host>` with the public host of your VPS (e.g.
   `ownsona.example.com`). Include `https://`. **Do not include a
   trailing slash** — the validator trims it but matching is easier
   to reason about when the configured value is canonical.

   Replace `/home/ownsona/oauth.ini` with whatever absolute path you
   chose in step 3.4a.

4. **Leave the existing `EMBEDDING_*` keys alone.** They are still
   required and unchanged from the old build.

5. **Leave the `Database*` keys alone.** Unchanged from the old build.

6. **Save the file.** Confirm it is still mode `600`:

   ```bash
   ls -l src/main/backend/application.ini
   # -rw------- 1 ownsona ownsona ...
   ```

Optional knobs you can leave at their defaults (all default sensibly if
omitted; see `INSTALL.md` §10 and `src/main/backend/application.ini.example`
for the full list):

```ini
# OAuthAccessTokenTtlSeconds   = 3600
# OAuthRefreshTokenTtlSeconds  = 2592000
# OAuthAllowDynamicRegistration = true
# OAuthRequiredScopes          =
```

### 3.4a Choose a location for `oauth.ini`

The AS persistence file holds the master signing key and every
registered MCP client. It is rewritten at runtime as new clients
register and refresh tokens rotate. Two reasons to put it outside the
deployed webapp:

- **Survives redeploys.** The default location `WEB-INF/backend/oauth.ini`
  is overwritten every time you `cp work/Kiss.war ROOT.war`, because
  Tomcat re-extracts the WAR over the existing directory. Every redeploy
  silently rotates the AS signing key → every issued access token
  fails verification → every LLM client gets bounced back through the
  browser OAuth flow.
- **Backup-friendly.** A predictable absolute path is easy to add to
  your existing backup policy.

Pick **any** absolute path that meets these requirements:

- It is outside `/home/ownsona/tomcat/webapps/`.
- The directory exists and is writable by the service user (`ownsona`).
- It is included in your backups going forward (the file holds the
  AS's master signing secret).

Common choices:

| Path | Why you might pick it |
|---|---|
| `/home/ownsona/oauth.ini` | Service user's home directory; no extra setup needed (the directory already exists and is owned by `ownsona`). |
| `/var/lib/ownsona/oauth.ini` | Conventional Linux state-file location; needs a one-time `sudo install -d -o ownsona -g ownsona -m 700 /var/lib/ownsona`. |
| `/etc/ownsona/oauth.ini` | If you treat it as host configuration; same one-time `install -d` step. |

If you go with the simplest option:

```bash
# Nothing to do --- /home/ownsona/ is already owned by ownsona:ownsona.
# The file itself is created automatically on the first AS request.
```

For any other path, create the directory once with the service user as
owner:

```bash
sudo install -d -o ownsona -g ownsona -m 700 /var/lib/ownsona   # adapt as needed
```

Record whatever path you chose; you'll paste it into `application.ini`
as `OAuthAsIniFile = <that path>` in step 3.4.

If you skip this step entirely (leave `OAuthAsIniFile` unset), the AS
falls back to `WEB-INF/backend/oauth.ini`. That works, but every
redeploy will reset OAuth state — fine for short-lived dev installs,
inadvisable for anything you don't want to re-authorize from every
client every time you ship.

### 3.5 Build and deploy the new WAR

The build embeds `application.ini` into the WAR. It deliberately
**excludes** `oauth.ini` (the AS signing key + registered client + refresh
token store), so any state the AS later writes lives only on the deployed
side and is not clobbered by a future redeploy.

```bash
cd /home/ownsona/ownsona
./bld -v build           # compile core + precompiled + tests
./bld war                # produce work/Kiss.war
ls -l work/Kiss.war      # sanity-check the timestamp
```

Hand the WAR to Tomcat (autoDeploy explodes it in ~10 seconds; no
service restart is needed for code/config changes, but watch the logs
to be sure):

```bash
cp work/Kiss.war /home/ownsona/tomcat/webapps/ROOT.war
```

### 3.6 Verify the server came up clean

In another shell on the same host:

```bash
sudo journalctl -u ownsona.service -f
```

Look for, in order:

1. The OwnSona startup banner with `server=ownsona-mcp version=1.0.0`
   and the embedding model/dim line.
2. One or more `migrator: applied v=N name="..."` lines, then
   `migrator: at current version`.
3. `record_migrator: done upgraded=... failed=0`.

If you see a stack trace from the static initializer, the servlet
refused to load — typically:

- a required key in `application.ini` is missing (the message names
  the key);
- DB grants are missing (re-check §3.3);
- the AS could not write to `WEB-INF/backend/` (run
  `sudo ls -ld /home/ownsona/tomcat/webapps/ROOT/WEB-INF/backend` and
  confirm it is owned by `ownsona`).

Stop the tail with `Ctrl-C` once you see the migrator finish and the
startup banner.

### 3.7 Confirm OAuth discovery + auth wall

From any host that can reach the public URL (your laptop is fine):

```bash
# 1. Protected-resource metadata advertises the AS.
curl -sS https://<your-host>/.well-known/oauth-protected-resource | jq .

# 2. AS metadata advertises the issuer + endpoints.
curl -sS https://<your-host>/.well-known/oauth-authorization-server | jq .

# 3. JWKS is reachable.
curl -sS https://<your-host>/oauth/jwks | jq .

# 4. Unauthenticated /mcp returns 401 with a proper challenge header.
curl -sS -i -X POST https://<your-host>/mcp \
    -H "Content-Type: application/json" \
    -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}'
```

The fourth call must respond `401`, with a `WWW-Authenticate: Bearer
realm="...", resource_metadata="..."` header. If it returns 200, the
OAuth resource-server is not gating requests — almost always because
`OAuthAuthorizationServer` was left blank in step 3.4.

If all four pass, **the server is ready.** Move on to the clients.

---

## 4. LLM client redeployment

For each LLM you used the old OwnSona from, you redo the connector
once. The work is small per client; the only thing that matters is
that you pick the OAuth mode and *do not* paste the old static token
anywhere.

### 4.1 Remove the old connector

Use the procedure you already know for each client. Each one stores
your old static bearer token; you want it gone so a future
"reconnect" doesn't try to reuse it.

When in doubt, look for the connector entry whose URL is your
`<your-host>/mcp` and delete it.

### 4.2 Add the new OAuth connector

In every modern OAuth-capable client, the **only** field you fill in
is the MCP server URL:

```
https://<your-host>/mcp
```

That single URL is enough because OwnSona advertises everything else
via the discovery endpoints you just verified in §3.7, and accepts
dynamic client registration at `/oauth/register`.

If a client UI shows a "bearer token" or "API key" text field, **leave
it empty**. That field is the legacy non-OAuth path; for OwnSona you
want OAuth.

### 4.3 First-time login flow

The first time the client talks to OwnSona after you add it, the
client will:

1. Discover the AS via `/.well-known/oauth-protected-resource` →
   `/.well-known/oauth-authorization-server`.
2. Register itself dynamically at `/oauth/register` (no input from
   you).
3. Open a browser tab to
   `https://<your-host>/oauth/authorize?...`.

You then:

4. **Enter your `OWNSONA_LOGIN_USERNAME` and `OWNSONA_LOGIN_PASSWORD`**
   from §3.4. These are *not* your OpenAI / Anthropic / Google
   credentials — the page is checking only against your own
   `application.ini`.
5. Read the consent screen (it names the OwnSona memory store) and
   click **Allow**.
6. The browser redirects back to the client, which exchanges the code
   for an access token + refresh token and stores them locally.

From then on, the client refreshes the access token automatically as
the original token approaches expiry. You will not see the browser
flow again unless:

- you revoke the refresh token, or
- the AS signing key rotates (e.g. you delete the AS state file at
  `OAuthAsIniFile`), or
- the refresh token TTL elapses (30 days by default).

### 4.4 Per-client notes

These are the labels in the current UIs at the time of writing —
vendors rename them periodically, but the **URL is always the only
thing they need**.

| Client | Where to click | What to paste |
|---|---|---|
| **Claude.ai (web)** | Settings → Custom connectors → *Add custom connector* | URL: `https://<your-host>/mcp`. OAuth is the default. |
| **Claude Desktop** | Settings → Connectors → *Add* | URL: `https://<your-host>/mcp`. |
| **ChatGPT** | Settings → Connectors → *Add custom connector* | URL: `https://<your-host>/mcp`. Pick **OAuth** as the auth mode. |
| **Grok / xAI** | Custom MCP server → *Add* | URL: `https://<your-host>/mcp` + OAuth. (Label varies.) |
| **OwnSona CLI** | Edit your `config.ini` to drop the old `token = ...`, then run `ownsona auth login` | The CLI opens the same browser flow and stores its own client_id + refresh token. |
| **Anything else with MCP support** | "Add MCP server" form | URL: `https://<your-host>/mcp`. If it asks for an auth mode, pick OAuth. Leave any "bearer token" field blank. |

---

## 5. End-to-end verification

Once at least one LLM client has finished the OAuth flow, exercise
each path end-to-end.

**Through the LLM:**

- Ask: "What do you remember about me?" — the client should call
  `recall` and return real entries from your store.
- Ask: "Please remember that this is a deployment verification on
  `<today's date>`." — the client should call `remember` and report a
  new memory id.
- Ask: "List my last few memories." — should call `list_memories` and
  include the one you just added.
- Then `forget` that test memory.

**Through curl** (if you want a non-LLM proof):

1. Grab the access token out of whichever client you just authorized
   (each one stores it in its own local config — see your client's
   docs for the path). Export it:

   ```bash
   export OWNSONA_ACCESS_TOKEN="eyJhbGciOiJSUzI1NiIs..."
   ```

2. Run the smoke test:

   ```bash
   /home/ownsona/ownsona/sql/smoke_test.sh https://<your-host>/mcp
   ```

   You should see successful JSON responses for `initialize`,
   `tools/list`, `remember`, `recall`, `list_memories`, and
   `text_search`. The script prints a one-liner cleanup command for
   the smoke-test memory at the end.

A 401 mid-stream means the token is expired (default TTL is 1 hour);
get a fresh one from the client and rerun. If `tools/list` shows the
expected 14 tools and `recall` returns real entries, the server is
fully operational on OAuth.

---

## 6. Rollback

The schema migrations are strictly additive, so the *old* WAR runs
against the *new* schema without complaint. If the new build is
misbehaving and you need to back out:

```bash
# 1. Restore the previous WAR.
cp ~/ROOT.war.pre-oauth /home/ownsona/tomcat/webapps/ROOT.war

# 2. Restore the previous application.ini (it has OWNSONA_API_TOKEN).
cp ~/application.ini.pre-oauth /home/ownsona/ownsona/src/main/backend/application.ini

# 3. Watch the redeploy.
sudo journalctl -u ownsona.service -f
```

After rollback you are back to bearer-token auth and the LLM
clients you reconfigured in §4 will all 401. You then have two
options:

- Re-add each client with the old static token (whatever workflow you
  used previously); or
- Roll forward again once the issue with the new build is fixed.

You generally do **not** need to restore the database from §3.1's
backup unless `journalctl` shows a migrator data corruption — the
new build only writes through the same `MemoryRepository` API the old
build used. Restore the DB only if you know it is needed.

If you want to also drop the columns the auto-migrator added (purely
to keep the schema tidy under the old code), use the rollback SQL
listed in the corresponding phase's ship checklist in
`OwnSona-rollout-plan.md`. This is optional.

---

## 7. What to expect afterwards

- Access tokens expire after `OAuthAccessTokenTtlSeconds` (default
  3600). Clients refresh them silently using the refresh token; you
  do not see this.
- Refresh tokens expire after `OAuthRefreshTokenTtlSeconds` (default
  30 days). At that point the client transparently opens the browser
  again; you re-enter your `OWNSONA_LOGIN_USERNAME` /
  `OWNSONA_LOGIN_PASSWORD` and click Allow.
- The AS state — signing key, dynamically-registered clients, refresh
  tokens — lives at the path you set in `OAuthAsIniFile` (§3.4a). The
  WAR build also excludes the dev `oauth.ini` from the packaged WAR,
  but the absolute-path setting is what guarantees future redeploys
  cannot touch the live AS state.
- To force-invalidate every issued token (e.g. you suspect a leak),
  stop the service, delete the AS state file at `OAuthAsIniFile`, and
  start the service. Each LLM client redoes the full OAuth flow on
  next use.
- To rotate the login password without invalidating any client
  sessions, just change `OWNSONA_LOGIN_PASSWORD` in
  `application.ini`, rebuild, redeploy. The password is consulted
  only by the AS login page; existing tokens stay valid.
- For other routine operational tasks — rotating credentials, key
  rotation, status checks, log paths — see `INSTALL.md` §17
  (*Operational reference*).
