# Drools on an AU release: what is content and what is not

Measured on the AU daily build 20260831, rule sets `common-authoring` +
`au-authoring`, engine = the upgraded RVF (snomed-drools 6.0.0, Drools 10.2.0,
Lucene 9.12.0).

    baseline                                                505,471 violations
    after AU semantic tags + hierarchy (test-resources-au)  218,106
    of which one rule                                       143,065
    after externalising that rule's exemption list           75,496
    that rule now                                               455
    after the administrative= hierarchy line                  75,418
    after the rule patches and one run-config exclusion       15,939
    loading SNAPSHOT only, as RVF does                         6,650   <- measured
    16 distinct rules

Each stage verified end to end in its own full run. The final 6,650 is
**6,634 WARNING and 16 ERROR**, over 15 warning assertions and 1 failing one.

RVF reports failed *assertions*, not findings: `constructValidationReport` sends
`Severity.WARNING` to `warningAssertions` and everything else to
`failedAssertions`. So the Drools contribution to `totalFailures` on this release
is **one assertion**, and 14 of its 16 findings are international content.

| rule | before | after | how |
|---|---|---|---|
| For each active FSN there is a synonym that has the same text | 143,065 | 455 | `fsn-synonym-exempt` |
| Term already exists within this hierarchy | 29,990 | 478 | `duplicate-term-exempt`, different-level pairs only |
| cI term should contain a capital after the first character | 18,062 | 3,766 | tag gate dropped, unit list extended |
| Two relationships with same type/target/group (ERROR) | 7,908 | 0 | probe artefact - load SNAPSHOT only, as RVF does |
| FSN must be represented in at least one dialect (ERROR) | 1,347 | 0 | same |
| Text definitions must be preferred in at least one dialect (ERROR) | 34 | 0 | same |
| Concept should not contain redundantly stated IsA | 6,524 | 287 | scoped to core modules |
| FSN contains &, %, $, @ or # | 5,147 | 12 | `fsn-special-char-exempt` |
| Relationship module differs from source concept | 4,299 | 0 | `assertionExclusionList` (run config, no rule change) |
| Semantic tag compatible with active parent(s) | 78 | 0 | `administrative=` hierarchy line |

Three of the fixes are worth reading twice, because the obvious version of each
was wrong:

* **duplicate term** exempts a pair only when the two concepts sit at DIFFERENT
  levels of the same product hierarchy. A tag-only guard was simpler and would
  have lost 38 real findings, 20 of them genuine `(clinical drug)` <->
  `(clinical drug)` duplicates.
* **redundant IsA** is scoped by MODULE, not by semantic tag. It encodes an
  international editorial policy that AMT and AU clinical content have not
  adopted, and that is not confined to the drug hierarchy - 6,237 of the 6,524
  are simply "in the AU extension module". The 287 left are inherited
  international content.
* **cI capitalisation** drops the tag gate entirely rather than widening it. A
  widened-but-still-gated version still missed 530 findings, 452 of them
  `(physical object)` dressings measured in `10 cm x 10 cm` - a wound dressing
  is not a drug, but `cm` is still `cm`. See `CASE-SIGNIFICANCE.md`.

The 3,766 cI findings that remain are genuine AU content, listed one row each in
`../../rvf-local-runs/au-cI-to-review.tsv`.

## Fixed by reference data (see README.md)

| rule | before | after |
|---|---|---|
| Active FSN should end with a valid semantic tag | 143,684 | 0 |
| A concept's semantic tag should be compatible with those of the active parent(s) | 143,761 | 0 |

## Not fixable by reference data alone: 143,065

`For each active FSN there is a synonym that has the same text.`
(`common-authoring/terms/fsn-term-having-a-same-synonym-term/FsnTermHavingASameSynonynTerm.drl`,
assertion `65adcfee-cca5-4e99-8441-acc53344a140`)

The rule carries a hardcoded exemption list:

    DescriptionHelper.getTag(term) not in (
      "product", "medicinal product", "medicinal product form", "clinical drug",
      "substance", "product name", "packaged clinical drug", "real clinical drug",
      "real medicinal product", "real packaged clinical drug", "supplier")

