# The plan

Written 2026-09-08, and the single entry point: `STATE.md` is where things
stand, `ROADMAP.md` is the history of how they got here, this is what happens
next and in what order.

Everything below is either **waiting on a decision**, **Attila's**, or **mine
and unblocked**. Nothing is waiting on discovery - the unknowns that mattered
were measured today.

---

## 1. Waiting on you. Nothing proceeds without it.

| # | what | where | why it needs you |
|---|---|---|---|
| 1.1 | Raise the upstream PRs | `duck/pr/PLAN.md` | Public PRs under `aehrc`, and the four-way split needs your sign-off. Forks exist; branches are local and unpushed. |
| 1.2 | Publish the AMT pack | `duck/ASSERTION-PACKS.md` | A branch on the private `aehrc/rvf`. Pack built and verified at `/data/work/amt-pack.json`: 200 assertions, merges to 560, no conflicts. |
| 1.3 | Lower the worker ceiling | `duck/WORKER-MEMORY.md` | 24Gi -> 16Gi changes KEDA's arithmetic. Experiment written out; findings comparison is the deliverable, not the memory number. |
| 1.4 | GitHub Support purge | `STATE.md` | Only you can raise it: `5ba5586c` and `f5d1e652` on `aehrc/release-validation-framework`. |
| ~~1.5~~ | ~~Storage~~ **DONE by Attila, 2026-09-08** | live cluster | Static PVs on `blob.csi.azure.com`, containers `rvf-jobs`/`rvf-releases` on `nctsdevstorage` in resource group `ncts`, `ReadWriteMany`, `Retain`, every blobfuse cache disabled. PVs live in `aehrc/ncts-argo`; this repo's chart and manifests now match. |
| ~~1.6~~ | ~~`STORAGE_LOCATION` join key~~ **ANSWERED, 2026-09-09** | indexer DB | The indexer keys `rvf_runs` on `(storage_location, rvf_run_id)` and PARSES the name: `ncts-<version>-<siBuild>-rvf<rvfBuild>` yields `release_run_id=<version>-<siBuild>`. Anything else is indexed with a NULL release_run_id - orphaned from its release. The nightly already emits the right shape. |

### Both queued runs have now answered  (2026-09-10)

Both waited on the same thing - the dedicated pool has **one online agent**, and
`daily-rvf` **16317** held it - and both have since run:

* **3.1** wanted definition 66 to resource-trigger off 16317's `RvfStage` with
  `amtv4` leading `groupsList`. Build **16321** did exactly that: `groupsList`
  led with `amtv4`, SQL 425, 1,482 tests, 21 failures, `reason=resourceTrigger`.
* **3.7** wanted a comparison step to complete. Build **16329** gated **PASS**:
  147 of 149 identical (98.7%), 0 unexplained, MySQL 1440s against DuckDB 240s.

Neither needed a decision, and neither needs one now.

## 2. Attila's - DONE, 2026-09-09

**2.1 Non-SQL CSV export: landed** as `6fd43a9f` and `e39314c3`, and it fixes
the cause rather than the symptom. `failures.parquet` came out of the engine's
`qa_result`, which only SQL execution writes, so the uncapped export I added in
`45262e49` and the "all N as CSV" link in `f0f5bec2` matched nothing for a
Drools rule: the browser saved a file reading "no failures" beside a report
saying 5,158.

He solved the part I flagged as the real work - the complete set did not exist,
because both services cap at `failureExportMax` and drop the rest, roughly 700k
rows per nightly. `FailureArchiveCollector` stages each validator's rows to a
fragment WHILE they still exist and unions the fragments into
`failures.parquet` after the parallel merge, which is also the right answer to
the trap I would have hit: the three validators run concurrently and the SQL
task used to write and upload the archive on its own.

Rebased my plan commit onto it and re-ran the affected suites together - only
the pre-existing Docker failures. Nothing of mine needs changing.

## 3. Mine, unblocked, in this order

**3.1 Confirm the scheduled nightly runs amtv4 by itself.** Build 16284 proved
the configuration (1,482 records, SQL 425, 200 amtv4, 5 real failures), but it
was queued by hand. One check of the next resource-triggered run closes it.
*Acceptance:* a scheduled build with `amtv4` in `groupsList` and SQL 425.

