# Nightly validation: keeping previous releases, and what to keep afterwards

Plan of record, 2026-09-01. Every number here was measured on `dmc` (8 cores,
DuckDB engine, real AU edition: 45.3M rows, 66 tables, 853MB zip).

## What we are building towards

A nightly job that exports a prospective release from Snowstorm and validates it
against the last published release. For AMT that is not done today because it is
too costly. Two separate cost problems, and only one of them is still open:

* **Run cost** - already solved by the DuckDB engine. A full AU edition takes
  **208s** end to end versus **1,215s** on MySQL 8.4.6 on the same 8 cores, and
  leaves no 9-13GB schema behind.
* **Repeated work** - still open. The previous release is materialised from
  scratch every single night, and for a month of nightlies it is the *same*
  release every time.

## Measured facts the plan rests on

| | value |
|---|---|
| materialise a full edition | **28.1s**, 45.3M rows, 66 tables |
| resulting `.duckdb` file | **1.57 GB** |
| source RF2 zip | 853 MB |
| a nightly materialises prospective + previous | **~56s** of a ~208s run |
| `results.json` per run | 37.1 KB |
| uploaded release retained per run | 853 MB |
| full failure detail, 1M rows, parquet+zstd | **20.8 MB**, written in 221ms |
| same as CSV | 177.3 MB |

Two behaviours of DuckDB that constrain the design, both tested here rather than
assumed:

1. **Many readers OR one writer, never both.** Three processes opened one file
   read-only simultaneously and all succeeded. While any process holds a file
   read-write, every other process is refused - *including read-only openers*:
   `IO Error: Could not set lock on file ...: Conflicting lock is held`.
2. **`ATTACH` satisfies the store's compiled literal, but only with the right
   internal layout.** The store's sentinels resolve `<PREVIOUS>` to the bare
   literal `previous`, so:

   ```
   cached file with tables in schema "previous"  ->  previous.concept_s        FAILS
                                                     previous.previous.concept_s   OK
   cached file with tables in schema "main"      ->  previous.concept_s        OK
   ```

   A cached release MUST therefore hold its tables in the default `main` schema.
   Then `ATTACH '<file>' AS previous (READ_ONLY)` works with no change to
   `DuckStore` or `DuckBinder`. Getting this wrong means patching sentinels.

## Decisions

### Keep the zips as the system of record; cache the materialised file as derived

The zip is required regardless - provenance, re-runs, and RVF's own
`previousRelease` resolution already reads a release **by filename** from release
storage (the path fixed in `d34ffb81`). So the release store is the truth and the
`.duckdb` cache is an evictable derivative. Never the other way round: a 1.57GB
file that cannot be re-derived is a liability.

### Cache the previous AND dependency editions, not the prospective

Hit rate is what makes this worth building:

* **previous** - changes monthly for AMT, so ~30 hits per rebuild.
* **dependency** - the International edition an extension is built against; the
  same file every night for a month, shared across *every* extension run.
* **prospective** - different every night by definition. Never cache it.

At 28.1s saved per hit, a month of AMT nightlies saves ~14 minutes of compute.
That is not the headline; the headline is that it removes the only remaining
piece of per-run rework, and it makes a same-day re-run nearly free.

### The cache key must include a store fingerprint

The materialiser loads some tables **positionally** where the shipped columns
disagree with the declared ones - observed live on this edition:

```
extendedassociation_d is declared as [... targetcomponentid, value]
                but its file has [... targetadministeredform, targetmanufacturedform]
                - loading positionally, as MySQL does
attributevaluemap_d  ... - loading positionally, as MySQL does
identifier_d ships its columns as [alternateidentifier, ...] - loading by name, not position
```

So a cached file is only valid for the `tableColumns` map that produced it.
Key on `{releaseFilename}-{sha256(store.tableColumns)[:12]}.duckdb` and a store
change simply misses instead of silently mis-loading.

### Node-local by default, and prove it before defaulting otherwise

Whether the cache should live on the shared Azure Files mount or on node-local
disk is an open measurement, not a preference: reading 1.57GB over SMB may lose
to materialising from a 853MB zip on local NVMe. Default the cache directory to
node-local (as `rvf.duck.work.directory` already is), make it configurable, and
settle it with a measurement on AKS.

### Retention: what to keep, and for how long

The question was open; this is the answer we go with unless measurement says
otherwise.

| artefact | size/run | policy |
|---|---|---|
| `results.json` | 37 KB | **keep indefinitely.** The dashboard and CI links resolve through it; tiering it 404s them. 1,000 runs is 37 MB. |
| `qa_result` full detail | ~21 MB per 1M failures | **new** - export to parquet+zstd before the run file is deleted. Tier to cool at 30 days, archive at 90. |
| uploaded release zip | **853 MB** | **delete after N days, default 7.** This is the real growth: ~25 GB/month at one nightly. Re-fetchable from release storage. |
| previous/dependency releases | 853 MB each | keep indefinitely - this is the point of the exercise. |
| materialised cache | 1.57 GB each | evictable, LRU by total size, node-local. |
| per-run `.duckdb` | 1.57 GB | already deleted, including `.wal` and `.tmp`. |

**Why parquet rather than raising `failureExportMax`:** `results.json` is parsed
*in the browser* by Release-Dashboard-UI, so inflating it degrades the viewer.
Parquet is 8.5x smaller than CSV, and DuckDB queries it directly months later -
verified with `read_parquet()`.

**What is lost today, and this fixes:** only `failureCount` plus the first
`failureExportMax` (default **10**) instances survive a run. "40,000 concepts
failed, here are 10" is answerable; "which 40,000" is not. Note this is not a
DuckDB regression - MySQL discards the same detail, because `qa_result` lives in
`rvf_master` and `ddl-auto=create` wipes it on the next boot.

