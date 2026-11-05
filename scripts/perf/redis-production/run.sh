#!/usr/bin/env bash
# Direct production-class Redis harness; no HTTP service and no project tests.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
BACKEND="$REPO_ROOT/backend"
MVN="${MVN:-mvn}"
MVN_MODE="${PERF_MVN_MODE:-online}"
case "$MVN_MODE" in
  online) MVN_OFFLINE= ;;
  offline) MVN_OFFLINE=-o ;;
  *) echo "PERF_MVN_MODE must be online or offline" >&2; exit 2 ;;
esac

cd "$BACKEND"
# shellcheck disable=SC2086
"$MVN" $MVN_OFFLINE -q -DskipTests compile
# shellcheck disable=SC2086
"$MVN" $MVN_OFFLINE -q dependency:build-classpath \
  -Dmdep.includeScope=test -Dmdep.outputFile=target/redis-production-classpath.txt

case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) SEP=';' ;;
  *) SEP=':' ;;
esac
DEPS="$(cat target/redis-production-classpath.txt)"
CP="target/classes${SEP}${DEPS}"
rm -rf target/redis-production-test-classes
mkdir -p target/redis-production-test-classes
find src/test/java/com/example/configmgr/perf/redis \
     src/test/java/com/example/configmgr/perf/production \
     -name '*.java' ! -name '*Test.java' > target/redis-production-sources.txt
javac -encoding UTF-8 -proc:none -cp "$CP" \
  -d target/redis-production-test-classes @target/redis-production-sources.txt
exec java -cp "target/redis-production-test-classes${SEP}${CP}" \
  com.example.configmgr.perf.production.ProductionHarnessMain "$@"
