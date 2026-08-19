# Drools on an AU release: what is content and what is not

Measured on the AU daily build 20260831, rule sets `common-authoring` +
`au-authoring`, engine = the upgraded RVF (snomed-drools 6.0.0, Drools 10.2.0,
Lucene 9.12.0).

    baseline                                               505,471 violations
    after AU semantic tags + hierarchy (test-resources-au) 218,106
    of which one rule                                      143,065

## Fixed by reference data (see README.md)

| rule | before | after |
|---|---|---|
| Active FSN should end with a valid semantic tag | 143,684 | 0 |
| A concept's semantic tag should be compatible with those of the active parent(s) | 143,761 | 0 |

## NOT fixable by reference data: 143,065

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

## Remaining after the reference-data fix, excluding the above

    18,062  active term, case significance 'only initial character case insensitive'
     5,147  active FSN contains one of & % $ @ #
     1,347  FSN must be represented in at least one dialect
       725  first letter of an active FSN should be capitalized
       391  active concept has top level parents that are both (product) and (physical object)
       299  active terms sharing first word with case-sensitive term should share case significance
       147  active FSN should follow SEP naming conventions

~75,000 total across all remaining rules. NOT yet triaged - some will be real AU
content findings and some will be further model mismatches of the same kind.
Nothing here should be treated as a defect list until each is checked the way
the three above were.
