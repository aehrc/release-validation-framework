# PR C — release-mrcm-validator
**Title:** `Answer the lateralizable domain with one ancestor query`

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

Checked against deliberately failing data as well as clean data: with member
`423857001` dropped so that violations must be reported, both the old and the
new code find the same 21.

## Why not `<< ^723264001`

The existing comment contemplates that expression, and it is the obvious way to
write this. It does not work, for a reason outside this change:
`snomed-query-service` discards the descendant operator when it is applied to a
member-of expression, so `<< ^723264001` returns only the members of the refset
and none of their descendants. Every descendant of a member would then be
reported as violating the MRCM.

That is a pre-existing defect in the query service. This PR does not fix it and
does not depend on it being fixed — it asks for the two sets directly instead.
It is fixed separately by
[Apply the constraint operator to the members of a member-of expression](https://github.com/dionmcm/snomed-query-service/pull/3).
Even once that has landed, the form used here stays, because resolving
`<< ^723264001` performs the same member lookup and the same term-set query
internally, so there is nothing to gain by switching.

## Scope

Query semantics only. The parallelism that was originally bundled with this is
a separate PR stacked on top.

## Dependencies

**Depends on:** [Answer an out-of-range ECL clause with one TermInSetQuery](https://github.com/dionmcm/snomed-query-service/pull/1) (`snomed-query-service`), for `conceptsWithAnyAncestor(Collection<Long>)`. **CI here will not compile until that is released in a snapshot** - that is expected, not a broken branch.

**Depended on by:** [Run the attribute checks in parallel](https://github.com/dionmcm/release-mrcm-validator/pull/2), and [Allow the MRCM index to be built on disk](https://github.com/dionmcm/release-mrcm-validator/pull/3) behind it.

**Related:** [Apply the constraint operator to the members of a member-of expression](https://github.com/dionmcm/snomed-query-service/pull/3) fixes the `<< ^X` behaviour this file's comment describes.

**Merge order:** first in this repository, after the query-service PR.
