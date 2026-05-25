#!/usr/bin/env bash
#
# Test runner for the Ownsona MCP server.
#
# Compiles tests under src/test/precompiled/ai/ownsona/ and runs them via
# JUnit Platform Console Launcher.  Unit tests have no external requirements.
# Integration tests are gated on OWNSONA_TEST_DATABASE_URL via
# @EnabledIfEnvironmentVariable --- they are silently skipped if it is not set.
#
# Usage:
#   sql/run_tests.sh                                  # unit tests only
#   OWNSONA_TEST_DATABASE_URL=postgresql://u:p@h:5432/db sql/run_tests.sh
#
# To set up a separate test database:
#   createdb -U postgres ownsona_test
#   psql -U postgres -d ownsona_test -f sql/001_init.sql
#   # The 'ownsona' role created by setup_db.sh against the production DB will
#   # also work for the test DB, since the migration grants it the needed
#   # privileges in whichever DB it is run against.
#
# This script doesn't touch the running Tomcat or the production database.

set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

if [[ ! -d work/exploded/WEB-INF/classes ]]; then
    echo "Production classes not built. Running ./bld -v build first..." >&2
    ./bld -v build >/dev/null
fi

TEST_OUT=work/test-classes
rm -rf "$TEST_OUT"
mkdir -p "$TEST_OUT"

# Compile-time classpath: production classes + JUnit API/params + DB drivers
COMPILE_CP="work/exploded/WEB-INF/classes"
COMPILE_CP+=":libs/junit-jupiter-api-5.11.0.jar"
COMPILE_CP+=":libs/junit-jupiter-params-5.11.0.jar"
COMPILE_CP+=":libs/apiguardian-api-1.1.2.jar"
COMPILE_CP+=":libs/postgresql-42.7.11.jar"
COMPILE_CP+=":libs/c3p0-0.12.0.jar"

echo "==> compiling tests"
find src/test/precompiled -name '*.java' -print > /tmp/ownsona-test-srcs.txt
if [[ ! -s /tmp/ownsona-test-srcs.txt ]]; then
    echo "No test sources found under src/test/precompiled" >&2
    exit 1
fi
javac -d "$TEST_OUT" -cp "$COMPILE_CP" @/tmp/ownsona-test-srcs.txt
rm -f /tmp/ownsona-test-srcs.txt

# Runtime classpath: same plus engine, log4j, console launcher
RUN_CP="$TEST_OUT:work/exploded/WEB-INF/classes"
RUN_CP+=":libs/junit-platform-console-standalone-1.11.0.jar"
RUN_CP+=":libs/postgresql-42.7.11.jar"
RUN_CP+=":libs/c3p0-0.12.0.jar"
RUN_CP+=":libs/log4j-api-2.25.4.jar"
RUN_CP+=":libs/log4j-core-2.25.4.jar"

echo "==> running tests"
if [[ -n "${OWNSONA_TEST_DATABASE_URL:-}" ]]; then
    echo "    integration tests enabled (OWNSONA_TEST_DATABASE_URL is set)"
else
    echo "    integration tests skipped (OWNSONA_TEST_DATABASE_URL is unset)"
fi

java -cp "$RUN_CP" \
     -Dlog4j2.configurationFile=src/main/core/log4j2.xml \
     org.junit.platform.console.ConsoleLauncher execute \
     --select-package=ai.ownsona \
     --details=tree \
     --disable-banner
