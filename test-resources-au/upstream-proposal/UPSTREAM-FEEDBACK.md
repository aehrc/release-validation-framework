# Feedback for SNOMED International: Drools on an extension release

Measured on the SNOMED CT-AU daily build 20260831 with the upgraded RVF
(snomed-drools 6.0.0, Drools 10.2.0), rule sets `common-authoring` +
`au-authoring`, 722,404 concepts. Five findings, in descending order of how
much they matter to anyone other than us.

---

## 1. SNAPSHOT_AND_DELTA loading duplicates every component changed this release

**Caveat first: RVF is not affected.** `DroolsRulesValidationService` unzips with
`ReleaseImporter.ImportType.SNAPSHOT`, so it only ever loads the Snapshot. We hit
this by pointing a probe at a full unpacked bundle, which is also what the
standalone `snomed-drools-rf2-validator` CLI does, so it is still worth fixing.

`ReleaseImporter` loads `SNAPSHOT_AND_DELTA`, so an OWL axiom that changed in
this release is read twice - once from
`sct2_sRefset_OWLExpressionSnapshot` and once from the Delta, with the same
axiom id. Each read instantiates the axiom's relationships again, so every
relationship inside it gains a twin with the same `axiomId`, `typeId`,
`destinationId` and `relationshipGroup`.

Three rules match on exactly that, all at **ERROR** severity:

| rule | assertion | findings | population read twice | overlap |
|---|---|---|---|---|
| Two relationships with same type/target/group | 1edb6c09 | 7,908 | 1,782 concepts with an axiom active in both files | 1,782, zero residue |
| An FSN must be represented in at least one dialect | a0372a76 | 1,347 | 1,347 active FSNs in the Delta | 1,347, zero residue |
| Text definitions must be preferred in at least one dialect | - | 34 | 34 active text definitions in the Delta | 34, zero residue |

Not one is content. Worked example - concept 75968004 has one axiom,
`0430055d-f979-43e0-bffb-2ac1b6d0f2a0`, appearing once in the Snapshot and once
in the Delta. All 22 of its relationships are reported, each exactly once, though
no two of them share a type/target/group. And counted from the Snapshot files
alone, the number of active FSNs with no PREFERRED language-refset row is
**zero**: the duplicate instance is the one with an empty acceptability map.

Measured both ways on the same release: 15,939 findings loading
SNAPSHOT_AND_DELTA, 6,650 loading SNAPSHOT only, with the WARNING count
byte-identical at 6,634. Every one of the 9,289 ERRORs is the double-load.

The importer should de-duplicate by component id when both files are loaded -
or, if reading both is deliberate, the Delta instance should be merged into the
Snapshot one rather than added alongside it.

---

## 2. "Relationships must have the same module as the source concept" is not
   valid for an extension

`RelationshipInappropriateModuleAgainstConcept.drl`
(`26713930-fece-484d-ac9c-3e00b0e1090d`) reports any relationship whose module
differs from its source concept's.

    findings                                          4,299
    AU-module relationship on an international concept 4,103  (95%)
    international-module relationship on an AU concept   195
    neither                                                1

Adding a relationship to an international concept, in your own module, is what
an extension *is*. The rule is written for a monolithic edition. The 195 in the
other direction are the part worth reporting - that genuinely should not happen.

We have turned it off for AU runs via `assertionExclusionList`, which works
cleanly, but the rule as written cannot be used by any extension. Suggest it
only fire when the relationship's module is not a declared dependency of the
concept's module (the module dependency refset is right there), or when the
direction is core-on-extension.

---

## 3. Five rules hardcode the international drug-model semantic tags

The repeating pattern: a rule names international tags in a literal, so an
extension that models the same levels under different names is reported as
broken content. AMT calls them `branded clinical drug`,
`containerized branded clinical drug package` and so on; none appear in any of
these lists.

| rule | assertion | findings | of which model mismatch |
|---|---|---|---|
| FsnTermHavingASameSynonynTerm | 65adcfee | 143,065 | 142,610 (99.7%) |
| TermUniqueInHierarchy | 25334385 | 29,990 | 29,513 (98%) |
| TermCaseSignificance (cI) | 4ee9cfeb | 18,062 | 14,826 (82%) - see item 4 |
| RedundantIsaRelationship | 5e04e3df | 6,524 | 6,237 (96%) - see item 3b |
| FSNTermFormat (special chars) | e3048fa9 | 5,147 | 5,145 (99.9%) |

**These need no engine change to fix.**
`DescriptionService.isSemanticTagCompatibleWithinHierarchy(term, keys)` is
already a rule-visible global, and `semantic-tag-hierarchies.txt` is already a
per-edition external file. So a named tag list can live in that file under its
own key and be queried from a rule. `rules.patch` in this directory does exactly
that for three of them, one key each:

    fsn-synonym-exempt   duplicate-term-exempt   fsn-special-char-exempt

