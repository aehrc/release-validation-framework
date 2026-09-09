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

**3.2 Give the bundled store an identity.** It reports as `bundled` with no
version or digest, so nothing can be required of it. Emit a version and a
sha256 in the store, and surface both in `GET /assertions/packs`.
*Acceptance:* provenance for a packless deployment names a real version and
digest, and `DuckStorePacksTest` pins it.

**3.3 Declared pack dependencies.** With 3.2 done, a pack can say
`requires: {international: {atLeast: <version>}}` or an exact digest, and the
merge can refuse a combination nobody tested with a named cause instead of
`no_such_macro`. Versions are dates, not semver, so the comparison has to be
defined rather than assumed.
*Acceptance:* a pack requiring a newer base is refused at merge, naming both
versions; a satisfied requirement merges silently.

**3.4 Per-run pack pins.** So an old report can be reproduced: the submission
names pins, the engine fetches/verifies/merges/verifies-executable per run and
caches corpora by digest. Costs 3.0s plus download on a cache miss, ~800KB per
cached corpus.
*Acceptance:* two runs of one release with different pins produce reports whose
`assertionPacks` differ and whose findings differ accordingly.

**3.5 Pack update notification, pipeline-side.** A scheduled job compares the
latest release digest against `GET /assertions/packs` and says "amtv4 2026.09.2
available, 2026.09.1 loaded". A notification, never an action - the moment the
server follows a channel we have reinvented `latest`.
*Acceptance:* the job reports drift on a stale pin and is silent otherwise.

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

**3.7 Schedule the differential arm.** `az/azure-pipeline.engine-ab.yml` exists
and has been run by hand. Parity that is re-proven weekly is worth more than
parity proven once.
*Acceptance:* a scheduled trigger, and one green scheduled run.

**3.8 Console: show what produced the report.** Reports now carry pack name,
version, digest and count; the UI does not show them.
*Acceptance:* the report header names the packs, and a packless run says so.

**3.9 Decide on the partial-skip behaviour.** An assertion mixing
previous-release-dependent statements with independent ones FAILS rather than
skipping when no previous release is supplied, because the later statements
reference a temp table the skipped one would have built
(`component-centric-snapshot-description-active-inactive-term-match.sql`).
Either report it as not-run, or record it as upstream behaviour with evidence.
*Acceptance:* a decision, with either a fix and a test or an entry in the
divergences file.

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
