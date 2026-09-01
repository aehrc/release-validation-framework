#!/usr/bin/env python3
"""Run one release through BOTH execution engines and gate on the difference.

This is the check that has to pass before a change to the DuckDB engine is
believable: the same release, the same assertion corpus, the same jar, with only
``rvf.execution.engine`` differing. Anything the two engines disagree about is
either a defect we introduced or a MySQL defect we are choosing to diverge from -
and the second kind has to be written down, with its evidence, in the baseline.

Why same-jar matters
--------------------
An earlier A/B compared a separate Python engine against a production MySQL
report. That was useful but weak: different corpora, different hosts, different
release. Two instances of one jar on one host validating one release removes
every variable except the engine, so a divergence means something.

What this does NOT do
---------------------
Start the servers. It takes two base URLs. Starting them is the pipeline's job,
or a developer's, and keeping it out of here is what makes the script the same
code in CI and on a laptop.

Exit status is the gate: 0 when every divergence is accounted for, 1 when one is
not, so a pipeline step needs no extra logic.
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent

# RVF trusts these and validates nothing; in a deployment the gateway supplies
# them. Sent explicitly so the script works against a bare instance too.
AUTH = {
    "X-AUTH-username": "engine-ab",
    "X-AUTH-roles": "ROLE_ihtsdo-ops-admin",
    "X-AUTH-token": "local",
}

TERMINAL = {"COMPLETE", "FAILED", "FAILED_TO_COMPLETE"}


def submit(base: str, run_id: int, storage: str, args) -> None:
    """Submits via /run-post-via-s3, naming a release already in the job store.

    Form-encoded, not multipart: this endpoint takes no file, only
    ``@RequestParam`` values, so multipart would be a hand-rolled body with a
    boundary to get wrong - which an earlier version of this did, and Tomcat sat
    waiting for a part that never came rather than answering 400.

    Deliberately not the multipart /run-post either. A real edition is 853MB,
    which the 1GB spring.servlet.multipart limit and any ingress body cap both
    apply to, and pushing it twice - once per engine - would dominate the run.
    Both engines reading the same bytes from the same path is also one fewer
    variable between the legs.
    """
    fields = [
        ("bucketName", args.bucket),
        ("releaseFileS3Path", args.release),
        ("runId", str(run_id)),
        ("storageLocation", storage),
        ("failureExportMax", str(args.failure_export_max)),
        ("releaseAsAnEdition", "true" if args.release_as_edition else "false"),
    ]
    for group in args.groups:
        fields.append(("groups", group))
    if args.previous_release:
        fields.append(("previousRelease", args.previous_release))
    if args.dependency_release:
        fields.append(("dependencyRelease", args.dependency_release))
    if args.effective_time:
        fields.append(("effectiveTime", args.effective_time))

    body = urllib.parse.urlencode(fields).encode()
    request = urllib.request.Request(
        f"{base}/run-post-via-s3", data=body, method="POST",
        headers={**AUTH, "Content-Type": "application/x-www-form-urlencoded"})
    with urllib.request.urlopen(request, timeout=args.submit_timeout) as response:
        if response.status not in (200, 201):
            raise SystemExit(f"{base}: submit returned {response.status}")


def poll(base: str, run_id: int, storage: str, timeout: int, label: str) -> dict:
    started = time.time()
    last = None
    while time.time() - started < timeout:
        request = urllib.request.Request(
            f"{base}/result/{run_id}?storageLocation={storage}", headers=AUTH)
        try:
            with urllib.request.urlopen(request, timeout=60) as response:
                report = json.loads(response.read())
        except urllib.error.URLError as e:
            # A restarting instance is not a failed run; keep waiting.
            print(f"  {label}: {e}", flush=True)
            time.sleep(10)
            continue
        status = report.get("status")
        if status != last:
            print(f"  {label}: {status} at {int(time.time() - started)}s", flush=True)
            last = status
        if status in TERMINAL:
            return report
        time.sleep(10)
    raise SystemExit(f"{label}: still {last} after {timeout}s")


def summarise(label: str, report: dict) -> None:
    result = (report.get("rvfValidationResult") or {}).get("TestResult") or {}
    print(f"  {label:8} status={report.get('status')} "
          f"run={result.get('totalTestsRun')} failures={result.get('totalFailures')} "
          f"warnings={result.get('totalWarnings')} incomplete={result.get('totalTestsIncomplete')} "
          f"time={result.get('timeTakenInSeconds')}s")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--mysql-url", required=True, help="base URL of the mysql-engine instance")
    ap.add_argument("--duck-url", required=True, help="base URL of the duckdb-engine instance")
    ap.add_argument("--release", required=True,
                    help="path of the release WITHIN the validation job store")
    ap.add_argument("--bucket", default="local",
                    help="ignored when job storage is local, but the endpoint requires it")
    ap.add_argument("--groups", nargs="+", required=True)
    ap.add_argument("--previous-release")
    ap.add_argument("--dependency-release")
    ap.add_argument("--effective-time")
    ap.add_argument("--release-as-edition", action="store_true")
    ap.add_argument("--failure-export-max", type=int, default=10)
    ap.add_argument("--run-id", type=int, help="defaults to the current epoch millis")
    ap.add_argument("--storage-prefix", default="engine-ab")
    ap.add_argument("--timeout", type=int, default=5400,
                    help="per-leg wait; the MySQL leg loads whole editions and is slow")
    ap.add_argument("--submit-timeout", type=int, default=300)
    ap.add_argument("--baseline", default=str(HERE / "known-engine-divergences.json"))
    ap.add_argument("--out", default="engine-ab.json")
    ap.add_argument("--junit", default="engine-ab.xml")
    ap.add_argument("--skip-mysql", action="store_true",
                    help="reuse a previously fetched mysql report (see --mysql-report)")
    ap.add_argument("--mysql-report", default="engine-ab-mysql.json")
    ap.add_argument("--duck-report", default="engine-ab-duck.json")
    args = ap.parse_args()

    run_id = args.run_id or int(time.time() * 1000)
    print(f"engine A/B  runId={run_id}  release={args.release}  groups={args.groups}")

    # Sequential, not parallel. Two full editions in MySQL plus two in DuckDB on
    # one host would contend for both CPU and page cache, and the timings are the
    # secondary output of this job - the findings are the primary one.
    if not args.skip_mysql:
        storage = f"{args.storage_prefix}-mysql"
        submit(args.mysql_url, run_id, storage, args)
        report = poll(args.mysql_url, run_id, storage, args.timeout, "mysql")
        Path(args.mysql_report).write_text(json.dumps(report, indent=1))
    mysql_report = json.loads(Path(args.mysql_report).read_text())
    summarise("mysql", mysql_report)

    storage = f"{args.storage_prefix}-duck"
    submit(args.duck_url, run_id, storage, args)
    duck_report = poll(args.duck_url, run_id, storage, args.timeout, "duckdb")
    Path(args.duck_report).write_text(json.dumps(duck_report, indent=1))
    summarise("duckdb", duck_report)

    for label, report in (("mysql", mysql_report), ("duckdb", duck_report)):
        if report.get("status") != "COMPLETE":
            print(f"\n{label} did not complete: {json.dumps(report.get('failureMessages'))[:400]}")
            return 1

    print("\n--- gate ---", flush=True)
    gate = subprocess.run(
        [sys.executable, str(HERE / "compare_reports.py"),
         "--incumbent", args.mysql_report, "--candidate", args.duck_report,
         "--out", args.out, "--junit", args.junit,
         "--baseline", args.baseline, "--gate"],
        check=False)
    return gate.returncode


if __name__ == "__main__":
    sys.exit(main())
