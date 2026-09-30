# Feedback for SNOMED International: Drools on an extension release

Measured on the SNOMED CT-AU daily build 20260831 with the upgraded RVF
(snomed-drools 6.0.0, Drools 10.2.0), rule sets `common-authoring` +
`au-authoring`, 722,404 concepts. Items 6-8, the special-character and
case-significance additions to items 3 and 4, and the patch for item 2 were
measured on the 20260930 daily build (snomed-drools 6.1.3, 723,537 concepts),
run the way RVF runs it: Snapshot only, `activeConceptsOnly`, and
`includedModules=32506021000036107,351000168100` where stated. Findings in
descending order of how much they matter to anyone other than us.

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
an extension *is*. The rule is written for a monolithic edition. We first read
the 195 in the other direction as the part worth reporting; they are not. On
20260930 they are 92 concepts, every one a core-namespace id the extension has
republished in its own module (`310011000 |Aural rehabilitation service|` and so
on), whose international relationships it has no reason to touch - an override,
the same mechanism seen from the concept's side.

The faithful fix is "fire only when no active module dependency links the two
modules, in either direction" - an extension's relationship on an international
concept is linked (the extension depends on the international modules), and so
is the international relationship left on an international concept the
extension has republished in its own module. **No rule-visible service exposes
the module dependency refset**: refset members are not Drools facts, and none of
`ConceptService`, `DescriptionService` or `RelationshipService` has a module
method. That is the request: a `ConceptService.isModuleDependency(from, to)`
(or a set of the active MDRS pairs), after which the rule is one `eval`.

Until then the patch uses the closest equivalent a rule can express, with the
helper that exists: every extension module depends on the international core
and model modules, and the core module depends on the model module, so a pair
where either side `ConceptHelper.isCoreModule` is always linked. The rule now
fires only for extension <-> extension. What that misses, against the MDRS
version:

* an extension <-> extension pair that IS declared (e.g. a hosted-refset module
  depending on its edition module) is still reported - over-reporting, and
  measured zero here: no relationship in this release crosses
  `32506021000036107` and `351000168100`;
* a core <-> extension pair whose MDRS does NOT declare the dependency is
  silenced - but an extension with no dependency on the international edition
  fails the MDRS assertions long before this rule matters.

On 20260930: 4,329 findings -> 0, with or without `includedModules`. The
assertion exclusion we ran it under is no longer needed.

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
| FSNTermFormat (special chars) | e3048fa9 | 5,147 | 5,145 (99.9%) - now a hierarchy test, below |

**These need no engine change to fix.**
`DescriptionService.isSemanticTagCompatibleWithinHierarchy(term, keys)` is
already a rule-visible global, and `semantic-tag-hierarchies.txt` is already a
per-edition external file. So a named tag list can live in that file under its
own key and be queried from a rule. `rules.patch` in this directory does exactly
that for two of them, one key each:

    fsn-synonym-exempt   duplicate-term-exempt

Defaulting each key to the tags currently in the literal leaves the
international edition's behaviour bit-for-bit unchanged, and lets an extension
add its own names in reference data instead of forking the rules.

Three of the five are worth a closer look than "add tags":

* **TermUniqueInHierarchy** - the patch exempts a pair only when the two
  concepts are at *different* levels of the same product hierarchy. AMT reuses a
  preferred term deliberately across TPUU/TPP/CTPP (the FSNs differ by tag; the
  preferred terms are identical by design), but two concepts at the *same* level
  sharing a term is still a duplicate. A tag-only guard would have lost 38 real
  findings; this one keeps them. The concept's own FSN is read inside the
  function rather than matched as a pattern, so a concept with no active FSN is
  still checked - your own test cases carry none, and a pattern silently turned
  the rule off for them. 20260930: 30,118 -> 478 (213 AU-module).
* **FSNTermFormat (special characters)** - first written as a third key,
  `fsn-special-char-exempt`, and replaced by a hierarchy test: the FSN is exempt
  when the concept is, or descends from, `373873005 |Pharmaceutical / biologic
  product|`, `774167006 |Product name|` or `260787004 |Physical object|`
  (`conceptService.findStatedAncestorsOfConcept`, the call
  `int-authoring/.../TermCaseSignificance.drl` already makes for `373873005`).
  Registered product names carry `&`, `%` and `#` and are not editable; those
  three roots are where they live - AMT's trade-name level `(product name)` sits
  under Qualifier value, not under product, and its device levels under Physical
  object. The hierarchy form needs no reference data, survives a new model level
  under an existing root, and cannot be satisfied by a mis-tagged concept.
  Measured identical to the key, finding for finding: 5,161 -> 12 (10 AU-module,
  every one a content question - see "What we have applied locally").
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

Three more gaps, all of which carry case that the rule's uppercase test cannot
see (20260930, 164 findings):

    132  a lowercase letter that IS the meaning: Anti-k (not Anti-K),
         p-aminophenol, d-alpha-tocopherol, interferon alfa-n3, Cytochrome b5,
         Little c, Weak e, serotype b, k-free, s-adenosylmethionine, p,p'-
     28  units the list still omits: m (metre - M is molar), w/w, w/v, v/v
      2  a unit written straight after its number: 7g protein exchanges/day

