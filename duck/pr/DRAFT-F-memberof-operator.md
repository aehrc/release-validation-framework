# PR F — snomed-query-service
**Title:** `Apply the constraint operator to the members of a member-of expression`

A constraint operator on a member-of expression was discarded. Every one of
these converted to the same Lucene query:

| ECL | converted to | should be |
|---|---|---|
| `^X` | `memberOf:X` | correct |
| `<< ^X` | `memberOf:X` | members **and their descendants** |
| `< ^X` | `memberOf:X` | the descendants of members |
| `> ^X` | `memberOf:X` | the ancestors of members |
| `>> ^X` | `memberOf:X` | members and their ancestors |

`enterEclfocusconcept` appended `memberOf:<id>` whenever `isMemberOf` was set
and never consulted `constraintOperatorContext`. No error, no warning.

The operator distributes over the **members**; it does not describe the refset
concept. `<< ^723264001` means the members of the lateralizable body structure
refset and everything beneath them.

## Effect on validation results

A caller using `<< ^X` to decide which concepts are permitted an attribute gets
the members alone, so every *descendant* of a member looks not permitted. On an
AU edition that is ~4,560 concepts wrongly reported as violating the MRCM, from
a run that looks completely healthy.

## An upstream test asserted the bug

`testExample_UnaryOperators_1` pinned `< ^700043003` → `memberOf:700043003` —
the discarded operator, encoded as expected behaviour. Corrected here, with the
`<<` and bare `^` forms added alongside it so all three are distinguished.

`MemberOfConstraintOperatorTest` covers it end to end against a real index:
a refset whose single member has a child and a grandchild. Reverting just the
converter line makes it fail with `[362961001]` where all three concepts are
expected.

## How the operator is resolved

The members are not known at conversion time, so the operator becomes an
internal function the query service resolves against the index — the mechanism
already used for `ANCESTOR_OF` and friends. Both directions resolve with a single `TermInSetQuery` rather than a search per
member, because a refset can have tens of thousands and each search here is
sized to the whole index. Descendants are the concepts holding a member in their
ancestor field; ancestors are read from the members' own stored ancestor values,
fetched in one query.

## Other changes this required

**The resolver matched functions by bare name.** `luceneQuery.contains(name)`
means one function whose name is a prefix of another's takes the other's text
and fails to parse it — `REFSET_MEMBER_DESCENDANTS` swallowing
`REFSET_MEMBER_DESCENDANTS_OR_SELF`. Now matched as `name + "("`. The existing
names avoid the collision by luck (`ANCESTOR_OR_SELF_OF` does not contain
`ANCESTOR_OF`), so this is latent upstream rather than a live bug.

**A constraint operator on a member-of expression inside an attribute value now
throws `UnsupportedOperationException`.** The member set is not expressible
there, and the operator was previously being dropped — so this converts a wrong
answer into a refusal rather than removing a working feature. `attr = ^X`
without an operator is untouched.

## Dependencies

**Related:** [Answer the lateralizable domain with one ancestor query](https://github.com/dionmcm/release-mrcm-validator/pull/1) carries a comment explaining why it does not use `<< ^X`. This is the fix for that. Neither needs the other to land.

**Merge order:** any time, but ideally not after that one, so its comment is checkable.
