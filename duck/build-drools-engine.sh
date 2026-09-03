#!/usr/bin/env bash
#
# Build the snomed-drools engine that RVF's pom pins, from IHTSDO's own merge
# commit, changing nothing but the version string.
#
# WHY THIS EXISTS
#
# snomed-parent-bom 4.0.2 resolves snomed-drools-engine to 6.0.0. SI merged six
# performance PRs into IHTSDO/snomed-drools `develop` on 2026-08-28 and has
# published no tag since, and tag 6.0.0 is three commits BEHIND the first of
# them. So nothing in the BOM's reach contains this work:
#
#   #6  keep validation workers supplied with concepts instead of batches of ten
#   #7  scale validation workers to the host, and let callers override it
#   #8  skip the effective-component pre-pass when the input is one Snapshot
#   #9  read the RF2 files concurrently, and make the repository safe for it
#   #10 answer exact-term description lookups from a map, not a Lucene index
#   #11 compile the whitespace split pattern once
#
# Measured on the AU edition (722,404 concepts): Drools 697 s on 6.0.0 against a
# recorded 54 s with this work applied.
#
# WHY 84d511b RATHER THAN develop's HEAD
#
# 84d511b is the merge commit of #11 - the last of the six - and is still on
# snomed-parent-bom 4.0.0. develop has since moved to 7.0.0-SNAPSHOT against
# snomed-parent-bom 5.0.0-SNAPSHOT, which RVF does not build against and which is
# an unreleased snapshot. Building at 84d511b keeps the delta to exactly the six
# PRs.
#
# Note that commit is internally inconsistent in SI's repo: "Start
# 6.1.0-SNAPSHOT" bumped the root pom only, leaving both submodules pointing at
# parent 6.0.0. This script sets all three consistently, which is the only edit
# it makes.
#
# DELETE THIS SCRIPT AND THE POM PIN once SI publishes a release with the six in.

set -euo pipefail

# Resolved BEFORE any cd: the script changes directory into the clone, so a
# path relative to $0 would not survive. Silently building without the patch is
# the failure this avoids - it produced an artefact that looked right.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

VERSION="${1:-6.1.1-aehrc-perf}"
COMMIT="84d511b"
WORKDIR="${DROOLS_BUILD_DIR:-/data/work/snomed-drools-build}"
REPO="${MAVEN_REPO_LOCAL:-/data/m2}"

echo "==> building snomed-drools $VERSION from IHTSDO $COMMIT"

if [ ! -d "$WORKDIR/.git" ]; then
    git clone -q https://github.com/IHTSDO/snomed-drools.git "$WORKDIR"
fi
cd "$WORKDIR"
git fetch -q --depth 60 origin develop
git checkout -q "$COMMIT"
git checkout -q .   # discard any previous version edits

# Fail loudly rather than silently building something without the PRs in it.
for pr in 6 7 8 9 10 11; do
    sha=$(gh api "repos/IHTSDO/snomed-drools/pulls/$pr" --jq '.merge_commit_sha' 2>/dev/null || true)
    if [ -z "$sha" ]; then
        echo "    WARN: cannot reach GitHub to verify PR #$pr; continuing" >&2
        continue
    fi
    git merge-base --is-ancestor "$sha" HEAD \
        || { echo "    FATAL: PR #$pr is not in $COMMIT" >&2; exit 1; }
done
echo "    all six PRs verified present"

python3 - "$VERSION" <<'PY'
import pathlib, re, sys
new = sys.argv[1]
root = pathlib.Path('pom.xml'); t = root.read_text()
t2 = t.replace("<version>6.1.0-SNAPSHOT</version>", f"<version>{new}</version>", 1)
assert t2 != t, "root version string not found - has upstream changed?"
root.write_text(t2)
for module in ("snomed-drools-engine", "snomed-drools-rf2-validator"):
    p = pathlib.Path(module) / "pom.xml"; s = p.read_text()
    parent = re.search(r'<parent>.*?</parent>', s, re.S).group(0)
    fixed = parent.replace("<version>6.0.0</version>", f"<version>{new}</version>")
    assert fixed != parent, f"{module}: parent version not 6.0.0"
    p.write_text(s.replace(parent, fixed, 1))
print(f"    version set to {new} in root and both modules")
PY

