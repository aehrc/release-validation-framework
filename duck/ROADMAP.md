# Where this is going, and what is left

Written 2026-09-01. The goal has not changed: **a nightly AMT validation against
the last published release, on our own infrastructure, cheap enough to run every
night**, with results a human can read and a build can gate on.

## What is already true

Measured on the AU edition (853MB, 45.3M rows), 8 cores, previous release
supplied, all on one host:

| | |
|---|---|
| nightly wall clock | **160s**, from 339s when this started |
| the same run on MySQL | **1380s** |
| engine agreement | **148 of 149** assertions identical, the last one fixed and pending |
| failure detail kept | full `qa_result` as parquet, ~21MB per million rows |
| uploaded releases | reaped after 7 days; previous releases kept |

Working end to end, locally: API + worker sharing one mount and one broker;
`GET /releases` listing kept releases; a named `previousRelease` driving 86 of 87
release-type assertions; both SI UIs rendering DuckDB results; JUnit for CI; the
container image built without a Docker daemon.

Raised: IHTSDO **#74** (MySQL fixes), **#75** (structural single pass), **#76**
(file sizes from the archive), assertions **#6** (the cursor fix #74 needs).
Fork-only: #24 engine seam, #25 DuckDB engine, #27 optional line splitting.

## What is NOT proven, and cannot be here

No cluster, no SMB mount, no ACR credentials on this host. So: Azure Files
throughput against node-local disk, KEDA actually scaling, the ingress that
supplies the `X-AUTH-*` headers RVF trusts, and node sizing on real AKS SKUs.
Everything below in Phase 3 and 4 depends on someone with cluster access.

## Phase 1 - unblock upstream. No infrastructure needed.

1. **Pair assertions#6 with IHTSDO#74.** #74 alone leaves
   `file-centric-snapshot-inactivated-component-module` reporting `-1` for any
   release with an Identifier file, which AU editions have. Needs SI review, not
   more work from us.
   *Done when:* #6 merges, or #74 carries an explicit note that it must not land
   without it.
2. **Chase #75 and #76.** Independent, clean, no engine dependency.
3. **Raise the `-1` reporting semantics with SI.** Two halves: MySQL reports a
   pass for an assertion it never fully ran, and Release-Dashboard-UI renders
   `-1` literally in Total Failures. An issue, not a PR - the fix is a product
   decision.
   *Done when:* an issue exists with the measurement (53 of 58 on a first-time
   release).
4. **Fix the per-run schema leak.** Four abandoned schemas held 41GB here and a
   run died on a full disk mid-measurement. `dropSchema` exists; something does
   not call it on the failure path. Cheap, prevents an outage, and is upstream
   material.
   *Done when:* a failed MySQL run leaves no schema behind, with a test.
5. **Fix `POST /assertions/{id}/run`.** Throws NullPointerException because
   `MysqlExecutionConfig.getIncludedModules()` is null and something calls
   `.stream()`. It is the one genuinely useful MySQL-only endpoint and it is
   broken in MySQL mode.

## Phase 2 - deploy. Needs ACR and AKS access.

6. **Push the image to ACR.** `mvn jib:build` with
   `-Ddocker.registry=ontoserver.azurecr.io -Ddocker.image.prefix=aehrc-rvf`.
   Verified locally as a 459MB tarball with the corpus, the Drools rules and
   `app/resources/duck/store.json` inside.
   *Blocked on:* registry credentials.
7. **Create the pipeline definitions** in `OD225632-NCTS-ContentAndTooling`
   against `aehrc/release-validation-framework`, branch `catchup-upgraded`:
   `az/azure-pipeline.engine-ab.yml` (the gate) and a nightly (task 10).
   *Blocked on:* project permissions.
8. **Apply `k8s/rvf-aks.yaml`** and smoke test: API accepts a validation, a
   worker on another node executes it, the API serves the report. That is the
   whole point of the shared mount and it is what proves the split on real
   storage.
   *Done when:* a report written by a worker is served by an API replica that
   never executed it.
9. **Settle authentication.** RVF builds a
   `PreAuthenticatedAuthenticationToken` from `X-AUTH-username`/`-roles`/`-token`
   and validates nothing - `X-AUTH-token: local` is accepted. Fine behind a
   gateway that sets those and strips client-supplied ones; catastrophic if
   exposed. Needs a decision, then an ingress.
