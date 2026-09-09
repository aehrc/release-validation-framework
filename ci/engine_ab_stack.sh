#!/usr/bin/env bash
# Brings up both engines against one MySQL and runs the A/B gate.
#
# Everything the pipeline does is in here, so the pipeline is a thin caller and
# this script is the thing that gets tested - on a laptop and in CI, identically.
#
# No Docker. The MySQL side is the official generic Linux tarball run as an
# unprivileged user, which is what makes this runnable on a build agent that has
# no daemon and no sudo. The same approach is how the test suite is run here: see
# ci/README-engine-ab.md.
#
# Usage:
#   ci/engine_ab_stack.sh --release au/amtv4.zip \
#       --previous SnomedCT_AU_20260630.zip \
#       --groups "release-type-validation file-centric-validation"
set -euo pipefail

JAR="${JAR:-target/release-validation-framework-9.0.1.jar}"
JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-25-openjdk-amd64}"
WORK="${WORK:-/data/work}"
MYSQL_HOME="${MYSQL_HOME:-$WORK/mysql8}"
MYSQL_PORT="${MYSQL_PORT:-3307}"
MYSQL_SOCKET="${MYSQL_SOCKET:-$WORK/mysql.sock}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-rvfpass}"
JOB_STORE="${JOB_STORE:-shared-jobs/}"
RELEASE_STORE="${RELEASE_STORE:-store/previous/}"
# RELATIVE, always. RVF's ResourceManager resolves this through Spring's
# FileSystemResource, which drops a leading slash: given
# /data/work/amt-build/corpus it looked for data/work/amt-build/corpus/manifest.xml
# and the MySQL leg died in bean creation before serving anything. A symlink in
# the working directory is the way to point at a corpus that lives elsewhere.
CORPUS="${CORPUS:-./snomed-release-validation-assertions/}"
# The DuckDB store to run against. Unset means the one baked into the jar, which
# is right for the international corpus and WRONG for any other: the locator
# would serve 360 international assertions while the MySQL leg imported an
# extension corpus from $CORPUS, and the A/B would compare two different
# assertion sets and call the difference a divergence.
DUCK_STORE="${DUCK_STORE:-}"
# How many failing components each report carries per assertion. The default is
# a sample, which is enough to compare COUNTS - and counts are not parity: a
# transpiled regex can match the wrong rows and still match as many. Raise it
# and the two reports can be compared on component ids.
FAILURE_EXPORT_MAX="${FAILURE_EXPORT_MAX:-}"
MYSQL_URL="${MYSQL_URL:-http://localhost:8090}"
DUCK_URL="${DUCK_URL:-http://localhost:8091}"
HEAP="${HEAP:-8g}"

RELEASE=""
PREVIOUS=""
DEPENDENCY=""
# NOT named GROUPS. In bash that is a special variable holding the calling
# user's group ids, and assigning to it does nothing: `--groups
# release-type-validation` silently became `--groups 1103459` - my primary gid -
# and RVF answered HTTP 412 because no such assertion group exists. The A/B
# submitted the wrong thing and failed for a reason that looked like a server
# problem.
ASSERTION_GROUPS="release-type-validation file-centric-validation"
EFFECTIVE_TIME=""
KEEP_UP="${KEEP_UP:-0}"

while [ $# -gt 0 ]; do
  case "$1" in
    --release)    RELEASE="$2"; shift 2 ;;
    --previous)   PREVIOUS="$2"; shift 2 ;;
    --dependency) DEPENDENCY="$2"; shift 2 ;;
    --groups)     ASSERTION_GROUPS="$2"; shift 2 ;;
    --effective-time) EFFECTIVE_TIME="$2"; shift 2 ;;
    --keep-up)    KEEP_UP=1; shift ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done
[ -n "$RELEASE" ] || { echo "--release is required" >&2; exit 2; }

export JAVA_HOME
export AWS_REGION="${AWS_REGION:-us-east-1}"
export AWS_DEFAULT_REGION="$AWS_REGION"
# Cleared, not just created. RVF extracts a release into
# $tmpdir/<version>/ and removes it with deleteIfExists, which throws
# DirectoryNotEmptyException on a partial extract left by an aborted run - so
# one failed attempt makes every later attempt fail with an error about the
# PREVIOUS release rather than about anything current. Two aborted runs cost a
# 15-minute leg to diagnose exactly that.
rm -rf "$WORK/tmp-mysql" "$WORK/tmp-duck"
mkdir -p "$WORK/tmp-mysql" "$WORK/tmp-duck" "$WORK/duckwork-ab" "$WORK/release-cache-ab"

