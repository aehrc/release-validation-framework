#!/usr/bin/env python3
"""Does each transpiled regex select the SAME rows MySQL selects?

The assertion-level A/B cannot answer this. Run against the AU edition, all
46 regex-bearing assertions agree - and 44 of them agree by matching nothing at
all, so the comparison never evaluated a single pattern against a matching row.
Agreement there is a question neither engine was given content to answer.

So compare the PREDICATE, not the report. For every `regexp_matches` call the
store carries, evaluate it against real release terms on DuckDB and evaluate the
MySQL `REGEXP` it was translated from against the SAME rows on MySQL, and
compare the id sets.

Why this is where a defect would hide: RVF's tables are `charset=utf8`, whose
collation is case-INSENSITIVE, so `term REGEXP 'ACL'` matches "acl" on MySQL.
DuckDB's `regexp_matches` is case-SENSITIVE unless passed 'i'. 70 of the store's
90 calls carry no flags argument, and a pattern that silently stops matching
does not error - the assertion just reports nothing, which reads as a pass.

The rows are exported from MySQL and loaded into DuckDB, so "the two engines saw
different data" is not available as an explanation of a difference.
"""

import argparse
import json
import pathlib
import re
import sys

# MySQL's REGEXP/RLIKE and the pattern it tests, anchored on the OPERATOR.
#
# Anchored there because the left operand is not a name: `get_cr_ADRS_PT(id)
# RLIKE '...'` tests a function call, and `BINARY term NOT REGEXP BINARY '...'`
# puts a cast on both sides. Parsing the operand adds nothing - the pattern and
# whether the comparison is binary are the whole input to this check.
# Both quote characters, because MySQL takes "..." as a string literal without
# ANSI_QUOTES and one AMT script writes `RLIKE "containing product"`.
MYSQL_REGEXP = re.compile(
    r"\b(?:REGEXP|RLIKE)\s+(?P<binary_pat>BINARY\s+)?"
    r"(?P<pat>'(?:[^'\\]|\\.|'')*'|\"(?:[^\"\\]|\\.|\"\")*\")", re.I)

# MySQL decides case sensitivity per OPERAND, from its collation. RVF declares
# `term ... collate utf8_bin`, case-SENSITIVE, matching DuckDB's default - but a
# stored function returns the SCHEMA default, utf8mb4_0900_ai_ci, which is
# case-INsensitive, so the publisher adds 'i' for exactly those operands.
#
# The measurement has to honour that or it measures its own mistake: evaluating
# an 'i'-flagged pattern against the case-sensitive column reports DuckDB
# matching a superset - 9 extra rows for `artifact` - which is an artefact of
# comparing two different collations, not a transpilation defect.
CI_RETURNING_FUNCTIONS = ("get_cr_adrs_pt", "get_cr_fsn")
CI_COLLATION = "utf8mb4_0900_ai_ci"


def collation_of(duck_operand):
    return (CI_COLLATION
            if any(f in duck_operand.lower() for f in CI_RETURNING_FUNCTIONS)
            else "binary")


# BINARY is part of the MEANING, not decoration: the AMT scripts write it
# exactly where the match must be case-sensitive, because without it MySQL's
# utf8 collation makes REGEXP case-INSENSITIVE. Whether the transpilation
# preserved that distinction is the question this file exists to answer. It can
# sit on either side of the operator, so the left side is found by looking back.
BINARY_LOOKBACK = 48
BINARY_LHS = re.compile(r"\bBINARY\b[^']*$", re.I)


def strip_comments(sql):
    """Code only, with string literals intact.

    An AMT script documents itself with an EXECUTABLE_QUERY block holding a
    runnable copy of the same SQL, so counting patterns without stripping
    comments finds each one twice and pairs nothing. A regex cannot do this
    safely: `--` and `/*` occur inside patterns, and stripping to end of line
    from one inside a literal deletes the closing quote and everything after.
    """
    out, i, n = [], 0, len(sql)
    while i < n:
        ch = sql[i]
        if ch in "'\"":
            j = i + 1
            while j < n:
                if sql[j] == "\\":
                    j += 2
                    continue
                if sql[j] == ch:
                    if j + 1 < n and sql[j + 1] == ch:
                        j += 2
                        continue
                    break
                j += 1
            out.append(sql[i:min(j + 1, n)])
            i = j + 1
        elif sql.startswith("--", i) or sql.startswith("#", i):
            j = sql.find("\n", i)
            i = n if j < 0 else j
        elif sql.startswith("/*", i):
            j = sql.find("*/", i + 2)
            i = n if j < 0 else j + 2
        else:
            out.append(ch)
            i += 1
    return "".join(out)


