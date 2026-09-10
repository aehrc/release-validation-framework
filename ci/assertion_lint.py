#!/usr/bin/env python3
"""Finds assertions that cannot report a finding for any release.

Standalone and dependency-free on purpose: it reads a directory of `.sql` files
and needs no database, no schema and no compiled store, so it can run in the
repository that OWNS the assertions, on every pull request, before anything is
published.

## What it looks for

A `SELECT` that reads no rows still RETURNS a row. Two consequences, and every
assertion built on either reports a pass for every release ever validated:

  NOT EXISTS(SELECT <expression>)                 -- no FROM at all
  NOT EXISTS(SELECT COUNT(1) FROM <table>)        -- ungrouped aggregate

The first is one row unconditionally. The second is one row even when the table
is EMPTY - which is the sharper case, because the assertions carrying it exist to
detect exactly that. Demonstrated rather than argued: create the table, leave it
empty, run the assertion, and it inserts nothing while the condition it looks
for is present.

## Where this came from

Measuring per-assertion coverage of a 560-assertion corpus on two engines
(2026-09-10). Twelve assertions never fired. They were not slow, skipped or
erroring - they ran, inserted nothing, and were reported in `assertionsPassed`
with a failure count of zero. Nothing could have noticed: both engines are
silent for the same reason, so an engine A/B agrees; the assertion does execute,
so an execution check is satisfied; and the finding count is zero, which is what
a clean release looks like.

## Usage

    assertion_lint.py <dir-or-file> [...]        # exits 1 if any are found
    assertion_lint.py --format=github <dir>      # ::error annotations for CI

Exit status is 1 when something is found and 0 otherwise, so it works as a
build step with no wrapper.
"""
import argparse
import pathlib
import re
import sys

NOT_EXISTS_SELECT = re.compile(r'NOT\s+EXISTS\s*\(\s*SELECT\b', re.I)
FROM = re.compile(r'\bFROM\b', re.I)
AGGREGATE = re.compile(r'\b(COUNT|SUM|MAX|MIN|AVG)\s*\(', re.I)
GROUPED = re.compile(r'\bGROUP\s+BY\b|\bHAVING\b', re.I)
BLOCK_COMMENT = re.compile(r'/\*.*?\*/', re.S)
LINE_COMMENT = re.compile(r'--[^\n]*')


def strip_comments(sql: str) -> str:
    """Comments only, and replaced by spaces so every offset is preserved - a
    finding reports a line number and it has to be the real one."""
    def blank(m):
        return re.sub(r'[^\n]', ' ', m.group(0))
    return LINE_COMMENT.sub(blank, BLOCK_COMMENT.sub(blank, sql))


def subquery_body(sql: str, after_select: int):
    """The text between `SELECT` and the paren closing its subquery."""
    depth = 1
    for i in range(after_select, len(sql)):
        c = sql[i]
        if c == '(':
            depth += 1
        elif c == ')':
            depth -= 1
            if depth == 0:
                return sql[after_select:i]
    return None


def findings(sql: str):
    """(line, reason) for every negated existence test over something that
    always exists.

    Only `NOT EXISTS` and only its own subquery. A bare `EXISTS(SELECT 1)` is a
    normal way to write a constant, an aggregate in the OUTER projection is how
    most of a corpus counts things, and an aggregate WITH a `GROUP BY` returns
    no row for an empty group - so all three are left alone.
    """
    out = []
    clean = strip_comments(sql)
    for m in NOT_EXISTS_SELECT.finditer(clean):
        inner = subquery_body(clean, m.end())
        if inner is None:
            continue
        line = clean.count('\n', 0, m.start()) + 1
        if not FROM.search(inner):
            out.append((line, 'NOT EXISTS over a SELECT with no FROM: it always'
                              ' returns one row, so this can never be true'))
            continue
        projection = FROM.split(inner, 1)[0]
        if AGGREGATE.search(projection) and not GROUPED.search(inner):
            out.append((line, 'NOT EXISTS over an ungrouped aggregate: it returns one'
                              ' row even when the table is empty, so this can never'
                              ' be true - including for the empty table it is'
                              ' presumably checking for'))
    return out


def sql_files(targets):
    for t in targets:
        p = pathlib.Path(t)
        if p.is_dir():
            yield from sorted(p.rglob('*.sql'))
        elif p.suffix.lower() == '.sql':
            yield p


def main():
    ap = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    ap.add_argument('targets', nargs='+', help='directories or .sql files')
    ap.add_argument('--format', choices=('text', 'github'), default='text',
                    help='github emits ::error annotations')
    args = ap.parse_args()

    scanned = 0
    hits = []
    for path in sql_files(args.targets):
        scanned += 1
        try:
            sql = path.read_text(encoding='utf-8', errors='replace')
        except OSError as e:
            print(f'{path}: unreadable: {e}', file=sys.stderr)
            continue
        for line, reason in findings(sql):
            hits.append((path, line, reason))

    for path, line, reason in hits:
        if args.format == 'github':
            print(f'::error file={path},line={line},title=Assertion cannot fire::{reason}')
        else:
            print(f'{path}:{line}: {reason}')

    print(f'\n{scanned} assertion file(s) scanned, {len(hits)} that cannot fire',
          file=sys.stderr)
    if hits:
        print('An assertion that cannot report a finding passes for every release'
              ' ever validated. Nothing downstream can notice: it executes, so an'
              ' execution check is satisfied; it finds nothing, which is what a'
              ' clean release looks like; and every engine is silent for the same'
              ' reason, so an engine comparison agrees.', file=sys.stderr)
    return 1 if hits else 0


if __name__ == '__main__':
    sys.exit(main())
