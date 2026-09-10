#!/usr/bin/env bash
# The AMT arm of the fixture A/B: both engines, per assertion, on the amtv4 group.
#
# WHY IT IS A SEPARATE SCRIPT. The AMT assertions are not in this repository and
# must not be: this repo is public and they are not. They live in the private
# `aehrc/rvf` corpus, so this script REFUSES rather than guesses when it cannot
# find them, and says exactly what to point it at.
#
#   ci/fixture_ab_amt.sh
#   AMT_CORPUS=/path/to/corpus-amtv4 AMT_STORE=/path/to/store.json ci/fixture_ab_amt.sh
#
# WHAT IT RUNS. Exactly the same two engines and the same gate as
# ci/fixture_ab.sh, with two things swapped:
#
#   * the MySQL leg imports the AMT corpus instead of the international one, so
#     the amtv4 group exists at all - the manifest declares 200 scripts under
#     `<useCase group="amtv4">` plus pre-requisites under `amtv4-prereq`;
#   * the DuckDB leg is pointed at the MERGED store (560 assertions), because the
#     one baked into the jar holds the international 360 and would otherwise be
#     compared against a corpus it does not contain - two different assertion
#     sets, every difference reported as a divergence.
#
# MySQL first, then DuckDB, which is the only order that tells you anything: if
# the incumbent cannot run an assertion there is nothing to be parity WITH.
set -euo pipefail
cd "$(dirname "$0")/.."

AMT_CORPUS="${AMT_CORPUS:-/data/work/corpus-amtv4}"
AMT_STORE="${AMT_STORE:-/data/work/amt-build/store.json}"
ASSERTION_GROUPS="${*:-amtv4}"

for required in "$AMT_CORPUS/manifest.xml" "$AMT_STORE"; do
  if [ ! -e "$required" ]; then
    echo "Missing $required."
    echo
    echo "The AMT assertions are not in this repository and must not be - it is"
    echo "public and they are not. Point this at a checkout of the private"
    echo "corpus and a store built from it:"
    echo
    echo "  AMT_CORPUS=<corpus dir> AMT_STORE=<store.json> ci/fixture_ab_amt.sh"
    echo
    echo "duck/ASSERTION-PACKS.md has the recipe for building the store."
    exit 2
  fi
done

# RVF resolves the corpus through Spring's FileSystemResource, which DROPS a
# leading slash: given /data/work/corpus-amtv4 it looks for
# data/work/corpus-amtv4/manifest.xml relative to the working directory and dies
# in bean creation before serving anything. A symlink in the working directory is
# the way to point at a corpus that lives elsewhere.
ln -sfn "$AMT_CORPUS" amt-corpus
trap 'rm -f amt-corpus' EXIT

FIXTURES="src/test/resources"
PROSPECTIVE="SnomedCT_RegressionTest_20130731"
PREVIOUS="SnomedCT_RegressionTest_20130131"
mkdir -p shared-jobs/releases store/previous

pack() {   # <fixture-dir> <output-zip>
  python3 - "$1" "$2" <<'PY'
import pathlib, sys, zipfile


def dedupe_snapshot(data: bytes, name: str) -> bytes:
    """Drops byte-identical duplicate rows from a snapshot, for THIS ARM ONLY.

    The AMT corpus's pre-requisites.sql builds concept_active with a UNIQUE
    index on id, so a snapshot containing the same id twice makes it die with
    `Duplicate entry '700132006' for key 'concept_active.concept_active_id_ix'`
    - and because every AMT assertion depends on those *_active tables, ONE
    duplicate row costs 184 of 239 assertions, which come back incomplete.

    The international fixture keeps that duplicate on purpose: it is what
    component-centric-snapshot-concept-unique-id exists to find, and it does
    find it. So the row is not removed there, and this arm gets a release the
    prerequisite can actually load.

    THE REAL DEFECT IS UPSTREAM, and it is worth stating plainly: the AMT
    prerequisite cannot process a release containing the very defect the corpus
    has an assertion to detect. On a real release with a duplicate concept, AMT
    validation would lose 184 assertions and report them as incomplete rather
    than saying why. pre-requisites.sql should build that table with a
    deduplicating select, or without the unique index.
    """
    text = data.decode('utf-8')
    eol = '\r\n' if '\r\n' in text else '\n'
    lines = text.rstrip(eol).split(eol)
    if len(lines) < 2:
        return data
    header, seen, kept, dropped = lines[0], set(), [], 0
    for line in lines[1:]:
        key = line.split('\t')[0]
        if key in seen:
            dropped += 1
            continue
        seen.add(key)
        kept.append(line)
    if dropped:
        print(f"    {name}: dropped {dropped} duplicate snapshot row(s)")
    return (eol.join([header] + kept) + eol).encode('utf-8')


src, out = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])
base = src / 'RF2Release'
with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as z:
    for p in sorted(base.rglob('*')):
        if not p.is_file():
            continue
        data = p.read_bytes()
        if p.suffix == '.txt' and b'\r\n' not in data:
            # RF2 is CRLF and RVF's loader reads `lines terminated by '\r\n'`.
            data = data.replace(b'\n', b'\r\n')
        if 'Snapshot' in p.parts and p.suffix == '.txt':
            data = dedupe_snapshot(data, p.name)
        z.writestr(str(pathlib.Path(src.name) / p.relative_to(base)), data)
print(f"  {out}  {out.stat().st_size} bytes")
PY
}

echo "=== packing the fixture ==="
pack "$FIXTURES/$PROSPECTIVE" "shared-jobs/releases/$PROSPECTIVE.zip"
pack "$FIXTURES/$PREVIOUS" "store/previous/$PREVIOUS.zip"
rm -f "store/binaryArchives/$PREVIOUS.zip"

echo "=== AMT corpus: $AMT_CORPUS ==="
python3 - "$AMT_CORPUS/manifest.xml" <<'PY'
import re, sys, pathlib
x = pathlib.Path(sys.argv[1]).read_text(encoding='utf-8-sig')
for group in ('amtv4-prereq', 'amtv4'):
    m = re.search(r'<useCase group="' + group + r'"[^>]*>(.*?)</useCase>', x, re.S)
    n = len(re.findall(r'<script ', m.group(1))) if m else 0
    print(f"  {group}: {n} scripts")
PY

HEAP="${HEAP:-3g}" CORPUS="./amt-corpus/" DUCK_STORE="$AMT_STORE" \
  BASELINES="${BASELINES:-ci/known-engine-divergences.json ci/known-fixture-divergences.json ci/known-fixture-divergences-amt.json}" \
  ci/engine_ab_stack.sh \
    --release "releases/$PROSPECTIVE.zip" \
    --previous "$PREVIOUS.zip" \
    --groups "$ASSERTION_GROUPS"
