#!/usr/bin/env bash
# Re-apply the AU rule patches to the snomed-drools-rules clone.
#
# checkout-resources.sh clones the rules at a pinned commit, so these patches do
# not survive a refresh - and a silent revert reads as a content regression, not
# as a reverted patch. Run this after any rules re-clone.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../.." && pwd)"
n=0
while IFS= read -r p; do
    rel="${p#"$here/rules/"}"; rel="${rel%.patched}"
    target="$root/snomed-drools-rules/$rel"
    [ -f "$target" ] || { echo "MISSING $target - rules layout changed?" >&2; exit 1; }
    cp "$p" "$target"; n=$((n+1))
    echo "  patched $rel"
done < <(find "$here/rules" -name "*.patched" | sort)
echo "applied $n rule patch(es)"
