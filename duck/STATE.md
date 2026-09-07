# Where things stand, 2026-09-07

Written so context can be dropped without losing anything. The reasoning lives
in the commits; this is the state and the open threads.

## Deployed and proven

**MRCM is ~2.8x faster and off the critical path.** Nightly build 16246,
verified in the running pod's `/app/libs`:

    snomed-query-service 6.0.3-aehrc-perf   out-of-range queries as a term set
    mrcm-validator       4.0.5-aehrc-perf   lateralizable check by ancestor set

    16226  11:36:08 -> 11:49:44   816 s   tail beyond SQL 443 s
    16246  10:17:58 -> 10:25:42   464 s   tail beyond SQL  55 s

MRCM findings unchanged, 4 and 4. SQL is now the long pole, 409 s of 464 s.
Caveats in `duck/NIGHTLY-PLAN.md`: same prospective filename but not the same
bytes, and the memory saving does not show as a smaller number.

**The AMT assertions run on the DuckDB engine.** Build 16247: 1,482 records -
MRCM 979, SQL 425, DROOL_RULES 78 - in **9m01s**, with 200 `amtv4` records: 195
passing, 0 skipped, 5 real content failures. `daily-rvf` (definition 42) does
comparable work on MySQL in **2h52m** via an ephemeral Job with its own
`mysql:8.0.28` sidecar in namespace `rvf-tests`, uninstalled afterwards - which
is why `helm list` shows nothing recent and why I wrongly concluded it had
stopped running.

**How AMT is wired, and its weakness.** `/app/releases/amt-corpus` on the
shared volume holds 654 scripts (200 under `scripts/amtv4`), a `groups.xml`
declaring `amtv4` BY CATEGORY, and a 560-assertion `store.json`. The api and
worker point at it via `env.assertionResourceLocalPath` and `RVF_DUCK_STORE` in
`aehrc/ncts-argo` (`c932ea7`). **Mutable state outside git**: streamed in by
hand, nothing reproduces it, and losing the volume fails the pods' hash check
on restart. `duck/ASSERTION-PACKS.md` is the fix.

## Done this session, in the repo

* Bidirectional store gate - `BundledStoreMatchesCorpusTest` fails when the
  corpus declares an assertion the store lacks, against an empty
  `duck/known-store-omissions.json`. It used to pass while a whole category
  silently never ran.
* Fork build order - query-service before mrcm-validator; only fails on a clean
  machine (build 16242).
* PVC protection - both PVs patched to `Retain`; claims carry
  `helm.sh/resource-policy: keep` and Argo `Prune=false,Delete=false`.
* `GET /result/{runId}/failures` - uncapped CSV or parquet, streamed,
  filterable by `assertionId`.
* `GET /assertions/{uuid}/source` - file and transpiled statements from the
  store; 404 with a reason for Drools/MRCM, 501 on MySQL.
* Console - failures grouped by assertion group worst-first, per-assertion
  source in the popup, "All failures (CSV)".

## Open work, in order

### 1. Parity tests for EVERY assertion

The headline commitment. The denominator:

    international SQL   360   in the bundled store (453 files in the corpus)
    AMT SQL             200   aehrc/rvf testscripts, not in this repo's store
    Drools rules         78   109 rule directories with test-cases.json
    MRCM                979   two content forms

**Done: the half that runs anywhere.** `AssertionCorpusDigestTest` runs every
assertion in the bundled store against RVF's committed regression pair
(`SnomedCT_RegressionTest_20130731` over `_20130131`) and records per assertion
whether it ran plus a sha256 over the sorted `concept_id`, `component_id`,
`table_name` and `details` of every row it inserted. 360 assertions in ~3s, no
clone, no Docker, no network. Golden file
`src/test/resources/duck/assertion-digests.tsv`; regenerate with
`-Dduck.digests.write=true` and commit the diff WITH the reason. Baseline: 355
ran, 224 of them finding something, 2 not run for want of a DEPENDENCY release,
and 0 that cannot execute.

Per-assertion because a total is not a parity check, and a digest rather than a
count because a transpilation can preserve how MANY rows an assertion finds
while changing WHICH components it names - the risk carried by all 43 `REGEXP`
rewrites, since a regex matching the wrong thing still matches something.

**Three latent production defects it found, now FIXED** in the publisher
(`bd61519a`, travelling as `duck/publisher-fixes.patch` because the publisher
lives in `aehrc/rvf`). `duck/known-assertion-errors.json` is empty and the
bidirectional gate keeps it that way:

* two `mapGroup = ''` against a SMALLINT column (complexmap, extendedmap) -
  MySQL coerces the literal to 0, DuckDB refuses the cast per row. `to_duckdb`
  now rewrites a numeric column compared to `''` into a comparison with `0`.
* one statement ending `... ) commit`, because its script omits the semicolon
  before its final `commit;`. The publisher strips a trailing
  transaction-control token and says so on stderr; the upstream fix is the
  semicolon, in `IHTSDO/snomed-release-validation-assertions`.

They were invisible in production because an AU release has no complexmap,
extendedmap or expressionassociation rows, so DuckDB evaluated nothing and
reported zero findings. Build 16247's only two incomplete assertions are both
"`<DEPENDENCY>` not supplied". **An assertion that cannot run looks exactly like
one that ran and found nothing** - which is the whole reason this test exists.