## Revision, 2026-09-01: measured, and reordered

The plan above put the cache next. A phase-by-phase measurement of a real
two-edition nightly says it should not be, and the estimate in this document was
wrong by about 5x - recorded rather than quietly corrected.

    received -> start structural              44s
    start structural -> prospective unpacked 198s   <- 58% of the run
    previous unpacked -> prospective mat.     23s
    prospective mat. -> previous mat.         25s   <- all the cache can save
    previous mat. -> archive written          45s
    TOTAL                                    339s

Caching the previous release saves **25s, 7%** - not the ~40% inferred from the
gap between a one-edition and a two-edition run. That gap is mostly structural
testing and unpacking a second edition.

The structural work already written and raised as IHTSDO#75 was not on this
branch. Cherry-picked, and re-measured on the same release and request:

    phase                          before   after   delta
    start structural -> unpacked     198s    127s    -71s
    TOTAL                            339s    257s    -82s   (1.32x)

with **identical findings** - 149 tests, 13 failures, 0 warnings, 1 incomplete,
and per-assertion comparison showing nothing in one report and not the other, and
nothing with a different bucket or count.

The cache is still worth building. It is now ~21s of a 257s nightly, about 8%,
and it removes the last piece of per-run rework - but it is an optimisation, not
the thing that makes a nightly affordable.

## Phases

Each phase stands alone and is independently verifiable. No phase depends on a
later one.

### Phase 1 - Retention and the failure archive

1. Export `qa_result` to `{storageLocation}/rvf/failures.parquet` immediately
   before the run database is deleted, in `DuckDbValidationService`.
2. Add a job-store reaper: delete `files_to_validate/` contents older than
   `rvf.validation.job.retention.days` (default 7), leaving `rvf/` intact.
3. Property to disable both, because a deployment that wants today's behaviour
   must be able to keep it.

**Acceptance:** a completed run leaves `results.json` plus `failures.parquet`;
the parquet is readable by `read_parquet()` and its row count equals the sum of
`failureCount` before whitelisting; a run older than the window loses its
uploaded zip and keeps its report.

### Phase 2 - A release store addressable by name

1. Releases the instance has been given are kept under
   `rvf.release.storage.local.path` (already exists, already used by the
   full-scope run) keyed by filename.
2. Make `GET /releases` engine-agnostic: list the release store rather than
   MySQL schema names. Today it is `@ConditionalOnMysqlEngine` and reports an
   in-memory `Set<String>` of schema names.
3. `previousRelease` and `dependencyRelease` continue to resolve by filename -
   no change needed, this already works.

**Acceptance:** with two editions in the store, `GET /releases` lists both in
DuckDB mode; a nightly naming one of them as `previousRelease` completes and
produces release-type findings rather than 53 `-1` not-executed sentinels.

### Phase 3 - The materialised cache

1. `DuckReleaseCache`: `get(releaseFile, storeFingerprint)` returns a path,
   materialising into `main` on a miss.
2. Build to `{key}.tmp` then atomic rename, so a concurrent reader never sees a
   partial file - required by the single-writer lock.
3. `ATTACH '<cached>' AS previous (READ_ONLY)` / `AS dependency (READ_ONLY)`
   instead of materialising into the run file.
