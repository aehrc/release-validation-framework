# PR C — release-mrcm-validator

**Title:** `Answer the lateralizable domain with one ancestor query`

**Base:** `develop` · **Branch:** `pr/c-lateralizable` · **+23/-6, 1 file** · upstream suite **24 pass, 0 fail**

**Depends on the `conceptsWithAnyAncestor` PR** in `snomed-query-service`.
Upstream CI cannot compile this until that is in a snapshot.

## Dependencies

**Depends on:** the out-of-range PR in `snomed-query-service`, for `conceptsWithAnyAncestor(Collection<Long>)`. **CI here will not compile until that is released in a snapshot** - that is expected, not a broken branch.
**Depended on by:** the parallel-checks PR, and the disk-index PR behind it.
**Merge order:** first in this repository, after the query-service PR.

---

The Laterality check asks, per candidate concept, whether any ancestor is a
member of the lateralizable refset. It does that with an ECL query per
candidate — `">" + conceptId` — so every query string is distinct, nothing
caches, and each pays a fresh ECL parse.

Measured on an 853 MB AU edition, 4,561 candidate concepts, single run:

| | before | after |
|---|---|---|
| ECL queries | 4,561 | 2 |
| wall clock | 145.7s | 0.198s |
| violated set | — | identical |

"Has an ancestor among the members" is one term-set query over the ancestor
field, which is already indexed per concept, so it is asked once for every
candidate at once. `conceptsWithAnyAncestor` returns proper descendants, so the
members themselves are unioned back in — the old code skipped members by an
explicit `continue`.

Checked under forced violations as well as on clean input: dropping member
`423857001` yields 21 violations both ways.

## One thing that looks equivalent and is not

`<< ^723264001` — which the existing comment contemplates — does not work here.
The descendant operator is dropped over a member-of expression in this service,
so that form silently returns the members alone and fails every descendant of
one. The code carries that note where the set is built.

## Scope

Query semantics only. The parallelism that was originally bundled with this is
a separate PR stacked on top.
