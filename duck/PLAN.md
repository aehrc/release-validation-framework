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
| 1.5 | Storage: static or dynamic | `STATE.md` | Attila's preference - keep dynamic provisioning or move to a static share in a resource group he owns. |
| 1.6 | `STORAGE_LOCATION` naming | `STATE.md` | Needs the exact key the indexer joins on. Rename drafted, not applied. |

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

**3.7 Schedule the differential arm. WIRED 2026-09-09, one run still queued.**
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

Every step now passes up to the comparison itself: JDK, forks, jar, MySQL,
release fetch (it resolves and downloads the newest AU edition, 892MB, by
walking the share the nightly reads), and the selftest. The A/B step is queued
behind tonight's `daily-rvf`, which holds the pool's one online agent.
*Remaining:* one green run of the comparison step.

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

**3.10 AMT assertion digests.** `AssertionCorpusDigestTest` covers the 360
international assertions; the 200 AMT ones have no recording. Belongs beside
them in `aehrc/rvf`, so it lands with 1.2.

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
