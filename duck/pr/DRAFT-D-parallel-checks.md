# PR D — release-mrcm-validator
**Title:** `Run the attribute checks in parallel`

The attribute cardinality, group-cardinality and range checks run serially and
are independent of each other. This runs them across cores.

| | before | after |
|---|---|---|
| MRCM phase | 1,292s | 735s |
| tests / failures / warnings / incomplete | 1037 / 3 / 4 / 2 | identical |
| assertions differing in bucket or count | — | 0 |

Measured locally on an 853 MB AU edition, single run each, on an 8-core box.

**That 1,292s → 735s is not all this PR.** The same measurement included an
unrelated change in our own caller — switching MRCM's archive unpack off
snomedboot's single-threaded `unzipRelease`, which is worth ~36s and is not in
this repo. This PR has not been measured in isolation. The identical
findings are the number to trust here, not the wall clock.

## What is NOT parallelised

`ATTRIBUTE_DOMAIN` validation stays serial, deliberately. The attribute-range
pass is also *planned* serially and only then executed in parallel, because its
dedupe key omits the domain: a range shared by two domains is validated once,
under whichever domain planned it. Parallelising the domain walk would change
which domain wins, and so the query, and so the findings.

## What the parallelism required

**`ValidationRun`'s three assertion lists are now synchronized.** A plain
`ArrayList` loses entries under concurrent `add`, and a lost entry here is a
lost assertion — the run reports *fewer* results rather than failing. Silent
under-reporting, not a crash.

**`getCompletedAssertions()` and `getSkippedAssertions()` now sort by
assertion uuid.** They were returning the live list in *completion* order,
which varies run to run. `MRCMValidationPassed.txt` and
`MRCMValidationSkipped.txt` write a line per assertion in list order, so
unsorted they would reorder between identical runs. The failure/warning
reports were never affected — they come from value-hashed sets.

One store is built per run and shared by the worker threads; the query service
over it is read-only and safe to share.

## Not here

The configurable on-disk index that was originally bundled with this is a
separate PR stacked on top. It is memory management, not parallelism.

## Dependencies

**Depends on:** [Answer the lateralizable domain with one ancestor query](https://github.com/dionmcm/release-mrcm-validator/pull/1), and transitively [the out-of-range PR](https://github.com/dionmcm/snomed-query-service/pull/1) in `snomed-query-service`.

**Depended on by:** [Allow the MRCM index to be built on disk](https://github.com/dionmcm/release-mrcm-validator/pull/3).

**Merge order:** second in this repository.

Stacked: the base of this PR is the lateralizable branch, so the diff shown is only this change. Retargeting the base to `develop` before that one merges would make it display both.
