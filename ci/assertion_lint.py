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
    assertion_lint.py --ddl create-tables.sql <dir>   # also check table-name case

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
# `x = (null)` and friends. In SQL a comparison to NULL is NULL, never true, so
# a WHERE built on one selects nothing and a conjunct built on one makes the
# whole conjunction unsatisfiable.
NULL_COMPARISON = re.compile(r'([\w.]+)\s*(?:=|<>|!=)\s*\(?\s*NULL\s*\)?(?!\s*\))', re.I)
# A call to one of the corpus's own predicates, so the same call appearing both
# negated and plain can be spotted.
_CALL = r'(\b(?:is\w+_cr(?:_refset)?|get_cr_\w+)\s*\([^()]*\))'
ADJACENT_CONTRADICTION = re.compile(
    r'(NOT\s+)?' + _CALL + r'\s+AND\s+(NOT\s+)?' + _CALL, re.I)
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

    # A predicate asserted and denied as ADJACENT conjuncts.
    #
    # `where not isActiveMemberOf_cr_refset(id, R) and
    #         isActiveMemberOf_cr_refset(id, R) and ...` is a contradiction, so
    # the assertion selects nothing for any release. Found in two assertions
    # named "Contains all Active <class>s", where the intent is evidently a
    # concept that QUALIFIES for the refset and is not in it - and the
    # qualifying half was lost. That intent cannot be recovered from the SQL,
    # which is why this reports rather than repairs.
    #
    # ADJACENT is the whole point, and a looser version of this check was wrong.
    # Scanning a whole statement for the same call negated somewhere and plain
    # somewhere else flagged two assertions that demonstrably fire: the same
    # predicate legitimately appears on both sides of an OR, and in separate
    # EXISTS subqueries, which are separate scopes. Two false positives out of
    # four findings is how a linter gets switched off. Requiring the two calls
    # to be separated by nothing but `AND` cannot span an OR or a subquery
    # boundary, and it is exactly the shape both real defects have.
    for m in ADJACENT_CONTRADICTION.finditer(clean):
        left_not, left, right_not, right = m.group(1), m.group(2), m.group(3), m.group(4)
        if re.sub(r'\s+', '', left).lower() != re.sub(r'\s+', '', right).lower():
            continue
        if bool(left_not) == bool(right_not):
            continue
        line = clean.count('\n', 0, m.start()) + 1
        out.append((line, f'the same predicate is required and forbidden as adjacent'
                          f' conjuncts - {left.strip()[:56]} - so this can never be true'))

    # A comparison to NULL, which is NULL rather than true.
    for m in NULL_COMPARISON.finditer(clean):
        line = clean.count('\n', 0, m.start()) + 1
        out.append((line, f'{m.group(1)} is compared to NULL, which is never true -'
                          f' use IS NULL, or bind the value this was meant to test'))
    return out


# The RF2 table names RVF's schema declares, minus the kind suffix. Built in so
# the case check works in a corpus repository, which does not contain the DDL -
# these names are part of the RF2 release format and move about once a year.
# --ddl overrides, for a schema that has moved on.
RF2_TABLE_STEMS = frozenset({
    'associationrefset',
    'attributevaluemap',
    'attributevaluerefset',
    'ccirefset',
    'ccsrefset',
    'complexmaprefset',
    'concept',
    'crefset',
    'description',
    'descriptiontyperefset',
    'expressionassociationrefset',
    'extendedassociation',
    'extendedmaprefset',
    'identifier',
    'isimplemaprefset',
    'langrefset',
    'mapcorrelationoriginrefset',
    'moduledependencyrefset',
    'mrcmattributedomainrefset',
    'mrcmattributerangerefset',
    'mrcmdomainrefset',
    'mrcmmodulescoperefset',
    'owlexpressionrefset',
    'refsetdescriptor',
    'relationship',
    'relationship_concrete_values',
    'simplemaprefset',
    'simplerefset',
    'stated_relationship',
    'textdefinition',
})


def table_stems(ddl_path):
    """The table names the schema declares, minus the RF2 kind suffix.

    Needed because an assertion writes `<PROSPECTIVE>.concept_<SNAPSHOT>` and
    the DDL declares `concept_s`.
    """
    ddl = pathlib.Path(ddl_path).read_text(encoding='utf-8', errors='replace')
    names = {m.group(1) for m in re.finditer(r'create\s+table\s+([A-Za-z_0-9]+)', ddl, re.I)}
    return {re.sub(r'_(f|s|d)$', '', n.lower()) for n in names}


def case_mismatches(sql, stems):
    """Table references whose CASE does not match the schema.

    MySQL compares table names case-sensitively wherever
    `lower_case_table_names = 0`, which is the default on Linux, so
    `ccsRefset_f` and `ccsrefset_f` are different tables and one of them does
    not exist. The statement then dies with "Table ... doesn't exist" and the
    assertion reports incomplete - which reads as a fault in the assertion, or
    as a release that failed to ship a file, rather than as a typo.

    It survives because it is invisible almost everywhere else: DuckDB resolves
    identifiers case-insensitively, and so does MySQL on macOS and Windows. Only
    a Linux MySQL sees it, and only for the one name that is spelled
    differently.

    Found exactly once in 1,107 assertion files, and it was the sole remaining
    divergence between the two engines on that corpus.
    """
    out = []
    # The trailing \b goes on the BARE-suffix branch only. A first version put
    # it after the whole alternation, and `>` followed by `)` is two non-word
    # characters with no boundary between them - so the placeholder form never
    # matched and the check silently found nothing at all.
    for m in re.finditer(
            r'\b([A-Za-z][A-Za-z_0-9]*)(?:_<(?:FULL|SNAPSHOT|DELTA)>|_[fsd]\b)', sql):
        ref = m.group(1)
        if ref.lower() in stems and ref != ref.lower():
            line = sql.count('\n', 0, m.start()) + 1
            out.append((line, f'{ref} is spelled differently from the schema, which declares'
                              f' {ref.lower()} - MySQL compares table names case-sensitively on'
                              f' Linux, so this statement dies on a table that does not exist'))
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
    ap.add_argument('--ddl', help='create-tables SQL, to check table names for case')
    args = ap.parse_args()

    stems = table_stems(args.ddl) if args.ddl else RF2_TABLE_STEMS

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
        for line, reason in case_mismatches(strip_comments(sql), stems):
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