PIDS=()
cleanup() {
  if [ "$KEEP_UP" = "1" ]; then
    echo "leaving the instances up (--keep-up)"
    return
  fi
  for pid in "${PIDS[@]:-}"; do
    [ -n "$pid" ] && kill "$pid" 2>/dev/null || true
  done
}
trap cleanup EXIT

wait_for_http() {
  local url="$1" label="$2" limit="${3:-300}" i=0
  while [ "$i" -lt "$limit" ]; do
    if curl -fsS -m 5 -o /dev/null -H 'X-AUTH-username: engine-ab' -H 'X-AUTH-roles: ROLE_ihtsdo-ops-admin' -H 'X-AUTH-token: local' "$url/version" 2>/dev/null; then
      echo "  $label is up"
      return 0
    fi
    sleep 2
    i=$((i + 2))
  done
  echo "$label did not come up within ${limit}s" >&2
  return 1
}

# MySQL's generic Linux tarball links libaio.so.1, and Ubuntu 24.04 ships that
# library as libaio.so.1t64 - the time_t transition rename. mysqld then dies
# with "error while loading shared libraries: libaio.so.1" before it logs
# anything, which reads like a broken download rather than a distro rename.
#
# A symlink in a private directory fixes it with no root and no apt, and
# LD_LIBRARY_PATH already points at $WORK/libs below.
mkdir -p "$WORK/libs"
if [ ! -e "$WORK/libs/libaio.so.1" ]; then
  for candidate in /usr/lib/x86_64-linux-gnu/libaio.so.1t64 \
                   /usr/lib64/libaio.so.1t64 /lib/x86_64-linux-gnu/libaio.so.1t64; do
    if [ -e "$candidate" ]; then
      ln -sf "$candidate" "$WORK/libs/libaio.so.1"
      echo "  shimmed $(basename "$candidate") as libaio.so.1"
      break
    fi
  done
fi

echo "=== MySQL ==="
if ! "$MYSQL_HOME/bin/mysqladmin" --socket="$MYSQL_SOCKET" -uroot -p"$MYSQL_PASSWORD" ping >/dev/null 2>&1; then
  echo "  starting mysqld on $MYSQL_PORT"
  # $WORK/libs first, then anything the caller supplied. CI shims the
  # libraries somewhere else - PublishPipelineArtifact cannot follow symlinks
  # inside the published directory - and hardcoding this dropped that shim, so
  # mysqld failed to start where it had just been initialised.
  LD_LIBRARY_PATH="$WORK/libs${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}" "$MYSQL_HOME/bin/mysqld" \
    --basedir="$MYSQL_HOME" --datadir="$WORK/mysqldata" --tmpdir="$WORK/mysqltmp" \
    --port="$MYSQL_PORT" --socket="$MYSQL_SOCKET" --mysqlx=OFF --skip-log-bin \
    --innodb-buffer-pool-size=4G --local-infile=ON --max-connections=200 \
    --sql-mode='STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' &
  PIDS+=($!)
  sleep 15
fi

# A datadir straight out of --initialize-insecure has NO root password, and
# everything below authenticates with one. CI initialises in a separate step, so
# this is the normal state there rather than an edge case: build 16315 started
# mysqld, reached "ready for connections", and then died on the version query
# below - whose stderr is discarded, so the log showed only a SHUTDOWN sent by
# this script's own cleanup and no reason at all.
if ! "$MYSQL_HOME/bin/mysqladmin" --socket="$MYSQL_SOCKET" -uroot -p"$MYSQL_PASSWORD" ping >/dev/null 2>&1 \
   && "$MYSQL_HOME/bin/mysqladmin" --socket="$MYSQL_SOCKET" -uroot ping >/dev/null 2>&1; then
  echo "  setting the root password on a freshly initialised datadir"
  "$MYSQL_HOME/bin/mysql" --socket="$MYSQL_SOCKET" -uroot \
    -e "ALTER USER 'root'@'localhost' IDENTIFIED BY '$MYSQL_PASSWORD'"
