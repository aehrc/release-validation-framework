#!/usr/bin/env python3
"""Fixes the assertions ci/assertion_lint.py reports as unable to fire.

Four shapes, twelve files, one line each. Every replacement preserves the
assertion's evident intent - taken from its own name, its docstring and, for the
one threshold, the function it calls - and none of them changes what a healthy
assertion does.

    not exists(select get_cr_ADRS_PT(X) = BINARY 'name')        7 files
      -> coalesce(get_cr_ADRS_PT(X), '') <> BINARY 'name'

      "Concept X has SYN = <name>". Wrapping a comparison in EXISTS asks
      whether the comparison HAS a result, which it always does. COALESCE is
      not decoration: the port returns NULL when the concept has no ADRS
      preferred term, and `NULL <> 'name'` is NULL, so without it the assertion
      would still miss the case where the term is absent entirely - which is
      the most likely way for it to be wrong.

    not exists(select count(1) from T)                          3 files
      -> not exists(select 1 from T)

      "<file> has been populated". COUNT with no GROUP BY returns one row even
      over an empty table, so the check was inverted into a constant. This is
      the sharpest of the four: all three exist to detect an empty file.

    not exists(select isChildOf_cr(a, b))                       1 file
      -> isChildOf_cr(a, b) = 0

      "IS A child of <concept>". The port returns a count, so compare it.

    not exists(select TRUNCATE(get_cr_PercentDefined(X), 1))     1 file
      -> TRUNCATE(get_cr_PercentDefined(X), 1) <> 99.8

      The threshold is in the assertion's own detail text - "02 DNF 99.8%
      Medicinal Product Pack are 900000000000073002|Defined|" - and
      get_cr_PercentDefined returns `@definedCount / @refsetSize * 100` as
      DECIMAL(6,4), a percentage. The file is named "... - 02 DNF 99.sql",
      which is the truncated filename, and reading 99 off it would have been
      wrong by 0.8 - the second time on this task that a plausible-looking
      constant taken from the wrong place missed.

Idempotent: a file already carrying the fix is left alone. Run
ci/assertion_lint.py afterwards - it is the acceptance test.

    assertion_fix_unfireable.py <corpus-dir>
"""
import argparse
import pathlib
import re
import sys

ADRS_PT = re.compile(
    r"not\s+exists\s*\(\s*select\s+(get_cr_ADRS_PT\s*\(\s*\d+\s*\))\s*=\s*(BINARY\s+'[^']*')\s*\)",
    re.I)
COUNT_POPULATED = re.compile(
    r"(not\s+exists\s*\(\s*select\s+)count\s*\(\s*1\s*\)(\s+from\s)", re.I)
CHILD_OF = re.compile(
    r"not\s+exists\s*\(\s*select\s+(isChildOf_cr\s*\(\s*\d+\s*,\s*\d+\s*\))\s*\)", re.I)
PERCENT_DEFINED = re.compile(
    r"not\s+exists\s*\(\s*select\s*(TRUNCATE\s*\(\s*get_cr_PercentDefined\s*\(\s*\d+\s*\)\s*,\s*1\s*\))\s*\)",
    re.I)


def fix(sql: str):
    """Returns (new_sql, [what changed])."""
    changes = []

    def note(label):
        changes.append(label)

    new, n = ADRS_PT.subn(lambda m: f"coalesce({m.group(1)}, '') <> {m.group(2)}", sql)
    if n:
        note(f'{n} ADRS preferred-term comparison(s)')
    sql = new

    new, n = COUNT_POPULATED.subn(lambda m: f'{m.group(1)}1{m.group(2)}', sql)
    if n:
        note(f'{n} populated-file check(s)')
    sql = new

    new, n = CHILD_OF.subn(lambda m: f'{m.group(1)} = 0', sql)
    if n:
        note(f'{n} hierarchy check(s)')
    sql = new

    new, n = PERCENT_DEFINED.subn(lambda m: f'{m.group(1)} <> 99.8', sql)
    if n:
        note(f'{n} percent-defined threshold(s)')
    sql = new

    return sql, changes


def main():
    ap = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    ap.add_argument('corpus', help='directory of .sql assertion files')
    ap.add_argument('--dry-run', action='store_true')
    args = ap.parse_args()

    root = pathlib.Path(args.corpus)
    touched = 0
    for path in sorted(root.rglob('*.sql')):
        original = path.read_text(encoding='utf-8', errors='replace')
        patched, changes = fix(original)
        if patched == original:
            continue
        touched += 1
        print(f'  {path.name[:72]}')
        for c in changes:
            print(f'      {c}')
        if not args.dry_run:
            path.write_text(patched, encoding='utf-8')
    verb = 'would patch' if args.dry_run else 'patched'
    print(f'\n{verb} {touched} file(s)', file=sys.stderr)
    return 0


if __name__ == '__main__':
    sys.exit(main())