Those are the INTERNATIONAL drug-model tags, exempted because a drug FSN
legitimately has no synonym identical to its FSN-minus-tag. AMT models the same
levels under different names, and none of them appear in the list:

    FSN tag                                       concepts with no matching synonym
    containerized branded clinical drug package                              51,187
    branded clinical drug package                                            48,396
    branded clinical drug                                                    24,350
    clinical drug package                                                    16,794
                                                                            -------
                                                                            140,727

Against 143,065 reported. The international equivalents ARE exempt, and are
present in the same release at similar scale (clinical drug 14,951, medicinal
product 10,344, medicinal product form 7,760) - all correctly silent. So this is
the same defect class as the other two: the check is not tag-model-agnostic, and
an extension that names its levels differently is reported as broken content.

### Why this is not fixed here

It is a rule, not reference data, so fixing it means changing
snomed-drools-rules - which we pin by commit and do not fork. Three options, in
order of preference:

1. **Upstream** - add the AMT tag names to the exemption list, or better, source
   the list from the test resources so an extension can supply its own. This is
   a genuine upstream gap: the rule assumes the international drug model.
2. **Exclude the assertion for AU** via RVF's `assertionExclusionList`
   (`65adcfee-cca5-4e99-8441-acc53344a140`). Cheap and needs no fork, but it
   turns the rule off for ALL AU content, including the non-drug concepts where
   it is working correctly.
3. Fork the rules repo. Rejected - it adds a second thing to keep in sync with
   upstream, for one line.

Until one of those lands, this rule's output on an AU release is ~99% false
positive and should not be read as content.

## The remaining 75,496, triaged

Counting by raw message hides most of this: the messages interpolate concept
ids, `|Fully specified name|` text and `Type_<sctid>` tokens, so one rule splits
into thousands of one-row buckets. An earlier top-10 accounted for 26,706 of
75,496 and made the rest look absent rather than merely unaggregated. Normalised
(`rvf-local-runs/triage-drools.py`), the whole 218,106 resolves to **30 rules**.

Two discriminators do nearly all the work:

* **the FSN semantic tag of the concept the rule fired on** - every model
  mismatch found so far is a rule that names international drug-model tags in a
  literal and therefore misfires on the AMT tags that mean the same thing;
* **the module of the concept** - 5% of all findings are on international-module
  concepts, but for some rules it is 100%. Those are not AU content at all.

| rule | n | what it actually is |
|---|---|---|
| For each active FSN there is a synonym that has the same text | 143,065 | model mismatch. **Fixed** above: 455 remain |
| Term already exists within this hierarchy | 29,990 | 29,480 (98%) are AMT package-level pairs - TPP/CTPP/TPUU sharing one preferred term by design. Every flagged description is a SYNONYM, not an FSN: the FSNs differ by tag, the preferred terms are deliberately identical. 510 same-tag residue |
| cI term should contain a capital after the first character | 18,062 | 11,876 (66%) carry a case-sensitive unit (`mg`, `mL`, `IU`...) but sit at an AMT tag. The rule's `isDrugWithCaseSensitiveUnit` exemption is gated on `"clinical drug".equals(semanticTag)` - same defect class, same one-tag-literal cause. 6,186 residue |
| Two relationships with same type, target and group (ERROR) | 7,908 | none of it is content - a loader artefact, see below |
| Redundantly stated IsA | 6,524 | 5,705 name an international drug top-parent (Medicinal product package, Medicinal product, Drug-device combination product) - AMT states both an international and an AMT parent. A content decision, not a rule bug. 819 other |
| FSN contains &, %, $, @ or # | 5,147 | 3,613 contain `&` and 1,594 `%` - ARTG-registered product names (`Bausch & Lomb`, `Naphcon-A 0.025%`). Not AU-fixable: the term is the registered name |
| Inferred relationship has a different module | 3,141 | 2,996 are an AU-module relationship on an international-module concept - which is what an extension IS. Written for a monolithic edition |
| Stated relationship has a different module | 1,158 | 1,102 the same |
| FSN must be in at least one dialect (ERROR) | 1,347 | 949 (70%) international content |
| First letter of FSN should be capitalized | 725 | 678 (93%) international content |
| Top parents both (product) and (physical object) | 391 | 374 AU, mostly (branded product). Needs content review |
| Terms sharing first word should share case significance | 299 | 283 (94%) international content |
| FSN should follow SEP naming conventions | 147 | 147 (100%) international content |
| Semantic tag compatible with active parent(s) | 78 | reference-data gap. **Fixed** below |
| FSN should not start with open parentheses | 55 | 55 (100%) international content |
| Text definitions must be in at least one dialect (ERROR) | 34 | 34 (100%) international content |
| 13 further rules | 35 | mostly singletons |