Fixing them also exposed a publisher regression: `ports()` had stopped emitting
`DUCKDB_PRELUDE`, and since `rvf_duck.py` executes it per connection while the
Java engine applies `store["ports"]` and nothing else, republishing dropped
`substring_index` and broke four working assertions. My check for callers
searched lowercase while the corpus writes `SUBSTRING_INDEX`, so it reported
zero in both stores - a case-sensitive grep is not evidence.

**The deployed image still carries the old store.** The nightly will not gain
these three assertions until an image is built from `bd61519a` and rolled out;
`duck/store.json` is baked in at build time.

It also found that `DuckMaterialiser` could not load RVF's own regression
fixture at all (fixed, `62132059`): `read_csv` refuses a ragged relation where
MySQL pads and truncates, and structural validation runs CONCURRENTLY with the
SQL phase rather than gating it, so refusing lost the content report for
precisely the releases someone needs one about.

**Still to do:**

* the differential arm against the live MySQL oracle, classified against
  `ci/known-engine-divergences.json` - needs Docker, so it is a CI job, not a
  developer gate. `ci/engine_ab.py` is the shape; it needs to report WHICH
  assertions it exercised, since a green gate can otherwise hide a class that
  never ran.
* the same digest recording for the 200 AMT assertions, which live in a store
  this repo deliberately does not carry - it belongs beside them in `aehrc/rvf`.
* Drools `RulesTestManual` (109 rule directories) does not run in CI at all:
  the class name matches no surefire pattern and it expects the rules checked
  out beside the engine.
* MRCM parity is a COUNT (`inferred 497, stated 481`), not a digest, so two
  changes swapping one finding for another would pass.

Two rules, both learned from real failures and now enforced by the test:

* **A parity test must assert the assertion EXECUTED.** `RangeSetProbe`
  reported 134 expressions identical while BOTH arms ran the old code.
* **Comparing two empty results proves nothing.** Both laterality forms
  returned zero violations, so that probe mutates the input to force real
  violations and requires agreement on those too.

### 2. Raise the performance work upstream with SI

There are **zero** `aehrc` PRs on `IHTSDO/release-mrcm-validator` or
`IHTSDO/snomed-query-service`, so we carry ~60KB of private patches including
both of this week's wins. Two PRs, each independently useful, against
**`develop`** - SI's integration branch; `master` is not.

* *query-service* - answer an out-of-range (`!=`) clause with one
  `TermInSetQuery` over the terms present in that field, instead of the
  complement rendered as one exclusive range per member. The old form made the
  classic parser compile an automaton per clause: 4.0M `TermRangeQuery` and
  **10.94 GB** of transition tables at the peak of an AU run, and it is also
  the 12.9% of the phase spent parsing query text. Evidence: 134 real
  out-of-range expressions identical both ways, **4.2x** faster serially
  (724.0 s -> 170.6 s), `-Dsqs.notin.rangeform=true` restores the old form.
  Carries `conceptsWithAnyAncestor`, which the validator change needs.
* *mrcm-validator* - answer the lateralizable-domain check with one ancestor-set
  query instead of an ECL query per candidate: **4,561 queries and 145.7 s
  become 2 queries and 0.2 s**, violated set identical, and still identical
  under a mutation forcing 21 real violations. Say in the PR that collapsing it
  to `<< ^723264001` looks equivalent and is NOT - the descendant operator is
  dropped over a member-of expression, inventing 4,560 failures.

Patches are already the deliverable: `duck/mrcm-validator-parallel.patch`,
`duck/snomed-query-service-docvalues.patch`.

### 3. Assertion packs

`duck/ASSERTION-PACKS.md`. Pack format and merge with conflict rejection; fetch
by pinned digest plus an atomic reload endpoint; record pack name/version/digest
in the report; publish the AMT pack from `aehrc/rvf` and retire the
hand-staged volume.

### 4. Smaller follow-ups

* `minAssertions` is 1000 against a measured 1,482. Re-tighten.
* MRCM memory: peak demand fell 13.92 -> ~9 GiB locally, but the worker reads
  18.92 GiB anon either way because `MaxRAMPercentage=75` of 24Gi gives an
  ~18 GiB heap that G1 fills and keeps. Realising it means lowering the
  ceiling, measured - and a smaller worker changes KEDA's arithmetic.

## Waiting on someone else

* **GitHub Support purge** for `aehrc/release-validation-framework`, commits
  `5ba5586c` and `f5d1e652`. I committed AMT SQL and the 200 assertion names to
  a PUBLIC repo, then rewrote history - but force-pushed commits stay fetchable
  by sha until GitHub garbage-collects. Treat as disclosed for that window.
* **Attila** - keep dynamic storage provisioning or move to a static share in a
  resource group he owns; and the exact key the indexer joins on before
  `STORAGE_LOCATION` is renamed (currently `nightly-<BuildId>`; the diff he saw
  is a proposal, not deployed).
* **`si-rvf-client` secret** rotation - exposed in a session transcript.

## Traps worth not rediscovering

* `duck/NIGHTLY-PLAN.md`'s "peak heap is the concept map" was wrong - that
  figure was `Runtime.totalMemory()`. The peak is ~10.9 GB of automaton
  transition tables from the range-chain queries.
* AMT assertion ids are `sha256(groupId + category + FILENAME)`, so renaming a
  script retires one assertion and creates another, orphaning whitelist
  entries.
* Run-to-run variance on MRCM is about 13%: nothing under ~10% can be claimed
  from single runs.
* A browser wait condition must not match the loading placeholder it is waiting
  to replace.
* `git add -A` swept an unrelated UI change into a docs commit. Stage by path
  when two threads are in flight.
