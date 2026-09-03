#!/usr/bin/env bash
# Build and install the three forked libraries this repo pins, on a machine that
# has never built them.
#
# WHY THIS EXISTS
#
# The pom pins three libraries to `-aehrc-perf` versions that exist in NO remote
# repository - they are built from IHTSDO sources plus the patches in this
# directory and installed locally. So `mvn package` on a fresh checkout fails to
# resolve them, which is the first thing anyone hits when they try to build the
# image somewhere else.
#
# It also removes a trap: each individual build script carries its own default
# version, and those defaults have drifted behind the pom (6.1.1 against 6.1.3,
# 4.0.3 against 4.0.4). Running them with no argument installs artefacts the pom
# does not want and the build still fails, one version number away from working.
# This reads the versions FROM THE POM so there is one source of truth.
#
# Usage, from anywhere:
#
#     ./duck/build-pinned-forks.sh
#
# KNOWN LIMITATION, 2026-09-03. This does NOT reliably work on a machine that
# has not built it before. The forks inherit from `org.snomed:snomed-parent-bom`,
# which is not on Maven Central (404) and comes only from
# nexus3.ihtsdotools.org. Fetching that POM by hand takes 1.4s, but a full
# resolution against an EMPTY local repository hangs - 40 minutes with no
# output, then an HTTP reactor error. These scripts previously passed `-o`
# (offline), which hid this completely: they only ever worked against a warm
# local repository, i.e. only on the machine that had already built them.
#
# So read this as "rebuild the forks where they have been built before".
# Making the image reproducible anywhere needs the three artefacts published to
# a feed instead - see k8s/HANDOVER.md.
#
# Needs: JDK 25, maven, git, network to github.com, Maven Central and
# nexus3.ihtsdotools.org.
# Honours MAVEN_REPO_LOCAL (default: maven's own ~/.m2/repository) and
# FORKS_BUILD_DIR (default: a temporary directory).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

BUILD_DIR="${FORKS_BUILD_DIR:-$(mktemp -d "${TMPDIR:-/tmp}/rvf-forks.XXXXXX")}"
mkdir -p "$BUILD_DIR"

# Read the pinned versions out of the pom rather than duplicating them here.
version_of() {
  python3 - "$1" <<'PY'
import re, sys, pathlib
prop = sys.argv[1]
pom = pathlib.Path(__file__).parent if False else None
text = pathlib.Path('pom.xml').read_text()
m = re.search(rf'<{re.escape(prop)}>([^<]+)</{re.escape(prop)}>', text)
if not m:
    raise SystemExit(f'FATAL: <{prop}> not found in pom.xml')
print(m.group(1))
PY
}

cd "$ROOT"
DROOLS_VERSION="$(version_of snomed.drools.version)"
MRCM_VERSION="$(version_of mrcm.validator.version)"
SQS_VERSION="$(version_of snomed.query.service.version)"

echo "==> pom pins:"
echo "      snomed-drools        $DROOLS_VERSION"
echo "      mrcm-validator       $MRCM_VERSION"
echo "      snomed-query-service $SQS_VERSION"
echo "==> build scratch: $BUILD_DIR"
echo

# MAVEN_REPO_LOCAL and MAVEN_OPTS must agree, or this builds into one repository
# and verifies against another - which reports all three forks missing after
# installing all three of them. If the caller already pinned the repository in
# MAVEN_OPTS, adopt that; otherwise MAVEN_REPO_LOCAL wins and is forced into
# MAVEN_OPTS.
if [ -z "${MAVEN_REPO_LOCAL:-}" ]; then
  from_opts="$(printf '%s' "${MAVEN_OPTS:-}" \
                 | grep -oE '\-Dmaven\.repo\.local=[^ ]+' | tail -1 | cut -d= -f2- || true)"
  MAVEN_REPO_LOCAL="${from_opts:-$HOME/.m2/repository}"
fi
export MAVEN_REPO_LOCAL
MAVEN_OPTS="$(printf '%s' "${MAVEN_OPTS:-}" | sed -E 's/-Dmaven\.repo\.local=[^ ]+//g')"
export MAVEN_OPTS="${MAVEN_OPTS} -Dmaven.repo.local=$MAVEN_REPO_LOCAL"
echo "==> maven repository: $MAVEN_REPO_LOCAL"

DROOLS_BUILD_DIR="$BUILD_DIR/snomed-drools"   \
  bash "$SCRIPT_DIR/build-drools-engine.sh" "$DROOLS_VERSION"

MRCM_BUILD_DIR="$BUILD_DIR/mrcm-validator"    \
  bash "$SCRIPT_DIR/build-mrcm-validator.sh" "$MRCM_VERSION"

QUERY_SERVICE_BUILD_DIR="$BUILD_DIR/query-service" \
  bash "$SCRIPT_DIR/build-query-service.sh" "$SQS_VERSION"

# Gate on the artefacts actually being resolvable, because each script's own
# bytecode checks only prove the patch took - not that the version the pom asks
# for is the version that landed.
echo
echo "==> verifying the pom's pins resolve"
missing=0
check() {
  local path="$1" label="$2"
  # The trailing /* matters: without it the pattern matches the version
  # DIRECTORY and never a jar inside it, so everything reads as missing.
  if find "$MAVEN_REPO_LOCAL" -path "*$path/*" -name '*.jar' 2>/dev/null | grep -q .; then
    echo "      ok      $label"
  else
    echo "      MISSING $label  (expected under $MAVEN_REPO_LOCAL/$path)"
    missing=1
  fi
}
check "org/ihtsdo/drools/snomed-drools-engine/$DROOLS_VERSION" "snomed-drools-engine $DROOLS_VERSION"
check "org/snomed/quality/mrcm-validator/$MRCM_VERSION"        "mrcm-validator $MRCM_VERSION"
check "org/ihtsdo/otf/snomed-query-service/$SQS_VERSION"       "snomed-query-service $SQS_VERSION"

if [ "$missing" -ne 0 ]; then
  echo "FATAL: at least one pinned fork did not install" >&2
  exit 1
fi

echo
echo "==> all three pinned forks installed into $MAVEN_REPO_LOCAL"
echo "    the image can now be built and pushed:"
echo
echo "      TOKEN=\$(az acr login --name ontoserver --expose-token --output tsv --query accessToken)"
echo "      mvn -B -ntp package -DskipTests jib:build \\"
echo "          -Djib.from.platforms=linux/amd64 \\"
echo "          -Djib.to.image=ontoserver.azurecr.io/aehrc-rvf/release-validation-framework:$(version_of project.version 2>/dev/null || echo 9.0.1)-duckdb \\"
echo "          -Djib.to.tags=latest \\"
echo "          -Djib.to.auth.username=00000000-0000-0000-0000-000000000000 \\"
echo "          -Djib.to.auth.password=\"\$TOKEN\""