### The 195 module mismatches that go the other way

4,098 of the 4,299 module findings are AU relationships on international
concepts and are by design. The other 195 are the reverse - an
**international-module relationship on an AU-module concept** - which should not
happen and is the one part of that rule's output worth reading.

### 9,289 of the 9,305 ERRORs were mine, not RVF's

Handing snomedboot a full unpacked release makes it load `SNAPSHOT_AND_DELTA`,
so every component changed this release is read twice under the same id - once
from the Snapshot, once from the Delta. Three rules match on exactly that:

| rule | findings | population read twice | overlap |
|---|---|---|---|
| Two relationships with same type/target/group | 7,908 | 1,782 concepts with an axiom active in both files | 1,782, zero residue |
| An FSN must be represented in at least one dialect | 1,347 | 1,347 active FSNs in the Delta | 1,347, zero residue |
| Text definitions must be preferred in at least one dialect | 34 | 34 active text definitions in the Delta | 34, zero residue |

Worked example: concept 75968004 has one axiom, present once in each file. All
22 of its relationships are reported, each exactly once, though no two of them
share a type/target/group. And from the Snapshot files alone, the number of
active FSNs with no PREFERRED language-refset row is **zero** - all 1,347 have
one; the duplicate instance is the one without an acceptability map.

**RVF does not have this problem.**
`DroolsRulesValidationService.extractFiles` unzips with
`ReleaseImporter.ImportType.SNAPSHOT`, so production only ever loads the
Snapshot. The 9,289 were an artefact of this probe pointing at the full bundle.
Re-running against a Snapshot-only view: 6,650 findings, **WARNING count
byte-identical at 6,634**, ERROR 9,305 -> 16. `run-drools.sh` now builds and
uses that view by default.

The underlying snomedboot behaviour is still real for anything that IS given a
full bundle - the standalone `snomed-drools-rf2-validator` CLI, for one - so it
stays in the upstream feedback, but as a caveat rather than an RVF defect.

### The 16 ERRORs that are real

`An active concept must not have two or more axioms containing only IsA
relationships` - 16 concepts, 14 of them international-module, and none of them
has an axiom duplicated across files. A genuine finding: two separate IsA-only
axioms on one concept should be one axiom.

### Why so much international content appears at all

RVF runs Drools over the **whole release snapshot**; the authoring platform runs
the same rules over a **change set**. So rules that have never been applied
retrospectively surface their entire historical backlog on the first full-snapshot
run, and it lands in an AU report even though AU authored none of it. That is not
a defect in either system - but it does mean a raw Drools count is not a measure
of AU content quality, and the module column has to be read before the number is.

### Second reference-data gap, fixed

`A concept's semantic tag should be compatible with those of the active parent(s)`
still fired 78 times after the AU tags were added. All 78 resolve to one
top-level hierarchy that the international file has no line for:

    32570731000036101 |Administrative value (administrative)|   77 concepts
    900000000000441003 |SNOMED CT Model Component (metadata)|    1 concept

`administrative` is already a recognised tag in `semantic-tags.txt`, but
`isSemanticTagCompatibleWithinHierarchy` walks `semantic-tag-hierarchies.txt`,
where it appeared under no key at all. Two lines fix it:

    administrative=administrative
    metadata=...,reference set        (appended)

