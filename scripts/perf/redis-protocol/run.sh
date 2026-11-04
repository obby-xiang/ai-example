#!/usr/bin/env bash
# Redis candidate protocol harness - Git Bash / Linux / macOS launcher.
# It only calls the backend module (test sources); it does not start the service,
# call HTTP APIs, or run the full test suite.
# PERF_MVN_MODE=online|offline selects Maven dependency resolution (default online).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
BACKEND="$REPO_ROOT/backend"
MVN="${MVN:-mvn}"
# online (default): a fresh device may download missing dependencies on first run.
# offline: pass -o and use the local repository only (keeps the repo's mvn -o habit).
MVN_MODE="${PERF_MVN_MODE:-online}"
MVN_MODE="$(printf '%s' "$MVN_MODE" | tr '[:upper:]' '[:lower:]')"

case "$MVN_MODE" in
  online|offline) ;;
  *)
    echo "PERF_MVN_MODE must be 'online' or 'offline' (got: $MVN_MODE)" >&2
    exit 2
    ;;
esac

if [ ! -d "$BACKEND" ]; then
  echo "backend module not found: $BACKEND" >&2
  exit 2
fi

run_mvn() {
  if [ "$MVN_MODE" = offline ]; then
    "$MVN" -o "$@"
  else
    "$MVN" "$@"
  fi
}

cd "$BACKEND"

echo "maven: $MVN (PERF_MVN_MODE=$MVN_MODE)" >&2

# Main classes only; the repository baseline may have unrelated test-source
# compile errors, so this launcher never compiles the whole test tree.
if ! run_mvn -q -DskipTests compile; then
  if [ "$MVN_MODE" = offline ]; then
    echo "offline build failed; rerun with PERF_MVN_MODE=online to resolve missing dependencies" >&2
  fi
  exit 1
fi
run_mvn -q dependency:build-classpath \
  -Dmdep.includeScope=test \
  -Dmdep.outputFile=target/redis-protocol-classpath.txt

case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) SEP=';' ;;
  *) SEP=':' ;;
esac
DEPS="$(cat target/redis-protocol-classpath.txt)"
CP="target/classes${SEP}${DEPS}"

rm -rf target/redis-protocol-test-classes
mkdir -p target/redis-protocol-test-classes
find src/test/java/com/example/configmgr/perf/redis -name '*.java' \
  ! -name '*Test.java' > target/redis-protocol-sources.txt
javac -encoding UTF-8 -proc:none -cp "$CP" \
  -d target/redis-protocol-test-classes @target/redis-protocol-sources.txt

exec java -cp "target/redis-protocol-test-classes${SEP}${CP}" \
  com.example.configmgr.perf.redis.HarnessMain "$@"