Defaulting each key to the tags currently in the literal leaves the
international edition's behaviour bit-for-bit unchanged, and lets an extension
add its own names in reference data instead of forking the rules.

Two of the five are worth a closer look than "add tags":

* **TermUniqueInHierarchy** - the patch exempts a pair only when the two
  concepts are at *different* levels of the same product hierarchy. AMT reuses a
  preferred term deliberately across TPUU/TPP/CTPP (the FSNs differ by tag; the
  preferred terms are identical by design), but two concepts at the *same* level
  sharing a term is still a duplicate. A tag-only guard would have lost 38 real
  findings; this one keeps them.
### 3b. RedundantIsaRelationship is a policy, so scope it to the modules whose
     policy it is

Redundantly-stated IsA is an **international editorial policy**. AMT and AU
clinical content have not adopted it - stating both an AMT parent and the
international top parent it sits under is deliberate, and it is not confined to
the drug hierarchy, so a tag exemption is the wrong shape.

    findings                                          6,524
    on SNOMED CT-AU extension-module concepts         6,237  (96%)
    on international core-module concepts               287

The patch scopes the rule with the helper that already exists,
`ConceptHelper.isCoreModule(c.moduleId)` (900000000000207008 or
900000000000012004), leaving the 287 inherited-international findings reported
and dropping the 6,237. If you would rather it stayed edition-wide by default,
a per-run switch would do - but a rule that encodes one edition's editorial
policy should not be on by default for editions that have not adopted it.

---

## 4. The case-significance unit list is incomplete - for the international
   edition too

`isDrugWithCaseSensitiveUnit` exempts a cI term that carries a case-sensitive
unit, but only from this list:

    mg  g  ml  mcg  unit  units  IU  mEq  mL  MBq  ppm

Of the 18,062 findings on AU:

    11,876  carry a unit already on that list, and were reported only because
            the function is gated on `"clinical drug".equals(semanticTag)`
     2,420  carry a case-significant unit the list omits - microgram, cm, mm,
            kg, mmol, milligram, millilitre
       530  carry a listed unit, but at a tag outside the drug model - 452 of
            them (physical object) dressings measured in "10 cm x 10 cm"
     3,766  carry no unit at all

The second bucket is not an extension problem. `microgram` must not become
`Microgram` for exactly the same reason `mg` must not become `MG`, and
international clinical drug terms spell it out too.

The third is why the patch **removes the tag gate entirely** rather than
externalising it. A gate is another list to get wrong: we first externalised it
to a key defaulting to the drug-model tags, and it still missed 530 findings
because a wound dressing is not a drug. The unit test is self-sufficient - if a
term carries a case-significant unit, its case must be preserved wherever in the
model the concept sits. The function is renamed `isTermWithCaseSensitiveUnit`
accordingly and takes only the term.

---

## 5. A policy question, not a bug: cI on lowercase ingredient names

The 3,766 remaining cI findings are terms like

    Ezetimibe + atorvastatin
    Insulin neutral human + insulin isophane human
    Cannabidiol

with no capital after the first character and no unit. The rule's premise is
that nothing after character one needs preserving, so the term should be `ci`.

AMT marks them `cI` on the reading that `ci` licenses a consumer to recase the
whole term - `Ezetimibe + Atorvastatin`, or full uppercase - which is wrong for
an INN. Preserving *lowercase* is meaningful, not only preserving uppercase.

Only 34 of these are on international-module descriptions, so this is a real
editorial divergence rather than your own content contradicting your own rule.
We would like a ruling rather than a patch: if `ci` is correct here we will
change the content.

---

## What we have applied locally

`rules.patch` (5 rules), the reference data in `../semantic-tags.txt` and
`../semantic-tag-hierarchies.txt`, and `assertionExclusionList` carrying
`26713930-fece-484d-ac9c-3e00b0e1090d` for item 2. `../CASE-SIGNIFICANCE.md`
has the full working behind item 4 and item 5.

One rule we did NOT patch: `FSNTermFormat`'s special-character check has 12
findings left after the branded levels opt out, and all 12 are correct as
content - nine AU `(qualifier value)` unit concepts of the `Tissue culture
infectious dose 50% unit` family, `Ethanol 90% (substance)`, and two of your own
metadata concepts naming the `European Directorate for the Quality of Medicines
& Healthcare`. Twelve readable findings is a better outcome than blinding two
more hierarchies to reach zero. Nothing here is a fork we
intend to carry; `apply.sh` re-applies the patches after every rules re-clone
precisely because we would rather they lived upstream.