4. LRU eviction on total bytes, `rvf.duck.cache.max-gb`, default off (0 = no
   cache, today's behaviour).

**Acceptance - the one that matters:** the same validation run cold and cached
produces **byte-identical findings**. Then, and only then, the timing: expect
~28s off a ~208s run per cached edition.

### Phase 4 - Measure on the target infrastructure

Cache on node-local disk versus on the shared mount versus no cache, on AKS,
same release. Decide the default from the result. If the shared mount wins, the
cache can be built once and shared by every worker - which the read-only lock
behaviour permits.

### Later, unlocked by Phase 2

`POST /assertions/{id}/run` on DuckDB. It needs "a release addressable by name",
which is exactly what Phase 2 provides, and it is the one genuinely useful
endpoint still MySQL-only. Lets a rule author test one assertion without a full
pipeline run.

## What this plan deliberately does not do

* **No shared writable DuckDB.** Tested: a writer locks out every other process
  including readers. The cache is read-only files, one writer at build time.
* **No caching of the prospective release.** Different every night.
* **No cache without a fingerprint.** Column drift is real and silent.
* **No archiving of `results.json`.** It is what the links resolve through.

## Outcome, 2026-09-01

| phase | status |
|---|---|
| 1 Retention | **done** - `failures.parquet` beside every report, job-store reaper at 7 days |
| 2 Release store | **done** - `GET /releases` engine-agnostic, releases kept by filename |
| 3 Cache | **done** - `DuckReleaseCache`, previous/dependency only, off by default |
| 4 Measure on AKS | **open, and only doable there** - see below |

Cumulative on the same AU edition and request: **339s -> 224s**, findings
identical at every step.

* Structural single-pass work: 339s -> 257s (1.32x), 149 tests / 13 failures /
  1 incomplete unchanged, 0 assertions differing.
* Release cache: the previous-release step 21,378ms -> 34ms. End-to-end 235s ->
  224s, but structural-phase variance was +11s between the same two runs, so the
  end-to-end figure is one sample, not a measurement.

### Phase 4 cannot be done on this host

Node-local versus shared-mount cache is a question about SMB throughput against
local NVMe, and there is no Azure Files mount here - `shared-jobs/` is local disk
wearing the name. Measuring it here would produce a number that means nothing.
What is settled and does transfer:

* A cached file is 1.57GB per edition; the budget is `rvf.duck.cache.max-gb`.
* Concurrency is safe either way: many readers OR one writer, verified, with
  publication by atomic rename.
* Eviction of an attached file is safe on POSIX; the inode outlives the unlink.

So the default stays node-local, and Phase 4 runs when there is a cluster.

### What the measurements say to do next, in value order

1. **Structural testing is still ~49% of the run** (127s of 257s). PR #27
   (line-splitting across cores, a further 1.38x) was declined upstream because
   it costs real concurrency machinery for a modest gain. On our nightly, where
   structural is half the wall clock, that trade is better than it was for SI.
2. **MySQL leaks per-run schemas.** Four abandoned schemas held 41GB and a run
   died with "No space left on device" mid-measurement. `dropSchema` exists;
   something is not calling it on the failure path. Costs nothing to fix and
   prevents an outage.
3. **The cache earns more with a dependency edition than a previous one.** An
   extension nightly attaches the same International edition every night for a
   month, shared across every extension - a strictly better hit rate than
   `previous`, which changes with each release. Untested here because these runs
   had no dependency release.

## Scope decided, 2026-09-01

**The nightly runs everything, against a deployed DuckDB RVF with workers doing
the assertion execution:** our assertions, SI's assertions, MRCM and Drools.

### This needs no code change

`ValidationRunner` submits three independent tasks to one executor:

    SQL Assertions   -> SqlAssertionValidationService  (the engine seam)
    Drools           -> if validationConfig.isEnableDrools()
    MRCM Validation  -> if validationConfig.isEnableMRCMValidation()

Only the first goes through the engine seam. **Drools and MRCM are gated on
request flags, not on engine**, and read the extracted RF2 files directly, so
they behave identically under `rvf.execution.engine=duckdb`. So the nightly is
one request with `enableDrools=true`, `enableMRCMValidation=true` and the union
of assertion groups.

Note in passing: `testTypeFailuresCount` records `-1` for Drools and MRCM when
they are *not requested* (ValidationRunner:266-267). So `-1` already carries a
third meaning - "not asked for", alongside "not executed" and, until today's
fix, "died on a SQL error". More weight than one sentinel should carry, and
another argument for IHTSDO#77.

### The one real constraint: the store must cover both corpora

The DuckDB engine reads a **precompiled store**, and the bundled
`src/main/resources/duck/store.json` is built from ONE corpus - currently
IHTSDO's, 360 assertions / 819 statements, verified by
`BundledStoreMatchesCorpusTest`.

aehrc's own AU assertions are not in it. They are delivered separately, as the
~200 scripts in the `rvf-k8s-job-helm-chart` the ADO nightly pulls. So running
"ours and SI's" requires a store published from the **union**:

    publish_store.py --manifest-root <corpus with both sets> --out store.json

`rvf.duck.store=/path/to/store.json` overrides the bundled one, so this needs
no rebuild of the artefact to try.

**Unverified, and it decides how much work this is:** whether aehrc's ~200
scripts are additional to IHTSDO's 360 or an overlapping subset, and whether
any assertion UUID collides. Comparing the chart's script names against the
corpus manifest answers it in minutes, but needs the chart - which needs ADO
access. Until then the size of this task is unknown, and it is the only part
of the decided scope that is not already proven.

### Cost of the decided scope

Measured separately on the AU edition, 8 cores:

| component | wall |
|---|---|
| SQL assertions, DuckDB | ~160-208 s |
| structural testing | 29-33 s (inside the above) |
| Drools, module-scoped | 674 s, 5,129 findings |
| MRCM | one further pass |

The three run concurrently, so the nightly is bounded by the slowest -
**Drools at ~674 s**, not the SQL engine. Worth knowing before optimising SQL
any further: on the decided scope, the assertion engine is no longer the long
pole.

### Correction, 2026-09-01: the Drools cost, and why we are not getting the speedup

I quoted 674 s earlier. Wrong configuration - that was the module-*filtered*
run, which produces fewer findings (5,129), not a faster same-work run. Worse,
I then treated 674 s as the nightly's cost without checking whether the
artefact contains the work that made Drools fast.

**Measured on the deployed artefact, this host, AU edition, DuckDB engine,
Drools + SQL in one validation:**

    RuleExecutor  : 722,404 of 722,404 concepts, rule execution 500 s
    DroolsRF2Validator : Tests complete. Total run time 697 s
    whole validation   : 780 s (13 minutes), 80 tests, 3 failures

**And the recorded figure with the engine performance work applied is 54 s.**

The gap is six PRs SI merged into `IHTSDO/snomed-drools` on 2026-08-28:

| PR | change |
|---|---|
| #6 | keep workers supplied with concepts instead of every 10 |
| #7 | scale validation workers to the host, callers can override |
| #8 | skip the effective-component pre-pass for a single Snapshot |
| #9 | read the RF2 files concurrently, repository made safe for it |
| #10 | exact-term description lookups from a map, not a Lucene index |
| #11 | compile the whitespace split pattern once |

**None of them are in what we build against.** `snomed-parent-bom` 4.0.0
resolves `snomed-drools-engine` to **6.0.0**, and 6.0.0 is **3 commits behind
PR #6's merge commit** - verified against the GitHub compare API. SI has
published no tag since. So every Drools run this project makes today, including
the 697 s above, is on the pre-PR engine.

**Consequence for the nightly, and it flips the answer:**

    engine 6.0.0 (today)   Drools ~700 s  -> Drools IS the long pole
    engine with #6-#11     Drools ~54 s   -> SQL is the long pole again

So "is Drools too expensive for the nightly" has no fixed answer - it depends
entirely on a dependency version we do not control.

**What unblocks it:** SI cutting a release containing #6-#11, or us building
the engine from their merged `develop` and pinning it. The second is a
half-hour and removes the wait, at the cost of shipping a non-released
dependency - which is a real decision, not an obvious one.

**How to avoid repeating my mistake:** never quote a Drools number without the
engine version alongside the rule set. The existing rule
([[rvf-drools-heap-baseline]]) names rule-set, exclusions, module filter, patch
state and contention - it needs "engine version" added, because that is the
term that moved this by 12x.

### Measured with everything applied, 2026-09-02

AU edition (`amtv4-15912.zip`, 722,404 concepts, 45,298,828 rows), DuckDB
engine, `snomed-drools-engine 6.1.0-aehrc-perf`, all phases concurrent,
8 cores, 12 GB heap, DuckDB capped at 3GB. SQL groups: file-centric,
component-centric, release-type. Drools: common-authoring + au-authoring with
our 5 rule patches applied. MRCM excluded - see the sizing note below.

    11:30:06  submitted
    11:30:28  structure testing starts        (22 s of acquisition first)
    11:31:08  DuckDB materialised             66 tables, 45,298,828 rows
    11:31:49  SQL failure detail -> parquet   SQL phase done, 103 s in
    11:34:27  Drools rule execution took 70 s
    11:34:28  Drools total run time 196 s
    11:34:29  COMPLETE                        263 s wall

209 tests, 59 failures, 18 warnings, 54 incomplete, no failure messages.

**Against the same request before this work:**

| | engine 6.0.0, structural serial | 6.1.0-aehrc-perf, all concurrent |
|---|---|---|
| whole run | 780 s | **263 s** |
| Drools total | 697 s | **196 s** |
| Drools rule execution | 500 s | **70 s** |
| SQL phase | inside the 780 | done at 103 s |

**Rule execution is 7.1x faster and the whole run 3.0x.** Same edition, same
groups, same host.

**The long pole moved.** It is still Drools, but no longer rule execution: of
the 196 s, 70 s is rules and ~126 s is loading - reading the RF2 and
deserialising axioms into the object graph. PR #9 already made those reads
concurrent, so the remaining cost is the graph construction itself. Any further
work on Drools should target loading, not rules.

**SQL is no longer the headline cost either** - 103 s including a 40 s
materialisation of 45.3M rows.

### Why MRCM is not in this run, and what it needs

From the run that included it:

    MRCMValidatorReleaseImportManager : Finished creating index. Using approx 11,952 MB
    MRCMValidatorReleaseImportManager : Finished creating index. Using approx  1,896 MB

**MRCM's index wants ~12 GB on its own**, and it builds two. Drools holds a
separate full object graph, DuckDB its own off-heap arena, and none of the three
share a model. A single worker sized for the decided scope therefore needs
roughly

    MRCM 14 GB + Drools 8 GB + DuckDB 4 GB ~= 26 GB

against 14 GB for the largest single phase. Two runs died proving it: 8 GB was
hopeless, and a 16 GB attempt was killed by the **kernel** OOM killer (no heap
dump, no JVM error - SIGKILL leaves neither) because MySQL held 5.8 GB on the
same 22 GB host.

**This is the argument for phases on separate workers, and it is a memory
argument rather than the CPU one.** Pods sized per phase cost far less than one
pod sized for the sum, and cannot take each other down. The price is a
duplicated acquisition and materialisation per worker (~105 s unpack, ~25 s
materialise) and a fan-out/join RVF does not have - so it is a design change,
not configuration. The numbers now favour it; before the engine pin they did
not.

### What the structural concurrency is worth

Measured inside the same run rather than by a second run, because the phase's
own duration is exactly the saving once it is no longer serial:

    structure testing starts   11:30:28
    column pass done           11:30:53   26 s  (75 files, 45,311,214 lines)
    structure report written   11:31:03   35 s total
    Drools, the critical path             196 s
    whole run                             263 s

**35 s comes off the critical path - 13% of the run.** It was previously 100%
additive, because the phase ran to completion before anything else started.

It is entirely hidden now: 35 s against a 196 s critical path, so the saving is
the full phase duration and will remain so until Drools loading drops below it.

Note this is with PR #27 in, so the 26 s column pass is already the
split-across-cores version. The largest single file no longer sets the floor
for that pass.

### The real nightly shape, with a previous release, 2026-09-02

The 263 s above was optimistic: no `previousRelease`, so 49 of 87 release-type
assertions never ran and only one edition was materialised. A monthly product
compares against last month's release, so this is the honest number.

Same everything, plus `previousRelease=SnomedCT_AU_20260630.zip`:

    12:00:39  submitted
    12:01:26  structure testing starts        47 s of acquisition (two releases)
    12:01:57  column pass done                31 s, 75 files, 45,311,214 lines
    12:02:17  prospective materialised        66 tables, 45,298,828 rows
    12:02:43  previous materialised           +26 s for the second edition
    12:03:37  SQL failure detail -> parquet   1,045 KB; SQL done ~131 s in
    12:06:28  Drools rule execution 60 s
    12:06:29  Drools total 221 s, COMPLETE    350 s wall

209 tests, 19 failures, 17 warnings, **2 incomplete** (was 54).

| | 6.0.0, structural serial | perf engine, concurrent | + previous release |
|---|---|---|---|
| wall | 780 s | 263 s | **350 s** |
| Drools total | 697 s | 196 s | 221 s |
| Drools rule execution | 500 s | 70 s | 60 s |
| assertions not executed | - | 54 | **2** |
| failure detail | - | 76 KB | 1,045 KB |

**Supplying the previous release costs 87 s and is not optional** - it takes
release-type coverage from 38 of 87 to 86 of 87, and the failure detail from
76 KB to 1 MB. A nightly without it is measuring almost nothing on the
half of the corpus that exists to compare releases.

**Do not compare failure counts across the two.** The same edition reports 59
failures without a previous release and 19 with one. Forty of those 59 were
artefacts of the absent comparison, not content.

### Where the 350 s actually goes

    acquisition (serial, before any phase)    47 s
    SQL phase, incl. 52 s of materialisation  131 s   } concurrent
    structural                                 31 s   } so the run costs
    Drools                                    221 s   } the slowest: Drools

