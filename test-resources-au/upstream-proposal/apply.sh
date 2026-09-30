#!/usr/bin/env bash
# Apply the AU rule patches to the snomed-drools-rules clone.
#
# Called by checkout-resources.sh straight after the rules clone, so every image
# carries them. Run it by hand only after re-cloning the rules some other way.
#
# Each patch is a pair under rules/, at the file's path relative to the rules
# root:
#   <file>.orig     the upstream file the patch was written against
#   <file>.patched  the AU version that replaces it
# <file> is a rule (.drl) or the test-cases.json beside it: where a patch
# changes what a rule reports, its upstream test cases are patched to match, or
# DroolsRuleTestCasesTest fails the build on the upstream expectation.
#
# FAILS LOUDLY, NEVER SKIPS. The target must exist in the clone and must still be
# byte-identical to its .orig. A pin bump that renames a rule would otherwise
# leave the patch unapplied (the upstream rule runs, and the AU fix silently
# disappears), and one that edits a rule would have its upstream change
# overwritten by a stale copy. Either way: re-derive the .patched against the
# new upstream file, replace the .orig, and re-run.
#
# Finally rules.patch is regenerated from the pairs, so the unified diff sent
# upstream is always exactly what is applied.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../.." && pwd)"
rules_dir="${RULES_DIR:-$root/snomed-drools-rules}"

[ -d "$rules_dir" ] || { echo "ERROR: rules clone $rules_dir does not exist" >&2; exit 1; }

mapfile -t patches < <(cd "$here/rules" && find . -name '*.patched' | sed 's|^\./||' | sort)
[ "${#patches[@]}" -gt 0 ] || { echo "ERROR: no *.patched under $here/rules" >&2; exit 1; }

# Verify everything before touching anything, so a failure leaves the clone
# wholly upstream rather than half patched.
for p in "${patches[@]}"; do
    rel="${p%.patched}"
    target="$rules_dir/$rel"
    orig="$here/rules/$rel.orig"
    if [ ! -f "$orig" ]; then
        echo "ERROR: $rel.patched has no $rel.orig to check the clone against" >&2
        exit 1
    fi
    if [ ! -f "$target" ]; then
        echo "ERROR: patch target $rel does not exist in the rules clone" >&2
        echo "       (renamed or removed upstream?) - re-derive rules/$rel.patched" >&2
        exit 1
    fi
    if ! cmp -s "$orig" "$target"; then
        echo "ERROR: $rel in the rules clone no longer matches rules/$rel.orig" >&2
        echo "       (changed upstream?) - re-derive rules/$rel.patched against it" >&2
        diff -u --label "rules/$rel.orig" --label "clone/$rel" "$orig" "$target" | head -40 >&2 || true
        exit 1
    fi
done

for p in "${patches[@]}"; do
    rel="${p%.patched}"
    cp "$here/rules/$p" "$rules_dir/$rel"
    echo "    patched $rel"
done
echo "    applied ${#patches[@]} patch(es)"

# diff exits 1 when the files differ, which is the point of every pair.
{
    for p in "${patches[@]}"; do
        rel="${p%.patched}"
        diff -u --label "a/$rel" --label "b/$rel" "$here/rules/$rel.orig" "$here/rules/$p" || [ $? -eq 1 ]
    done
} > "$here/rules.patch"
echo "    regenerated upstream-proposal/rules.patch"
