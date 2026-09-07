#!/usr/bin/env python3
"""Does the parity gate distinguish evidence from silence?

The gate joins two reports on assertionUuid and compares failureCount. Zero
equals zero, so an assertion that never RAN agrees with one that ran and found
nothing, and both count towards the agreement percentage. That is not a
hypothetical failure mode in this project:

  * 200 amtv4 assertions were absent from the DuckDB store for weeks while the
    report stayed green, because absent assertions produce no findings;
  * three international assertions could not execute at all - two casting '' to
    SMALLINT, one with a bare `commit` glued to its SQL - and the AU nightly
    showed nothing, because the tables they read are empty in an AU release.

So this checks the classification itself, on fixtures small enough to reason
about, including the two cases the gate used to call agreement.

Run: python3 ci/compare_reports_selftest.py
"""
import json
import pathlib
import subprocess
import sys
import tempfile

HERE = pathlib.Path(__file__).resolve().parent
GATE = HERE / "compare_reports.py"

# Every fixture record is one assertion. Only the fields the gate reads.
def rec(uuid, count, bucket="assertionsFailed", message=None, text=None,
        test_type="SQL"):
    out = {"assertionUuid": uuid, "failureCount": count,
           "assertionText": text or f"assertion {uuid}", "testType": test_type}
    if message is not None:
        out["failureMessage"] = message
    return {"bucket": bucket, "record": out}


def report(records, seconds=10):
    buckets = {}
    for r in records:
        buckets.setdefault(r["bucket"], []).append(r["record"])
    return {"rvfValidationResult": {"TestResult": dict(
        buckets, timeTakenInSeconds=seconds)}}


BASELINE = {"divergences": {}, "coverage": {"categories": {}, "knownGaps": {
    "expectedCount": 0}}}


def run_gate(incumbent, candidate, baseline=None):
    """Returns (exit code, stdout, parsed result json)."""
    with tempfile.TemporaryDirectory() as tmp:
        tmp = pathlib.Path(tmp)
        (tmp / "inc.json").write_text(json.dumps(incumbent))
        (tmp / "cand.json").write_text(json.dumps(candidate))
        (tmp / "base.json").write_text(json.dumps(baseline or BASELINE))
        proc = subprocess.run(
            [sys.executable, str(GATE),
             "--incumbent", str(tmp / "inc.json"),
             "--candidate", str(tmp / "cand.json"),
             "--baseline", str(tmp / "base.json"),
             "--out", str(tmp / "out.json"),
             "--junit", str(tmp / "out.xml"), "--gate"],
            capture_output=True, text=True)
        return (proc.returncode, proc.stdout + proc.stderr,
                json.loads((tmp / "out.json").read_text()),
                (tmp / "out.xml").read_text())


CASES = []


def case(fn):
    CASES.append(fn)
    return fn


@case
def both_ran_and_found_the_same_is_real_agreement():
    code, out, res, xml = run_gate(
        report([rec("u1", 7)]), report([rec("u1", 7)]))
    assert code == 0, out
    assert res["identicalWithFindings"] == 1, res
    assert res["identicalNeitherRan"] == 0, res
    assert not res["ranOnOneEngineOnly"], res
    assert "<failure" not in xml


@case
def both_ran_and_found_nothing_is_weaker_but_still_counted():
    code, out, res, xml = run_gate(
        report([rec("u1", 0, "assertionsPassed")]),
        report([rec("u1", 0, "assertionsPassed")]))
    assert code == 0, out
    assert res["identicalBothRanEmpty"] == 1, res
    assert res["identicalWithFindings"] == 0, res


@case
def neither_ran_is_reported_as_no_evidence_not_as_a_pass():
    # The AU nightly's two "<DEPENDENCY> not supplied" assertions are this
    # shape on both engines. Legitimate, and worth nothing as parity evidence,
    # so it must not be counted as agreement or rendered as a passing test.
    msg = "Not run: requires <DEPENDENCY>, which was not supplied"
    code, out, res, xml = run_gate(
        report([rec("u1", 0, message=msg)]),
        report([rec("u1", 0, message=msg)]))
    assert code == 0, out
    assert res["identicalNeitherRan"] == 1, res
    assert res["identicalBothRanEmpty"] == 0, res
    assert "<skipped" in xml, xml
    assert "ran on NEITHER engine" in out, out


