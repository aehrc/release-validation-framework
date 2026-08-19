#!/usr/bin/env bash
# Re-apply the AU exemption patch to the snomed-drools-rules clone.
# The clone is a detached-HEAD checkout that build/fetch steps reset, so the
# patch does not survive a rebuild - run this after any rules refresh.
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
target="$root/snomed-drools-rules/common-authoring/terms/fsn-term-having-a-same-synonym-term/FsnTermHavingASameSynonynTerm.drl"
cp "$(dirname "$0")/FsnTermHavingASameSynonynTerm.drl.patched" "$target"
echo "patched $target"
