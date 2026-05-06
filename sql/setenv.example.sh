#!/usr/bin/env bash
# NOTE: Application secrets and URLs no longer live in setenv.sh.
#
# Ownsona reads its configuration from src/main/backend/application.ini
# via MainServlet.getEnvironment(). The keys it expects are:
#
#   EMBEDDING_API_KEY        (required)
#   OWNSONA_API_TOKEN     (required)
#   EMBEDDING_ENDPOINT       (required)
#   EMBEDDING_MODEL       (required)
#   EMBEDDING_DIMENSIONS  (required; must match vector(N) in sql/001_init.sql)
#   OWNSONA_USER_ID       (optional, default "default")
#   EMBEDDING_PROVIDER    (optional, default "openai")
#   DEFAULT_RECALL_LIMIT  (optional, default 8)
#   MAX_RECALL_LIMIT      (optional, default 50)
#   MAX_TEXT_CHARS        (optional, default 16000)
#   MAX_BATCH_SIZE        (optional, default 200)
#
# The PostgreSQL connection itself uses Kiss's standard keys
# (DatabaseType, DatabaseHost, DatabasePort, DatabaseName, DatabaseUser,
# DatabasePassword) in the same file.
#
# Tomcat's setenv.sh only needs JVM tuning options now; secrets do not
# need to be exported into the JVM environment.
