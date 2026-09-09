#!/usr/bin/env python3
"""Do the transpiled regexes match the SAME components MySQL matches?

MySQL's `col REGEXP 'pat'` becomes DuckDB's `regexp_matches(col, 'pat')` at
publish time, and 46 assertions in the AMT-bearing store carry 90 such calls.
Until now nothing compared them against the engine they were translated from.

Why this is its own check rather than a line in the A/B summary
---------------------------------------------------------------
`compare_reports.py` joins on assertionUuid and compares failureCount, which is
the right gate for a whole corpus and the wrong one for a regex. A pattern that
matches the wrong rows can match just as many of them: RE2 and MySQL's ICU
regex differ on character classes, anchoring, and case folding, and every one
of those differences can preserve a count while changing which components are
named. So this compares the component IDS, and it only looks at the assertions
whose SQL actually contains a rewritten regex.

It also refuses to be satisfied by silence. An assertion that matched nothing
on both engines proves only that both were asked; it is reported separately
from one where both engines named the same non-empty set, because the second is
evidence and the first is an absence of it.

  regexp_parity.py --mysql-report a.json --duck-report b.json \\
                   --assertions regexp-assertions.json --out parity.json [--gate]
"""
from __future__ import annotations

import argparse
import json
import pathlib
import sys

BUCKETS = ("assertionsFailed", "assertionsWarning", "assertionsPassed",
           "assertionsSkipped")

NOT_EXECUTED_PREFIXES = ("not run:", "error executing", "no precompiled statements",
                         "failed to execute")


def load(path):
    """uuid -> record, from either report shape."""
    d = json.loads(pathlib.Path(path).read_text())
    tr = d.get("rvfValidationResult", d).get("TestResult", d.get("TestResult", {}))
    out = {}
    for bucket in BUCKETS:
        for a in tr.get(bucket) or []:
            uuid = str(a.get("assertionUuid"))
            out.setdefault(uuid, a)
    return out


def executed(record):
    message = (record.get("failureMessage") or "").strip().lower()
    return not any(message.startswith(p) for p in NOT_EXECUTED_PREFIXES)


def component_ids(record):
    """The components this assertion named, as strings.

    componentId is the failing component; conceptId is what the report shows
    when an assertion is about a concept rather than one of its components.
    Both are compared because different assertions populate different ones, and
    an id present in one report and absent from the other is the finding.
    """
    ids = set()
    for instance in record.get("firstNInstances") or []:
        for key in ("componentId", "conceptId"):
            value = instance.get(key)
            if value not in (None, "", "null"):
                ids.add(str(value))
    return ids


def classify(uuid, name, mysql, duck):
    m, d = mysql.get(uuid), duck.get(uuid)
    if m is None or d is None:
        missing = "MySQL" if m is None else "DuckDB"
        return "absent", f"not in the {missing} report at all"
    if not executed(m) or not executed(d):
        which = "MySQL" if not executed(m) else "DuckDB"
        return "not-run", f"{which} did not execute it: " \
                          f"{(m if which == 'MySQL' else d).get('failureMessage')}"

    # -1 is RVF's "this assertion errored", not a failure count. Comparing it
    # as a number reports "MySQL -1 vs DuckDB 0" as a count difference, which
    # reads as a content disagreement and is really an execution failure.
    if m.get("failureCount") == -1 or d.get("failureCount") == -1:
        which = "MySQL" if m.get("failureCount") == -1 else "DuckDB"
        other = d if which == "MySQL" else m
        return "errored", (f"{which} errored (-1); the other reported "
                           f"{other.get('failureCount')}")

    mc, dc = m.get("failureCount", 0), d.get("failureCount", 0)
    mi, di = component_ids(m), component_ids(d)

    if mc != dc:
        return "count-differs", f"MySQL {mc} vs DuckDB {dc}"
    if mi != di:
        # THE case this file exists for: same number of failures, different
        # components. A count-only comparison calls this parity.
        only_m, only_d = sorted(mi - di)[:3], sorted(di - mi)[:3]
        return "ids-differ", (f"both {mc}, but only MySQL names {only_m} and "
                              f"only DuckDB names {only_d}")
    if mc == 0:
        return "both-empty", "neither engine matched anything - asked, not answered"
    return "identical", f"{mc} failures, same components"


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--mysql-report", required=True)
    ap.add_argument("--duck-report", required=True)
    ap.add_argument("--assertions", required=True,
                    help="json object of uuid -> file name for the regex-bearing assertions")
    ap.add_argument("--out", required=True)
    ap.add_argument("--gate", action="store_true",
                    help="exit 1 unless every assertion is identical or both-empty")
    a = ap.parse_args()

    wanted = json.loads(pathlib.Path(a.assertions).read_text())
    mysql, duck = load(a.mysql_report), load(a.duck_report)

    results, counts = [], {}
    for uuid, name in sorted(wanted.items(), key=lambda kv: kv[1]):
        verdict, detail = classify(uuid, name, mysql, duck)
        counts[verdict] = counts.get(verdict, 0) + 1
        results.append({"assertionUuid": uuid, "file": name,
                        "verdict": verdict, "detail": detail})

    width = 78
    print("=" * width)
    print(f"  REGEXP PARITY   {len(wanted)} assertions carrying a rewritten regex")
    for verdict in ("identical", "both-empty", "ids-differ", "count-differs",
                    "not-run", "absent"):
        if verdict in counts:
            print(f"    {verdict:<16} {counts[verdict]}")
    for row in results:
        if row["verdict"] not in ("identical", "both-empty"):
            print(f"\n  {row['verdict'].upper()}  {row['file']}")
            print(f"    {row['detail']}")
    if counts.get("both-empty"):
        print(f"\n  {counts['both-empty']} matched nothing on either engine. That is not a "
              f"pass;\n  it is a question neither engine was given content to answer.")

    pathlib.Path(a.out).write_text(json.dumps(
        {"summary": counts, "assertions": results}, indent=1))
    print(f"\nwrote {a.out}")

    bad = sum(v for k, v in counts.items() if k not in ("identical", "both-empty"))
    if a.gate and bad:
        print(f"\nFAIL: {bad} regex assertion(s) do not agree with MySQL")
        return 1
    if a.gate:
        print(f"\nPASS: every regex assertion agrees with MySQL on component ids "
              f"({counts.get('identical', 0)} with content, "
              f"{counts.get('both-empty', 0)} empty on both)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
