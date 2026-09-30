#!/usr/bin/env bash
#
# Build the MRCM validator that RVF's pom pins: IHTSDO's 4.0.1 with the
# validation loops parallelised and the SEP/Lateralizable phases module-filtered
# instead of switched off.
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
#
# THE SECOND PATCH: SEP AND LATERALIZABLE FILTERED BY MODULE, NOT SKIPPED
#
# Upstream, ValidationService runs the SEP and Lateralizable phases only when
# the run has NO module ids:
#
#     ContentType.INFERRED.equals(...) && CollectionUtils.isEmpty(run.getModuleIds())
#
# Those are the only two of the seven MRCM validation types that are skipped
# rather than filtered. Each service registers its own assertions, so a skipped
# phase is not reported as skipped - its 16 assertions (14 SEP, 2
# Lateralizable) vanish from every bucket, and submitting includedModules took
# the AU nightly's totalTestsRun from 1,507 to 1,491 with no gate noticing. The
# gate also made LateralizableRefsetValidationService's own module filter dead
# code: the only way moduleIds is non-empty is the condition that stops it.
#
# mrcm-validator-module-filter.patch drops the module half of the gate (the
# INFERRED half stays), and filters SEP findings on the flagged concept's
# module, the key Lateralizable already uses. An assertion left with no
# findings stays registered and reports as passed, so the count holds. It
# also stops Lateralizable's remove-check from pruning the run's own member
# set, which only mattered once that code could run. Measured on the AU
# 20260930 edition: 16 SEP findings, all international-module, with no
# modules; 0 with AU's two modules, 16 assertions still reported, all passed.
#
# Coverage restored: with includedModules the SEP and Lateralizable checks
# police AU-module content instead of not running at all.
#
# DELETE THAT PATCH if SI replaces the gate with a module filter upstream.

set -euo pipefail

# SI's parent POMs are not on Maven Central and a POM's own <repositories>
# cannot resolve that POM's parent, so the repositories must come from
# settings.xml or an empty local repository cannot build this at all.
MAVEN_SETTINGS="${MAVEN_SETTINGS:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/maven-settings.xml}"

# Resolved before any cd: this script changes directory into the clone.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Defaulted FROM THE POM, not duplicated here. A hardcoded default drifts the
# moment the pom is bumped, and then a no-argument rebuild quietly installs a
# version nothing consumes while the build keeps using a stale jar.
VERSION="${1:-$(grep -oP '(?<=<mrcm.validator.version>)[^<]+' "$SCRIPT_DIR/../pom.xml")}"
[ -n "$VERSION" ] || { echo "FATAL: cannot read mrcm.validator.version from pom.xml" >&2; exit 1; }
COMMIT="${MRCM_COMMIT:-bfdf76f}"          # "Release 4.0.1"
WORKDIR="${MRCM_BUILD_DIR:-/data/work/mrcm-src}"
REPO="${MAVEN_REPO_LOCAL:-/data/m2}"
PATCH="${PATCH_FILE:-$SCRIPT_DIR/mrcm-validator-parallel.patch}"
# Applied after PATCH, and only on top of it: its context is the parallelised
# ValidationService.
FILTER_PATCH="${MODULE_FILTER_PATCH_FILE:-$SCRIPT_DIR/mrcm-validator-module-filter.patch}"

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

[ -f "$FILTER_PATCH" ] || { echo "FATAL: $FILTER_PATCH not found. Without it a run with" >&2
                            echo "       includedModules silently drops the 16 SEP and" >&2
                            echo "       Lateralizable assertions, at the same version." >&2
                            exit 1; }
git apply --check "$FILTER_PATCH" || { echo "FATAL: $FILTER_PATCH does not apply on top of $(basename "$PATCH")" >&2; exit 1; }
git apply "$FILTER_PATCH"
echo "    applied $(basename "$FILTER_PATCH")"

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

# -Ddependency-check.skip=true because snomed-parent-bom binds OWASP
# dependency-check into the lifecycle. With no cached NVD data and no API key it
# downloads the whole CVE database - 385,855 records at NVD's unauthenticated
# rate limit, which is hours - and that, not Maven resolution, is what made a
# clean-machine build look like it hung. A vulnerability scan is a thing to run
# deliberately, not a side effect of building a patched library.
# The patched validator calls SnomedQueryService.conceptsWithAnyAncestor, which
# exists only in our query-service fork. snomed-parent-bom pins
# snomed-query-service to its own release, so without this override the build
# compiles against the BOM's version and fails on a missing symbol. Taken from
# the pom that consumes both, so the two forks cannot drift apart.
SQS_VERSION="$(grep -oP '(?<=<snomed.query.service.version>)[^<]+' "$SCRIPT_DIR/../pom.xml")"
[ -n "$SQS_VERSION" ] || { echo "FATAL: cannot read snomed.query.service.version from pom.xml" >&2; exit 1; }
echo "==> compiling against snomed-query-service $SQS_VERSION"

mvn -q -s "$MAVEN_SETTINGS" -Dmaven.legacyLocalRepo=true -Ddependency-check.skip=true \
    -Dsnomed-query-service.version="$SQS_VERSION" \
    install -DskipTests -Dmaven.repo.local="$REPO"

JAR="$REPO/org/snomed/quality/mrcm-validator/$VERSION/mrcm-validator-$VERSION.jar"
[ -f "$JAR" ] || { echo "FATAL: $JAR not produced" >&2; exit 1; }

# Prove the parallelism is in the bytecode rather than only in the diff.
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
unzip -qo "$JAR" 'org/snomed/quality/validator/mrcm/ValidationService.class' -d "$tmp"
pool=$("${JAVA_HOME:-/usr}/bin/javap" -p "$tmp/org/snomed/quality/validator/mrcm/ValidationService.class" \
        | grep -c 'runInParallel' || true)
[ "$pool" -ge 1 ] || { echo "FATAL: runInParallel absent - patch did not take" >&2; exit 1; }

# And the module filter, for the same reason.
unzip -qo "$JAR" 'org/snomed/quality/validator/mrcm/SEPRefsetValidationService.class' -d "$tmp"
filter=$("${JAVA_HOME:-/usr}/bin/javap" -p "$tmp/org/snomed/quality/validator/mrcm/SEPRefsetValidationService.class" \
        | grep -c 'retainViolationsInModules' || true)
[ "$filter" -ge 1 ] || { echo "FATAL: retainViolationsInModules absent - module-filter patch did not take" >&2; exit 1; }

echo "==> installed $VERSION; bytecode confirms the parallel executor and the SEP module filter"
echo "    pom pin: <mrcm.validator.version>$VERSION</mrcm.validator.version>"
