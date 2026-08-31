#!/usr/bin/env python3
"""
Turn an RVF validation report into JUnit XML for a CI test tab, with links back
to the report on the server.

Why this exists: RVF's report is a bespoke JSON shape. Azure DevOps, GitHub
Actions and GitLab all render JUnit XML natively - per-test history, flakiness,
failure text - so converting is the cheapest way to get validation results in
front of humans without hosting anything new. The raw JSON stays the
machine-readable source of truth for the parity gate; this is the human view.

The mapping that actually matters
--------------------------------
RVF has FOUR outcomes and JUnit has three, and the interesting one is easy to
get wrong:

  failureCount > 0    -> <failure>
  failureCount == -1  -> <skipped>, "NOT EXECUTED"
  assertionsWarning   -> <skipped>, "WARNING" (or <failure> with
                         --warnings-as-failures)
  assertionsPassed    -> bare <testcase>

`failureCount == -1` is RVF's sentinel for "this assertion never ran", not for
"it found one problem" and not for "it found none". On a first-time release with
no previous version supplied, 53 of 58 entries in `assertionsFailed` are this
sentinel - a release-comparison assertion with nothing to compare against. Map
it to <failure> and the build goes red for work that was never attempted; drop
it silently and a genuinely skipped check looks like a pass. It gets its own
bucket, and the reason travels with it.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from xml.etree import ElementTree as ET

NOT_EXECUTED = -1


def load_report(path: Path) -> dict:
    report = json.loads(path.read_text())
    status = report.get("status")
    result = (report.get("rvfValidationResult") or {}).get("TestResult")
    if result is None:
        raise SystemExit(
            f"{path}: no rvfValidationResult.TestResult - status is {status!r}. "
            "A report is only complete once status is COMPLETE; poll "
            "GET /result/{runId}?storageLocation={loc} until then."
        )
    return report


def classname(item: dict) -> str:
    """
    JUnit groups by classname, and CI test tabs show it as the containing suite.
    testCategory is RVF's own grouping (release-type-validation,
    component-centric-validation, ...) and testType distinguishes SQL from
    DROOL_RULES, so together they are the grouping a reader wants.
    """
    category = item.get("testCategory") or "uncategorised"
    test_type = item.get("testType") or "UNKNOWN"
    return f"rvf.{category}.{test_type}"


def testcase_name(item: dict) -> str:
    """
    Assertion text is the only human-meaningful identifier, but it is a sentence
    and CI tabs truncate. Keep it whole and append the UUID, which is what makes
    a row stable across runs even when the corpus rewords an assertion.
    """
    text = (item.get("assertionText") or "unnamed assertion").strip()
    uuid = item.get("assertionUuid")
    return f"{text} [{uuid}]" if uuid else text


def failure_detail(item: dict, limit: int) -> str:
    lines = []
    count = item.get("failureCount")
    if count is not None and count != NOT_EXECUTED:
        lines.append(f"failureCount: {count}")
    if item.get("failureMessage"):
        lines.append(f"message: {item['failureMessage']}")
    instances = item.get("firstNInstances") or []
    if instances:
        shown = instances[:limit]
        lines.append(f"first {len(shown)} of {count if count not in (None, NOT_EXECUTED) else len(instances)}:")
        for d in shown:
            parts = [
                f"conceptId={d.get('conceptId')}" if d.get("conceptId") else None,
                f"componentId={d.get('componentId')}" if d.get("componentId") else None,
                f"moduleId={d.get('moduleId')}" if d.get("moduleId") else None,
                f"fsn={d.get('conceptFsn')}" if d.get("conceptFsn") else None,
                f"detail={d.get('detail')}" if d.get("detail") else None,
            ]
            lines.append("  " + "  ".join(p for p in parts if p))
    return "\n".join(lines)


def build_suites(report: dict, links: str, instance_limit: int,
                 warnings_as_failures: bool) -> ET.Element:
    result = report["rvfValidationResult"]["TestResult"]
    config = report["rvfValidationResult"].get("validationConfig") or {}

    failed = [i for i in (result.get("assertionsFailed") or []) if i.get("failureCount") != NOT_EXECUTED]
    not_executed = [i for i in (result.get("assertionsFailed") or []) if i.get("failureCount") == NOT_EXECUTED]
    warnings = result.get("assertionsWarning") or []
    skipped = result.get("assertionsSkipped") or []
    passed = result.get("assertionsPassed") or []

    buckets = [
        ("failure", failed),
        ("not-executed", not_executed),
        ("warning", warnings),
        ("skipped", skipped),
        ("passed", passed),
    ]

    # Group by classname across all buckets so a suite holds every outcome for
    # its category, which is how a reader expects to browse it.
    by_suite: dict[str, list[tuple[str, dict]]] = {}
    for kind, items in buckets:
        for item in items:
            by_suite.setdefault(classname(item), []).append((kind, item))

    root = ET.Element("testsuites", {
        "name": "RVF validation",
        "tests": str(sum(len(v) for v in by_suite.values())),
        "failures": str(len(failed) + (len(warnings) if warnings_as_failures else 0)),
        "skipped": str(len(not_executed) + len(skipped) + (0 if warnings_as_failures else len(warnings))),
        "time": str(result.get("timeTakenInSeconds") or 0),
    })

    for suite_name in sorted(by_suite):
        entries = by_suite[suite_name]
        suite_failures = sum(
            1 for k, _ in entries if k == "failure" or (k == "warning" and warnings_as_failures)
        )
        suite_skipped = sum(
            1 for k, _ in entries
            if k in ("not-executed", "skipped") or (k == "warning" and not warnings_as_failures)
        )
        suite = ET.SubElement(root, "testsuite", {
            "name": suite_name,
            "tests": str(len(entries)),
            "failures": str(suite_failures),
            "skipped": str(suite_skipped),
            "errors": "0",
        })

        props = ET.SubElement(suite, "properties")
        for key, value in (
            ("rvf.runId", config.get("runId")),
            ("rvf.storageLocation", config.get("storageLocation")),
            ("rvf.executionId", result.get("executionId")),
            ("rvf.totalTestsRun", result.get("totalTestsRun")),
        ):
            if value is not None:
                ET.SubElement(props, "property", {"name": key, "value": str(value)})

        for kind, item in sorted(entries, key=lambda e: testcase_name(e[1])):
            case = ET.SubElement(suite, "testcase", {
                "name": testcase_name(item),
                "classname": suite_name,
                "time": str((item.get("queryInMilliSeconds") or 0) / 1000.0),
            })
            detail = failure_detail(item, instance_limit)

            if kind == "failure" or (kind == "warning" and warnings_as_failures):
                label = "WARNING" if kind == "warning" else "assertion failed"
                node = ET.SubElement(case, "failure", {
                    "message": f"{label}: {item.get('failureCount')} failures",
                    "type": item.get("severity") or item.get("testType") or "assertion",
                })
                node.text = f"{detail}\n\n{links}" if detail else links
            elif kind == "not-executed":
                node = ET.SubElement(case, "skipped", {
                    "message": "NOT EXECUTED - RVF reports failureCount -1, which means the "
                               "assertion never ran. Most often a release-comparison assertion "
                               "with no previous release supplied.",
                })
                node.text = links
            elif kind == "warning":
                node = ET.SubElement(case, "skipped", {
                    "message": f"WARNING: {item.get('failureCount')} instances "
                               "(non-blocking; pass --warnings-as-failures to gate on these)",
                })
                node.text = f"{detail}\n\n{links}" if detail else links
            elif kind == "skipped":
                ET.SubElement(case, "skipped", {"message": "skipped by RVF"}).text = links
            else:
                # Passed. The link still goes in system-out so every row in the
                # test tab can get you back to the report it came from.
                ET.SubElement(case, "system-out").text = links

    return root


def summary_markdown(report: dict, links: str) -> str:
    result = report["rvfValidationResult"]["TestResult"]
    config = report["rvfValidationResult"].get("validationConfig") or {}
    failed = [i for i in (result.get("assertionsFailed") or []) if i.get("failureCount") != NOT_EXECUTED]
    not_executed = [i for i in (result.get("assertionsFailed") or []) if i.get("failureCount") == NOT_EXECUTED]
    return "\n".join([
        "## RVF validation",
        "",
        f"| run | {config.get('runId')} |",
        "|---|---|",
        f"| storage location | `{config.get('storageLocation')}` |",
        f"| tests run | {result.get('totalTestsRun')} |",
        f"| **failed** | **{len(failed)}** |",
        f"| warnings | {len(result.get('assertionsWarning') or [])} |",
        f"| not executed | {len(not_executed)} |",
        f"| passed | {len(result.get('assertionsPassed') or [])} |",
        f"| time | {result.get('timeTakenInSeconds')}s |",
        "",
        links,
        "",
    ])


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("report", type=Path, help="RVF report JSON from GET /result/{runId}")
    parser.add_argument("--out", type=Path, default=Path("rvf-junit.xml"), help="JUnit XML to write")
    parser.add_argument("--summary", type=Path,
                        help="also write a Markdown summary (Azure DevOps: "
                             "##vso[task.uploadsummary] this file)")
    parser.add_argument("--report-url",
                        help="URL of the report itself, e.g. "
                             "https://rvf.example/result/1788069581?storageLocation=duck-8core")
    parser.add_argument("--dashboard-url",
                        help="URL of the Release Dashboard product page, e.g. "
                             "https://dashboard.example/international/rvf_bench_product")
    parser.add_argument("--instance-limit", type=int, default=10,
                        help="failure instances to include per assertion (default 10)")
    parser.add_argument("--warnings-as-failures", action="store_true",
                        help="gate the build on RVF warnings as well as failures")
    args = parser.parse_args()

    report = load_report(args.report)

    link_lines = []
    if args.dashboard_url:
        link_lines.append(f"Report on the dashboard: {args.dashboard_url}")
    if args.report_url:
        link_lines.append(f"Raw RVF report: {args.report_url}")
    links = "\n".join(link_lines)

    root = build_suites(report, links, args.instance_limit, args.warnings_as_failures)
    ET.indent(root, space="  ")
    args.out.write_bytes(ET.tostring(root, encoding="utf-8", xml_declaration=True))

    if args.summary:
        args.summary.write_text(summary_markdown(report, links))

    print(f"{args.out}: {root.get('tests')} tests, {root.get('failures')} failures, "
          f"{root.get('skipped')} skipped, {len(root)} suites")
    return 0


if __name__ == "__main__":
    sys.exit(main())