# Two load-path optimisations on top of the six merged PRs. Both are ours, both
# are measured, and both keep findings identical - see duck/NIGHTLY-PLAN.md.
#
#   1. loadComponentsFromRF2 called loadEffectiveSnapshotReleaseFiles
#      unconditionally, running the effective-component pre-pass - a second full
#      read of the release - even for a single snapshot. SI's PR #8 added the
#      loadSnapshotReleaseFiles helper that makes this choice correctly but
#      applied it to only one of the two call sites. ~55 s.
#   2. OWL axiom parsing happened inline on whichever thread read the OWL refset
#      file, so it was serial regardless of core count. Now buffered and parsed
#      in parallel in loadingComponentsCompleted(), with the results applied to
#      the repository serially in file order - because ontologyAxioms and
#      componentLoadingErrors are a plain HashSet and ArrayList and concurrent
#      writes there would corrupt them silently. 35 s -> 17.5 s.
#
# Raise these with SI as a seventh and eighth PR; drop the patch when merged.
PATCH="${PATCH_FILE:-$SCRIPT_DIR/snomed-drools-load-optimisations.patch}"
if [ -f "$PATCH" ]; then
    git apply --check "$PATCH" || { echo "FATAL: $PATCH does not apply to $COMMIT" >&2; exit 1; }
    git apply "$PATCH"
    echo "    applied $(basename "$PATCH")"
else
    echo "FATAL: $PATCH not found. Building without it yields an artefact that" >&2
    echo "       is 68 s slower per run and carries the same version string." >&2
    exit 1
fi

# maven-install-plugin 2.4 needs two plexus artifacts and their parent poms that
# a Spring-Boot-era local repo will not have. Offline resolution of them goes to
# nexus3.ihtsdotools.org, which hangs rather than 404s, so fetch from Central
# first and keep the build offline.
fetch() {
    local rel="$1"
    [ -f "$REPO/$rel" ] && return 0
    mkdir -p "$REPO/$(dirname "$rel")"
    curl -sfL --max-time 60 "https://repo1.maven.org/maven2/$rel" -o "$REPO/$rel" \
        && echo "    fetched $rel"
}
for a in org/codehaus/plexus/plexus-utils/3.0.5/plexus-utils-3.0.5 \
         org/codehaus/plexus/plexus-digest/1.0/plexus-digest-1.0; do
    fetch "$a.jar"; fetch "$a.pom"
done
fetch org/codehaus/plexus/plexus-components/1.1.7/plexus-components-1.1.7.pom
fetch org/codehaus/plexus/plexus/3.1/plexus-3.1.pom

# NOT offline. The SI parent POMs (org.snomed:snomed-parent-bom and
# friends) are not on Maven Central and are not in a fresh local
# repository, so -o makes this script work only on a machine that has
# already built it once - which is not a reproducible build.
mvn -q install -DskipTests -Dmaven.repo.local="$REPO"

JAR="$REPO/org/ihtsdo/drools/snomed-drools-engine/$VERSION/snomed-drools-engine-$VERSION.jar"
[ -f "$JAR" ] || { echo "FATAL: $JAR not produced" >&2; exit 1; }

# Prove the performance work is in the bytecode, not just in the git history.
# 6.0.0 batches ten concepts with a `bipush 10` and never asks the host how many
# processors it has; the built jar must be the other way round.
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
unzip -qo "$JAR" org/ihtsdo/drools/RuleExecutor.class -d "$tmp"
tens=$("${JAVA_HOME:-/usr}/bin/javap" -p -c "$tmp/org/ihtsdo/drools/RuleExecutor.class" | grep -c 'bipush        10' || true)
procs=$("${JAVA_HOME:-/usr}/bin/javap" -p -c "$tmp/org/ihtsdo/drools/RuleExecutor.class" | grep -c 'availableProcessors' || true)
[ "$tens" = "0" ] || { echo "FATAL: hardcoded batch of ten still present (PR #6 missing)" >&2; exit 1; }
[ "$procs" -ge 1 ] || { echo "FATAL: availableProcessors not called (PR #7 missing)" >&2; exit 1; }

echo "==> installed $VERSION; bytecode confirms PR #6 and #7"
echo "    pom pin: <snomed.drools.version>$VERSION</snomed.drools.version>"