10. **Seed the release store** with the last published AMT release, so
    `previousRelease` resolves on night one.

## Phase 3 - wire the nightly.

11. **Nightly pipeline.** **WRITTEN 2026-09-03** as
    `az/azure-pipeline.nightly.yml`, unrun - it needs the deployed API. Reuse what already works: `daily-rvf` publishes the
    prospective release as an `rf2-bundle` pointer and the existing duckdb-rvf
    pipeline triggers on its `RvfStage` and azcopys the blob. So: trigger the
    same way, land the release on the Azure Files job store, `POST
    /run-post-via-s3` naming the path, poll `/result/{runId}`.
    No Snowstorm export step to build.
12. **Publish results.** **WIRED 2026-09-03** into the pipeline above and
    exercised against real reports. `ci/rvf_junit.py` for the test tab -
    `failureCount == -1` becomes `<skipped>` rather than a red build - plus
    `failures.parquet` and a link to the report.
13. ~~**Decide the nightly's scope.**~~ **ANSWERED 2026-09-03** - measured, so
    it is no longer a trade-off between cheap-and-incomplete and unmeasured.
    The full decided scope on the AU edition, all phases concurrent, 8 cores:

    | phase | cost | notes |
    |---|---|---|
    | acquisition | 42 s | serial, before anything else |
    | SQL assertions | 131 s | incl. materialising 45.3 M rows |
    | structural | 31 s | concurrent, off the critical path |
    | Drools | 154 s | from 697 s, after three load optimisations |
    | MRCM | 735 s | from 1,292 s; still the long pole |

    **Run everything.** MRCM is ~4.8x the next phase but the whole run is still
    under a quarter hour against `daily-rvf`'s 181 minutes, so skipping phases
    buys minutes off something already 15x faster while giving up coverage.
    See `duck/NIGHTLY-PLAN.md` for the full derivation and what is left in MRCM.
14. **First autonomous green nightly**, then leave it alone for a week and count
    how often it needed a human.

## Phase 4 - the measurements that need the cluster.

15. **Release cache: node-local vs the share vs off.** One cached edition is
    1.57GB and rebuilds in ~21s, so this is ~8%, and reading 1.57GB over SMB may
    lose to rebuilding from local disk. `rvf.duck.cache.max-gb` is 0 until
    measured.
16. **KEDA on queue depth.** Prove it scales up on a backlog and that scaling
    down never kills a run in flight - `terminationGracePeriodSeconds: 600` is a
    guess until tested.
17. **Confirm sizing on real SKUs.** 8 cores / 8GB is measured on Petrichor and
    this VM; AKS node types and the DuckDB thread cap
    (`RVF_DUCK_THREADS` must match the CPU limit, or DuckDB reads the machine and
    not the quota: 674s against 259s) should be confirmed once.

## Phase 5 - the dashboard question, which is still open.

18. **Decide how humans read a nightly.** Two options, and they are not
    exclusive:
    * **CI-only.** JUnit in the test tab plus the parquet and the raw report as
      artefacts. Zero services. Already built.
    * **SI-equivalent.** Deploy `snomed-release-service` and
      `Release-Dashboard-UI` as well, which is what SI operators use. Proven
      locally, including a DuckDB report rendering in the real dialog - but the
      dashboard reads `build.rvfURL` off an SRS build record, so it needs SRS,
      and SRS needed two changes to get there.
19. **If deploying SRS, land its two changes.** `srs.build.offlineMode` was
    gating both storage selection and the RVF call, now split as
    `srs.build.rvf.enabled`; and `RVFClient` forwarded only `Cookie`, so RVF
    answered 401. Both are in `/data/work/srs` and contributed nowhere.

## Phase 6 - later, in value order.

20. **Mount the corpus instead of baking it**, and pin
    `checkout-resources.sh`. `develop`'s pom runs it unpinned during every
    build, which is how a store ended up 20 assertions behind a moving corpus.
    A permanently running service should not be able to do that.
21. **Move the fixed divergence to `removedAllowances`** once the pinned corpus
    includes assertions#6, so the gate fails if it returns.
22. **Revisit PR #27** (line splitting across cores). Structural testing is now
    29s of a 160s run, so its 1.38x is worth ~8s. Probably never.
23. **`{id}/run` on DuckDB**, now that releases are addressable by name. Lets a
    rule author test one assertion without a pipeline.

