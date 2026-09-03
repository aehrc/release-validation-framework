#!/usr/bin/env bash
# Push the DuckDB-engine RVF server image to ACR, without Docker.
#
# WHY A SCRIPT
#
# Three things about this push are easy to get wrong and expensive to get wrong:
#
#   1. The repository `aehrc-rvf/release-validation-framework` also holds
#      MySQL-engine builds of RVF, so the ENGINE MUST BE IN THE TAG. A bare
#      `9.0.1` would be ambiguous at best and would shadow an existing build at
#      worst. This script refuses to push a tag without the engine suffix.
#   2. `az acr login` wants a Docker daemon. `--expose-token` does not, and jib
#      pushes registry layers itself, so no daemon is needed anywhere.
#   3. The registry may not be in the subscription you are defaulted to. The
#      tenant literally named `Ontoserver` is NOT the same thing as the CSIRO
#      subscription named "ontoserver dev", and one of them may require MFA that
#      the other does not.
#
# It prints what already exists before pushing anything, so a collision is a
# thing you see rather than a thing you cause.
#
# Usage:
#     ./duck/push-image.sh                 # push <version>-duckdb + an immutable companion
#     DRY_RUN=1 ./duck/push-image.sh       # resolve and report, push nothing

set -euo pipefail

REGISTRY_NAME="${REGISTRY_NAME:-ontoserver}"
REGISTRY_HOST="${REGISTRY_HOST:-${REGISTRY_NAME}.azurecr.io}"
REPOSITORY="${REPOSITORY:-aehrc-rvf/release-validation-framework}"
ENGINE_SUFFIX="${ENGINE_SUFFIX:-duckdb}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$ROOT"

command -v az >/dev/null || { echo "FATAL: az CLI not found" >&2; exit 1; }
command -v mvn >/dev/null || { echo "FATAL: maven not found" >&2; exit 1; }

VERSION="$(python3 - <<'PY'
import xml.etree.ElementTree as ET
ns = {'m': 'http://maven.apache.org/POM/4.0.0'}
root = ET.parse('pom.xml').getroot()
node = root.find('m:version', ns)
if node is None or not (node.text or '').strip():
    raise SystemExit('FATAL: no project <version> in pom.xml')
print(node.text.strip())
PY
)"
SHA="$(git rev-parse --short=12 HEAD)"
PRIMARY="${VERSION}-${ENGINE_SUFFIX}"
IMMUTABLE="${PRIMARY}.${SHA}"

case "$PRIMARY" in
  *-"$ENGINE_SUFFIX") : ;;
  *) echo "FATAL: refusing to push '$PRIMARY' - the engine must be in the tag" >&2; exit 1 ;;
esac

echo "==> image      $REGISTRY_HOST/$REPOSITORY"
echo "    tags       $PRIMARY  (moving within this version)"
echo "               $IMMUTABLE  (immutable, traceable to this commit)"
echo "    tree       $(git rev-parse --abbrev-ref HEAD) @ $SHA$( [ -n "$(git status --porcelain)" ] && echo '  *** DIRTY ***')"
echo

# --- find the subscription that actually owns the registry -------------------
echo "==> locating registry '$REGISTRY_NAME'"
FOUND_SUB=""
while read -r sub_id sub_name; do
  [ -n "$sub_id" ] || continue
  if az acr show --name "$REGISTRY_NAME" --subscription "$sub_id" \
        --query loginServer -o tsv >/dev/null 2>&1; then
    echo "    found in: $sub_name  ($sub_id)"
    FOUND_SUB="$sub_id"
    break
  fi
  echo "    not in  : $sub_name"
done < <(az account list --query "[].[id,name]" -o tsv 2>/dev/null | sed 's/\t/ /')

if [ -z "$FOUND_SUB" ]; then
  cat >&2 <<EOF

FATAL: '$REGISTRY_NAME' is not visible in any subscription this login can see.

The registry is probably in a tenant you have not authenticated against. Your
tenant list may include one named for the registry that is DISTINCT from a
subscription named similarly - and it may enforce MFA or conditional access that
your default tenant does not. Try:

    az login --tenant <tenant-id>
    az account set --subscription <subscription-id>

then re-run this script. If a conditional-access policy blocks token issuance
entirely, this push has to happen from a managed device or from CI - see
az/azure-pipeline.image.yml, which is the preferred route anyway.
EOF
  exit 1
fi
az account set --subscription "$FOUND_SUB"

# --- show what is already there ----------------------------------------------
echo
echo "==> existing tags in $REPOSITORY"
if az acr repository show-tags --name "$REGISTRY_NAME" --repository "$REPOSITORY" \
      --output tsv 2>/dev/null | sort | sed 's/^/      /'; then
  :
else
  echo "      (repository absent or not readable - a first push will create it)"
fi

if az acr repository show-tags --name "$REGISTRY_NAME" --repository "$REPOSITORY" \
      --output tsv 2>/dev/null | grep -qx "$IMMUTABLE"; then
  echo
  echo "FATAL: $IMMUTABLE already exists - this commit has been pushed already." >&2
  echo "       Commit your changes or push a different tree." >&2
  exit 1
fi

if [ "${DRY_RUN:-}" = "1" ]; then
  echo
  echo "==> DRY_RUN=1, stopping before the push"
  exit 0
fi

# --- token, then push ---------------------------------------------------------
echo
echo "==> requesting a refresh token (no Docker required)"
TOKEN="$(az acr login --name "$REGISTRY_NAME" --expose-token \
           --output tsv --query accessToken)"
[ -n "$TOKEN" ] || { echo "FATAL: no token returned" >&2; exit 1; }

echo "==> building the three pinned forks if they are missing"
"$SCRIPT_DIR/build-pinned-forks.sh" >/dev/null

echo "==> pushing"
# The null GUID is ACR's documented username for a token credential.
mvn -B -ntp package -DskipTests jib:build \
    -Djib.from.platforms=linux/amd64 \
    -Djib.to.image="$REGISTRY_HOST/$REPOSITORY:$PRIMARY" \
    -Djib.to.tags="$IMMUTABLE" \
    -Djib.to.auth.username=00000000-0000-0000-0000-000000000000 \
    -Djib.to.auth.password="$TOKEN"

echo
echo "==> pushed"
echo "      $REGISTRY_HOST/$REPOSITORY:$PRIMARY"
echo "      $REGISTRY_HOST/$REPOSITORY:$IMMUTABLE"
echo
echo "    k8s/rvf-aks.yaml already references :$PRIMARY, so hand that manifest"
echo "    to whoever applies it. To pin the immutable tag instead:"
echo
echo "      kubectl -n rvf set image deploy/rvf-api    rvf=$REGISTRY_HOST/$REPOSITORY:$IMMUTABLE"
echo "      kubectl -n rvf set image deploy/rvf-worker rvf=$REGISTRY_HOST/$REPOSITORY:$IMMUTABLE"
