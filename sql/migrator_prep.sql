-- One-time prep for an existing Ownsona database before deploying the
-- auto-migrator (rollout plan Phase 2).
--
-- Fresh installs (sql/setup_db.sh + sql/001_init.sql) already set these
-- up.  This script is for databases that were bootstrapped BEFORE
-- 001_init.sql was updated to include the GRANT CREATE / ALTER OWNER
-- statements --- i.e. Blake's existing prod DB.
--
-- Idempotent: safe to re-run.  Run as the postgres superuser:
--
--   sudo -u postgres psql -d ownsona -f sql/migrator_prep.sql

-- 1. Let the application role create new tables (the migrator's first
--    act is CREATE TABLE db_version).
GRANT CREATE ON SCHEMA public TO ownsona;

-- 2. Make the application role the owner of memories so it can ALTER
--    TABLE / CREATE INDEX without superuser in future migrations.
--    These are idempotent --- ALTER OWNER TO the existing owner is a
--    no-op.
ALTER TABLE memories OWNER TO ownsona;
ALTER SEQUENCE memories_id_seq OWNER TO ownsona;

-- 3. Verification.
\echo
\echo 'After this script, ownsona should:'
\echo '  * have CREATE on schema public'
\echo '  * own the memories table'
\echo
SELECT
    has_schema_privilege('ownsona', 'public', 'CREATE') AS can_create_in_public,
    pg_catalog.pg_get_userbyid(relowner) AS memories_owner
FROM pg_class
WHERE relname = 'memories';