**3.2 Give the bundled store an identity. DONE 2026-09-09.** It reported as the
literal `bundled`, version `bundled`, digest `bundled`. Now
`international 2026.07.27 sha256:d6f0a930e8acd55f`, where the version is the
corpus's own commit date (a rebuild of unchanged inputs gives the same identity;
dates are orderable, a sha is not) and the digest covers every assertion's
source hash plus the prerequisites. **Recomputed on load, not repeated** -
editing either kind of hash in the real 360-assertion store is refused, naming
both digests. Python and Java compute it independently and agree.

The gap was on the NORMAL deployment: `loadedPacks()` is configuration, so one
pinning no packs answered `[]`, and the report and `GET /assertions/packs` said
nothing about the assertions that ran. Both now read `DuckStore.provenance()`.
Republishing changed **only** the new `pack` node - 0 assertions' SQL - so there
is no behavioural risk in the shipped artefact. 30 tests green across
`DuckStorePacksTest` and `AssertionPackReloadTest`, including a cold-server
ordering trap the endpoint hit.

**3.3 Declared pack dependencies. DONE 2026-09-09.** A pack declares
`"requires": [{"pack": "international", "atLeast": "2026.07.27"}]` - `atLeast`
for a floor, `digest` for an exact corpus - published with
`--requires international:atLeast=2026.07.27`. Proven on the real 360 + 560
pair: the satisfied requirement merges to 560 assertions, and
`atLeast 2026.08.01` is refused with **both versions named**.

Versions are dates, so the comparison is defined: `YYYY.MM.DD` parsed field by
field, anything else **refused as uncomparable** rather than sorted - a
lexicographic compare mis-orders `2026.9.1` against `2026.10.1`. An unknown
requirement key is refused at publish and at load, never ignored: an ignored
requirement reads as a checked combination and is an unchecked one.

A merged store keeps every input's requirements and can still satisfy them from
its nested provenance, because it is a legitimate base for a later merge while
arriving as one pack under one name. 39 tests green.

**3.4 Per-run pack pins. DONE 2026-09-09.** A submission carries
`assertionPacks=name=..;version=..;uri=..;sha256=..`, and the engine resolves a
corpus for THOSE pins through the same pipeline as a reload - fetch, verify each
digest, merge with the bundled base, prove it executes - without touching what
is serving. Proven by two full validations of one release: `packOne` gives
`[international-fixture, packOne]` and 2 findings, `packTwo` gives
`[international-fixture, packTwo]` and 1, and neither run executed the other's
assertion. Pins accepted and ignored would still have produced two reports, so
that is the check that counts.

Cached by pin set, access-ordered, bounded at four - an unbounded map keyed by
request input is a memory leak a caller controls. Two resolutions of one pin set
cost one fetch. A wrong digest is refused with the running corpus untouched.
The run takes store AND source from one `Corpus`: asking the owner separately
would execute one pack set while selecting assertions from another.

**3.5 Pack update notification, pipeline-side. DONE 2026-09-09.**
`ci/pack_update_check.py` + `az/azure-pipeline.pack-check.yml`, daily. Both
acceptance cases proven locally against a served payload: a matching digest
prints `in sync` and exits 0, a stale pin prints `DRIFT` naming both digests and
exits 1, and the failed build is the notification. Compared on DIGEST, not
version - same version with different bytes is the case a pin exists to catch.

GitHub's release API carries `digest: "sha256:..."` per asset, so nothing had to
be invented; the selftest pins that shape against a recorded REAL response. An
unreachable side reports "nothing was compared, this is not in sync" and fails,
because a check that read a 404 registry as agreement would go green for as long
as the token stayed missing.

`packAssets` ships empty on purpose: no deployment pins a pack yet and
`aehrc/rvf` has no readable release, so a mapping would fail nightly for a
reason nobody can act on. **Remaining wiring, needs org access:** register the
YAML as a definition on `catchup-upgraded` and grant it the `ncts-release`
variable group.

**3.6 The REGEXP transpilations against the MySQL oracle. DONE 2026-09-09.**
**84 calls, 83 identical on 1,826,331 real terms, 0 divergences**, after fixing
the one real defect: `[[:alnum:]]` is Unicode-aware in MySQL's ICU and
ASCII-only in DuckDB's RE2, so "vertigo/Ménière disease" escaped the check.
Fixed in the publisher (`duck/publisher-fixes.patch`), which now refuses any
POSIX class it has no measured equivalent for. Full write-up, including the
three harness bugs that each produced a confident wrong answer, in
[REGEXP-PARITY.md](REGEXP-PARITY.md).