def duck_calls(sql):
    """Every `regexp_matches(...)` in `sql`, as its argument list.

    Written out rather than done with a regex because the arguments contain
    parentheses and quoted commas - `regexp_matches(concat(a, b), '(x|y)')` has
    both, and a non-greedy match on `\\)` takes the wrong one.
    """
    out = []
    for m in re.finditer(r"regexp_matches\s*\(", sql, re.I):
        i, depth, args, cur, quote = m.end(), 1, [], "", None
        while i < len(sql) and depth:
            ch = sql[i]
            if quote:
                cur += ch
                if ch == quote:
                    quote = None
            elif ch in "'\"":
                quote = ch
                cur += ch
            elif ch == "(":
                depth += 1
                cur += ch
            elif ch == ")":
                depth -= 1
                if depth:
                    cur += ch
            elif ch == "," and depth == 1:
                args.append(cur)
                cur = ""
            else:
                cur += ch
            i += 1
        args.append(cur)
        out.append([a.strip() for a in args])
    return out


# MySQL's string-literal escapes. The regex engine never sees the backslashes
# the SQL text carries: `'\\.\\.\\.'` is the three-character regex `\.\.\.`, and
# passing the raw text through as a parameter asks MySQL for "backslash followed
# by any character", which matches nothing. Comparing that against the store's
# `\.\.\.` reports the transpilation as broken when it is exact.
#
# `\%` and `\_` KEEP their backslash - MySQL leaves them for LIKE - and every
# other escaped character loses it.
MYSQL_ESCAPES = {"0": "\0", "'": "'", '"': '"', "b": "\b", "n": "\n",
                 "r": "\r", "t": "\t", "Z": "\x1a", "\\": "\\",
                 "%": "\\%", "_": "\\_"}


def unquote_duck(sql_literal):
    """The DuckDB literal's value: doubled quotes only.

    DuckDB does not treat backslash as an escape in a plain single-quoted
    string, so `'\\((clinical drug)\\)$'` IS the regex `\\((clinical drug)\\)$`.
    Applying MySQL's rules here strips those backslashes and turns a correct
    transpilation into `((clinical drug))$`, which matches terms ending in the
    bare word - 11398 rows against MySQL's 52840 - and reports the publisher as
    broken. Two of these were the S8 patterns, whose mangled form DuckDB then
    refuses to compile at all.
    """
    quote = sql_literal[0]
    return sql_literal[1:-1].replace(quote * 2, quote)


def unquote(sql_literal):
    """The bytes MySQL's engine gets, not the bytes the file holds."""
    quote = sql_literal[0]
    body = sql_literal[1:-1].replace(quote * 2, quote)
    out, i = [], 0
    while i < len(body):
        ch = body[i]
        if ch == "\\" and i + 1 < len(body):
            nxt = body[i + 1]
            out.append(MYSQL_ESCAPES.get(nxt, nxt))
            i += 2
        else:
            out.append(ch)
            i += 1
    return "".join(out)


# `call validateConceptIdsInMRCMDomainRefsetExpression_procedure` - an assertion
# whose regex lives in a stored procedure, which the publisher unrolls into the
# store. The script text then holds no pattern at all while the store holds one
# per unrolled call site, so position cannot pair them.
CALL = re.compile(r"\bcall\s+([A-Za-z_][\w]*)", re.I)
CREATE_PROC = re.compile(r"\bcreate\s+procedure\s+([A-Za-z_][\w]*)", re.I)


def procedure_patterns(corpus_scripts):
    """proc name -> its distinct patterns, over every script that defines one."""
    out = {}
    for script in corpus_scripts.rglob("*.sql"):
        body = strip_comments(script.read_text(errors="replace"))
        names = CREATE_PROC.findall(body)
        if not names:
            continue
        patterns = []
        for m in MYSQL_REGEXP.finditer(body):
            before = body[max(0, m.start() - BINARY_LOOKBACK):m.start()]
            patterns.append((unquote(m.group("pat")),
                             bool(m.group("binary_pat")) or bool(BINARY_LHS.search(before))))
        for name in names:
            out.setdefault(name.lower(), []).extend(patterns)
    return out