fi
# The engine boots against rvf_master and does not create it. ddl-auto=create
# makes the tables; the schema itself has to exist first.
"$MYSQL_HOME/bin/mysql" --socket="$MYSQL_SOCKET" -uroot -p"$MYSQL_PASSWORD" \
  -e "CREATE DATABASE IF NOT EXISTS rvf_master"
"$MYSQL_HOME/bin/mysql" --socket="$MYSQL_SOCKET" -uroot -p"$MYSQL_PASSWORD" \
  -e "SELECT CONCAT('  ', VERSION(), ' on ', @@port)" 2>/dev/null | tail -1

# Each leg gets a fresh assertion database. The importer only populates an EMPTY
# one (AssertionsDatabaseImporter.isAssertionImportRequired), and ddl-auto=create
# drops the tables at boot anyway, so this is what the MySQL engine does on every
# start - doing it explicitly keeps the two legs comparable.
echo "=== RVF, mysql engine ==="
"$JAVA_HOME/bin/java" -Djava.io.tmpdir="$WORK/tmp-mysql" -Xmx"$HEAP" \
  --add-opens=java.base/java.lang=ALL-UNNAMED -jar "$JAR" \
  --rvf.execution.engine=mysql \
  --spring.datasource.url="jdbc:mysql://localhost:$MYSQL_PORT/rvf_master?useSSL=false&allowPublicKeyRetrieval=true&allowLoadLocalInfile=true&sessionVariables=sql_mode='STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION'" \
  --spring.datasource.username=root --spring.datasource.password="$MYSQL_PASSWORD" \
  --rvf.assertion.resource.local.path="$CORPUS" \
  --rvf.validation.job.storage.local.path="$JOB_STORE" \
  --rvf.release.storage.local.path="$RELEASE_STORE" --rvf.release.storage.useCloud=false \
  --spring.activemq.broker-url='vm://localhost?broker.persistent=false' \
  --server.port="${MYSQL_URL##*:}" > "$WORK/ab-mysql.log" 2>&1 &
PIDS+=($!)

# A SEPARATE in-JVM broker per instance, not a shared one. Both instances are
# workers, so on one broker they would compete for the same queue and each leg
# would validate whichever message it happened to win.
echo "=== RVF, duckdb engine ==="
"$JAVA_HOME/bin/java" -Djava.io.tmpdir="$WORK/tmp-duck" -Xmx"$HEAP" \
  --add-opens=java.base/java.lang=ALL-UNNAMED -jar "$JAR" \
  --rvf.execution.engine=duckdb \
  ${DUCK_STORE:+--rvf.duck.store="$DUCK_STORE"} \
  --rvf.assertion.resource.local.path="$CORPUS" \
  --rvf.validation.job.storage.local.path="$JOB_STORE" \
  --rvf.release.storage.local.path="$RELEASE_STORE" --rvf.release.storage.useCloud=false \
  --rvf.duck.work.directory="$WORK/duckwork-ab" \
  --spring.activemq.broker-url='vm://localhost?broker.persistent=false' \
  --server.port="${DUCK_URL##*:}" > "$WORK/ab-duck.log" 2>&1 &
PIDS+=($!)

wait_for_http "$MYSQL_URL" "mysql engine" 400
wait_for_http "$DUCK_URL" "duckdb engine" 400

echo "=== A/B ==="
ARGS=(--mysql-url "$MYSQL_URL" --duck-url "$DUCK_URL" --release "$RELEASE"
      --groups $ASSERTION_GROUPS --release-as-edition
      --out "$WORK/engine-ab.json" --junit "$WORK/engine-ab.xml"
      --mysql-report "$WORK/engine-ab-mysql.json" --duck-report "$WORK/engine-ab-duck.json")
[ -n "$FAILURE_EXPORT_MAX" ] && ARGS+=(--failure-export-max "$FAILURE_EXPORT_MAX")
[ -n "$PREVIOUS" ] && ARGS+=(--previous-release "$PREVIOUS")
[ -n "$DEPENDENCY" ] && ARGS+=(--dependency-release "$DEPENDENCY")
[ -n "$EFFECTIVE_TIME" ] && ARGS+=(--effective-time "$EFFECTIVE_TIME")

python3 ci/engine_ab.py "${ARGS[@]}"
