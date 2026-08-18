#!/bin/bash
# Build a local image from the upgraded (post-merge) tree.
#
# This replaces `docker build .` for the catch-up branches. Upstream deleted the
# Dockerfile in favour of jib configured in pom.xml, which is why the merge
# reports a modify/delete conflict on it. Two consequences drive this script:
#
#   1. jib copies snomed-drools-rules/ and snomed-release-validation-assertions/
#      from the PROJECT BASEDIR into /app. They are no longer cloned inside the
#      image build, so checkout-resources.sh has to run first. A stale or absent
#      clone silently produces an image with the wrong assertions - the failure
#      mode that made the fork pin them in the first place.
#
#   2. snomed-parent-bom 4.0.0 compiles at release 25. The default JDK 17 fails
#      with "error: release version 25 not supported".
#
# jib:dockerBuild (not jib:build) writes to the local docker daemon rather than
# pushing to a registry - upstream's <to> defaults to docker.io/snomedinternational,
# which we must never push to.
set -euo pipefail

TREE=${TREE:-/Users/mcm184/Projects/rvf-catchup}
TAG=${TAG:-rvf-gate:catchup}
JDK=${JDK:-/Users/mcm184/Library/Java/JavaVirtualMachines/jdk-25.0.4+7/Contents/Home}
ASSERTIONS_REF=${ASSERTIONS_REF:-0160dd2ee830cf77e10678de753c8fc06de671d6}
EXPECT_SQL=${EXPECT_SQL:-453}   # scripts in corpus 0160dd2

export JAVA_HOME="$JDK"
export PATH="$JAVA_HOME/bin:$PATH"

cd "$TREE"
echo "==> tree      $(git branch --show-current) @ $(git rev-parse --short HEAD)"
echo "==> java      $(java -version 2>&1 | head -1)"

# The resources must be present AND at the ref we mean, because jib will happily
# bake whatever is on disk.
if [ ! -d snomed-release-validation-assertions ] \
   || [ "$(git -C snomed-release-validation-assertions rev-parse HEAD)" != "$ASSERTIONS_REF" ]; then
    echo "==> (re)cloning resources at $ASSERTIONS_REF"
    ASSERTIONS_REF="$ASSERTIONS_REF" ./checkout-resources.sh
fi
GOT=$(git -C snomed-release-validation-assertions rev-parse HEAD)
[ "$GOT" = "$ASSERTIONS_REF" ] || { echo "FATAL: assertions at $GOT, wanted $ASSERTIONS_REF"; exit 1; }
echo "==> assertions $GOT"

# The upgraded engine reads these two from the assertions directory; without
# them Spring dies with a misleading "No manifest.xml file found".
for f in policies.xml groups.xml manifest.xml; do
    [ -f "snomed-release-validation-assertions/$f" ] \
      || { echo "FATAL: corpus has no $f - the upgraded engine cannot start"; exit 1; }
done
echo "==> corpus carries policies.xml, groups.xml, manifest.xml"

# NOT -o here. Maven dependencies resolve offline happily, but jib must pull the
# base image (amazoncorretto:25) over the network, and in offline mode it fails
# with "Cannot run Jib in offline mode; amazoncorretto:25 not found in local Jib
# cache". Package offline, then build the image online.
echo "==> package (offline)"
mvn -o -q -DskipTests package

echo "==> jib:dockerBuild -> $TAG"
# The pom declares both arm64 and amd64 so CI can publish a manifest list. The
# local docker engine cannot accept one ("multi-platform image building not
# supported when pushing to Docker engine"), so pin to the host architecture for
# local builds. This is a LOCAL-ONLY narrowing - do not copy it into CI, which
# needs both platforms.
ARCH=$(uname -m); case "$ARCH" in arm64|aarch64) JARCH=arm64 ;; *) JARCH=amd64 ;; esac
echo "==> jib:dockerBuild -> $TAG  (single platform: linux/$JARCH)"
mvn -DskipTests jib:dockerBuild \
    -Djib.to.image="$TAG" \
    -Djib.from.platforms="linux/$JARCH" \
    -Djib.container.creationTime=USE_CURRENT_TIMESTAMP \
    "$@"

echo "==> built:"
docker image inspect "$TAG" --format '    {{.Id}}  {{.Created}}' 2>/dev/null || true
# Verify what is actually IN the image, not what we asked for. jib strips .git,
# so the ref cannot be read back - count the scripts instead.
#
# Do NOT use `find` here, and never hide its stderr: upstream's base image is
# amazoncorretto:25, which has no find binary. `find ... 2>/dev/null | wc -l`
# reports a confident 0 for a perfectly good image, which is exactly the kind of
# false negative that sends you debugging the wrong thing.
echo "==> scripts inside the image (expect $EXPECT_SQL for $ASSERTIONS_REF):"
GOT_SQL=$(docker run --rm --entrypoint sh "$TAG" -c '
    T=0
    for d in /app/snomed-release-validation-assertions/scripts/*/ \
             /app/snomed-release-validation-assertions/scripts/*/*/; do
        [ -d "$d" ] || continue
        T=$((T + $(ls "$d"*.sql 2>/dev/null | wc -l)))
    done
    echo $T')
GOT_SQL=$(echo "$GOT_SQL" | tr -d " \r")
echo "    $GOT_SQL"
[ "$GOT_SQL" = "$EXPECT_SQL" ] || { echo "FATAL: image carries $GOT_SQL scripts, expected $EXPECT_SQL"; exit 1; }

# The upgraded engine reads these from the assertions dir at startup.
for f in policies.xml groups.xml manifest.xml; do
    docker run --rm --entrypoint sh "$TAG" -c "[ -f /app/snomed-release-validation-assertions/$f ]" \
      || { echo "FATAL: image has no $f"; exit 1; }
done
echo "==> image carries policies.xml, groups.xml, manifest.xml"
echo "==> OK: $TAG"