**SQL is no longer the constraint and has not been for some time.** The MySQL
engine took 8,760 s over ~200 assertions; the SQL phase here is 131 s over 189
executed, including materialising 90.6M rows across two editions. Every second
of the 780 -> 350 improvement came from the Drools engine pin and from taking
structure testing off the critical path - the SQL work was already done and was
always running concurrently with Drools inside the old 780 s.

**What to attack next, in order:**

1. **Drools loading, 161 s of its 221 s.** Rule execution is 60 s. The loading
   is snomedboot reading the RF2 and deserialising axioms into the object graph.
2. **Acquisition, 47 s**, which is serial before every phase and is mostly
   copying two 853 MB zips into place.
3. Not SQL, and not rules.

### Inside the Drools 167 s load, 2026-09-02

You asked for the axiom deserialisation to be multi-threaded. It can be, but it
is not where the time is, and the measurement says so.

**Axiom deserialisation: 35 s of the 167 s load.** Single thread throughout
(`pool-13-thread-4`), 13:50:12 -> 13:50:46, 598,891 OWL members at ~16,300
axioms/s. Perfect 8-way parallelism would take it to ~5 s: **a 30 s saving,
10% of the run.**

**The release is loaded THREE times, and that is 150 s.** Thread names give it
away:

| pass | threads | window | cost |
|---|---|---|---|
| 1 | `pool-12-thread-*` concurrent | 13:48:15 - 13:48:51 | 36 s |
| 2 | **`pool-6-thread-3`, single thread** | 13:48:51 - 13:49:46 | **55 s** |
| 3 | `pool-13-thread-*` concurrent, holds the axiom work | 13:49:46 - 13:50:46 | 60 s |