@case
def candidate_silently_skipping_fails_the_gate():
    # The case that motivated all of this: DuckDB cannot execute the assertion,
    # so it reports nothing; MySQL runs it and finds nothing on this release.
    # Counts match, and the assertion is unchecked.
    code, out, res, xml = run_gate(
        report([rec("u1", 0, "assertionsPassed")]),
        report([rec("u1", 0, message="Error executing DuckDB statement: boom")]))
    assert code == 1, out
    assert len(res["ranOnOneEngineOnly"]) == 1, res
    assert res["ranOnOneEngineOnly"][0]["notRunOn"] == "candidate", res
    assert "ran on one engine only" in xml
    assert "ONE ENGINE RAN IT" in out, out


@case
def incumbent_silently_skipping_fails_the_gate_too():
    # Symmetric on purpose. If MySQL is the engine that stopped running an
    # assertion, the DuckDB result has nothing to be compared against, and
    # calling that agreement would flatter the candidate.
    code, out, res, xml = run_gate(
        report([rec("u1", 0, message="Not run: requires <PREVIOUS>")]),
        report([rec("u1", 0, "assertionsPassed")]))
    assert code == 1, out
    assert res["ranOnOneEngineOnly"][0]["notRunOn"] == "incumbent", res


@case
def a_not_run_record_filed_under_failed_is_still_not_run():
    # RVF files a not-run assertion under assertionsFailed WITH a message, so
    # reading the bucket alone would treat it as a real failure with a real
    # count. Build 16247 has exactly two of these.
    code, out, res, xml = run_gate(
        report([rec("u1", 0, "assertionsFailed",
                    message="Not run: requires <DEPENDENCY>")]),
        report([rec("u1", 0, "assertionsFailed",
                    message="Not run: requires <DEPENDENCY>")]))
    assert code == 0, out
    assert res["identicalNeitherRan"] == 1, res


@case
def a_real_divergence_still_fails_the_gate():
    code, out, res, xml = run_gate(
        report([rec("u1", 5)]), report([rec("u1", 9)]))
    assert code == 1, out
    assert len(res["unexplained"]) == 1, res


@case
def an_explained_divergence_still_passes():
    baseline = {"divergences": {"u1": {
        "class": "mysql-blind-to-content", "direction": "candidate-higher",
        "rationale": "MySQL's varchar SCTID parameter loses ids above 2^53"}},
        "coverage": {"categories": {}, "knownGaps": {"expectedCount": 0}}}
    code, out, res, xml = run_gate(
        report([rec("u1", 5)]), report([rec("u1", 9)]), baseline)
    assert code == 0, out
    assert res["explained"] == 1, res


@case
def the_agreement_percentage_no_longer_hides_the_denominator():
    # Nine assertions neither engine ran, one both ran. The old report said
    # "10 identical, 100%"; it now has to say how many of those are evidence.
    msg = "Not run: requires <DEPENDENCY>"
    inc = [rec(f"u{i}", 0, message=msg) for i in range(9)] + [rec("real", 3)]
    cand = [rec(f"u{i}", 0, message=msg) for i in range(9)] + [rec("real", 3)]
    code, out, res, xml = run_gate(report(inc), report(cand))
    assert code == 0, out
    assert res["identical"] == 10, res
    assert res["identicalNeitherRan"] == 9, res
    assert res["identicalWithFindings"] == 1, res
    assert "1 of 10 agreements ran on both engines" in out, out


def main():
    failed = []
    for fn in CASES:
        try:
            fn()
            print(f"  [pass] {fn.__name__}")
        except AssertionError as e:
            failed.append(fn.__name__)
            print(f"  [FAIL] {fn.__name__}: {str(e)[:300]}")
    print(f"\n{len(CASES) - len(failed)}/{len(CASES)} passed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