The assertion-level A/B could not have found it: all 46 regex-bearing
assertions agreed, 44 of them by matching nothing on either engine. Counting
failures per assertion cannot tell "both engines answered the same" from
"neither engine was given content to answer".

**3.6a An AMT pack must declare its prerequisite in the corpus.** The AMT
`pre-requisites.sql` builds the 13 `*_active` views its assertions read. It was
carried in the store for the DuckDB path but never declared in the corpus
manifest, so the incumbent engine never built them: **192 of 258 assertions came
back incomplete** with `Table 'rvf_au_...description_active' doesn't exist`, and
every one of them read as "not run" rather than as a wiring error. Declaring it
`category="resource"` - which is how the international corpus declares its 16,
and the filename RVF's own importer special-cases - took the run to 1 incomplete
and 259 assertions. The publisher now skips that entry as an assertion, since
the ports stand in for it on the DuckDB side.
*Acceptance:* the pack build emits the entry, and an A/B of the pack shows no
`*_active` table errors.

**3.6b Three AMT-run divergences, none yet in a baseline.** From the same A/B
(AU edition, amtv4 + component-centric-validation, no dependency release
supplied; MySQL 4020s vs DuckDB 120s, 33.5x):

* `17b6c41e` "Language refset members have the wrong module" - **MySQL reports
  1,405,850 findings; DuckDB refuses to run it**, saying `requires <DEPENDENCY>,
  which was not supplied`. DuckDB is right and MySQL's 1.4M are junk from
  comparing against an absent release. Baseline-worthy as a MySQL defect.
* `fc0f240c` "Any refset with a refsetDescriptor record, that is a subset..." -
  MySQL 8, DuckDB 0. Undiagnosed.
* `5451f5c6` "Full ccsRefset validation - 01..." - MySQL errored (-1), DuckDB 0.
  Undiagnosed.

*Acceptance:* each classified with a cause in `ci/known-engine-divergences.json`
or fixed. Note that baseline is keyed to the international nightly, so an
AU/AMT run needs its own.