`DroolsRF2Validator` has **three** `loadSnapshotReleaseFiles` calls and three
`loadComponentsFromRF2` calls. Pass 2 runs on the calling thread - SI's PR #9
made reads concurrent but did not reach this path.

**And one of those passes is almost pure waste.**
`loadPreviousReleaseComponentIds` loads with **`LoadingProfile.complete`**,
while its factory:

    class PreviousReleaseComponentFactory extends ImpotentComponentFactory {
        Set<Long>   releasedConceptIds;
        Set<Long>   releasedDescriptionIds;
        Set<Long>   releasedRelationshipIds;
        Set<String> releasedRefsetMemberIds;
    }

Four sets of identifiers. Nothing else. So the previous release has 2,246,130
descriptions and 6,138,313 language refset members parsed in full, and every
OWL axiom deserialised, so that four `Set`s of ids can be populated. An
`ImpotentComponentFactory` is *designed* to discard what it does not need - the
profile just never got narrowed to match.

**Priority, by size:**

1. **Stop loading the same prospective release twice** - two of the three
   passes read it. Worth up to ~60 s.
2. **Narrow the profile on the ID-only pass.** It cannot drop descriptions or
   refset members (their ids are wanted) but it has no use for axioms at all,
   which is where the 35 s goes for that edition.
3. **Then** parallelise `AxiomDeserialiser`. Worth ~30 s, and the most work of
   the three: the class is single-threaded *by construction* - one shared
   `OWLOntology`, one `OWLFunctionalSyntaxOWLParser`, one `owlAxiomsLoaded`
   list mutated per call, a non-atomic counter. Parallelising means one
   deserialiser per thread, each with its own `OWLOntologyManager`, which is a
   patch to **snomed-owl-toolkit** - a third library to fork after
   snomed-drools.

All three are upstream of RVF. Items 1 and 2 are in
`snomed-drools-rf2-validator`, which we already build ourselves, so they are
reachable on the same pin. Item 3 needs snomed-owl-toolkit forked too.

### The three load optimisations, done and measured, 2026-09-02

Same nightly request throughout: AU edition, previous release supplied, SQL +
Drools + structural concurrent, 8 cores, DuckDB engine.

| | wall | Drools total | axioms | rules |
|---|---|---|---|---|
| engine 6.0.0, structural serial | 780 s | 697 s | - | 500 s |
| + engine pin, structural concurrent | 350 s | 221 s | 35 s | 60 s |
| + parallel archive unpack | 292 s | 226 s | 35 s | 59 s |
| **+ one read fewer, parallel axioms** | **224 s** | **154 s** | **17.5 s** | **47 s** |

**Findings identical at every step** - 209 tests, 19 failures, 17 warnings, 2
incomplete, 0 assertions differing in bucket or count. That is the acceptance
criterion for all of this; none of it is worth a second if a finding moves.

**Item 2 was withdrawn, not delivered.** I claimed the id-only pass loaded with
`LoadingProfile.complete` and deserialised every axiom to populate four `Set`s
of ids. Wrong on the second half: only one axiom sequence exists in a run, in
the prospective's real load, and `PreviousReleaseComponentFactory` is an
`ImpotentComponentFactory` that discards content as it reads. The profile is
already narrowed with `withoutAllRefsets()` plus four explicit patterns, and
all four of its id sets are consumed by `SnomedDroolsComponentFactory`. There
was nothing to remove.

**Where the 224 s now goes:**

    acquisition (serial, before any phase)     42 s
    SQL, incl. two materialisations           131 s  } concurrent
    structural                                 31 s  } run costs the
    Drools  (16 s unpack, 91 s load, 47 s rules) 154 s } slowest: Drools

