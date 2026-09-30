# AU Drools test resources

The canonical bucket (`validation-resources.ihtsdo`) publishes only
`prod/international`. There is no `prod/au`, so validating an AMT release
against it reports AMT's own semantic tags as invalid content.

These two files are the international ones plus AMT's additions. Everything else
in the resource set is unchanged and is fetched from the bucket by
`fetch-test-resources.sh`. `../checkout-resources.sh` assembles the whole set -
the international files, then these two over them - into `../test-resources/`,
which jib bakes into the image as `/app/test-resources` (see "How the image
uses it" below).

## What was added, and why it is not guesswork

**`semantic-tags.txt`** — 13 tags. 12 are taken from snomio's
`api/src/main/resources/default-model-config.yaml` under `MAIN_SNOMEDCT-AU`,
which is the authoritative definition of the AMT model levels (MP, MPUU, TPUU,
MPP, TPP, CTPP, TP) and their `medicine`/`device`/`drugDevice` semantic tags.
The 13th is `reference set`, the tag of AU's refset concepts
(`281000036105 |Substance to SNOMED CT-AU mapping reference set (reference
set)|`); it was on the `metadata=` line of the hierarchy file but missing here,
so the FSN-tag rule reported it.

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

**`semantic-tag-hierarchies.txt`** — the 12 AMT model-level tags added to the `product=`
and `physical object=` lines, plus `administrative=administrative` and
`reference set` on `metadata=` (AU has a top-level hierarchy,
`32570731000036101 |Administrative value (administrative)|`, that the
international file has no line for), plus two keys that are not hierarchies at
all - see below.

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

## The two exemption keys

`semantic-tag-hierarchies.txt` is loaded into a plain `Map<String, Set<String>>`
and queried by `isSemanticTagCompatibleWithinHierarchy(term, keys)`, which is a
rule-visible global. Nothing requires a key to name a real hierarchy. So a rule
that today hardcodes a list of drug-model tags can instead ask this file, and an
extension can supply its own level names without forking the rules:

    fsn-synonym-exempt              FsnTermHavingASameSynonynTerm
    duplicate-term-exempt           TermUniqueInHierarchy

Each defaults to the tags already in the rule's literal, so the international
edition's behaviour is unchanged. The matching rule patches are in
`upstream-proposal/`, applied by `checkout-resources.sh` after every rules
clone; they are a proposal, not a fork we intend to carry. The other patched
rules need no key: the special-character rules test the concept's hierarchy,
redundant IsA tests the module, and case significance tests the term.

## Effect, on the AU daily build 20260831

    Active FSN should end with a valid semantic tag        143,684 -> 0
    Concept's semantic tag compatible with parent(s)       143,761 -> 0
    total rule violations                                  505,471 -> 218,106
    ...with the exemption keys and the rule patches        218,106 -> 16,736

On the 20260930 daily build, with every rule patch in `upstream-proposal/` and
`includedModules=32506021000036107,351000168100`, Drools goes from 498,503
findings to 4,747, measured with the engine RVF deploys (snomed-drools 6.1.3).

## Where this should really live

A `prod/au` path in the same bucket. RVF already selects the resource set with
`test-resources.cloud.path`, so that needs no engine change at all - which is
the reason to do it there rather than carry these files in the repo.

## How the image uses it

The engine reads test resources from ONE place - the bucket or a local
directory, never a merge - so the local directory has to carry the whole set.
The deployment selects it with two environment variables on both the API and
the worker:

    TESTRESOURCES_USECLOUD=false
    TESTRESOURCES_LOCAL_PATH=test-resources/

The path is relative to the container's working directory, `/app`. It must not
be written `/app/test-resources/`: the resource manager strips one leading `/`
from a local path (`ResourceConfiguration.normalisePath`), so that value resolves
to `/app/app/test-resources/`, the listing returns nothing, and the Drools phase
stops with a NullPointerException before any rule runs.