## Decisions needed before Phase 2 can start

1. **Who runs the deploy?** Credentials for ACR and AKS, or do you apply and I
   prepare?
2. **Drools and MRCM in the nightly** - in, out, or measure first?
3. **CI-only reporting, or deploy SRS + Release-Dashboard-UI too?**
4. **Authentication:** internal-only behind a header-injecting gateway, or
   something stronger?
5. **Corpus baked or mounted** for the first deployment?

## Update, 2026-09-01 (afternoon)

Phase 1 is closed. Both defects that needed nobody are fixed, and a third was
found while packaging them for upstream.

**Raised:**

| where | what |
|---|---|
| IHTSDO/release-validation-framework#77 | issue: an assertion that could not run is reported as passed, and `-1` reaches the dashboard |
| aehrc/release-validation-framework#29 | PR: drop a run's schemas when the run ends (genuine upstream defect) |
| IHTSDO/snomed-release-validation-assertions#6 | corpus fix, awaiting review |

**Fixed on `catchup-upgraded`, not upstream-worthy - both are OUR regressions
from `6f84a1ee`, which extracted `MySqlQueryTransformer` from
`AssertionExecutionService`:**

* `aedaf958` - the single-assertion endpoints NPE'd on a null module list.
  Upstream guards this at `AssertionExecutionService:239`; the extraction
  dropped the guard.
* `bff28117` - an empty module set rendered as `""` instead of the literal
  `NULL`, so three assertions that gate on `'NULL' = '<INCLUDED_MODULES>'`
  passed without validating anything on international runs. `DuckBinder` was
  always correct, so DuckDB was unaffected - which is why the AU A/B never
  showed it. **AU is an extension: non-empty modules, both engines identical.**

**What that says about the gate.** A shared-cause defect is invisible to an
A/B, and a single-corpus A/B only exercises the module paths that corpus
uses. The AU nightly cannot see international-only branches. Running the gate
against an INT edition at least once is now worth more than another AU night.

**Deployment topology - answering "was this ever decided":** yes, on
2026-08-30, in `k8s/HOSTING.md`, `k8s/SCALING.md` and `k8s/README.md`, but
never in conversation, which is why it does not feel decided.

* **Chosen:** listener claims work. `rvf.execution.isWorker` is RVF's own
  property, so the split needed no code change. KEDA `ScaledObject` on
  ActiveMQ queue depth.
* **Rejected for now:** job per run. It is the better model and is recorded as
  such; cold start is only 9 s (11% of an 83 s run) so cost is not the
  objection. The blocker is that RVF's worker never exits - no
  `CommandLineRunner`, no `System.exit`. Needs a one-shot mode, which is a new
  deployment contract rather than a bug fix.
* **Idle:** `minReplicaCount: 1` today, so one worker always idle against a
  0.24% duty cycle for a single nightly. `minReplicaCount: 0` removes it with
  no code change; the API stays warm and holds the queue.
* **Unproven, needs a cluster:** whether KEDA's ActiveMQ scaler counts
  in-flight unacknowledged messages. If it does, queue depth stays non-zero
  for the run and scale-in never targets a busy pod. If it does not, safety
  rests entirely on `terminationGracePeriodSeconds: 600` - which covers a
  339 s DuckDB nightly but **not** a 1380 s MySQL run.
* **Honest recommendation for one nightly run:** single server with the
  embedded worker, which is the zero-config default. Deploy the split when a
  second concurrent consumer actually exists.

### Both fixes confirmed on a real international run, 2026-09-01

An INT edition (`SnomedCT_InternationalRF2_PRODUCTION_20260801T120000Z`, 556 MB)
through the MySQL engine with no `includedModules` - i.e. the exact shape the
gate bug broke. COMPLETE in 360 s, 104 tests, 1 failure, 1 incomplete.

**The module gate.** `component-centric-snapshot-language-valid-moduleid` now
passes with 0 having taken the *international* arm. What the broken rendering
did to its *extension* arm on the same run, confirmed directly against
MySQL 8:

    SELECT 1 WHERE 1 NOT IN ()   ->  ERROR 1064 (42000)
    SELECT 1 WHERE 'NULL'='NULL' ->  gate open