**Next levers, in order, all upstream:**

1. **Acquisition, 42 s**, serial before every phase, mostly copying two 853 MB
   zips into place. The only item on the critical path that no phase overlaps.
2. **Drools loading, 91 s.** Two editions into the object graph. Axioms are
   17.5 s of it now; the rest is graph construction, which is inherent to how
   the rules query previous-versus-current state.
3. Axiom parallelism is ~2x rather than 8x because OWLAPI parsing allocates
   heavily and is partly GC-bound. A pooled or reused parser inside
   `AxiomDeserialiser` might do better, but that is a snomed-owl-toolkit change
   and worth ~10 s.

### MRCM memory, and a correction to a number I quoted all day, 2026-09-02

**I was wrong about MRCM needing ~12 GB.** The log line I built that on is

    MRCMValidatorReleaseImportManager : Finished creating index. Using approx 11,952 MB of memory.

and the bytecode of `ReleaseImportManager` shows it is computed from
**`Runtime.totalMemory()`** - the JVM's committed heap at that instant, which
included the Drools object graph and everything else in the process. It is not
the index's footprint. The second line in the same run said 1,896 MB: the same
measure, earlier, when the heap was smaller. Two wildly different "index sizes"
for the same 722,404 concepts should have made me check sooner.

**Measured properly** - MRCM enabled, Drools off, one small SQL group, sampling
the worker's heap directly:

    t+100 s   6.89 GB     during index build
    t+160 s   7.79 GB     peak
    t+220 s   1.04 GB     after the build, while validating
    t+9 min   2.75 GB     still validating

So the shape is a **~7.8 GB transient peak while building the Lucene index**,
settling to **1-2.8 GB retained**. Not a 12 GB resident index.

**The conclusion about worker sizing survives, for a different reason.** MRCM
peaks near 7.8 GB and Drools near 8.7 GB; two such peaks in one 12 GB heap is
what killed those runs, not one giant index.

**The lever that exists.** `ValidationService$MRCMValidatorReleaseImportManager`
calls `loadReleaseFilesToMemoryBasedIndex`, which uses `RamReleaseStore` - a
Lucene `Directory` held on the heap. `snomed-query-service` ships
`DiskReleaseStore(File)` right beside it, taking a directory. With a
disk-backed store the flushed segments live in the page cache instead of the
heap, which helps **both** halves: the transient peak, because flushing
actually releases heap, and the retained index.

`ValidationService.loadMRCM(...)` exposes no choice of store, so this is a
change to **mrcm-validator** - a third library to patch after snomed-drools.
Small, though: a disk variant of one method plus a way to pass the directory.

**MRCM, not Drools, may be the real long pole.** This run had not finished
after nine minutes with Drools disabled, against Drools' 154 s. Its validation
phase issues a Lucene query per domain/attribute pair
(`ValidationService : Selecting content within domain ... with attribute ...`).
That needs measuring before the decided scope is costed, and it changes the
answer to "what should the nightly include".

**How to judge any of these changes:** the same way the axiom batching was
judged - take `jcmd GC.class_histogram` and `GC.heap_info` against a live run
before and after. The axiom change looked correct, passed its tests, produced
identical findings, and still retained 173 MB for nothing. Only the histogram
showed it.

### MRCM measured, 2026-09-02: 21.5 minutes, and it got none of our work

**MRCM alone, Drools off, one small SQL group: 1,292 s and 1,037 tests.** That
is 8x the whole Drools phase (154 s) and five times the assertion count of
everything else combined. On the decided scope MRCM does not just dominate the
nightly, it *is* the nightly.

    16:28:11  submitted
    16:29:09  unzipping, snomedboot, single thread (pool-15-thread-3)
    16:30:50  index built: 722,404 concepts   (pool-20-thread-2)
    16:31:01  index built: 722,404 concepts   (pool-20-thread-1)   <- a SECOND one
    16:49:43  COMPLETE                        21 minutes

| phase | cost | share |
|---|---|---|
| unzip (twice: snapshot and delta) | ~60 s | 5% |
| two full Lucene index builds | ~110 s | 9% |
| **ECL validation queries, single thread** | **~1,122 s** | **87%** |

**It benefits from almost nothing we built.** The artefact it ran on carries
every change - `snomed.drools.version 6.1.3-aehrc-perf` - but MRCM's code path
touches hardly any of it:

