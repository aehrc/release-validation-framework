# Upstream PRs: what is ready, and what I got wrong first

Nothing is raised. State as at 2026-09-08.

## What is already public

Two forks, created under the `aehrc` org and nothing else:

* `aehrc/snomed-query-service` (fork of `IHTSDO/snomed-query-service`)
* `aehrc/release-mrcm-validator` (fork of `IHTSDO/release-mrcm-validator`)

No branches pushed, no PRs opened. The commits below exist only in
`/data/work/up-*`.

## Verified against upstream

Both patches apply cleanly to `develop` **and** `master`, and upstream's own
suites pass with them applied:

| repo | base | tests |
|---|---|---|
| snomed-query-service | `develop` @ `6b3027e` | **125 pass, 0 fail** |
| release-mrcm-validator | `develop` @ `d1c8841` | **24 pass, 0 fail** |

The validator needs the query-service change to compile at all
(`conceptsWithAnyAncestor`), so its 24 green required installing the patched
query-service locally. Upstream CI will hit the same wall until the
query-service change is in a snapshot - that is a stacking fact to declare, not
a defect.

## What I got wrong, and caught before raising

I drafted two PR bodies describing **one change each**. Both patches carry
more than that. The bodies were accurate about the headline and silent about
the rest, which would have put a reviewer in front of 483 changed lines
described as "one ancestor-set query".

Actual inventory:

**snomed-query-service** - 8 files, +527/-39

1. Out-of-range clause -> one `TermInSetQuery` (the 4.2x, and the 10.94 GB of
   automaton tables). Carries the sentinel/`ThreadLocal` handoff and the
   `termSetQueriesBuilt()` counter.
2. `conceptsWithAnyAncestor` - the API the validator needs.
3. Concept id as `NumericDocValues` - 24.7us per hit saved over a 3.9M-hit
   corpus, and what makes (2) usable when it returns the whole domain.
4. **Parallel document construction with ordered writes** in
   `ReleaseImportManager`, plus a `later()` max-effectiveTime helper in
   `ReleaseWriter`. Documents are built across cores and written in iteration
   order deliberately: write order fixes docids, Lucene returns equal-scoring
   hits in docid order, so writing concurrently would keep every result set
   identical and still reorder it - and a validation report would then name a
   different sample of failing concepts run to run.

**release-mrcm-validator** - 2 files, +347/-146

5. Lateralizable domain by ancestor set: 4,561 queries and 145.7s -> 2 queries
   and 0.2s.
6. **Parallel domain/attribute checks**, which needs `ValidationRun`'s three
   assertion lists synchronized - a plain `ArrayList` loses entries under
   concurrent add, and a lost entry is a lost assertion, so the run would
   report FEWER results rather than fail - and a per-thread `ReleaseStore`,
   because the store is not thread-safe.
7. `mrcm.validator.index.directory`, making the index location configurable.

## Recommended: four PRs, not two

Splitting on the reviewable seam - query semantics vs concurrency - because the
concurrency half is where the risk is and the query half is where the
measurement is. Bundled, the safe win waits for the risky review.

| # | repo | contents | depends on | why separable |
|---|---|---|---|---|
| A | query-service | 1 + 2 + 3 | - | all query-side; (3) exists to serve (2) |
| B | query-service | 4 | - | index-build side, touches no query path |
| C | mrcm-validator | 5 | **A** | small and decisive; the phase win |
| D | mrcm-validator | 6 + 7 | B-style review | thread safety, needs its own scrutiny |
| E | snomed-release-validation-assertions | one missing semicolon | - | one character, no fork made yet |
| F | release-validation-framework | GET /assertions owns its list | - | Attila's report; upstream is fragile, we were broken |

F is Attila's: `getAssertionsAndJoinGroups` returns the list `findAll()` gave
it and the endpoint then appends to it. Harmless upstream, where `findAll()` is
a repository query returning a fresh list; against a corpus loaded once and
shared it threw and answered HTTP 500. Fixed here in `AssertionController`
with a defensive copy plus a group join that only writes when the group is
missing. Body in `assertion-endpoint-owns-its-list.md`.

E is the smallest and the most clearly upstream's: one script omits the `;`
before its final `commit;`, so every splitter yields one unparseable statement
and the assertion cannot run - invisible because the table it reads is empty in
the releases being validated. Patch and body in
`assertions-missing-semicolon.patch` / `.md`. No fork of that repo exists yet.

C is the one I would want landed first: 145.7s -> 0.2s, identical violated set
including under forced violations, and it is a dozen lines.

## The two things I expect a reviewer to attack in A

Both are already written into `query-service-out-of-range.md` rather than left
to be discovered:

* **The `ThreadLocal` handoff.** The converter emits Lucene query *text*, so it
  cannot build a `TermInSetQuery`; it writes a `__notinset__` token and parks
  the terms in a `ThreadLocal` the service drains. That is hidden state, and it
  leaks if the token is emitted without being consumed. The honest alternative -
  change the converter's return type from `String` to a query object - touches
  every caller and all 83 converter tests, and I would rather carry that diff
  than the invisible state if they prefer it.
* **The instrumentation counter.** `termSetQueriesBuilt()` exists because two of
  my own parity results in this area were wrong through both arms running the
  old code and agreeing. Easy to drop.

## Local commits, for review

    /data/work/up-snomed-query-service    pr/termset-out-of-range      5b3a7f1
    /data/work/up-release-mrcm-validator  pr/lateralizable-ancestor-set 4a624fb

The validator commit's message now states that it bundles what should be two
PRs, so nothing inaccurate exists even locally.

## Also worth knowing

`mvn` against these clones stalls for **~30 minutes** resolving snapshots from
IHTSDO's Nexus. Offline (`-o`) the same install takes **2.8 seconds**. Two
half-hour waits in this session were that, not compilation.