def pairs_for(store, corpus_scripts, uuids):
    """(file, mysql_pattern, duck_pattern, duck_flags) for every call.

    Paired by position: the transpiler rewrites in place and preserves order, so
    the nth REGEXP in the source is the nth regexp_matches in the store. Where
    the counts disagree the assertion is reported rather than guessed at.
    """
    out, unpaired = [], []
    for uuid in uuids:
        assertion = store["assertions"][uuid]
        name = assertion["file"]
        found = list(corpus_scripts.rglob(name))
        if not found:
            unpaired.append((name, "not in the corpus"))
            continue
        source = strip_comments(found[0].read_text())
        mysql = []
        for m in MYSQL_REGEXP.finditer(source):
            before = source[max(0, m.start() - BINARY_LOOKBACK):m.start()]
            mysql.append((unquote(m.group("pat")),
                          bool(m.group("binary_pat")) or bool(BINARY_LHS.search(before))))
        duck = duck_calls(" ".join(assertion["statements"]))
        if not mysql and duck:
            # Unrolled from a procedure: pair the DISTINCT patterns, since the
            # store has one call per unrolled site and the source has one
            # definition. Multiplicity is not the question - equivalence is.
            defined = procedure_patterns(corpus_scripts)
            called = [pat for proc in CALL.findall(source)
                      for pat in defined.get(proc.lower(), [])]
            distinct_src = list(dict.fromkeys(called))
            distinct_duck = list(dict.fromkeys(c[1] for c in duck))
            if len(distinct_src) == len(distinct_duck) and distinct_src:
                for (src, binary), pattern in zip(distinct_src, distinct_duck):
                    call = next(c for c in duck if c[1] == pattern)
                    flags = unquote_duck(call[2]) if len(call) >= 3 else ""
                    out.append((name, src, unquote_duck(pattern), flags, binary,
                                collation_of(call[0])))
                continue
            unpaired.append((name, f"no REGEXP in the script and "
                                   f"{len(distinct_src)} in the procedures it calls, "
                                   f"against {len(distinct_duck)} distinct in the store"))
            continue
        if len(mysql) != len(duck):
            unpaired.append((name, f"{len(mysql)} REGEXP in source, "
                                   f"{len(duck)} regexp_matches in the store"))
            continue
        for (src, binary), call in zip(mysql, duck):
            flags = unquote_duck(call[2]) if len(call) >= 3 else ""
            out.append((name, src, unquote_duck(call[1]), flags, binary,
                        collation_of(call[0])))
    return out, unpaired


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--store", required=True)
    ap.add_argument("--corpus-scripts", required=True)
    ap.add_argument("--assertions", required=True,
                    help="uuid -> file, from the regexp-bearing set")
    ap.add_argument("--pairs-out", required=True,
                    help="written for RegexpOracleProbe to measure")
    ap.add_argument("--measured",
                    help="the probe's output; classify and gate when given")
    ap.add_argument("--gate", action="store_true")
    a = ap.parse_args()

    store = json.loads(pathlib.Path(a.store).read_text())
    uuids = json.loads(pathlib.Path(a.assertions).read_text())
    calls, unpaired = pairs_for(store, pathlib.Path(a.corpus_scripts), uuids)

    if not a.measured:
        pathlib.Path(a.pairs_out).write_text(json.dumps(
            [{"assertion": n, "mysql_pattern": m, "duck_pattern": d,
              "duck_flags": f, "mysql_binary": b, "mysql_collation": c}
             for n, m, d, f, b, c in calls], indent=1))
        print(f"  {len(calls)} regexp calls paired from {len(uuids)} assertions")
        for name, why in unpaired:
            print(f"    UNPAIRED  {name}: {why}")
        flagged = sum(1 for c in calls if "i" in c[3])
        binary = sum(1 for c in calls if c[4])
        print(f"  {flagged} carry DuckDB's 'i' flag, {len(calls) - flagged} do not")
        print(f"  {binary} are BINARY in MySQL (case-sensitive there), "
              f"{len(calls) - binary} are not (case-INsensitive there)")
        # What the transpilation had to get right, stated as a count: the flag
        # must follow the operand's collation, not the pattern.
        ci = sum(1 for c in calls if c[5] == CI_COLLATION)
        wrong = sum(1 for c in calls if (c[5] == CI_COLLATION) != ("i" in c[3]))
        print(f"  {ci} test a function result (case-insensitive in MySQL), "
              f"{len(calls) - ci} test a binary-collated column")
        print(f"  {wrong} carry a flag that contradicts their operand's collation")
        print(f"  wrote {a.pairs_out} - measure it with RegexpOracleProbe")
        return 0

    measured = json.loads(pathlib.Path(a.measured).read_text())
    verdicts = {}
    for call in measured["calls"]:
        verdicts[call["verdict"]] = verdicts.get(call["verdict"], 0) + 1
    print("  " + "=" * 74)
    print(f"    REGEXP ORACLE   {len(measured['calls'])} calls against "
          f"{measured['terms']} real terms from {measured['table']}")
    for k, v in sorted(verdicts.items(), key=lambda kv: -kv[1]):
        print(f"      {k:18} {v}")
    for call in measured["calls"]:
        if not call["verdict"].startswith("identical"):
            print(f"\n    {call['verdict'].upper()}  {call['assertion']}")
            print(f"      {call['detail']}")
            print(f"      MySQL pattern: {call['mysql_pattern'][:110]}")
            print(f"      DuckDB       : {call['duck_pattern'][:110]}"
                  f"  flags={call['duck_flags']!r}")
    empty = verdicts.get("identical-empty", 0)
    if empty:
        print(f"\n    {empty} matched nothing on either engine - agreement, but not"
              "\n    evidence: no row exercised the pattern.")

    if a.gate:
        bad = sum(v for k, v in verdicts.items() if not k.startswith("identical"))
        if bad or unpaired:
            print(f"\n  GATE FAIL: {bad} calls disagree, {len(unpaired)} unpaired")
            return 1
        print("\n  GATE PASS: every transpiled regex selects the rows MySQL selects")
    return 0


if __name__ == "__main__":
    sys.exit(main())