| what we did | does MRCM get it? | why |
|---|---|---|
| engine pin, SI's #6-#11 | **no** | those are `snomed-drools-{engine,rf2-validator}` classes; MRCM uses `mrcm-validator` + `snomed-query-service` |
| parallel archive unpack | **no** | wired into `DroolsRulesValidationService`; `MRCMValidationService:110,114` still call `new ReleaseImporter().unzipRelease(...)` |
| skip the pre-pass (#14) | **no** | `DroolsRF2Validator` |
| parallel axiom parsing (#15) | **no** | `SnomedDroolsComponentFactory`; MRCM has its own `OWLExpressionAndDescriptionFactory`, still serial |
| phases running concurrently | **yes** | `ValidationRunner`, engine-agnostic |

So every class of waste removed from Drools is still present in MRCM, plus one
that is larger than all of them together.

**The dominant lever: the query loop is serial.** Every ECL query runs on
`pool-20-thread-1`, and `ValidationService` contains no `parallelStream`, no
executor - two `forEach` calls. Each query is an independent domain/attribute
check:

    Selecting content within domain '373873005' with attribute '999000051000168108'
      without group cardinality ECL:'<<373873005:[0..*] { [0..*] 999000051000168108=* }'

Timestamps put them at 0.09-0.55 s each, thousands of them. They are
independent by construction - one assertion object per domain/attribute pair -
so this is the same shape as the axiom parsing: embarrassingly parallel work
pinned to one core.

**Levers, in order of size:**

1. **Parallelise the ECL query loop** - ~1,122 s, 87% of the phase. In
   `mrcm-validator`'s `ValidationService`. Needs the same care as the axioms:
   check what the assertions write into before fanning out.
2. **Wire `RF2ReleaseTypeUnpacker` into `MRCMValidationService`** - ours, two
   lines, ~40 s. No excuse for this one; it was an oversight when the unpacker
   went in.
3. **The two index builds** - ~110 s. Both report 722,404 concepts, so this
   looks like duplicated work rather than snapshot-versus-delta. Needs
   confirming before assuming.
4. **`DiskReleaseStore`** for memory, once the above are done.

**And the memory figure confirms the earlier correction.** The two index builds
logged "approx 10,136 MB" and "approx 1,872 MB" minutes apart for the same
722,404 concepts. Same `Runtime.totalMemory()` call, different heap at each
moment. Neither is an index size.

### MRCM parallelised, 2026-09-02: 1,292 s -> 735 s

| | baseline | parallel |
|---|---|---|
| MRCM phase | 1,292 s | **735 s** |
| tests / failures / warnings / incomplete | 1037 / 3 / 4 / 2 | identical |
| assertions differing in bucket or count | - | **0** |

Two changes in RVF, one in `mrcm-validator`:

* `MRCMValidationService` uses `RF2ReleaseTypeUnpacker` instead of snomedboot's
  single-threaded `unzipRelease` - the same oversight Drools had, because the
  unpacker was only wired into one caller when it went in.
* `ValidationService`'s domain/attribute loops now run across all cores, and
  `ValidationRun`'s assertion lists are synchronized because they were plain
  `ArrayList`s appended from those loops.

**The attribute-range pass is planned serially and only then executed in
parallel**, and that split matters: its dedupe key omits the domain, so a range
shared by two domains is validated once and the *winning* domain's constraint
feeds the out-of-range ECL. Parallelising the domain walk would change which
domain wins, and so the query, and so the findings.

**A wrong turn worth keeping.** Parallelising the two cardinality loops first
bought 4% - 1,292 s to 1,242 s - because those loops run in the last two minutes
of the phase. The log's thread names proved the work really had spread over
eight threads; it was simply the wrong loop. The attribute-range pass was the
87%.

### Where the nightly stands now

    SQL assertions        131 s   } concurrent
    structural             31 s   }
    Drools                154 s   }
    MRCM                  735 s   }  <- still the long pole
    acquisition            42 s   serial, before all of them

**Decided scope end to end: ~780 s, from an unoptimised ~2,000 s+.** MRCM is
still 4.8x the next phase, and the remaining levers on it are:

1. **The Lucene index on the heap.** `loadReleaseFilesToMemoryBasedIndex` uses
   `RamReleaseStore`; `snomed-query-service` ships `DiskReleaseStore(File)`
   beside it but `ValidationService` exposes no choice. Peak during the build is
   ~7.8 GB, settling to 1-2.8 GB. A disk-backed store puts it in the page cache
   and lets flushes actually release heap - the memory lever, and it may help
   the build time too.
2. **Two index builds**, one per content form (INFERRED and STATED), of the same
   722,404 concepts. They already run concurrently, but if the index content is
   form-independent they could share one - worth confirming before assuming.
3. **`executeAttributeDomainValidation`** is still serial. It has a different
   shape (attribute-major, with its own domain grouping) and was left alone
   rather than guessed at.

### MRCM memory: the disk index is not the lever, 2026-09-02

`mrcm.validator.index.directory` now builds the Lucene index on disk instead of
the heap. Default off. Measured on the AU edition:

| | RAM index | disk index |
|---|---|---|
| MRCM phase | **735 s** | 776 s |
| index off heap | - | 312 MB |
| peak heap | 11.32 GB | 11.32 GB |
| findings | 1037/3/4/2 | identical |

**Only 312 MB leaves the heap, because the peak is not the index.**
`loadReleaseFiledToStore` materialises the whole concept map and only then
writes it:

    final Map<Long, ? extends Concept> conceptMap = componentFactory.getComponentStore().getConcepts();
    return writeToIndex(conceptMap, releaseStore, loadingProfile);

The several GB are that map. A disk-backed index cannot touch it, and the
queries then read through `NIOFSDirectory` rather than the heap, which is where
the extra 41 s goes.

Kept, default off: a worker starved of heap can trade 5% of the runtime for
312 MB. Not the default, because on the evidence it costs time and buys little.

**The real MRCM memory lever** is streaming concepts into the index instead of
building the full map first - in `snomed-query-service`'s
`ReleaseImportManager`, not in `mrcm-validator`. Not attempted.

**One thing found on the way that was worth more than the feature:** nothing
called `ReleaseStore.destroy()` anywhere. Harmless for a RAM store, but a disk
index would have leaked its directory every run, per content form - the third
instance today of the same leak class after the MySQL schemas and the unpacked
release directories. Now destroyed in a `finally`; verified 312 MB during the
run, 0 after.

**And a measurement invalidated by my own setup:** the first disk-index run was
taken by a *second* worker still listening on the same queue without the
property set. It showed as an empty index directory and a 0.11 GB peak.
Competing consumers make a per-worker configuration change unmeasurable, and
nothing in the report says which worker ran it.

### Can the 466 s of query loops be compressed further? Two levers ruled out, one open

**Ruled out: batching `retrieveConcept`.** `processValidationResults` retrieves
one concept per invalid id, which looked like thousands of point lookups. It is
not: the AU run produced **685 failures across 5 assertions**, so that loop runs
~685 times in the whole phase. At even 1 ms each it is under a second. A batch
API in `snomed-query-service` would buy nothing.

