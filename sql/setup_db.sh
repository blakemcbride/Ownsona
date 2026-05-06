#!/usr/bin/env bash
#
# One-time PostgreSQL setup for the Ownsona MCP server.
#
# Prerequisites:
#   1. PostgreSQL 16 running on localhost.
#   2. The 'ownsona' database already exists (CREATE DATABASE ownsona).
#   3. The 'postgresql-16-pgvector' OS package installed.
#   4. You can connect as the postgres superuser without prompting (peer auth
#      via the local socket is the default on Ubuntu).
#
# Pass the desired application-role password as $1, e.g.:
#   sql/setup_db.sh "$(openssl rand -hex 16)"

set -euo pipefail

PASSWORD="${1:-}"
if [[ -z "$PASSWORD" ]]; then
    echo "Usage: $0 <ownsona_role_password>" >&2
    exit 1
fi

DB="ownsona"
ROLE="ownsona"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "1/3  Creating role '$ROLE' (if missing) and setting password"
psql -v ON_ERROR_STOP=1 -U postgres -d postgres <<SQL
DO \$\$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '$ROLE') THEN
        CREATE ROLE $ROLE LOGIN PASSWORD '$PASSWORD';
    ELSE
        ALTER ROLE $ROLE WITH LOGIN PASSWORD '$PASSWORD';
    END IF;
END\$\$;

GRANT CONNECT ON DATABASE $DB TO $ROLE;
SQL

echo "2/3  Running schema migration in database '$DB'"
psql -v ON_ERROR_STOP=1 -U postgres -d "$DB" -f "$SCRIPT_DIR/001_init.sql"

echo "3/3  Verifying"
psql -U postgres -d "$DB" -c "\dt memories"
psql -U postgres -d "$DB" -c "SELECT extname, extversion FROM pg_extension ORDER BY extname;"

echo
echo "Done.  Set these in src/main/backend/application.ini (the application reads them"
echo "at startup via MainServlet.getEnvironment):"
echo "  DatabaseType     = PostgreSQL"
echo "  DatabaseHost     = localhost"
echo "  DatabasePort     = 5432"
echo "  DatabaseName     = ${DB}"
echo "  DatabaseUser     = ${ROLE}"
echo "  DatabasePassword = <password>"
