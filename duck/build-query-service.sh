#!/usr/bin/env bash
# Build the snomed-query-service that RVF's pom pins: IHTSDO's 6.0.1 with concept
# ids read from doc values instead of a stored field per hit.
#
# WHY THIS EXISTS
#
# eclQueryReturnConceptIdentifiers returned one concept id per hit by calling
# indexSearcher.doc(), which decompresses a stored-fields block to recover a
# number the index can hand over directly. Measured over the real 432-expression
# MRCM corpus of an 853MB AU edition, against a real 722,404-concept index:
#
#   3,885,244 hits returned
#   stored fields   90.3 s single-threaded   20.3 us/hit
#   doc values      16.8 s single-threaded    4.3 us/hit   5.4x
#
# End to end on the nightly, same 12 GB heap, post-index validation window:
# 632 s -> 520 s. Findings identical, and identical in ORDER: the read is done in
# fixed-size chunks, sorted by docid for the doc-values iterator and scattered
# back to hit order, so paging and "first N failures" name the same instances.
#
# Two traps this encodes:
#   - The read path falls back to the stored field when an index predates the
#     doc-values field, so a broken WRITER leaves every query correct and merely
#     slow, silently. ConceptIdDocValuesTest pins the writer separately; deleting
#     the writer line fails exactly that test and nothing else.
#   - A first version sorted the whole hit array via a boxed Integer[]. Correct,
#     all 125 tests passing, and it OOM'd a 12 GB heap on the real edition at
#     125,000 hits x 8 threads. Scratch is now one bounded long[8192].
#
# DROP THIS PIN if SI takes the change upstream.

set -euo pipefail

VERSION="${1:-6.0.1-aehrc-perf}"
BASE_TAG="${BASE_TAG:-6.0.1}"
BUILD_DIR="${QUERY_SERVICE_BUILD_DIR:-/data/work/sqs-build}"
PATCH_FILE="$(cd "$(dirname "$0")" && pwd)/snomed-query-service-docvalues.patch"

if [ ! -f "$PATCH_FILE" ]; then
  echo "FATAL: $PATCH_FILE not found" >&2
  exit 1
fi

echo "==> building snomed-query-service $VERSION from IHTSDO $BASE_TAG"

rm -rf "$BUILD_DIR"
git clone -q --depth 1 --branch "$BASE_TAG" https://github.com/IHTSDO/snomed-query-service.git "$BUILD_DIR"
cd "$BUILD_DIR"

git apply --check "$PATCH_FILE" || {
  echo "FATAL: $PATCH_FILE does not apply to $BASE_TAG" >&2
  exit 1
}
git apply "$PATCH_FILE"
echo "==> patch applied"

# The version has to change in the project coordinates only; the parent BOM is
# resolved from the local repository, which needs legacyLocalRepo offline
# because the cached metadata names a repository id this build does not define.
python3 - "$VERSION" <<'PY'
import pathlib, re, sys
version = sys.argv[1]
pom = pathlib.Path('pom.xml')
text = pom.read_text()
patched, count = re.subn(r'(<version>)6\.0\.1(</version>)', rf'\g<1>{version}\g<2>', text, count=1)
if count != 1:
    raise SystemExit('FATAL: could not rewrite the project version')
pom.write_text(patched)
print(f'==> version set to {version}')
PY

# No machine-specific default: an unset MAVEN_OPTS means maven's own
# ~/.m2/repository, which is right everywhere.
export MAVEN_OPTS="${MAVEN_OPTS:-}"
# NOT offline - see build-drools-engine.sh.
mvn -q -Dmaven.legacyLocalRepo=true install

JAR="$(find "$HOME/.m2" /data/m2 -path "*snomed-query-service/$VERSION/snomed-query-service-$VERSION.jar" 2>/dev/null | head -1)"
if [ -z "$JAR" ]; then
  echo "FATAL: $VERSION did not install" >&2
  exit 1
fi

# Gate on the optimisation actually being present, not just on a green build -
# the fallback means a jar without it still passes every functional test.
if ! javap -p -c -cp "$JAR" org.ihtsdo.otf.sqs.service.SnomedQueryService 2>/dev/null \
     | grep -q 'getNumericDocValues'; then
  echo "FATAL: $VERSION does not read doc values - the patch did not take" >&2
  exit 1
fi
if ! javap -p -c -cp "$JAR" org.ihtsdo.otf.sqs.service.ReleaseWriter 2>/dev/null \
     | grep -q 'NumericDocValuesField'; then
  echo "FATAL: $VERSION does not WRITE the doc-values field - reads would silently fall back" >&2
  exit 1
fi

# The wildcard rewrite and the parallel document build have the same property:
# a jar missing them is correct and merely slow, so gate on the bytecode.
# The constant is compile-time folded onto the inner listener, so it shows up
# under -constants rather than in the disassembly.
if ! javap -p -constants -cp "$JAR" \
       'org.ihtsdo.otf.sqs.service.ExpressionConstraintToLuceneConverter$ExpressionConstraintListener' 2>/dev/null \
     | grep -q 'type:concept'; then
  echo "FATAL: $VERSION still emits the id wildcard for '*'" >&2
  exit 1
fi
if ! javap -p -c -cp "$JAR" org.ihtsdo.otf.sqs.service.ReleaseImportManager 2>/dev/null \
     | grep -q 'parallelStream'; then
  echo "FATAL: $VERSION does not build index documents in parallel" >&2
  exit 1
fi
if javap -p -cp "$JAR" org.ihtsdo.otf.sqs.service.ReleaseWriter 2>/dev/null \
     | grep -q 'SimpleDateFormat'; then
  echo "FATAL: $VERSION still holds a shared SimpleDateFormat - the parallel build would corrupt effective times" >&2
  exit 1
fi

echo "==> installed $VERSION; bytecode confirms both halves"
echo "==> pom pin: <snomed.query.service.version>$VERSION</snomed.query.service.version>"