So pre-fix that assertion did not quietly pass on an international run - it
died on a syntax error and was reported as `-1`, which is the same sentinel
IHTSDO#77 is about. Two of our own defects were compounding: the empty
rendering produced the error, and the `-1` reporting hid it as "not run"
rather than "broken".

**The schema leak.** After the run reached COMPLETE the per-run schema was
already gone - only `rvf_master`, `rvf_emptyrf2snapshot` and the two
deliberately-kept AU previous releases remain. Pre-fix it would have sat there
until the next run started, which for a nightly is a day, and forever if the
pod is replaced.


## Update, 2026-09-03 - the nightly pipeline exists, and what it cannot prove

`az/azure-pipeline.nightly.yml` is written. It is the pipeline that runs
**alongside** def 42 `daily-rvf` and is meant to replace it: it hands a release
to the **deployed** API and reports what comes back, so the thing under test is
the deployment rather than an agent-local jar. That is the difference from
`azure-pipeline.engine-ab.yml`, which is a PR gate running both engines on the
agent.

**It has never run**, because it needs an API that is not deployed. Everything
that could be checked without a cluster was:

| checked | how |
|---|---|
| YAML parses, 12 steps, params resolve | `yaml.safe_load` |
| all 7 inline scripts are valid bash | extracted, ADO macros neutralised, `bash -n` |
| JUnit conversion works on a real report | 1037 tests -> 3 suites, 2 failures, 5 skipped |
| completion detection | three-way against three REAL reports |
| scope guard | passes a genuine full run, fails a reconstructed skipped-phase run |

### Three defects found by testing it rather than reading it

1. **The completion predicate was wrong.** `GET /result/{runId}` returns
   `{status, rvfValidationResult:{TestResult,...}}`; the on-disk `results.json`
   has `TestResult` at the top level. Checking the on-disk shape polls forever
   against a finished run.
2. **A FAILED run looks complete.** `status: FAILED` arrives **with** a
   well-formed `TestResult` and `endTime`, so presence of a report is not
   completion. The OOM'd run from 2026-09-02 returns 62 assertions instead of
   1037 - a *smaller green nightly*. Status is now checked first, and
   `failureMessages` is surfaced in the build error.
3. **An indented `python3 -c` body is an `IndentationError`.** It would have
   made the poll loop never detect a finished run and always time out.

Also fixed before they could run: an ADO template expression spliced into the
middle of a `curl` line-continuation, and `GITHUB_STEP_SUMMARY` - a GitHub
Actions variable - used to publish the summary.

### The guard that exists because of today

A phase that silently does not run is the failure this nightly is least able to
notice. On 2026-09-02 a misspelled `enableMrcmValidation` (the parameter is
`enableMRCMValidation`, capital MRCM) bound to the `false` default and produced
a COMPLETE report in **94 s with 62 tests instead of 1037** - green, fast, and
validating almost nothing. So the pipeline asserts `reportSummary` shows each
requested phase as executed, and that the assertion count clears a floor
(`minAssertions`, default 900). Verified to fail on exactly that shape.

### What is still blocked, and on what

Nothing below is work I can do from here - no `az`, `kubectl`, `helm` or
`docker` on this host, and no registry or cluster credentials.

| # | step | blocked on |
|---|---|---|
| 6 | push the image to ACR | credentials for `ontoserver.azurecr.io` |
| 8 | apply `k8s/rvf-aks.yaml`, prove a worker's report is served by an API replica that never ran it | AKS access |
| 9 | settle auth and put an ingress in front | a decision, then config |
| 10 | seed the release store with the last published AMT release | storage access |
| - | set `jobStoreShare` in the nightly | the AKS PVC is DYNAMIC, so its share name is generated - bind a static share or read the generated name once |

The share name is the one precondition that cannot be inferred from this repo,
and it is why that parameter has a placeholder default rather than a real value.

### Then, and only then, the parallel run

Both pipelines against the same nightly release for a week: `daily-rvf` at ~181
minutes and this one at ~13. Compare assertion-by-assertion with
`ci/compare_reports.py --gate` against `ci/known-engine-divergences.json`, count
how often a human had to intervene, and retire def 42 when that count is zero.

One caveat on comparing reports night to night, found 2026-09-02: **RVF's
exported failure instances are not reproducible.** One assertion names a
different sample of failing concepts on every run, including between two stock
runs. Failure counts and buckets are stable, so a count-level comparison is
sound; an instance-level diff is not, until that is fixed.
