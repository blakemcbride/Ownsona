# Installing Ownsona on a Fresh Ubuntu System

This document walks through a complete install of the Ownsona MCP server on
a virgin Ubuntu 24.04 LTS system. It is the canonical "build from scratch"
procedure; if you have a recent backup, the [Restore from Backup](#restore-from-backup)
section at the end is faster.

Everything in this guide assumes you can `sudo`. Commands shown without
`sudo` should run as the `ownsona` user (created in step 3).

---

## Contents

1. [Architecture summary](#1-architecture-summary)
2. [Prerequisites](#2-prerequisites)
3. [Install OS packages](#3-install-os-packages)
4. [Create the `ownsona` user](#4-create-the-ownsona-user)
5. [PostgreSQL with pgvector](#5-postgresql-with-pgvector)
6. [Apache Tomcat 11](#6-apache-tomcat-11)
7. [TLS certificates](#7-tls-certificates)
8. [Clone the repository and build](#8-clone-the-repository-and-build)
9. [Apply the database migration](#9-apply-the-database-migration)
10. [Configure environment variables](#10-configure-environment-variables)
11. [Install the systemd service](#11-install-the-systemd-service)
12. [Smoke test](#12-smoke-test)
13. [Daily backups (optional but recommended)](#13-daily-backups)
14. [Restore from backup](#14-restore-from-backup)
15. [Upgrading an existing install](#15-upgrading-an-existing-install)
16. [Operational reference](#16-operational-reference)

---

## 1. Architecture summary

Ownsona is a Model Context Protocol (MCP) server exposed at
`https://<your-host>/mcp`. Three processes do the work:

- **Apache Tomcat 11** terminates HTTPS and runs the Kiss-framework webapp.
  The MCP servlet lives at `src/main/precompiled/ai/ownsona/MCPServer.java`
  and is mounted at `/mcp`.
- **PostgreSQL 16** with the `pgvector` and `pg_trgm` extensions stores
  durable memories.
- **OpenAI's `text-embedding-3-small`** turns memory text into the 1536-dim
  vectors that drive recall.

A `systemd` unit (`ownsona.service`) supervises Tomcat. A timer-driven
`ownsona-backup.service` writes daily backups to S3 via `s3fs`.

For background and the per-tool wire format see `OWNSONA_SPEC.md` and
`MCPServer.md`.

---

## 2. Prerequisites

- **Hardware:** 2 GB RAM minimum (1 GB works but is OOM-tight; the
  reference deployment runs with 2 GB total + a 2 GB swap file). 10+ GB
  disk.
- **OS:** Ubuntu 24.04 LTS, fresh install. Other Debian-family releases
  may work but are not tested.
- **Network:** TCP 80 and 443 open inbound. SSH open for administration.
- **DNS:** an A record pointing `<your-host>` (e.g. `ownsona.com`) at the
  VM's public IP.
- **Accounts:**
  - An OpenAI account with billing enabled (the embeddings endpoint is
    pay-per-use).
  - An AWS S3 bucket if you want the daily backups (optional).

---

## 3. Install OS packages

```bash
sudo apt update
sudo apt install -y \
    openjdk-21-jdk-headless \
    postgresql-16 postgresql-16-pgvector \
    git curl ca-certificates \
    s3fs \
    openssl
```

Verify:

```bash
java -version          # 21.x
psql --version         # 16.x
```

The `postgresql-16-pgvector` apt package provides the `vector` extension.
`pg_trgm` ships with PostgreSQL itself.

---

## 4. Create the `ownsona` user

This user owns Tomcat, the Kiss source tree, and the running JVM.

```bash
sudo adduser --disabled-password --gecos '' ownsona
```

Switch to it for the build steps later:

```bash
sudo -iu ownsona
```

(Most of the rest of this document assumes you are `ownsona` unless a
command starts with `sudo`.)

---

## 5. PostgreSQL with pgvector

### 5.1 Confirm PostgreSQL is running

The Ubuntu package starts and enables PostgreSQL automatically:

```bash
sudo systemctl status postgresql
```

### 5.2 Create the application databases

```bash
sudo -u postgres createdb ownsona
sudo -u postgres createdb ownsona_test     # used by sql/run_tests.sh
```

### 5.3 Create the application role and run the schema migration

The repository ships a one-shot setup script. Pass it the password you
want the application to use:

```bash
# Generate a random password (or pick your own):
PGPW="$(openssl rand -base64 24 | tr -d '/+=' | head -c 32)"
echo "Save this somewhere safe: $PGPW"

cd /home/ownsona/ownsona     # see step 8 if you haven't cloned yet
sql/setup_db.sh "$PGPW"
```

The script:

- creates the `ownsona` PostgreSQL role with the supplied password,
- runs `sql/001_init.sql` against the `ownsona` database (creates the
  `vector` and `pg_trgm` extensions, the `memories` table, all indexes
  including the HNSW vector index, the duplicate-prevention partial
  unique index, and the `updated_at` trigger),
- grants the `ownsona` role the table and sequence privileges it needs
  plus `CREATE ON SCHEMA public` and ownership of `memories` (and its
  sequence). The auto-migrator (see section 15) needs these to create
  its `db_version` bookkeeping table and apply future migrations
  without requiring the postgres superuser at runtime.

Run the migration against the test database too:

```bash
sudo -u postgres psql -d ownsona_test -f sql/001_init.sql
```

You will use `$PGPW` again in step 10.

---

## 6. Apache Tomcat 11

### 6.1 Download and extract

Pick the latest Tomcat 11.0.x from <https://tomcat.apache.org/>. As the
`ownsona` user:

```bash
cd /home/ownsona
curl -L -O https://dlcdn.apache.org/tomcat/tomcat-11/v11.0.X/bin/apache-tomcat-11.0.X.tar.gz
tar xf apache-tomcat-11.0.X.tar.gz
mv apache-tomcat-11.0.X tomcat
rm apache-tomcat-11.0.X.tar.gz
```

### 6.2 Heap settings (`tomcat/bin/setenv.sh`)

The heap defaults shipped with Tomcat are too aggressive for a 2 GB VM.
Create or edit `/home/ownsona/tomcat/bin/setenv.sh`:

```bash
#!/bin/sh
# Heap sized for a 2 GB VM. Larger boxes can raise -Xmx.
export CATALINA_OPTS="-Xms256M -Xmx768M -Djava.awt.headless=true \
    -XX:+UseG1GC -XX:+DisableExplicitGC \
    -Djava.library.path=/usr/local/apr/lib"
```

(Application secrets and URLs go in `application.ini` — see step 10.
Don't put them in `setenv.sh`.)

```bash
chmod +x /home/ownsona/tomcat/bin/setenv.sh
```

### 6.3 Configure HTTPS and autoDeploy in `server.xml`

Edit `/home/ownsona/tomcat/conf/server.xml` and make four changes:

1. **HTTP connector on port 80** (Tomcat defaults to 8080):

   ```xml
   <Connector port="80" protocol="HTTP/1.1"
              connectionTimeout="20000"
              redirectPort="443" />
   ```

2. **HTTPS connector on port 443**, with one or more `SSLHostConfig`
   blocks (one per certificate / SNI name):

   ```xml
   <Connector port="443" protocol="org.apache.coyote.http11.Http11NioProtocol"
              maxThreads="150" SSLEnabled="true">
       <UpgradeProtocol className="org.apache.coyote.http2.Http2Protocol" />
       <SSLHostConfig hostName="ownsona.com">
           <Certificate certificateKeystoreFile="conf/tomcat.p12"
                        certificateKeystoreType="PKCS12"
                        certificateKeystorePassword="<keystore-pw>"
                        type="RSA" />
       </SSLHostConfig>
   </Connector>
   ```

   (Add additional `SSLHostConfig` blocks if you serve multiple
   hostnames from the same VM.)

3. **`<Host>` element with autoDeploy and unpackWARs**:

   ```xml
   <Host name="localhost" appBase="webapps"
         unpackWARs="true" autoDeploy="true">
   ```

   Both must be `true`. With `unpackWARs="false"` the Kiss webapp NPEs
   at startup because `ServletContext.getRealPath("/")` returns null.

4. **`AccessLogValve` with retention**:

   ```xml
   <Valve className="org.apache.catalina.valves.AccessLogValve" directory="logs"
          prefix="localhost_access_log" suffix=".txt"
          pattern="%h %l %u %t &quot;%r&quot; %s %b"
          maxDays="90" />
   ```

   Without `maxDays`, daily access log files accumulate forever.

### 6.4 Permissions

Make sure everything under `tomcat/` is owned by `ownsona`:

```bash
sudo chown -R ownsona:ownsona /home/ownsona/tomcat
```

---

## 7. TLS certificates

Tomcat reads PKCS12 keystores. The simplest production path is Let's
Encrypt via certbot, then a one-time conversion:

```bash
sudo apt install -y certbot
sudo certbot certonly --standalone -d <your-host>
# certbot writes /etc/letsencrypt/live/<your-host>/{fullchain,privkey}.pem

sudo openssl pkcs12 -export \
    -in  /etc/letsencrypt/live/<your-host>/fullchain.pem \
    -inkey /etc/letsencrypt/live/<your-host>/privkey.pem \
    -out /home/ownsona/tomcat/conf/tomcat.p12 \
    -name tomcat \
    -password pass:<keystore-pw>

sudo chown ownsona:ownsona /home/ownsona/tomcat/conf/tomcat.p12
sudo chmod 600 /home/ownsona/tomcat/conf/tomcat.p12
```

Use the same `<keystore-pw>` you put in the `SSLHostConfig` block in step 6.3.

Set up a renewal hook so each renewal regenerates the keystore and
restarts Tomcat. As an example,
`/etc/letsencrypt/renewal-hooks/deploy/ownsona-tomcat`:

```bash
#!/bin/sh
set -e
openssl pkcs12 -export \
    -in   "$RENEWED_LINEAGE/fullchain.pem" \
    -inkey "$RENEWED_LINEAGE/privkey.pem" \
    -out  /home/ownsona/tomcat/conf/tomcat.p12 \
    -name tomcat \
    -password pass:<keystore-pw>
chown ownsona:ownsona /home/ownsona/tomcat/conf/tomcat.p12
chmod 600              /home/ownsona/tomcat/conf/tomcat.p12
systemctl restart ownsona.service
```

```bash
sudo chmod +x /etc/letsencrypt/renewal-hooks/deploy/ownsona-tomcat
```

---

## 8. Clone the repository and build

As `ownsona`:

```bash
cd /home/ownsona
git clone <git-url-of-ownsona-repo> ownsona
cd ownsona
./bld -v build       # compiles core + precompiled into work/exploded/
./bld war            # produces work/Kiss.war
```

The build also writes a copy of the WAR to `tomcat/webapps/ROOT.war`
inside the **repo's** local Tomcat directory (`ownsona/tomcat/`), which
is a dev convenience and not used in production. Production deploys
come from `work/Kiss.war`.

---

## 9. Apply the database migration

If you ran `sql/setup_db.sh` in step 5.3 you are already done. Otherwise:

```bash
cd /home/ownsona/ownsona
sql/setup_db.sh "$PGPW"
sudo -u postgres psql -d ownsona_test -f sql/001_init.sql
```

Confirm:

```bash
sudo -u postgres psql -d ownsona -c "\dt"
sudo -u postgres psql -d ownsona -c "SELECT extname FROM pg_extension ORDER BY extname;"
```

You should see the `memories` table and the `pg_trgm`, `plpgsql`, and
`vector` extensions.

---

## 10. Configure application.ini

All application settings — secrets, URLs, DB credentials, tunables — live
in `src/main/backend/application.ini`. The `bld` build copies this file
into the WAR (`work/exploded/WEB-INF/backend/`) on every build; the
servlet reads it once at load via `MainServlet.getEnvironment()`. Edit
the source-tree copy, then rebuild and redeploy.

The repo ships `application.ini.example` (a redacted template) and
gitignores `application.ini` itself, so your live secrets never end up
in commits. First-time setup is a copy + edit:

```bash
cp src/main/backend/application.ini.example src/main/backend/application.ini
$EDITOR src/main/backend/application.ini
```

Required:

```ini
[main]

DatabaseType     = PostgreSQL
DatabaseHost     = localhost
DatabasePort     = 5432
DatabaseName     = ownsona
DatabaseUser     = ownsona
DatabasePassword = <PGPW>

EMBEDDING_API_KEY       = sk-...your-OpenAI-key...
OWNSONA_API_TOKEN    = <openssl rand -hex 32>
EMBEDDING_ENDPOINT   = https://api.openai.com/v1/embeddings
EMBEDDING_MODEL      = text-embedding-3-small
EMBEDDING_DIMENSIONS = 1536
```

Generate the bearer token with `openssl rand -hex 32` and save it
somewhere safe — it's what every MCP client must present in
`Authorization: Bearer ...`.

`EMBEDDING_DIMENSIONS` must match the `vector(N)` column type in
`sql/001_init.sql`. The shipped schema uses `vector(1536)`, which
matches `text-embedding-3-small`.

Optional keys with defaults:

```ini
# OWNSONA_USER_ID      = default
# EMBEDDING_PROVIDER   = openai
# DEFAULT_RECALL_LIMIT = 8
# MAX_RECALL_LIMIT     = 50
# MAX_TEXT_CHARS       = 16000
# MAX_BATCH_SIZE       = 200
```

`tomcat/bin/setenv.sh` is no longer used for application secrets —
keep it only for JVM tuning options like `JAVA_OPTS`.

---

## 11. Install the systemd service

Deploy the production WAR and install the unit. As `ownsona`:

```bash
cp /home/ownsona/ownsona/work/Kiss.war /home/ownsona/tomcat/webapps/ROOT.war
```

Then as root:

```bash
sudo /home/ownsona/ownsona/sql/install_systemd.sh
```

The script:

- stops any manually-launched Tomcat,
- removes the stale `/etc/systemd/system/tomcat.service` if present (a
  preinstalled file from earlier deployments that points at the wrong
  user's Tomcat),
- installs `/etc/systemd/system/ownsona.service` from
  `sql/ownsona.service`,
- runs `daemon-reload`, then `enable --now ownsona.service`.

The unit runs `catalina.sh run` as the `ownsona` user with
`AmbientCapabilities=CAP_NET_BIND_SERVICE`. That capability is granted by
systemd at exec time, which means **`apt`-upgrading openjdk does not
break port-binding** — unlike the older `setcap` path, where every JDK
upgrade silently strips `cap_net_bind_service` from the binary and
breaks the next manual restart.

If you ever need to launch Tomcat by hand for debugging (not
recommended), reapply the cap:

```bash
sudo setcap 'cap_net_bind_service=+ep' /usr/lib/jvm/java-21-openjdk-amd64/bin/java
```

---

## 12. Smoke test

```bash
curl -sS -X POST https://<your-host>/mcp \
    -H "Authorization: Bearer $OWNSONA_API_TOKEN" \
    -H "Content-Type: application/json" \
    -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}'
```

Expected:

```json
{"result":{"capabilities":{"tools":{"listChanged":false}},
 "serverInfo":{"name":"ownsona-mcp","version":"1.0.0"},
 "protocolVersion":"2025-06-18"},"id":1,"jsonrpc":"2.0"}
```

A 401 means the bearer token is wrong. A connection refused/reset
generally means Tomcat failed to bind 443 — check
`journalctl -u ownsona.service`.

End-to-end exercise of every tool:

```bash
OWNSONA_API_TOKEN="..." /home/ownsona/ownsona/sql/smoke_test.sh
```

Run the test suite:

```bash
sql/run_tests.sh                                          # unit only
OWNSONA_TEST_DATABASE_URL="postgresql://ownsona:$PGPW@localhost:5432/ownsona_test" \
    sql/run_tests.sh                                      # also integration
```

---

## 13. Daily backups

Backups go to an S3 bucket mounted at `/mnt/backups` via
`s3fs` and are triggered by a `systemd` timer at 03:00 daily. Two files
per day:

- `files-YYYY-MM-DD.tar.gz` — `/home`, `/root`, `/etc`, `/usr/local`,
  `/opt`, `/var/spool/cron`, plus a `/var/lib/ownsona-backup/` metadata
  bundle (package list, crontabs, enabled units, OS info).
- `database-YYYY-MM-DD.gz` — `pg_dumpall` output (every database **and**
  global state: roles, passwords, GRANTs, tablespaces).

Retention: every backup ≤ 30 days old, plus the last day of every month
forever.

### 13.1 Configure s3fs

Create an IAM user with `s3:GetObject`/`s3:PutObject`/`s3:ListBucket` on
the target bucket. Store its credentials in `/etc/passwd-s3fs`:

```bash
echo "<bucket-name>:<AWS_ACCESS_KEY_ID>:<AWS_SECRET_ACCESS_KEY>" | \
    sudo tee /etc/passwd-s3fs
sudo chmod 600 /etc/passwd-s3fs
```

Create the mount point and a small mount helper:

```bash
sudo mkdir -p /mnt/backups
```

`/etc/mount-s3fs` (root-owned, mode 0700) is a wrapper that mounts the
bucket if it isn't already mounted. A minimal version:

```bash
#!/bin/sh
mountpoint -q /mnt/backups || \
    /usr/bin/s3fs <bucket-name> /mnt/backups \
        -o passwd_file=/etc/passwd-s3fs \
        -o allow_other \
        -o use_path_request_style
```

```bash
sudo chmod 700 /etc/mount-s3fs
```

Test it:

```bash
sudo /etc/mount-s3fs
mountpoint /mnt/backups        # should print "is a mountpoint"
```

### 13.2 Install the backup timer

```bash
sudo /home/ownsona/ownsona/sql/install_backup.sh
```

The installer copies `sql/ownsona-backup.sh` to `/usr/local/sbin/`,
installs `ownsona-backup.{service,timer}` under
`/etc/systemd/system/`, and `enable --now`s the timer. It also
pre-creates `/var/log/ownsona-backup.log` with sane permissions
(`root:adm`, mode 0640).

Run one immediately for today's date instead of waiting for 03:00:

```bash
sudo systemctl start ownsona-backup.service
sudo tail -f /var/log/ownsona-backup.log
```

When the log shows `==== ... ownsona-backup done ====`, verify:

```bash
sudo ls -lh /mnt/backups/
```

### 13.3 What's in `/etc/mount-s3fs` is itself backed up

The file backup includes `/etc/`, so `/etc/mount-s3fs` and
`/etc/passwd-s3fs` are captured. After a full disaster recovery you can
extract `etc/mount-s3fs` from the tarball before mounting — bootstrapping
restore-from-backup off the same backup that contains the bootstrap
script. (You'll need the AWS credentials handy by other means to do the
initial mount, since `/etc/passwd-s3fs` is required to mount `/mnt/backups`
in the first place.)

---

## 14. Restore from backup

If you have a recent backup, **this path is faster than steps 5–11.**

1. **Provision Ubuntu 24.04** (step 2).

2. **Install just enough packages to extract and restore:**

   ```bash
   sudo apt update
   sudo apt install -y openjdk-21-jdk-headless \
       postgresql-16 postgresql-16-pgvector \
       s3fs gzip tar
   ```

3. **Mount the backup bucket.** You will need the AWS credentials by
   other means (password manager, etc.) since `/etc/passwd-s3fs` is in
   the backup itself:

   ```bash
   sudo mkdir -p /mnt/backups
   echo "<bucket>:<KEY>:<SECRET>" | sudo tee /etc/passwd-s3fs
   sudo chmod 600 /etc/passwd-s3fs
   sudo s3fs <bucket> /mnt/backups -o passwd_file=/etc/passwd-s3fs -o allow_other -o use_path_request_style
   ```

4. **Reinstall the previously-managed apt packages** (optional but
   matches the source system):

   ```bash
   sudo tar -xzf /mnt/backups/files-YYYY-MM-DD.tar.gz -C /tmp \
       var/lib/ownsona-backup/apt-mark-manual.txt
   xargs -a /tmp/var/lib/ownsona-backup/apt-mark-manual.txt sudo apt install -y
   ```

5. **Extract the file backup over the root filesystem:**

   ```bash
   sudo tar -xzpf /mnt/backups/files-YYYY-MM-DD.tar.gz -C /
   ```

   This restores `/home`, `/root`, `/etc`, `/usr/local`, `/opt`, the
   per-user crontabs in `/var/spool/cron`, the live systemd units, and
   the metadata bundle.

6. **Restore PostgreSQL** (`pg_dumpall` output drops and recreates
   everything):

   ```bash
   zcat /mnt/backups/database-YYYY-MM-DD.gz | sudo -u postgres psql -X postgres
   ```

7. **Reload systemd and bring services up:**

   ```bash
   sudo systemctl daemon-reload
   sudo systemctl enable --now ownsona.service
   sudo systemctl enable --now ownsona-backup.timer
   ```

8. **Smoke test** (step 12).

The OS-level `ownsona` Linux user is restored along with `/home/ownsona`,
so no manual `adduser` is needed. PostgreSQL roles (including the
`ownsona` role and its password hash) are restored by `pg_dumpall`.

---

## 15. Upgrading an existing install

If your Ownsona database was created BEFORE the auto-migration
framework landed (rollout Phase 2), you need a one-time privilege
fixup before deploying any release that includes the auto-migrator.
Fresh installs done via `sql/setup_db.sh` already include everything
this step does — skip it if you ran `setup_db.sh` against a clean
database after Phase 2 shipped.

### 15.1 One-time prep before the first auto-migrator deploy

The auto-migrator (`DbMigrator`) starts up by creating a `db_version`
bookkeeping table and, in later phases, by `ALTER TABLE`-ing
`memories`. Neither works with only the original grants from
`sql/001_init.sql`. Apply the additional privileges once:

```bash
# /root cannot be read by the postgres user, so pipe the file via stdin
# (or copy it to /tmp first --- either approach works).
cat sql/migrator_prep.sql | sudo -u postgres psql -d ownsona
```

The script:

- `GRANT CREATE ON SCHEMA public TO ownsona;` — lets the migrator
  create the `db_version` table on first startup.
- `ALTER TABLE memories OWNER TO ownsona;` (and the sequence) — lets
  the migrator's future `ALTER TABLE` / `CREATE INDEX` statements
  run as the application role instead of needing the postgres
  superuser at runtime.
- Prints a verification block showing `can_create_in_public = t` and
  `memories_owner = ownsona`. If you don't see those, the grants
  didn't apply.

The script is idempotent — safe to re-run.

### 15.2 Deploying the new WAR

After the prep runs (or if it was already in place), upgrade
deploys follow the normal "build → swap WAR → restart Tomcat"
pattern documented in `OwnSona-rollout-plan.md`. The auto-migrator
runs at servlet load and brings the database up to whatever
`CURRENT_DB_VERSION` the new WAR is built against; you can verify
in `journalctl -u ownsona.service`:

```
... migrator: db_version baseline established (version=1)
... migrator: applied v=2 name="add record_version column" ms=...
... record_migrator: done upgraded=0 failed=0
```

If the migrator fails (typically: permission errors or a registry
mismatch), the servlet refuses to load and the failure shows in
the journal. Fix the underlying issue and redeploy — the database
is in its pre-migration state because each migration runs in its
own transaction.

### 15.3 Rollback

Roll back the WAR by restoring the previous `Kiss.war`. Migrations
in the rollout plan are strictly additive, so the older code
simply ignores the newer columns. If you also want to remove the
newly-created columns or the `db_version` row that recorded a
specific migration, run the rollback SQL listed in the
corresponding phase's ship checklist in
`OwnSona-rollout-plan.md`.

---

## 16. Operational reference

### Service control

```bash
sudo systemctl status   ownsona.service
sudo systemctl restart  ownsona.service     # required after any application.ini change
sudo systemctl stop     ownsona.service
sudo systemctl start    ownsona.service
journalctl -u ownsona.service -f             # live application + Tomcat logs
```

### Code change → deploy

```bash
cd /home/ownsona/ownsona
./bld -v build && ./bld war
cp work/Kiss.war /home/ownsona/tomcat/webapps/ROOT.war
# autoDeploy redeploys in ~10 s; no service restart needed for code changes.
```

`application.ini` is read once at servlet load, so editing it requires
a fresh build (so the new ini lands in the WAR) followed by a redeploy
or service restart.

### Backup control

```bash
sudo systemctl list-timers ownsona-backup.timer       # next run
sudo systemctl start ownsona-backup.service           # one-shot now
sudo tail -f /var/log/ownsona-backup.log              # last run + history
```

### Log retention summary

| Log path                                              | Rotated by             | Retention                   |
|-------------------------------------------------------|------------------------|-----------------------------|
| Application stdout (log4j2 console)                   | journald               | journald defaults (~4 GiB)  |
| `tomcat/logs/catalina.YYYY-MM-DD.log` (and friends)   | Tomcat juli            | `maxDays=90`                |
| `tomcat/logs/localhost_access_log.YYYY-MM-DD.txt`     | `AccessLogValve`       | `maxDays=90`                |
| `tomcat/logs/catalina.out`                            | n/a                    | unused under systemd        |

### Rotating the bearer token

Generate a new token, update `OWNSONA_API_TOKEN` in
`src/main/backend/application.ini`, run `./bld -v build && ./bld war`
to rebuild the WAR, redeploy, and distribute the new token to MCP
clients. There is no dual-token grace window; clients see a 401 until
they update.

### Top common failure modes

- **`journalctl -u ownsona.service` shows
  `BindException: Permission denied`** — the service couldn't bind 80/443.
  systemd's `AmbientCapabilities` is missing from `ownsona.service`, or
  the service's `User=` was changed and the override no longer applies.
- **MCP returns `EMBEDDING_ERROR` with `insufficient_quota`** — the
  OpenAI account is out of credit. Top up; the rest of the system
  (auth, DB, listing, text search) keeps working.
- **MCP returns `AUTH_FAILED`** on every request — `OWNSONA_API_TOKEN`
  in `application.ini` doesn't match what the client is sending, or
  the WAR wasn't rebuilt/redeployed after editing the ini.
- **First request after deploy is slow / 404** — Tomcat is still
  redeploying the WAR; ~10 s window with `autoDeploy="true"`. Subsequent
  requests are normal.

---

For the per-tool wire format, the schema, embedding-provider
abstraction, and design rationale see **`OWNSONA_SPEC.md`** and
**`MCPServer.md`**.