**3.7 Schedule the differential arm. DONE 2026-09-10, first green run 16329.**
`az/azure-pipeline.engine-ab.yml` had **no ADO definition at all** - it had only
ever been run by hand, locally. Now: definition **69 `rvf-duckdb-engine-ab`**,
authorised for the `ncts-release` variable group and the dedicated pool, and
scheduled weekly (Sunday 14:00 UTC = Monday midnight Sydney, four hours clear of
daily-rvf's 18:00 UTC).

Registering it found **nine things a developer's host had been providing**, each
presenting as something other than its cause - authorization, the `-aehrc-perf`
forks, JDK 17 against a BOM wanting 25, no xz-utils, empty apt lists, a 22.04
pool where `libaio1t64` does not exist plus a missing `libnuma`, symlinks the
artifact publisher cannot follow, absent MySQL data directories, an account key
appended to a URL as though it were a SAS against a container that does not
exist, `az storage file list` without `--recursive`, GUID directories sorting
after timestamps, and a fresh datadir with no root password. The table in
[ci/README-engine-ab.md](../ci/README-engine-ab.md) lists each with its build
number and its symptom.

Every step now passes, including the comparison. **Build 16329 (2026-09-10) is
the first green end-to-end run of definition 69:**

```
identical failureCount           147  (98.7%)
divergent                          2
UNEXPLAINED                        0
uncovered                          0  (known gaps: 0)
only in DuckDB                     0
SPEED   RVF/MySQL 1440s   DuckDB 240s   6.0x
PASS: every divergence is accounted for, coverage is intact, and 147 of 149
agreements ran on both engines
```

Against the newest AU daily build (892MB, `...BETA_AU1000036_20260930T120000Z`)
with the newest published edition before it (891MB, `...PRODUCTION_..._20260831`)
as the previous release. The two divergences are the two classified baseline
entries. **DONE.**

**3.8 Console: show what produced the report. DONE 2026-09-09.** The report card
carries an `assertions from` row, verified in a browser against two real
reports:

    assertions from   international 2026.07.27 · d6f0a930e8ac · 360 assertions
                      amtv4 2026.09.1 · ef49b93a0600 · 200 assertions

and for a report without them, the row says *"not recorded - this report
predates pack provenance, so which assertions produced it cannot be answered"*
rather than staying silent. Silence there reads as "the usual ones", which is
the assumption recording provenance exists to remove.

**3.9 The partial-skip behaviour. DECIDED 2026-09-09: keep the outcome, name
the cause.** `component-centric-snapshot-description-active-inactive-term-match`
builds `tmp_active_desc` from the previous release in statement 3 of 10 and
joins it in statement 6, so with no previous release the build is skipped and
the join dies on `Table with name tmp_active_desc does not exist` - a symptom
nobody can act on.

**Why not report it as not-run.** The incumbent fails it too. `MySqlQueryTransformer`
skips a statement only when it contains the literal `<PREVIOUS>` (a bare
`continue`, line 57-61), and this corpus writes bound aliases like
`prev_description_s` instead - so MySQL does not skip it at all, it substitutes
an absent schema and errors. Reporting a skip on the DuckDB side would trade a
nicer message for a NEW divergence: skip here, error there, and a report that
disagrees about whether the check happened.

So the outcome is unchanged - same error, same incomplete count - and the
message now says `after skipping 1 statement(s) that require <PREVIOUS>, which
was not supplied - a skipped statement builds what this one reads`. Test:
`aPartlyDependentAssertionNamesTheMissingReleaseNotJustTheMissingTable`, which
pins the outcome as well as the message.

**3.10 AMT assertion digests. PARTLY COVERED 2026-09-09; the rest belongs in
`aehrc/rvf`.** Two things landed today that cover part of it:

* the pack identity from 3.2 gives the AMT pack a digest over every assertion's
  source hash AND the prerequisites - `amtv4 2026.09.1
  sha256:ef49b93a06009258` for the 560-assertion build - **recomputed on load**,
  so an edited pack is refused rather than believed;
* `verifyExecutable` from the reload work runs the whole merged corpus against
  an empty schema before any swap, and measured 560 assertions in 2,150ms - so
  the AMT 200 do now get an executability check, on every reload and every
  pinned run.

What is still missing is the PER-ASSERTION recording `AssertionCorpusDigestTest`
does for the international 360, and that has to live beside the scripts in
`aehrc/rvf`: recording a digest for an assertion whose SQL is not in this
repository would pin a number nobody here can regenerate. It lands with 1.2.

**3.11 Gate the AMT parity, not only the international.** Asked directly:
*does this plan include the tests that prove parity for the assertions running
DuckDB vs MySQL?* Honestly, for the international corpus yes and for the AMT 200
not yet:

| what is proven | how | where |
|---|---|---|
| 200 international assertions, per assertion, every night | `compare_reports.py --gate` classifies each divergence against `ci/known-engine-divergences.json` by cause and direction, and fails on an unexplained one, on a baseline entry that stopped diverging, and on coverage loss. 198/200 identical on the first autonomous green (15833) | nightly, ADO 64/66 |
| the regex surface, per PREDICATE | `ci/regexp_oracle.py` + `RegexpOracleProbe`: 84 calls, 83 identical on 1,826,331 real terms, one real defect found and fixed | [REGEXP-PARITY.md](REGEXP-PARITY.md) |
| every assertion still executes and is unchanged | `AssertionCorpusDigestTest` runs all 360 against an empty schema in ~3s and digests them; the pack identity (3.2) extends the digest half to all 560 | build |
| the AMT 200 against MySQL, per assertion | **once, by hand, today**: 259 assertions, MySQL 4020s vs DuckDB 120s, 3 divergences (§3.6b) | nothing gates it |

So the missing piece is precise: the AMT set has no *baseline* and no *gate*.
`ci/known-engine-divergences.json` is keyed to the international nightly - its
two entries are an INT-release module rule and the `<PREVIOUS>`/`<DEPENDENCY>`
skip class - and an AU+AMT run diverges for its own reasons, which is why
today's three are recorded in the plan rather than in that file.

**DONE 2026-09-10, and the three resolved into one.** Build **16325** is the
first A/B to run both engines end to end (149 in both, MySQL 1980s vs DuckDB
120s, 16.5x). It gated FAIL with 49 unexplained divergences, and every one was
`rvf=<n> duck=-1` on a release-type assertion, because the run supplied no
previous release. An absent previous release does not lose coverage, it
MANUFACTURES disagreement on a third of the corpus - MySQL substitutes an empty
schema and answers anyway (7,015,456 findings on one assertion, a false 0 on
others) while DuckDB skips `<PREVIOUS>` and says so. Nothing there to baseline:
the fetch step now resolves the newest PUBLISHED edition dated strictly before
the build under test, and fails the run rather than reporting 66% agreement.

Of §3.6b's three, therefore:

| assertion | verdict |
|---|---|
| `17b6c41e` (1,405,850 findings) | submission artefact - no dependency release. Fixed by supplying one, not baselined |
| `fc0f240c` (MySQL 8, DuckDB 0) | **our defect, fixed today.** MySQL's 8 are real - `900000000000469006` has 7 ancestors and `707000009` is not among them. Of 12 statements exactly one writes `qa_result` and exactly that one needs the dependency, so 11 ran, nothing could be reported, and it PASSED. Now reports not-run unless a finding-writer executed |
| `5451f5c6` (ccsRefset) | genuine engine difference, and the one entry in `ci/known-engine-divergences-au.json`: the AU release ships no ccsRefset file, MySQL errors on the missing table, DuckDB runs the assertion against the empty one its store declares |

`--baseline` is now repeatable and merges, because this arm runs the
international corpus AND the AMT one and each has its own proven causes; a UUID
claimed by two baselines is refused rather than letting one arm's tolerance
decide another's verdict. Run **16329** is the first with all of it in place.

**3.12 The international corpus as a pack, and what a pin cannot reproduce.**
Also asked: *what about the pack for the international tests?* Today it is not a
pack - it is the store baked into the image, `international@2026.07.27`, and the
engine always uses it as the merge BASE so a pack set cannot silently drop it.

That is right for safety and it caps what 3.4 can reproduce. A per-run pin names
PACKS; the base comes from the image. So "re-run build 16247 with the assertions
it actually used" reproduces the AMT side exactly and the international side
only as far as the deployed image happens to match. Worse, pinning a DIFFERENT
international version cannot work today: the same uuid with different SQL is a
merge conflict, which is the rule that makes packs safe.

Two ways out, and the choice is a design decision rather than a task:

* **Publish the international store as a pack too** (from this repo - it is
  public, so no secrecy question) and let a pin REPLACE the base rather than
  extend it, with the replacement named explicitly so it cannot happen by
  accident. Full reproducibility; the cost is that "the base is always the
  bundled corpus" stops being an invariant.
* **Leave it bundled** and accept that reproducing an old report exactly means
  running the old IMAGE, which the tag already identifies. Nothing to build; the
  cost is that pack pins are only half an answer.

*Acceptance:* a decision recorded here. My inclination is the second until
someone actually needs to re-run an old report, because the first trades a
load-bearing invariant for a capability nobody has asked for yet.

**3.13 Console: in-flight runs, and why the list was slow. DONE 2026-09-10.**
Asked: show running and pending executions with when they were submitted and how
long they have been running; and why does the panel take a few seconds, should it
be paginated.

The listing already included in-flight runs - a run appears as soon as
`rvf/state.txt` exists, before any report - but it showed
`ago(lastModified)`, which is the age of the last state or progress write. A run
twenty-seven minutes in that logged a phase eight seconds ago read **"just
now"**. Nothing on disk could do better: `state.txt` is overwritten on every
transition, so its timestamp is the last change. `writeState` now writes
`rvf/submitted.txt` once, at QUEUED, and the card shows `10:41 · 27m` with the
instant on hover. Runs predating the stamp say `last activity 40m ago`, labelled
as such.

The latency was measured on the API pod, 18 runs:

| what | cost |
|---|---|
| the stat walk over the store | 569 ms |
| reading every report | 1110 ms |
| one report (1.1 MB) | 32 ms |
| reading every report **again** | 1106 ms |

Nothing is cached, by design: the storage decision (§1.5) turns every blobfuse
cache off. So the listing was reading **10.3 MB to display a dozen numbers per
row**, and at the 200 rows the console asks for it would have been ~18s. Each
operation on that mount is a round trip at ~16 ms, so the fix was to do fewer of
them: one `readAttributes` where there were three calls per directory; a few
hundred byte `rvf/summary.json` beside each report, written by the same parser
that reads it and deleted when the report is rewritten; and `progress.txt` and
`submitted.txt` read for in-flight runs only, since nothing else displays them.

The table pages 25 at a time. The in-flight card is deliberately **not** paged -
a run queued four days ago sorts below sixty finished ones and is exactly the run
someone is looking for.

**3.14 An absent dependency was silently shrinking 17 assertions. FIXED
2026-09-10.** Asked whether there are actual per-assertion tests. There are,
and checking them properly found this.

**The oracle nobody had joined.** MySQL's per-assertion expectations for the
regression fixture are already in this repo -
`src/test/resources/regressionTestResults/*.json`, 330 assertions with
`totalFailed` keyed by `assertionUuid` - and `AssertionCorpusDigestTest` runs
the whole corpus over the SAME two fixture releases. Joining them gives **264
assertions with both engines' numbers on identical data**, offline, in about
three seconds. 230 agreed; 34 did not.

**Why the 34 are not a parity measurement.** Those files were last touched
**2023-02-09**, by a project reorganisation, against a corpus that is now
`international@2026.07.27`. Most of the 34 are three and a half years of
assertion SQL drift, so nothing is gated on them.

**But one direction cannot be drift:** DuckDB finding FEWER than MySQL.
`release-type-snapshot-concept-successive-states` has two statements that write
findings, and the second ends
`LEFT JOIN <DEPENDENCY>.concept_s AS e ... WHERE e.id IS NULL`. With no
dependency the binder skipped it, so one of two checks ran and the report
carried the survivor's count as the whole answer: **1 finding where MySQL found
4**. No error, no not-run, nothing to notice - the failure mode this engine
exists to remove.

An anti-join requiring NULL is a **no-op** against an empty relation, so an
empty dependency and an absent one give the same answer. Across the bundled
corpus, for a release with no dependency - every edition run, and
`releaseAsAnEdition=true` is what the nightly submits:

| | |
|---|---|
| 324 | fully answered |
| 3 | honestly not-run |
| **17** | **silently partial** |

Of those 17: **16** are anti-join statements in the
`release-type-snapshot-*-successive-states` family, and **one**
(`file-centric-snapshot-inactivated-component-module`, 28 statements) compares
AGAINST the dependency and must still be skipped. Hence not "bind an empty
schema and run everything" - that is MySQL's behaviour, and it is how one AU
assertion reported 1,405,850 findings against a dependency that was never
there.

`DuckBinder` now stands in an empty schema for a statement whose ONLY use of the
absent dependency is an anti-join, and refuses every other shape, including a
half-anti-joined one and a LEFT JOIN whose alias is read rather than required to
be NULL. `<PREVIOUS>` is never stood in for: "this did not exist before" and
"there is no before" are different answers, and a first-time release is a real
case.

**Verified against an oracle that is not ours:** 15 of the 16 now match MySQL's
recorded counts EXACTLY where they under-reported - concept 1->4, simple-map
1->4, language 1->3, attribute-value 1->3, inferred-relationship 1->2, and four
that moved NOT_RUN -> RAN. Fixture agreement 230/264 -> 236/264. The holdout is
`description-successive-states` (MySQL 2, DuckDB 1), where the materialiser logs
`description_s: strict read refused` - ragged rows in the fixture, not the
assertion.

*Consequence to expect:* baseline entry `26c25479`
(`duckdb-reports-not-run-where-mysql-false-passes`) is keyed to
`release-type-snapshot-owl-expression-successive-states`, which is in the fixed
family. Its divergence should collapse, and the gate fails on a baseline entry
that has stopped diverging - by design. Build **16337** decides it.

## 4. Known, deliberate, not scheduled

* `minAssertions` 1,400 / `minSqlAssertions` 400 depend on the AMT overlay
  existing. When 1.2 replaces the overlay with a pack, the floors stay but the
  thing they guard changes from a directory to a pinned digest - which is the
  improvement.
* The overlay itself is still mutable state outside git. 1.2 is the fix.
* `DuckDbEngineContextTest` fails 7/7 without Docker, as do four other
  Testcontainers suites. Pre-existing and unrelated to any of the above.

## 5. Two traps to re-read before touching the cluster

* The pod's `find` reports **0** `.sql` files in a directory holding 654. Use
  `python3`, which the image has. I believed it twice in one day.
* `mvn` against the IHTSDO clones stalls ~30 minutes resolving snapshots from
  their Nexus. Offline (`-o`) the same install takes 2.8 seconds.
