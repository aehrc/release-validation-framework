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

11. **Nightly pipeline.** Reuse what already works: `daily-rvf` publishes the
    prospective release as an `rf2-bundle` pointer and the existing duckdb-rvf
    pipeline triggers on its `RvfStage` and azcopys the blob. So: trigger the
    same way, land the release on the Azure Files job store, `POST
    /run-post-via-s3` naming the path, poll `/result/{runId}`.
    No Snowstorm export step to build.
12. **Publish results.** `ci/rvf_junit.py` for the test tab -
    `failureCount == -1` becomes `<skipped>` rather than a red build - plus
    `failures.parquet` and a link to the report.
13. **Decide the nightly's scope.** SQL only is 160s. Drools adds a separate
    pass (measured 5,129 findings in 674s module-scoped, 10,949 in 636s on the
    heap path) and MRCM another. A nightly that skips them is cheap and
    incomplete; one that runs them is the real thing. Measure both before
    choosing.
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