The first is a lowercase-token test, `isTermWithLowercaseSignificantToken`: a
single lowercase letter standing as its own token after character 1, optionally
with a digit suffix, excluding `a` (the article) and `x` (the multiplication
sign in `6 x 77 tablets`). 30 of the 132 are your own descriptions, 18 of them
tightened `ci -> cI` by your authors in 2020-2021 (`p-` and `alfa-n` products,
`Weak c/e phenotype`) - so the rule and your editors disagree today. The other
two extend the unit list and add a unit-after-digit match. 3,766 -> 3,604
(3,602 AU-module), and every row that left is one of those 164.

---

## 5. A policy question, not a bug: cI on lowercase ingredient names

The 3,604 cI findings that remain on 20260930 once item 4's gaps are closed
(3,766 before) are terms like

    Ezetimibe + atorvastatin
    Insulin neutral human + insulin isophane human
    Cannabidiol

with no capital after the first character and no unit. The rule's premise is
that nothing after character one needs preserving, so the term should be `ci`.

AMT marks them `cI` on the reading that `ci` licenses a consumer to recase the
whole term - `Ezetimibe + Atorvastatin`, or full uppercase - which is wrong for
an INN. Preserving *lowercase* is meaningful, not only preserving uppercase.

Only 2 of these are on international-module descriptions, so this is a real
editorial divergence rather than your own content contradicting your own rule.
We would like a ruling rather than a patch: if `ci` is correct here we will
change the content.

---

## 6. "An active term should not contain ... @, $, #, \" has no exemption at all

`TermCharacters.drl` (`fbd4bbb5-3e62-4ccb-824a-e82d9771c0ee`) matches every
active description, FSN or synonym, with no concept pattern. On 20260930 every
finding (11 on our copy of the build, 22 on the one validated) is `#` in a
registered medicinal-cannabis strain name, `Lot420 Gelato #33` / `Terphogz
Gelanoidz #20`, at all four AMT levels including `(product name)`.

The patch gives it the same hierarchy exemption as item 3 - it needs a `Concept`
pattern, the `conceptService` global and the ancestor lookup, evaluated only
for a term that already contains a flagged character. The reported component is
still the description. 11 -> 0. The second rule in the file (zero-width space,
smart quotes, dashes) is deliberately NOT exempted: a smart quote in a
registered name is still a data-entry defect.

---

## 7. "Two or more axioms containing only 'is a'" fires on every extension override

`AxiomsContainOnlyIsaRelationship.drl` (`e77f303a-a954-477b-b73b-b05516cf3fc7`,
ERROR). All 16 findings on 20260930 are concepts with one IsA-only axiom in the
core module and one in the AU module: the extension republishing a concept adds
its own axiom beside the one it cannot edit, which is how an extension overrides
anything. The patch requires the two axioms to be in the same module
(`moduleId == r1.moduleId`). 16 -> 0, with or without `includedModules`; two
IsA-only axioms authored in one module are still an ERROR.

---

## 8. "The first letter of an FSN should be capitalized" cannot tell a locant from a typo

`FSNTermFormat.drl` (`8a74edc2-ebbe-4c59-992b-d80ea72a11ba`). 726 findings on
20260930; 679 on your descriptions. The 47 on ours are all terms that must
start lowercase: `d-alpha-tocopherol` 29, `dl-alpha-tocopheryl`/`-tocopherol`
15, `sec-Hexyl acetate`, `de Morton Mobility Index`, `cE blood group antibody
identification`. The patch exempts an FSN whose first token is

    a chemical / stereo locant prefix  d- l- dl- n- o- m- p- s- p,p'- sn- sec- tert-
                                       cis- trans- sym- meso- erythro- threo- scyllo-
                                       myo- para- ortho- meta- bis- alpha- beta-
                                       gamma- delta-  (the hyphen must join a name)
    a mixed-case token                 pH, pT, cE, cDE, iStent, mL/kg, eIF-5A
    a blood-group antigen sign         s+ phenotype, k- phenotype
    a proper-name particle + name      de Morton, von Willebrand, van ..., le ...

and nothing else: a plain lowercase word is still reported, hyphenated or not.
On your 679 that exempts 217 (`p-aminophenol`, `trans-Nonachlor`, `von
Willebrand`, `pH`, `alpha-Chlordane`, ...) and leaves 462 - gene names
(`coagulation factor X gene`), units (`gram`, `per 10 minutes`, `pounds
sterling`) and the like, which are the rule doing its job. Ours: 47 -> 0.

---

## What we have applied locally

`rules.patch` (8 rules), the reference data in `../semantic-tags.txt` and
`../semantic-tag-hierarchies.txt`, and, since item 2 is patched, no
`assertionExclusionList`. `../CASE-SIGNIFICANCE.md` has the full working behind
items 4 and 5.

The special-character check (item 3) has 12 findings left, and all 12 are
correct as content - nine AU `(qualifier value)` unit concepts of the `Tissue
culture infectious dose 50% unit` family, `Ethanol 90% (substance)`, and two of
your own metadata concepts naming the `European Directorate for the Quality of
Medicines & Healthcare`. Twelve readable findings is a better outcome than
blinding two more hierarchies to reach zero.

Where a patch changes what a rule reports, the `test-cases.json` beside it is
patched too - one case per behaviour, each of which fails against your rule and
passes against ours - so `DroolsRuleTestCasesTest` states the new behaviour
rather than the old. Nothing here is a fork we intend to carry;
`../../checkout-resources.sh` runs `apply.sh` after every rules clone, and
`apply.sh` refuses to apply a patch whose `.orig` no longer matches your file,
precisely because we would rather they lived upstream.

