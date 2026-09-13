# PR C — release-mrcm-validator

**Title:** `Answer the lateralizable domain with one ancestor query`

**Base:** `develop` · **Branch:** `pr/c-lateralizable` · **+26/-6, 1 file** · upstream suite **24 pass, 0 fail**

**Depends on PR A** in `snomed-query-service` for `conceptsWithAnyAncestor`.
Upstream CI will not compile this until that is in a snapshot.

---

The Laterality check asks, per candidate concept, whether any ancestor is a
member of the lateralizable refset. It does that with an ECL query per
candidate — `">" + conceptId` — so every query string is distinct, nothing
caches, and each pays a fresh ECL parse.

| | before | after |
|---|---|---|
| ECL queries | 4,561 | 2 |
| time | 145.7s | 0.2s |
| violated set | — | identical |

"Has an ancestor among the members" is one term-set query over the ancestor
field, which is already indexed per concept. It is asked once for every
candidate at once.

Checked under forced violations as well as on clean input: dropping member
`423857001` yields 21 violations both ways.

## One thing that looks equivalent and is not

`<< ^723264001` — which the existing comment contemplates — does not work here.
The descendant operator is dropped over a member-of expression in this service,
so that form silently returns the members alone and invents 4,560 failures. The
code carries that note where the expression is built.

## Scope

This is the query-semantics change only. The parallelism and the configurable
index directory that were originally bundled with it are PR D.
