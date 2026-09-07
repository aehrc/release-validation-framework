# Answer the lateralizable-domain check with one ancestor-set query

**Repo:** IHTSDO/release-mrcm-validator · **base:** `develop` · **files:** `ValidationService`, `ValidationRun`

**Depends on** IHTSDO/snomed-query-service - it calls
`conceptsWithAnyAncestor`, added there. Without that PR this does not compile,
which is why it is stacked rather than standalone.

## What is slow today

The lateralizable-reference-set check asks, for each candidate concept, whether
it is in the lateralizable domain - by running an ECL query per concept. On an
AU release that is **4,561 ECL queries** and **145.7s**, and it is most of the
phase's tail.

## What this changes

One query for the domain's ancestor set, then a set membership test per
candidate: **2 queries, 0.2s**. Same check, same shape of answer.

## Evidence it is equivalent

* Violated set **identical** on an AU release - ids, not counts.
* Still identical under a **mutation that forces 21 real violations**. This
  matters more than the clean run: both forms returned *zero* violations on the
  unmodified release, and two empty results agreeing proves nothing. The probe
  that produced this number injects violations so the comparison has content.
* End to end on a real nightly (Azure DevOps build 16246 against 16226, same
  input release): MRCM's tail beyond the SQL phase **443s -> 55s**, whole
  validation **816s -> 464s**, and **MRCM findings unchanged at 4 inferred / 4
  stated**.
* Upstream's own suite on `develop` with this applied: **24 tests, 0 failures**.

## The trap a reviewer will want to fall into

Collapsing this to `<< ^723264001` looks like the same thing and is not: the
descendant operator is dropped over a member-of expression, so the query
returns the refset's members rather than their subtypes, and the check then
invents **4,560 failures**. The code carries that note where the expression is
built, because the wrong version is the obvious one.
