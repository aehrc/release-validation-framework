#!/bin/bash
#
# Check out the two resource repositories that jib bakes into the image.
#
# PINNED BY DEFAULT. Upstream's version of this script clones a branch
# (master/develop) with no ref, which makes the image non-reproducible: what
# you get depends on when it was built, so an image that works can stop working
# with no change to this repository.
#
# That is not hypothetical. On 2026-08-07 the first rebuild in two years picked
# up two years of upstream drift and RVF failed to start:
#
#   FileNotFoundException: .../release-type-delta-previous-snapshot-validation-
#                              inferred-relationship_EDITION.sql
#
# IHTSDO had removed the _EDITION variants in 9cea111 (2024-08-19) while the
# mounted manifest.xml still referenced four of them. The RVF server never came
# up, the k8s job was killed after ~12 minutes, and the nightly produced no
# report.
#
# Override either ref to move deliberately:
#
#   DROOLS_RULES_REF=<sha|branch|tag> ASSERTIONS_REF=<sha> ./checkout-resources.sh
#
# WHEN BUMPING A PIN, CHECK IT AGAINST THE OTHER SIDE OF ITS PAIRING. Both fail
# silently if you get it wrong:
#
#   snomed-drools-rules  -> every service method AND DOMAIN TYPE the rules use
#                           must exist in ${snomed-drools.version} in pom.xml.
#                           Get this wrong and rule compilation dies, Drools
#                           contributes nothing, and the build still reports
#                           success. Checking only method names is not enough:
#                           the rules also reference domain TYPES, and DRL will
#                           silently resolve a missing one to an unrelated class
#                           of the same simple name.
#
#   assertions           -> every sqlFile named in the mounted manifest.xml must
#                           resolve. Get this wrong and RVF fails to start at
#                           all, which surfaces as a job timeout that looks like
#                           an infrastructure problem.
set -euo pipefail

# snomed-drools-rules @ 2026-07-28 (develop HEAD at time of pinning).
# NOTE: these rules require snomed-drools >= 6.0.0 for the domain type
# Annotation, and 6.0.0 is compiled for Java 25. They work with this branch's
# amazoncorretto:25 base image and BOM 4.0.0. They do NOT work on the Java 17
# branch, which must stay on 45e0d9e2 - the last commit before Annotation.
DROOLS_RULES_REF="${DROOLS_RULES_REF:-55795d5d19b1db99d2f5757e6aa397014aaaf268}"

# snomed-release-validation-assertions @ 2024-05-23 - the SAME pin production
# runs. Deliberately NOT moved forward, for two reasons.
#
# CORRECTED 2026-08-18. The pin above was wrong, and so was the plan it
# described ("move the assertions forward as its own change, once the engine
# comparison is banked"). That sequence is not possible.
#
# Upstream moved assertion-group membership out of Java and into the corpus:
# AssertionGroupImporter now reads groups.xml and policies.xml from the
# assertions directory (see AssertionGroupingXml). Neither file exists at
# fad36466 - that corpus root is LICENSE.md, manifest.xml, README.md, scripts.
# So the upgraded engine cannot start against the old corpus at all; every
# Spring-context test fails with
#     IOException: No manifest.xml file found in the assertions directory
#     Caused by: FileNotFoundException: .../policies.xml
# which is a misleading message - manifest.xml is present, policies.xml is not.
#
# The compatibility is therefore one-way, and it fixes the deployment order:
#
#   old engine + new corpus   WORKS      (measured as arm H, 2026-08-18)
#   new engine + old corpus   IMPOSSIBLE (missing groups.xml/policies.xml)
#
# So the corpus must move FIRST and can ship on its own, and the engine upgrade
# must carry the corpus with it. It is not two independent variables, and the
# original concern - that moving both at once makes differences unattributable -
# is answered by arm H having already measured the corpus change in isolation.
#
# The manifest remap that pairs with this ref lives on the chart side
# (aehrc/rvf e94fa2f). It is not needed for `mvn test`, which reads the corpus's
# own manifest.xml from the clone, but it IS needed for any containerised run
# that mounts the chart's testscripts.
ASSERTIONS_REF="${ASSERTIONS_REF:-0160dd2ee830cf77e10678de753c8fc06de671d6}"

