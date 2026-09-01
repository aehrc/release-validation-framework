#!/usr/bin/env python3
"""Nightly A/B: diff the incumbent RVF report against the DuckDB report.

Joins on assertionUuid and compares failureCount. Emits a human summary, a JSON
artifact, and a JUnit XML so Azure Pipelines renders the outcome as test results
(mirroring how daily-rvf already surfaces rvf2junit output).

The point of --gate
-------------------
A plain "do the two engines agree?" check is useless here in both directions.
Failing on any divergence fails every night, because MySQL has defects that make
it blind to real content and DuckDB correctly is not. Passing on any divergence
proves nothing at all. So divergences are classified against
duck/known-divergences.json, which records each one with its cause and the
evidence for that cause, and the gate fails only on divergences that are NOT
accounted for there.

The same applies to coverage. The incumbent reports assertions that do not join
to a DuckDB assertion by UUID - most of them MRCM and structural findings, which
DuckDB does validate but through separate phases with their own parity gates.
Those are accounted for by category; anything outside the accounted categories
counts as a coverage gap.

Green therefore means "no unexplained divergence and no new coverage gap",
not "the job ran".

  compare_reports.py --incumbent rvf-report.json --candidate duck-report.json \
                     --out comparison.json --junit comparison.xml --gate
"""
import argparse, json, pathlib, re, sys
from xml.sax.saxutils import escape

BASELINE = pathlib.Path(__file__).with_name("known-divergences.json")


def load(path):
    """Accept either RVF's nested report or the flat TestResult shape."""
    d = json.loads(pathlib.Path(path).read_text())
    tr = d.get("rvfValidationResult", d).get("TestResult", d.get("TestResult", {}))
    out, seconds = {}, tr.get("timeTakenInSeconds")
    for bucket in ("assertionsFailed", "assertionsWarning", "assertionsPassed",
                   "assertionsSkipped", "assertionsIncomplete"):
        for a in tr.get(bucket, []) or []:
            uuid = a.get("assertionUuid")
            if not uuid:
                continue
            # Keep the first sighting: RVF can repeat a uuid across forms.
            out.setdefault(uuid, {
                "failureCount": a.get("failureCount", 0),
                "ms": a.get("queryInMilliSeconds"),
                "text": a.get("assertionText", ""),
                "testType": a.get("testType"),
                "bucket": bucket,
            })
    return out, seconds, tr


