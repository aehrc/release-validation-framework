#!/usr/bin/env bash
# The A/B, at fixture scale: both engines, per assertion, in about 50 seconds.
#
# WHY THIS EXISTS. The nightly A/B is the real cross-engine measurement, and it
# costs 40 minutes against an 892MB release on a dedicated agent. That is the
# wrong loop for asking "did this change move any assertion", which is the
# question every engine change raises. This runs the same script, the same gate
# and the same two engines against RVF's own regression fixture - 58 files, 240KB
# - so the answer arrives before you have stopped thinking about the change.
#
# No Docker, no cluster, no release download: the MySQL side is the generic
# Linux tarball run unprivileged, exactly as ci/engine_ab_stack.sh does it in CI.
#
#   ci/fixture_ab.sh                      # all three SQL groups
#   ci/fixture_ab.sh release-type-validation
#
# WHAT IT SHOWS TODAY, and read this before believing a number:
#
#   mysql    run=247  ARCHIVE_STRUCTURAL 56 failed  SQL 190, findings in 0
#   duckdb   run=247  ARCHIVE_STRUCTURAL 56 failed  SQL 189, findings in 136
#
# The two engines agree EXACTLY on the 56 structural failures - the fixture is
# 2013 data and does not satisfy today's column patterns - and disagree entirely
# on the SQL side, because the MySQL leg's assertions see no rows. That is not
# yet explained, and these are the things it is NOT:
#
#   * extraction. ReleaseImporter.unzipRelease on the package this script builds
#     yields 21 snapshot, 20 full and 20 delta files with the right names.
#   * the table shape. The generated DDL for concept_s matches the fixture
#     header column for column.
#   * the data. Loading that file by hand into that DDL with LOAD DATA LOCAL
#     INFILE inserts all 19 rows.
#   * the server. local_infile is ON and secure_file_priv is NULL.
#   * the loader not being reached. RVF issues 115 load statements - 57 + 58,
#     both releases - naming the fixture's own files.
#
# What it looks like: NEITHER release reaches MySQL's schemas. Every SQL
# assertion in release-type-validation compares the release against its
# previous, and empty-against-empty is 0 findings for all of them, which is
# exactly the shape of MySQL's report. The log carries
# `Previous release ... not found from Module Storage Coordinator` and then
# creates the schema anyway, so the run proceeds green over nothing - the
# vacuous pass this project exists to remove, in the incumbent.
#
# Until that is answered the MySQL column here is not evidence, and this script
# is a DuckDB regression harness with an unexplained MySQL leg beside it. Note
# the CI A/B does NOT have this problem - build 16337 had MySQL finding real
# failures on a real release - so it is this fixture or this path, not MySQL.
# See duck/PLAN.md 3.15.
set -euo pipefail
cd "$(dirname "$0")/.."

# NOT named GROUPS. That is a bash builtin array holding the caller's group
# IDs, assignment to it is silently ignored, and "$GROUPS" then expands to a
# gid - which RVF answers with HTTP 412 and no explanation. It cost this
# project a run once already; ci/README-engine-ab.md records it.
ASSERTION_GROUPS="${*:-release-type-validation file-centric-validation component-centric-validation}"
FIXTURES="src/test/resources"
PROSPECTIVE="SnomedCT_RegressionTest_20130731"
PREVIOUS="SnomedCT_RegressionTest_20130131"

mkdir -p shared-jobs/releases store/previous

# The fixture keeps Snapshot/ Delta/ Full/ one level down, under RF2Release/,
# and a release package has them directly under its root. Packing the RF2Release
# CONTENTS under a root named after the release is what makes it a package.
pack() {   # <fixture-dir> <output-zip>
  python3 - "$1" "$2" <<'PY'
import pathlib, sys, zipfile
src = pathlib.Path(sys.argv[1])
out = pathlib.Path(sys.argv[2])
base = src / 'RF2Release'
if not base.is_dir():
    raise SystemExit(f"{src} has no RF2Release directory")
with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as z:
    for p in sorted(base.rglob('*')):
        if p.is_file():
            z.write(p, pathlib.Path(src.name) / p.relative_to(base))
print(f"  {out}  {out.stat().st_size} bytes")
PY
}

echo "=== packing the fixture ==="
pack "$FIXTURES/$PROSPECTIVE" "shared-jobs/releases/$PROSPECTIVE.zip"
pack "$FIXTURES/$PREVIOUS" "store/previous/$PREVIOUS.zip"

# A cached binary archive of a previous run's previous release would be loaded
# in preference to the zip just built, which is how an edit to the fixture can
# appear to change nothing at all.
rm -f "store/binaryArchives/$PREVIOUS.zip"

# 3g per JVM is the documented figure for this stack, and the fixture needs
# nothing like the 8g default that two JVMs plus MySQL would want.
HEAP="${HEAP:-3g}" exec ci/engine_ab_stack.sh \
  --release "releases/$PROSPECTIVE.zip" \
  --previous "$PREVIOUS.zip" \
  --groups "$ASSERTION_GROUPS"
