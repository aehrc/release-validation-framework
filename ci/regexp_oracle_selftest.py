#!/usr/bin/env python3
"""Pins the extraction rules in regexp_oracle.py, each of which cost a run.

Every case here is a mistake that produced a confident WRONG answer about the
transpiler, not a crash:

* MySQL-unescaping the DuckDB literal turned a correct `\\((clinical drug)\\)$`
  into `((clinical drug))$`, which matches terms ending in the bare word - 11,398
  rows against MySQL's 52,840 - and reported the publisher as broken.
* NOT unescaping the MySQL literal asked MySQL for "backslash then any
  character", which matches nothing, so 15 calls read as "MySQL 0 vs DuckDB N".
* Not stripping the EXECUTABLE_QUERY comment block found every pattern twice and
  paired nothing.
* Evaluating an 'i'-flagged pattern against the binary-collated column reported
  DuckDB matching a superset - the harness measuring itself.

Run: python3 ci/regexp_oracle_selftest.py
"""

import sys
import pathlib

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

import regexp_oracle as O  # noqa: E402

FAILURES = []


def check(what, got, want):
    if got != want:
        FAILURES.append(f"{what}\n    got  {got!r}\n    want {want!r}")


# --- MySQL literals: the engine never sees the backslashes the file holds ----
check("MySQL '\\\\.' is one backslash and a dot",
      O.unquote(r"'\\.\\.\\.'"), r"\.\.\.")
check("MySQL escaped parens survive as regex metacharacters",
      O.unquote(r"'\\((clinical drug|product)\\)$'"), r"\((clinical drug|product)\)$")
check("MySQL \\Z is the SUB character, which RE2 spells \\x1a",
      O.unquote(r"'[\\\t\Z]'"), "[\\\t\x1a]")
check("MySQL keeps the backslash on \\% and \\_, for LIKE",
      O.unquote(r"'\%\_'"), r"\%\_")
check("doubled quote is one quote", O.unquote("'it''s'"), "it's")
check("double-quoted literal, which MySQL takes as a string",
      O.unquote('"containing product"'), "containing product")
check("a pattern with no escapes is unchanged",
      O.unquote("'[Ff]oet(us|al)'"), "[Ff]oet(us|al)")

# --- DuckDB literals: backslash is NOT an escape ----------------------------
check("DuckDB keeps every backslash",
      O.unquote_duck(r"'\((clinical drug|product)\)$'"), r"\((clinical drug|product)\)$")
check("DuckDB keeps a lone backslash class",
      O.unquote_duck(r"'[\\\t\r\n\x1a@$#]'"), r"[\\\t\r\n\x1a@$#]")
check("DuckDB still collapses a doubled quote",
      O.unquote_duck("'it''s'"), "it's")

# --- comments: a regex cannot do this safely --------------------------------
check("the EXECUTABLE_QUERY block is removed",
      O.strip_comments("/* EXECUTABLE_QUERY:\nselect 1 where a regexp 'x';\n*/\n"
                       "select 2 where b regexp 'y';").strip(),
      "select 2 where b regexp 'y';")
check("a line comment is removed",
      O.strip_comments("select 1; -- and a note\nselect 2;"),
      "select 1; \nselect 2;")
check("-- INSIDE a literal is not a comment",
      O.strip_comments("select 1 where t regexp 'a--b'; select 2;"),
      "select 1 where t regexp 'a--b'; select 2;")
check("/* inside a literal is not a comment",
      O.strip_comments("select t regexp 'a/*b';"), "select t regexp 'a/*b';")

# --- extraction is anchored on the operator, not the operand ----------------
def patterns(sql):
    body = O.strip_comments(sql)
    out = []
    for m in O.MYSQL_REGEXP.finditer(body):
        before = body[max(0, m.start() - O.BINARY_LOOKBACK):m.start()]
        out.append((O.unquote(m.group("pat")),
                    bool(m.group("binary_pat")) or bool(O.BINARY_LHS.search(before))))
    return out


check("a function call as the operand is found",
      patterns("where get_cr_ADRS_PT(id) RLIKE '\\\\.\\\\.\\\\.';"),
      [(r"\.\.\.", False)])
check("BINARY on the left marks the comparison case-sensitive",
      patterns("where BINARY term NOT REGEXP ' x$';"), [(" x$", True)])
check("BINARY on the right does too",
      patterns("where term NOT REGEXP BINARY ' x$';"), [(" x$", True)])
check("a plain column comparison is not marked",
      patterns("where term REGEXP 'abc';"), [("abc", False)])
check("both operators are recognised, and both quote styles",
      patterns('where a RLIKE "p" and b REGEXP \'q\';'), [("p", False), ("q", False)])

# --- collation follows the OPERAND, which is what decides case sensitivity ---
check("a function result carries the schema default, which is case-insensitive",
      O.collation_of("GET_CR_ADRS_PT(id)"), O.CI_COLLATION)
check("get_cr_FSN too", O.collation_of("get_cr_fsn(id)"), O.CI_COLLATION)
check("a column is binary-collated in RVF's schema",
      O.collation_of("term"), "binary")
check("a concat over columns is still the column's collation",
      O.collation_of("concat(term, ' ')"), "binary")

# --- DuckDB call parsing: arguments contain parens and quoted commas --------
check("nested parens do not end the argument list",
      O.duck_calls("select regexp_matches(concat(a, b), '(x|y)') from t"),
      [["concat(a, b)", "'(x|y)'"]])
check("the flags argument is captured",
      O.duck_calls("regexp_matches(GET_CR_ADRS_PT(id), 'p', 'i')"),
      [["GET_CR_ADRS_PT(id)", "'p'", "'i'"]])
check("a comma inside the pattern is not an argument separator",
      O.duck_calls("regexp_matches(t, '[0-9]{6,20}')"),
      [["t", "'[0-9]{6,20}'"]])
check("two calls in one statement",
      len(O.duck_calls("regexp_matches(a, 'x') or regexp_matches(b, 'y')")), 2)

if FAILURES:
    print(f"FAIL: {len(FAILURES)} of the pinned rules broke\n")
    for f in FAILURES:
        print(f"  {f}\n")
    sys.exit(1)
print("OK: every extraction rule holds")
