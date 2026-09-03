#!/usr/bin/env bash
#
# Build the MRCM validator that RVF's pom pins: IHTSDO's 4.0.1 with the
# validation loops parallelised.
#
# WHY THIS EXISTS
#
# MRCM's validation phase issues one or two ECL queries per domain/attribute
# pair against a Lucene index, plus a concept lookup per failure, and ran every
# one of them on a single thread. Measured on the AU edition: 1,292 s for the
# MRCM phase, of which ~1,122 s was that loop - 87%. It is also 1,037 of the
# nightly's assertions, five times everything else combined, so it does not
# just dominate the nightly, it is the nightly.
#
# Parallelised, the same phase takes 735 s with findings identical: 1,037
# tests, 3 failures, 4 warnings, 2 incomplete, and no assertion changing bucket
# or failure count.
#
# WHY IT IS SAFE TO FAN OUT
#
#   * SnomedQueryService is read-only per query and thread-safe by
#     construction: a Lucene IndexSearcher and Analyzer, a stateless
#     ECL-to-Lucene converter, and a ConcurrentHashMap for its refset cache.
#   * ValidationRun's three assertion lists were plain ArrayLists appended from
#     the loop, so the patch synchronizes them. A lost entry there is a lost
#     assertion - the validation would report fewer results rather than fail.
#   * The attribute-range pass is PLANNED serially and only then executed in
#     parallel. Its dedupe key excludes the domain, and the domain's constraint
#     feeds the out-of-range ECL, so which domain claims a shared range changes
#     the query. Walking the domains in order keeps that choice exactly as it
#     was; only the queries are fanned out.
#
# WHAT IS NOT DONE
#
# The Lucene index is still built on the heap: ValidationService uses
# loadReleaseFilesToMemoryBasedIndex, and snomed-query-service ships
# DiskReleaseStore(File) beside RamReleaseStore but ValidationService exposes no
# way to choose. A disk-backed store would move the index into the page cache
# and let flushes actually release heap. Peak during the build was ~7.8 GB,
# settling to 1-2.8 GB retained.
#
# DELETE THIS SCRIPT AND THE POM PIN if SI takes the change upstream.

set -euo pipefail

# Resolved before any cd: this script changes directory into the clone.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

VERSION="${1:-4.0.3-aehrc-perf}"
COMMIT="${MRCM_COMMIT:-bfdf76f}"          # "Release 4.0.1"
WORKDIR="${MRCM_BUILD_DIR:-/data/work/mrcm-src}"
REPO="${MAVEN_REPO_LOCAL:-/data/m2}"
PATCH="${PATCH_FILE:-$SCRIPT_DIR/mrcm-validator-parallel.patch}"

echo "==> building mrcm-validator $VERSION from IHTSDO $COMMIT"

if [ ! -d "$WORKDIR/.git" ]; then
    git clone -q https://github.com/IHTSDO/release-mrcm-validator.git "$WORKDIR"
fi
cd "$WORKDIR"
git fetch -q --depth 30 origin || true
git checkout -q "$COMMIT"
git checkout -q .

[ -f "$PATCH" ] || { echo "FATAL: $PATCH not found. Without it the build is" >&2
                     echo "       557 s slower per MRCM run and carries the same version." >&2
                     exit 1; }
git apply --check "$PATCH" || { echo "FATAL: $PATCH does not apply to $COMMIT" >&2; exit 1; }
git apply "$PATCH"
echo "    applied $(basename "$PATCH")"

python3 - "$VERSION" <<'PY'
import pathlib, re, sys
new = sys.argv[1]
p = pathlib.Path('pom.xml'); t = p.read_text()
t2 = re.sub(r'(<artifactId>mrcm-validator</artifactId>\s*<version>)[^<]+(</version>)',
            rf'\g<1>{new}\g<2>', t, count=1)
assert t2 != t, "mrcm-validator version not found - has upstream restructured the pom?"
# Two plugins whose dependency closures are not on this host and are not needed
# for the jar RVF consumes. maven-resources-plugin drops to the cached 2.6;
# the assembly plugin builds a distribution artefact nothing here uses.
t2 = t2.replace('<version>3.3.1</version>', '<version>2.6</version>')
t2 = re.sub(r'\s*<plugin>\s*<groupId>org\.apache\.maven\.plugins</groupId>\s*'
            r'<artifactId>maven-assembly-plugin</artifactId>.*?</plugin>', '', t2, flags=re.S)
p.write_text(t2)
print(f"    version set to {new}")
PY

# -Dmaven.legacyLocalRepo=true because this repository holds artifacts fetched
# under other repository ids; without it, offline resolution refuses files that
# are present on disk.
# NOT offline - see build-drools-engine.sh. legacyLocalRepo stays because
# it also relaxes the _remote.repositories check on artifacts that a
# previous run installed locally.
mvn -Dmaven.legacyLocalRepo=true -q install -DskipTests -Dmaven.repo.local="$REPO"

JAR="$REPO/org/snomed/quality/mrcm-validator/$VERSION/mrcm-validator-$VERSION.jar"
[ -f "$JAR" ] || { echo "FATAL: $JAR not produced" >&2; exit 1; }

# Prove the parallelism is in the bytecode rather than only in the diff.
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
unzip -qo "$JAR" 'org/snomed/quality/validator/mrcm/ValidationService.class' -d "$tmp"
pool=$("${JAVA_HOME:-/usr}/bin/javap" -p "$tmp/org/snomed/quality/validator/mrcm/ValidationService.class" \
        | grep -c 'runInParallel' || true)
[ "$pool" -ge 1 ] || { echo "FATAL: runInParallel absent - patch did not take" >&2; exit 1; }

echo "==> installed $VERSION; bytecode confirms the parallel executor"
echo "    pom pin: <mrcm.validator.version>$VERSION</mrcm.validator.version>"