def classify_uncovered(rec, categories):
    """Which accounted-for category does this incumbent-only assertion fall in?

    Prefer testType: RVF stamps every record with SQL / MRCM /
    ARCHIVE_STRUCTURAL / DROOL_RULES, which is exact. Fall back to matching the
    assertion text, needed only to separate the helper-definition rows from real
    assertions - both are testType SQL.
    """
    for name, spec in categories.items():
        if name.startswith("_"):
            continue
        want = spec.get("matchTestType")
        if want and rec.get("testType") == want:
            return name, spec.get("status", "coveredElsewhere")
    for name, spec in categories.items():
        if name.startswith("_"):
            continue
        pattern = spec.get("match")
        if pattern and re.search(pattern, rec.get("text") or ""):
            return name, spec.get("status", "coveredElsewhere")
    return None, "uncovered"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--incumbent", required=True, help="RVF/MySQL report json")
    ap.add_argument("--candidate", required=True, help="DuckDB report json")
    ap.add_argument("--out", required=True)
    ap.add_argument("--junit")
    ap.add_argument("--baseline", default=str(BASELINE),
                    help="known-divergences.json (default: alongside this script)")
    ap.add_argument("--gate", action="store_true",
                    help="exit 1 on unexplained divergence or new coverage gap")
    ap.add_argument("--fail-on-divergence", action="store_true",
                    help="strict: exit 1 on ANY divergence, explained or not")
    a = ap.parse_args()

    base = json.loads(pathlib.Path(a.baseline).read_text())
    known = base.get("divergences", {})
    cov = base.get("coverage", {})
    categories = cov.get("categories", {})
    expected_gaps = cov.get("knownGaps", {}).get("expectedCount", 0)

    inc, inc_secs, _ = load(a.incumbent)
    cand, cand_secs, _ = load(a.candidate)

    shared = sorted(set(inc) & set(cand))
    only_inc = sorted(set(inc) - set(cand))
    only_cand = sorted(set(cand) - set(inc))

    agree, differ = [], []
    for u in shared:
        (agree if inc[u]["failureCount"] == cand[u]["failureCount"]
         else differ).append(u)

    # --- classify divergences against the baseline -----------------------
    expected, wrong_direction, unexplained = [], [], []
    for u in differ:
        i, c = inc[u]["failureCount"], cand[u]["failureCount"]
        entry = known.get(u)
        if entry is None:
            unexplained.append(u)
            continue
        want = entry.get("direction")
        actual = "candidate-higher" if c > i else "incumbent-higher"
        (expected if want == actual else wrong_direction).append(u)

    # A baseline entry that no longer diverges. Not a failure, but it means the
    # allowance is stale and would hide a future regression on that assertion.
    resolved = [u for u in known if u in agree]

    # --- classify coverage -----------------------------------------------
    accounted, uncovered = {}, []
    for u in only_inc:
        name, status = classify_uncovered(inc[u], categories)
        if status == "uncovered":
            uncovered.append(u)
        else:
            accounted.setdefault(name, []).append(u)

    speed = round(inc_secs / max(cand_secs, 1e-9), 1) if inc_secs and cand_secs else None

    # --- report -----------------------------------------------------------
    W = 78
    print("=" * W)
    print("  PARITY")
    print(f"    assertions joined on uuid        {len(shared)}")
    print(f"    identical failureCount           {len(agree)}"
          f"  ({100*len(agree)/max(len(shared),1):.1f}%)")
    print(f"    divergent                        {len(differ)}")
    print(f"      explained by baseline          {len(expected)}")
    print(f"      UNEXPLAINED                    {len(unexplained)}")
    print(f"      wrong direction                {len(wrong_direction)}")
    print()
    print("  COVERAGE")
    for name, us in sorted(accounted.items()):
        spec = categories.get(name, {})
        print(f"    {name:<30} {len(us):>4}  ({spec.get('status')})")
    print(f"    {'uncovered':<30} {len(uncovered):>4}  (known gaps: {expected_gaps})")
    print(f"    {'only in DuckDB':<30} {len(only_cand):>4}")
    if inc_secs is not None and cand_secs is not None:
        print()
        print(f"  SPEED   RVF/MySQL {inc_secs}s   DuckDB {cand_secs}s   {speed}x")

    if expected:
        print("\n  --- explained divergences ---")
        by_class = {}
        for u in expected:
            by_class.setdefault(known[u]["class"], []).append(u)
        for cls, us in sorted(by_class.items()):
            print(f"    {cls}  ({len(us)})")
            for u in us:
                print(f"      rvf={inc[u]['failureCount']:<8} "
                      f"duck={cand[u]['failureCount']:<8} {inc[u]['text'][:44]}")

    if unexplained:
        print("\n  --- UNEXPLAINED DIVERGENCES (these fail the gate) ---")
        for u in unexplained:
            print(f"    {u}")
            print(f"      rvf={inc[u]['failureCount']:<8} "
                  f"duck={cand[u]['failureCount']:<8} {inc[u]['text'][:60]}")

    if wrong_direction:
        print("\n  --- DIVERGES IN THE WRONG DIRECTION (these fail the gate) ---")
        for u in wrong_direction:
            print(f"    {u}  expected {known[u]['direction']}, "
                  f"got rvf={inc[u]['failureCount']} duck={cand[u]['failureCount']}")
            print(f"      {known[u]['class']}: {inc[u]['text'][:60]}")

    if resolved:
        print("\n  --- baseline entries that no longer diverge (remove them) ---")
        for u in resolved:
            print(f"    {u}  {known[u]['class']}  {known[u].get('assertion','')[:48]}")

    if len(uncovered) > expected_gaps:
        print(f"\n  --- COVERAGE REGRESSION: {len(uncovered)} uncovered, "
              f"baseline allows {expected_gaps} ---")
        for u in uncovered[:30]:
            print(f"    {inc[u]['text'][:70]}")

    result = {
        "sharedAssertions": len(shared),
        "identical": len(agree),
        "divergent": len(differ),
        "explained": len(expected),
        "unexplained": [
            {"assertionUuid": u, "assertionText": inc[u]["text"],
             "incumbentFailureCount": inc[u]["failureCount"],
             "candidateFailureCount": cand[u]["failureCount"]}
            for u in unexplained],
        "wrongDirection": [
            {"assertionUuid": u, "expectedDirection": known[u]["direction"],
             "class": known[u]["class"],
             "incumbentFailureCount": inc[u]["failureCount"],
             "candidateFailureCount": cand[u]["failureCount"]}
            for u in wrong_direction],
        "resolvedBaselineEntries": resolved,
        "explainedDivergences": [
            {"assertionUuid": u, "class": known[u]["class"],
             "assertionText": inc[u]["text"],
             "incumbentFailureCount": inc[u]["failureCount"],
             "candidateFailureCount": cand[u]["failureCount"]}
            for u in expected],
        "coverage": {
            "accounted": {k: len(v) for k, v in accounted.items()},
            "uncovered": len(uncovered),
            "uncoveredAllowed": expected_gaps,
            "uncoveredAssertions": [inc[u]["text"] for u in uncovered],
            "onlyCandidate": only_cand,
        },
        "incumbentSeconds": inc_secs,
        "candidateSeconds": cand_secs,
        "speedup": speed,
    }
    pathlib.Path(a.out).write_text(json.dumps(result, indent=1))
    print(f"\nwrote {a.out}")

    gate_failures = len(unexplained) + len(wrong_direction) + \
        max(0, len(uncovered) - expected_gaps)

    if a.junit:
        cases = []
        for u in agree:
            cases.append(f'<testcase classname="rvf.parity" name="{escape(u)}"/>')
        for u in expected:
            # Explained: pass, but carry the reason so it is visible in ADO.
            cases.append(
                f'<testcase classname="rvf.parity.explained" name="{escape(u)}">'
                f'<system-out>{escape(known[u]["class"])}: '
                f'{escape(known[u].get("rationale",""))}</system-out></testcase>')
        for u in unexplained:
            msg = (f"UNEXPLAINED divergence: RVF={inc[u]['failureCount']} "
                   f"DuckDB={cand[u]['failureCount']}")
            cases.append(
                f'<testcase classname="rvf.parity" name="{escape(u)}">'
                f'<failure message="{escape(msg)}">{escape(inc[u]["text"])}'
                f'</failure></testcase>')
        for u in wrong_direction:
            msg = (f"divergence in the wrong direction: expected "
                   f"{known[u]['direction']}, got RVF={inc[u]['failureCount']} "
                   f"DuckDB={cand[u]['failureCount']}")
            cases.append(
                f'<testcase classname="rvf.parity" name="{escape(u)}">'
                f'<failure message="{escape(msg)}">{escape(inc[u]["text"])}'
                f'</failure></testcase>')
        if len(uncovered) > expected_gaps:
            msg = (f"coverage regression: {len(uncovered)} incumbent assertions "
                   f"unaccounted for, baseline allows {expected_gaps}")
            cases.append(
                f'<testcase classname="rvf.coverage" name="coverage">'
                f'<failure message="{escape(msg)}">'
                f'{escape(chr(10).join(inc[u]["text"] for u in uncovered[:40]))}'
                f'</failure></testcase>')
        fails = len(unexplained) + len(wrong_direction) + \
            (1 if len(uncovered) > expected_gaps else 0)
        xml = (f'<?xml version="1.0" encoding="UTF-8"?>\n'
               f'<testsuites name="RVF DuckDB Parity" tests="{len(cases)}" '
               f'failures="{fails}">\n'
               f'<testsuite name="failureCount parity + coverage" '
               f'tests="{len(cases)}" failures="{fails}">\n' + "\n".join(cases) +
               '\n</testsuite>\n</testsuites>\n')
        pathlib.Path(a.junit).write_text(xml)
        print(f"wrote {a.junit}")

    if a.fail_on_divergence and differ:
        print(f"\nFAIL (strict): {len(differ)} divergence(s)")
        sys.exit(1)
    if a.gate and gate_failures:
        print(f"\nFAIL: {len(unexplained)} unexplained, {len(wrong_direction)} "
              f"wrong-direction, {max(0, len(uncovered)-expected_gaps)} new "
              f"coverage gap(s)")
        sys.exit(1)
    if a.gate:
        print("\nPASS: every divergence is accounted for and coverage is intact")


if __name__ == "__main__":
    main()
