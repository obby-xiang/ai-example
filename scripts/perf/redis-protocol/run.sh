#!/usr/bin/env bash
# Redis candidate protocol harness - Git Bash / Linux / macOS launcher.
# It only calls the backend module (test sources); it does not start the service,
# call HTTP APIs, or run the full test suite.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
BACKEND="$REPO_ROOT/backend"
MVN="${MVN:-mvn}"

if [ ! -d "$BACKEND" ]; then
  echo "backend module not found: $BACKEND" >&2
  exit 2
fi

cd "$BACKEND"

# Main classes only; the repository baseline may have unrelated test-source
# compile errors, so this launcher never compiles the whole test tree.
"$MVN" -o -q -DskipTests compile
"$MVN" -o -q dependency:build-classpath \
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