DROOLS_RULES_DIR=snomed-drools-rules
ASSERTIONS_DIR=snomed-release-validation-assertions

rm -rf "$DROOLS_RULES_DIR" "$ASSERTIONS_DIR"

checkout() {
    local url=$1 dir=$2 ref=$3
    echo "==> $dir @ $ref"
    git clone --quiet "$url" "$dir"
    git -C "$dir" checkout --quiet "$ref"
    echo "    $(git -C "$dir" log -1 --format='%h %ad %s' --date=short)"
}

checkout https://github.com/IHTSDO/snomed-drools-rules.git "$DROOLS_RULES_DIR" "$DROOLS_RULES_REF"
checkout https://github.com/IHTSDO/snomed-release-validation-assertions.git "$ASSERTIONS_DIR" "$ASSERTIONS_REF"

# AU patch set over the pinned corpus. assertions-au/scripts holds AU-edited
# copies of individual SI scripts at their corpus-relative paths; README.md
# there says, per file, which ruling it implements and what it measured.
#
# OVERLAY ONLY, NEVER ADD. Every target must already exist in the clone. A pin
# bump that renames or deletes a script would otherwise leave the AU copy as a
# stray file no manifest entry names - RVF never runs it, and the AU fix
# silently disappears while the original, unpatched, runs under its new name.
#
# The bundled DuckDB store is published from the OVERLAID corpus, so changing a
# file here means republishing src/main/resources/duck/store.json (see
# duck/README.md); BundledStoreMatchesCorpusTest fails until you do.
AU_OVERLAY_DIR=assertions-au/scripts

echo "==> overlay $AU_OVERLAY_DIR -> $ASSERTIONS_DIR/scripts"
(cd "$AU_OVERLAY_DIR" && find . -type f | sort) | while read -r f; do
    f=${f#./}
    target="$ASSERTIONS_DIR/scripts/$f"
    if [ ! -f "$target" ]; then
        echo "ERROR: overlay target $target does not exist at $ASSERTIONS_REF" >&2
        echo "       (renamed or removed upstream?) - re-derive $AU_OVERLAY_DIR/$f" >&2
        exit 1
    fi
    cp "$AU_OVERLAY_DIR/$f" "$target"
    echo "    overlaid scripts/$f"
done

# AU rule patches over the pinned Drools rules. Same contract as the overlay
# above: every target must exist and must still equal the .drl.orig the patch
# was written against, or this fails - so a pin bump cannot silently ship an
# unpatched rule or a stale copy of one. What each patch does and what it
# measured: test-resources-au/upstream-proposal/UPSTREAM-FEEDBACK.md.
echo "==> rule patches test-resources-au/upstream-proposal -> $DROOLS_RULES_DIR"
RULES_DIR="$PWD/$DROOLS_RULES_DIR" test-resources-au/upstream-proposal/apply.sh

# Drools test resources, baked into the image by jib as /app/test-resources.
# The engine reads them from ONE place - a local directory or the bucket, never
# both - so the directory carries the whole international set plus the two AU
# files over it (test-resources-au/README.md). The deployment selects it with
# TESTRESOURCES_USECLOUD=false and TESTRESOURCES_LOCAL_PATH=test-resources/
# (relative to the image's /app working directory: the resource manager strips
# a leading '/', so an absolute path does not work).
TEST_RESOURCES_DIR=test-resources
echo "==> test resources -> $TEST_RESOURCES_DIR"
rm -rf "$TEST_RESOURCES_DIR"
./fetch-test-resources.sh "$TEST_RESOURCES_DIR" | sed 's/^/    /'
for f in semantic-tags.txt semantic-tag-hierarchies.txt; do
    cp "test-resources-au/$f" "$TEST_RESOURCES_DIR/$f"
    echo "    AU $f"
done
for f in semantic-tags.txt semantic-tag-hierarchies.txt cs_words.txt us-to-gb-terms-map.txt; do
    if [ ! -s "$TEST_RESOURCES_DIR/$f" ]; then
        echo "ERROR: $TEST_RESOURCES_DIR/$f missing or empty - the Drools phase cannot start without it" >&2
        exit 1
    fi
done
echo "    $(find "$TEST_RESOURCES_DIR" -type f | wc -l) files, required four present"
