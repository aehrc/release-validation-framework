#!/usr/bin/env python3
"""Self-test for rvf_junit.py's outcome mapping.

Exists because of one specific mistake, and it is the kind that ships quietly.
RVF puts "this assertion never ran" into `assertionsFailed` with
`failureCount == -1`, alongside genuine failures. The obvious conversion - every
entry in assertionsFailed becomes a <failure> - produces a red build for work
that was never attempted. On the report this was built against, that is 58
failures reported where 5 are real; the other 53 are release-comparison
assertions with no previous release supplied.

Nothing about the JSON makes that visible: the sentinel is a valid integer in a
list called "failed". So the mapping is pinned here, with a case that fails if
anyone "simplifies" it back.

Run: python3 ci/rvf_junit_selftest.py    (exit 0 = pass)
"""
import importlib.util
import json
import pathlib
import sys
import tempfile
from xml.etree import ElementTree as ET

HERE = pathlib.Path(__file__).resolve().parent

_spec = importlib.util.spec_from_file_location("rvf_junit", HERE / "rvf_junit.py")
rvf_junit = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(rvf_junit)


def report(failed=(), warning=(), passed=(), skipped=()):
    return {
        "status": "COMPLETE",
        "rvfValidationResult": {
            "validationConfig": {"runId": 1, "storageLocation": "loc"},
            "TestResult": {
                "executionId": "1",
                "totalTestsRun": len(failed) + len(warning) + len(passed) + len(skipped),
                "timeTakenInSeconds": 7,
                "assertionsFailed": list(failed),
                "assertionsWarning": list(warning),
                "assertionsPassed": list(passed),
                "assertionsSkipped": list(skipped),
            },
        },
    }


def item(uuid, count, text="an assertion", category="cat", test_type="SQL", instances=None):
    d = {"assertionUuid": uuid, "failureCount": count, "assertionText": text,
         "testCategory": category, "testType": test_type}
    if instances:
        d["firstNInstances"] = instances
    return d


def convert(rep, **kw):
    root = rvf_junit.build_suites(
        rep,
        links=kw.pop("links", "LINKS"),
        instance_limit=kw.pop("instance_limit", 10),
        warnings_as_failures=kw.pop("warnings_as_failures", False),
    )
    return root


def counts(root):
    return {
        "tests": int(root.get("tests")),
        "failures": len(root.findall(".//failure")),
        "skipped": len(root.findall(".//skipped")),
        "cases": len(root.findall(".//testcase")),
        "declared_failures": int(root.get("failures")),
        "declared_skipped": int(root.get("skipped")),
    }


CASES = {}


def case(name):
    def wrap(fn):
        CASES[name] = fn
        return fn
    return wrap


@case("the -1 sentinel is skipped, not failed")
def _():
    root = convert(report(failed=[item("a", -1), item("b", 3)]))
    c = counts(root)
    assert c["failures"] == 1, f"expected 1 real failure, got {c['failures']}"
    assert c["skipped"] == 1, f"expected the sentinel skipped, got {c['skipped']}"
    msg = root.find(".//skipped").get("message")
    assert "NOT EXECUTED" in msg, msg
    assert "never ran" in msg, "the reason must travel with the skip"


@case("declared attribute totals match the emitted elements")
def _():
    root = convert(report(failed=[item("a", -1), item("b", 3), item("c", 1)],
                          warning=[item("w", 2)], passed=[item("p", 0)]))
    c = counts(root)
    assert c["declared_failures"] == c["failures"], (c["declared_failures"], c["failures"])
    assert c["declared_skipped"] == c["skipped"], (c["declared_skipped"], c["skipped"])
    assert c["tests"] == c["cases"] == 5, c


@case("warnings are non-blocking by default and gate on request")
def _():
    rep = report(warning=[item("w", 2)])
    default = counts(convert(rep))
    assert default["failures"] == 0, "a warning must not fail the build by default"
    assert default["skipped"] == 1
    gated = counts(convert(rep, warnings_as_failures=True))
    assert gated["failures"] == 1, "--warnings-as-failures must gate on warnings"
    assert gated["skipped"] == 0


@case("passed assertions carry the link but no failure node")
def _():
    root = convert(report(passed=[item("p", 0)]), links="SEE-HERE")
    assert root.findall(".//failure") == []
    assert root.findall(".//skipped") == []
    out = root.find(".//system-out")
    assert out is not None and "SEE-HERE" in out.text


@case("links reach failures, skips and warnings")
def _():
    root = convert(report(failed=[item("a", -1), item("b", 3)], warning=[item("w", 1)]),
                   links="LINK-X")
    for node in list(root.findall(".//failure")) + list(root.findall(".//skipped")):
        assert node.text and "LINK-X" in node.text, f"{node.tag} lost the link"


@case("suites group by category and type, and hold every outcome")
def _():
    root = convert(report(
        failed=[item("a", 2, category="release-type-validation", test_type="SQL")],
        passed=[item("b", 0, category="release-type-validation", test_type="SQL"),
                item("c", 0, category="component-centric-validation", test_type="DROOL_RULES")],
    ))
    names = sorted(s.get("name") for s in root.findall("testsuite"))
    assert names == ["rvf.component-centric-validation.DROOL_RULES",
                     "rvf.release-type-validation.SQL"], names
    mixed = [s for s in root.findall("testsuite") if s.get("name").endswith("SQL")][0]
    assert mixed.get("tests") == "2" and mixed.get("failures") == "1", mixed.attrib


@case("failure detail includes instances up to the limit")
def _():
    instances = [{"conceptId": str(i), "componentId": f"c{i}"} for i in range(25)]
    root = convert(report(failed=[item("a", 25, instances=instances)]), instance_limit=3)
    text = root.find(".//failure").text
    assert "first 3 of 25" in text, text[:200]
    assert "conceptId=0" in text and "conceptId=3" not in text.split("LINKS")[0], text[:300]


@case("testcase names stay stable via the uuid")
def _():
    root = convert(report(failed=[item("uuid-1", 1, text="Text may be reworded")]))
    name = root.find(".//testcase").get("name")
    assert name.endswith("[uuid-1]"), name


@case("an incomplete report is refused, not silently emptied")
def _():
    with tempfile.TemporaryDirectory() as d:
        p = pathlib.Path(d) / "r.json"
        p.write_text(json.dumps({"status": "RUNNING", "Progress": "half"}))
        try:
            rvf_junit.load_report(p)
        except SystemExit as e:
            assert "RUNNING" in str(e), str(e)
        else:
            raise AssertionError("a RUNNING report must be refused")


@case("output is well-formed XML that re-parses")
def _():
    root = convert(report(failed=[item("a", 2), item("b", -1)], passed=[item("p", 0)]))
    xml = ET.tostring(root, encoding="utf-8")
    ET.fromstring(xml)


def main():
    failed = 0
    for name, fn in CASES.items():
        try:
            fn()
            print(f"[pass] {name}")
        except AssertionError as e:
            failed += 1
            print(f"[FAIL] {name}\n         {e}")
    print(f"\n{len(CASES) - failed}/{len(CASES)} passed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
