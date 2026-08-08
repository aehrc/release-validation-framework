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

# snomed-release-validation-assertions @ 2026-07-27 (master HEAD at time of
# pinning). Current, deliberately: this branch exists to run the latest upstream
# assertions alongside the CSIRO ones.
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