## What is left that is genuinely AU content

Everything above that is neither a tag-model mismatch, nor an
extension-by-design module difference, nor inherited international content:

    ~6,186  cI terms with no capital and no unit                needs a case-significance review
    ~5,705  AMT dual-parenting to an international top parent   a modelling decision, not a bug
    ~5,147  &/% in ARTG-derived product names                   not fixable at our end
    ~2,893  duplicate stated relationships on AU concepts       genuine, worth a defect list
      ~819  other redundant IsA
      ~510  duplicate terms at the same semantic tag            genuine
      ~455  FSNs with no matching synonym, non-drug             genuine
      ~398  FSNs not preferred in any dialect                   genuine, ERROR severity
      ~374  concepts under both (product) and (physical object)
      ~195  international-module relationships on AU concepts

The only ERROR-severity items in that list are the duplicate relationships and
the dialect findings; everything else is WARNING.

---

# Externalising the FSN-synonym exemption list

**It is possible with no engine change**, reusing machinery that already exists.

`DroolsDescriptionService.isSemanticTagCompatibleWithinHierarchy(term, tags)` is
already a global available to every rule, and its implementation is simply:

    tag = getTag(term)
    for topLevel in tags:
        if semanticHierarchyMap.get(topLevel).contains(tag): return true
    return false

`semanticHierarchyMap` is `semantic-tag-hierarchies.txt`, which is already an
external, per-edition file. So an arbitrary named list of tags can be put in that
file under its own key and queried from a rule - no new resource file, no new
loader, no change to snomed-drools-engine.

## The change

`semantic-tag-hierarchies.txt` gains one line:

    fsn-synonym-exempt=product,medicinal product,medicinal product form,clinical drug,substance,product name,packaged clinical drug,real clinical drug,real medicinal product,real packaged clinical drug,supplier,<extension tags>

`FsnTermHavingASameSynonynTerm.drl` replaces the hardcoded literal:

    && DescriptionHelper.getTag(term) not in ("product", "medicinal product", ...)

with:

    && !descriptionService.isSemanticTagCompatibleWithinHierarchy(
           term, new HashSet(Arrays.asList("fsn-synonym-exempt")))

plus `import java.util.Arrays` / `java.util.HashSet` and the
`descriptionService` global, which this rule did not previously declare.

## Why a new key rather than reusing `product=`

The obvious move is to ask "is this tag under the product hierarchy?", since the
exemption list looks like the drug model. It is not equivalent, and the
difference is not academic:

    in the exemption list, NOT under product=   substance, product name, supplier
    under product=, NOT in the exemption list   physical object, medicinal product precisely

Reusing `product=` would silently stop checking every `(physical object)`
concept and start checking every `(substance)` one. A dedicated key keeps the
rule's semantics exactly as they are today for the international edition, and
lets an extension add its own names.

## Measured effect

    For each active FSN there is a synonym that has the same text
        before   143,065
        after        455    (0.3% - the remainder are genuine, i.e. non-drug)
    total findings
        before   218,106
        after     75,496

No other rule's count moves: the change is confined to one rule's guard, so the
remaining-75k triage above is valid whether or not the patch is applied.

## Status

Implemented and compiling on this branch as a PROOF, not as a fork we intend to
carry - `checkout-resources.sh` clones the rules at a pinned commit, so this
patch is applied on top locally and is lost on every re-clone. It HAS been lost
once already, silently, between two runs. Artefacts in `upstream-proposal/`:

    FsnTermHavingASameSynonynTerm.drl.orig      pristine, at the pinned commit
    FsnTermHavingASameSynonynTerm.drl.patched   the proposed rule
    rule.patch                                  unified diff between the two
    apply.sh                                    re-applies it after any re-clone

Run `apply.sh` after `checkout-resources.sh`, or the next run silently reverts to
143,065 and looks like a regression in the reference data. The right home is
upstream in snomed-drools-rules.
