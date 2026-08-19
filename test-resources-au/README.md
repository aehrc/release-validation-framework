# AU Drools test resources

The canonical bucket (`validation-resources.ihtsdo`) publishes only
`prod/international`. There is no `prod/au`, so validating an AMT release
against it reports AMT's own semantic tags as invalid content.

These two files are the international ones plus AMT's additions. Everything else
in the resource set is unchanged and should still be fetched from the bucket by
`fetch-test-resources.sh`.

## What was added, and why it is not guesswork

**`semantic-tags.txt`** — 12 tags, taken from snomio's
`api/src/main/resources/default-model-config.yaml` under `MAIN_SNOMEDCT-AU`,
which is the authoritative definition of the AMT model levels (MP, MPUU, TPUU,
MPP, TPP, CTPP, TP) and their `medicine`/`device`/`drugDevice` semantic tags.

An earlier attempt derived the list from the release itself. It was reverted:
deriving from the content is circular (a malformed tag blesses itself), and it
does not work anyway - taking every trailing parenthetical from an active FSN
yields 182 candidates of which 48 are plainly CTV3 legacy text
(`& [abdominal wall] or [back]`) and many of the rest are too
(`ca ovary/uterine adnexa nos`). No filter separates those from
`branded clinical drug` by shape.

Note the model config lists `medicinal product package`, but no FSN in the
release uses it - AMT's MPP tag is `clinical drug package`. It is deliberately
NOT added: a tag nothing uses cannot prevent a false positive, and would hide a
real one if the level were ever renamed.

**`semantic-tag-hierarchies.txt`** — the same 12 tags added to the `product=`
and `physical object=` lines.

The format is NOT a parent/child chain. `FSNSemanticTagAgainstParent.drl` calls
`isSemanticTagCompatibleWithinHierarchy(term, getTags(topLevelFSNs))` where
`topLevelFSNs` comes from `findTopLevelHierarchiesOfConcept` - the ROOT
hierarchy, not the immediate parent. So each line is
`top-level tag = every tag permitted anywhere beneath it`, flat. The AMT
concept hierarchy (MP <- MPUU <- TPUU) is real but is not what this file
encodes.

Which top-level each tag belongs under was MEASURED from the release, by
transitive closure over active `is a` relationships, rather than inferred from
the config's column names:

    tag                                          product   physical object
    branded clinical drug package                 48,055                 0
    containerized branded clinical drug package   50,846                 0
    clinical drug package                         17,027                 0
    branded physical object package                    0               415
    containerized branded physical object package      0               415
    branded product                                  272               272   <- genuinely dual
    branded clinical drug                         24,693                11   <- 11 drug-devices

`branded product` is dual, and 11 `branded clinical drug` concepts sit under
`physical object` as well. Both are covered. A config-based guess would have
missed the 11 and left them as false positives.

The international model already contains AMT's levels under different names -
`real clinical drug` is TPUU, `packaged clinical drug` is MPP,
`real packaged clinical drug` is TPP. Only CTPP has no international equivalent.

## Effect, on the AU daily build 20260831

    Active FSN should end with a valid semantic tag        143,684 -> 0
    Concept's semantic tag compatible with parent(s)       143,761 -> 0
    total rule violations                                  505,471 -> 218,106

## Where this should really live

A `prod/au` path in the same bucket. RVF already selects the resource set with
`test-resources.cloud.path`, so that needs no engine change at all - which is
the reason to do it there rather than carry these files in the repo.