**Ruled out: sharing the two indexes.** The `LoadingProfile` genuinely differs
per content form - STATED uses `withStatedRelationships`,
`withStatedAttributeMapOnConcept`, `withoutRelationships`; INFERRED does not -
so the two indexes hold different content and cannot be one. Settled by reading
the profiles, no measurement needed.

**Already in place: Lucene's query cache.** `SnomedQueryService` constructs its
`IndexSearcher` without calling `setQueryCache`, so it keeps Lucene's default
`LRUQueryCache`. Repeated sub-expressions - the same `<<domain` across a
domain's twenty-odd attributes - are cached where the default policy allows.
Nothing to enable.

**Still open, and it is the whole 466 s:** roughly 2,000 ECL queries at about
**1.9 core-seconds each** (466 s wall x 8 cores / ~2,000). That is slow for
Lucene over 722,404 documents, so something in the evaluation is expensive -
the domain expansion, the attribute join, or the cardinality arithmetic. Which
one, I do not know.

**I tried to find out by sampling stacks and mistimed it.** Samples at 45 s
intervals from submission caught the structural phase first
(`ColumnPatternTester.validateBatch`) and then parked threads after the run
finished. Inconclusive, and recorded as inconclusive rather than dressed up.
Two samples showed `Throwable.fillInStackTrace`, which would suggest exceptions
on a hot path, but two samples is not evidence.

**What would answer it:** a profile timed to the query window specifically -
start sampling once `Total concepts loaded` appears and stop at the last
`Selecting content` line - or per-query timing added to the two cardinality
methods and the range check. Roughly 20 minutes of work for a real answer,
against guessing at an ECL evaluator none of us wrote.

### The remaining MRCM ledger

| | cost | status |
|---|---|---|
| acquisition + unpack | 36 s | parallel, done |
| two index builds | 108 s | cannot be shared; each is a serial map build then index write |
| ECL query loops | 466 s | parallel; per-query cost not yet attributed |
| unlogged tail | 166 s | three delegated services, unprofiled |
| `executeAttributeDomainValidation` | inside the 466 s | still serial, different shape |
| heap peak | 11.3 GB | the pre-index concept map, not the index |

### Where the 466 s of query loops actually goes, 2026-09-02

Profiled properly this time - 45,155 stack frames sampled every 2 s across a
full MRCM run, filtered to the validator, query service and Lucene. Caveat: this
is the **disk-index** variant (776 s), so the store/codec share is inflated
against the 735 s RAM default.

| share | package | what it is |
|---|---|---|
| 28.8% | `org.apache.lucene.search` | executing the queries - irreducible without changing them |
| 17.5% | `org.snomed.quality.validator` | `runAttributeRangeCheck`, the parallel range work |
| **12.9%** | **`org.apache.lucene.queryparser`** | **parsing the query STRING** |
| 15.9% | `codecs` + `store` + `index` | I/O and decode, part of it the disk index |
| 11.6% | `org.ihtsdo.otf.sqs` | ECL -> Lucene string conversion |

**The finding: 12.9% of the phase is spent parsing query text, not searching.**
`SnomedQueryService.eclQueryReturnConceptIdentifiers` converts ECL to a Lucene
query *string* and hands it to the classic `QueryParser` on every call - the hot
frames are `QueryParser.Query`, `TopLevelQuery`, `Clause`, `Term` and
`CustomizedQueryParser.getRangeQuery`.

**Half of that parsing is provably redundant.** Counting the queries the run
actually issued:

```
total queries logged      859
distinct query strings    432
redundant re-parses       427  (49.7%)
```

Every distinct query is issued exactly **twice** - once against the INFERRED
index and once against STATED. The two *searches* are both needed, because the
indexes hold different content. The two *parses* are not: a Lucene `Query` is
immutable and reusable across searchers, so the second parse of an identical
string is pure waste.

**Sizing the lever, on the 735 s phase:**

- a parsed-query cache keyed on the string removes the 49.7% -> **~48 s**
- building `Query` objects directly instead of the string round-trip removes all
  12.9% -> **~95 s**, but that is a rewrite of
  `ExpressionConstraintToLuceneConverter`, not a cache

The cache is the cheap half and it is contained. It lives in
`snomed-query-service` (`org.ihtsdo.otf.sqs`) - a **fourth** library to fork,
patch, build and pin, after snomed-drools and mrcm-validator.

**Not compressible:** the 28.8% of real search execution, and the 2x query
issue itself - both content forms must be validated.

### Getting the measurement was four failed submissions

Recorded because the failures were all mine and all silent:

- `groups=mrcm` -> **412**. MRCM is not an assertion group; it is
  `enableMRCMValidation=true` alongside a real group.
- `releaseFileS3Path=amtv4.zip` -> **400**, empty body. The path is resolved
  under the job store root, so it must be `au/amtv4.zip`. `saveUploadedFiles`
  writes `State.FAILED` and returns the reason in a response map the controller
  discards - a 400 with no body and nothing logged.
- `enableMrcmValidation=true` -> accepted, ran in **94 s**, MRCM **disabled**.
  The parameter is `enableMRCMValidation`, capital MRCM, and a misspelling binds
  silently to the `false` default. A green report that skipped the phase.
- First profile attempt sampled at 45 s from submission and caught the
  structural phase, then parked threads after completion. Sample the window, not
  the wall clock.

Also found: the long-lived API node was serving a jar I had rebuilt underneath
it, and threw `NoClassDefFoundError: ModelAndViewDefiningException` on lazily
loaded Spring classes. Restart the API after any `mvn install`.
