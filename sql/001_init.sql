-- Ownsona MCP server schema (PostgreSQL + pgvector).
--
-- Run as the postgres superuser against the ownsona database, e.g.:
--   psql -U postgres -d ownsona -f sql/001_init.sql
--
-- Assumes the application role 'ownsona' has already been created out-of-band
-- (the password is held in the deployment environment, not in this file).

CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE IF NOT EXISTS memories (
    id BIGSERIAL PRIMARY KEY,

    user_id TEXT NOT NULL DEFAULT 'default',

    text TEXT NOT NULL,
    normalized_text TEXT,

    embedding vector(1536) NOT NULL,

    tags TEXT[] NOT NULL DEFAULT '{}',

    importance DOUBLE PRECISION NOT NULL DEFAULT 0.5,

    source_provider TEXT,
    source_client TEXT,
    source_conversation_id TEXT,

    embedding_provider TEXT NOT NULL DEFAULT 'openai',
    embedding_model TEXT NOT NULL DEFAULT 'text-embedding-3-small',

    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ,

    metadata JSONB NOT NULL DEFAULT '{}'
);

CREATE INDEX IF NOT EXISTS memories_user_id_idx     ON memories(user_id);
CREATE INDEX IF NOT EXISTS memories_created_at_idx  ON memories(created_at DESC);
CREATE INDEX IF NOT EXISTS memories_deleted_at_idx  ON memories(deleted_at);
CREATE INDEX IF NOT EXISTS memories_tags_idx        ON memories USING GIN(tags);
CREATE INDEX IF NOT EXISTS memories_text_trgm_idx   ON memories USING GIN(text gin_trgm_ops);

-- HNSW vector index for cosine-similarity recall.  Picked over IVFFLAT
-- because HNSW does not need a populated table to seed centroids
-- (IVFFLAT's k-means is meaningless on an empty table) and gives better
-- recall-for-the-speed at every scale we expect to reach.  pgvector
-- defaults (m=16, ef_construction=64) are fine for 1536-dim vectors;
-- query-time accuracy is tunable per session via SET hnsw.ef_search.
CREATE INDEX IF NOT EXISTS memories_embedding_idx
    ON memories USING hnsw (embedding vector_cosine_ops);

-- Defense-in-depth: app does a duplicate check before insert, but a unique
-- partial index also blocks two concurrent identical inserts.
CREATE UNIQUE INDEX IF NOT EXISTS memories_unique_normalized_active
    ON memories(user_id, normalized_text)
    WHERE deleted_at IS NULL AND normalized_text IS NOT NULL;

CREATE OR REPLACE FUNCTION set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS memories_set_updated_at ON memories;
CREATE TRIGGER memories_set_updated_at
BEFORE UPDATE ON memories
FOR EACH ROW
EXECUTE FUNCTION set_updated_at();

GRANT USAGE ON SCHEMA public TO ownsona;
-- CREATE on schema lets the auto-migrator (DbMigrator) create the
-- db_version bookkeeping table and any future ownsona-owned tables.
GRANT CREATE ON SCHEMA public TO ownsona;
GRANT SELECT, INSERT, UPDATE, DELETE, TRUNCATE ON memories TO ownsona;
GRANT USAGE, SELECT ON SEQUENCE memories_id_seq TO ownsona;
-- Make ownsona the owner of memories so the auto-migrator can ALTER
-- TABLE / CREATE INDEX on it in future migration steps without needing
-- superuser privileges at runtime.
ALTER TABLE memories OWNER TO ownsona;
ALTER SEQUENCE memories_id_seq OWNER TO ownsona;
