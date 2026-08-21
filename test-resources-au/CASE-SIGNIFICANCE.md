# Case significance in AMT, and what the cI rule is actually testing

## The three values, as the release itself names them

    900000000000017005  Entire term case sensitive                (CS)
    900000000000448009  Entire term case insensitive              (ci)
    900000000000020002  Only initial character case insensitive    (cI)

They are a scale of how much of the term a consumer may recase:

| value | first character | every other character |
|---|---|---|
| CS | must be preserved | must be preserved |
| cI | may be recased | **must be preserved** |
| ci | may be recased | may be recased |

cI reads as odd until you read it as "all-but-the-first is CS". It exists for
terms whose first letter is capitalised only because of its position and which
carry genuinely case-significant content later - `Vitamin B12 deficiency`,
`pH measurement`, `Paracetamol 500 mg tablet`. Marking cI when nothing after
position 1 is case-significant asserts a constraint that is not there; ci is the
accurate value. That is exactly what
`TermCaseSignificance.drl` / `4ee9cfeb-3ce5-48bf-b238-de7498fde042` tests, and
the test is legitimate.

Its flaw is the definition of "case-significant". The rule looks for an
**uppercase** letter after character 1, but lowercase can be case-significant
too: `mg` is not `MG`, `mL` is not `ML`. That is why the rule already carries an
`isDrugWithCaseSensitiveUnit` exemption - the exemption is the acknowledgement
that "no uppercase" does not mean "nothing to preserve".

## What is happening in the content

AU's usage is nothing like the international edition's. Active descriptions whose
term carries no uppercase letter after character 1:

    international   ci 1,004,704  89.5%     CS 107,185   9.5%     cI  11,236   1.0%
    AU                 CS 93,894  59.8%     cI  34,979  22.3%     ci  28,128  17.9%

It is not data-entry noise. Of the 4,296 concepts still reported after the rule
patches, **3,713 have every one of their descriptions marked cI** - a systematic
authoring default, not a scatter of mistakes.

## The 18,062 findings, fully split

| n | what it is | who is wrong |
|---|---|---|
| 11,876 | term carries a unit already on the rule's own list, suppressed only because `isDrugWithCaseSensitiveUnit` was gated on `"clinical drug".equals(tag)` | rule |
| 2,420 | term carries a case-significant unit the list omits - `microgram`, `cm`, `mm`, `kg`, `mmol` | rule (and for international content too) |
| 530 | term carries a unit but the concept's tag was outside the exemption - 452 of them `(physical object)` dressings measured in `10 cm x 10 cm` | rule |
| 850 | strength as a percentage only: `Clindamycin 2% cream` | AU, arguably |
| 2,916 | nothing after character 1 is case-significant: `Lotion`, `Heparinoids`, `Potassium chloride + glucose + sodium chloride` | AU |

The rule patch drops the tag gate entirely rather than widening it. The unit test
needs no gate: if a term carries a case-significant unit its case must be
preserved wherever in the model it sits, and any gate is another list to get
wrong - the `(physical object)` dressings are what a widened-but-still-gated
version missed.

## What AU should change

`../../rvf-local-runs/au-cI-to-review.tsv` - 3,766 descriptions, one row each,
classified:

    2,916  no-case-significant-character   cI -> ci
      850  percentage-only                 cI -> ci, if % alone is not held to be significant

Both are cI asserting a constraint the term does not carry. The second class is
listed separately because `%` has no case at all, so the argument for cI there
rests on the digits rather than on any letter - a policy call rather than a
straightforward correction.

## The larger over-specification nothing checks

AU marks **93,894** descriptions CS whose terms carry no uppercase letter after
character 1 - a stronger claim than the 34,979 cI ones, on three times as much
content. No rule in `common-authoring` tests CS this way, so it has never been
reported. If the cI findings are worth correcting, that population is worth
looking at first.
