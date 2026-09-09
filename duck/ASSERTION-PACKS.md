# Assertion packs: versioned assertion sets pulled in at runtime

A design note, not yet built. Written 2026-09-07 after the AMT assertions were
made to run by hand-staging a corpus and a store onto a shared volume - which
worked, and is not a mechanism anyone should have to repeat.

## What it would replace

Today an assertion set reaches the engine one of two ways:

* **baked into the image** - the international corpus, via jib
  (`/app/snomed-release-validation-assertions`) and the precompiled store as a
  classpath resource. Changing it means a rebuild and a redeploy.
* **staged onto a volume by hand** - what the AMT assertions currently do:
  654 scripts, a `groups.xml` declaring `amtv4` by category, and a 560-assertion
  `store.json`, with `rvf.assertion.resource.local.path` and `rvf.duck.store`
  pointed at them. Nothing reproduces it; if the volume were lost the pods would
  fail their hash check on restart.

Neither extends. A pack model gives one pattern for both, and for anything
else - a national extension, a project-specific rule set - without touching the
server.

## Why the engine can already almost do this

Nothing about the runtime assumes the image:

* the store is read from a **file path** (`rvf.duck.store`), and only falls back
  to the bundled classpath copy when that is unset;
* groups and policies are read from a **directory** (`rvf.assertion.resource.local.path`);
* `DuckAssertionService` holds the parsed store in a single lazily-populated
  `volatile` field, so a reload is a reference swap rather than a restart;
* `DuckStoreLocator` already verifies every assertion's recorded `sha256`
  against the corpus file of the same name and REFUSES a mismatched pair.

The AMT run proved the paths work: 200 assertions executed, 0 skipped, with no
image change and no chart change.

## What a pack has to contain, and why

The server cannot compile SQL. Transpilation is done by `publish_store.py` with
sqlglot in Python, at publish time, which is why the store exists at all. So a
pack is a **published artefact**, not a folder of scripts: it must carry the
compiled statements, and the sources beside them so the hash check still means
something.

    pack.json          name, version, formatVersion, generator, digest
    store-fragment     the pack's own assertions (uuid -> statements, sha256)
                       plus any macros and prerequisites it needs
    scripts/           the SQL those hashes are of
    groups.xml         the group rules it contributes

## The merge rules, taken from what the store actually holds

Comparing the 360-assertion base store against the 560-assertion AMT build:

| section | base | AMT | rule |
|---|---|---|---|
| `formatVersion`, `generator` | 1 | identical | **must match exactly** - a pack compiled by a different sqlglot is a different language |
| `sentinels`, `runIdSentinel`, `qaResultToken` | identical | identical | **must match exactly** - substitution contract |
| `knownTables`, `tableColumns` | 103 / 90 | identical | union; overlapping keys must be identical (DDL-derived) |
| `assertions` | 360 | 560 | additive; **uuid collision is an error** |
| `prerequisites` | 1 | 1 | union by file; same file must have the same hash |
| `ports` | 20 | **19** | union BY MACRO NAME; same name with a different body is an error |

That `ports` row is the wrinkle, and it is why this note exists rather than a
patch. Ports are DuckDB **macros emitted per publish**, not per corpus - the AMT
build has nineteen where the base has twenty, and carries its own
`get_cr_ADRS_PT` among them. So a pack cannot just append its ports; the merge
has to union by name and reject a redefinition, or one pack silently changes the
meaning of another pack's SQL.

## Configuration

    rvf.assertion.packs[0].url      https://github.com/aehrc/rvf/releases/download/amtv4-2026.09.1/amtv4-pack.tgz
    rvf.assertion.packs[0].digest   sha256:...
    rvf.assertion.packs[0].tokenRef a secret holding a PAT, for a private repo

Pinned versions and digests, never `latest`: a pack is executable SQL fetched
over the network, so this is remote code execution by design and the only
defence is that the operator named exactly what they trust. A private repo needs
a token, which is a secret reference rather than a value.

## Endpoints

    GET  /assertions/packs              installed packs, versions, digests, counts
    POST /assertions/packs/refresh      re-fetch the configured packs and swap
    POST /assertions/packs              upload a pack directly (air-gapped case)

Refresh must be atomic and must not disturb a running validation: stage, verify,
merge, then swap the reference. A run already holds its own snapshot, so an
in-flight validation keeps the assertions it started with.

## The part that must not be forgotten

**Provenance in the report.** A report currently records the corpus by
implication - the store was baked into the image, so the image tag identified
it. Once packs are pulled at runtime, "which 200 assertions produced this
report" becomes unanswerable unless the report says so. So a run must record
each pack's name, version and digest, the same way it records the release under
test. Without that the pack model trades a rebuild for a silent drift, which is
the same failure the store/corpus hash check exists to prevent.

**Whitelists key on assertion uuid.** The AMT ids are derived from the FILENAME,
so renaming a script retires one assertion and creates another. A pack's version
should therefore be allowed to change the assertions it contains, but an id must
mean the same check across versions - and a pack that renames files needs to say
so in its release notes, because whitelist entries will detach.

## What this generalises

The international corpus becomes a pack like any other, published by its own
pipeline rather than vendored into the image. So does any national extension.
The image then ships an engine and no assertions at all, which is the right
shape: the engine's version and the assertion set's version are different
questions and should not be answered by one tag.


## Implemented: the merge, 2026-09-08

`DuckStorePacks.merge(List<Pack>)` in
`src/main/java/org/ihtsdo/rvf/core/service/duck/`, with `DuckStorePacksTest`
pinning one rule per case - 13 of them, each a way for two packs to combine
into a store that runs and reports the wrong thing.

`DuckStore` gained the accessors the merge needs (`toJson`, `formatVersion`,
`runIdSentinel`, `qaResultToken`, `knownTables`, `transpilerVersion`), and the
merged store records every pack's name, version, digest and assertion count -
because once packs are fetched at runtime, that is the only thing that can
answer "which assertions produced this report".

**Proven against the two real stores.** Merging the committed 360-assertion
international store with the 560-assertion AMT build is REFUSED, with exactly
three conflicts:

    assertion 7e70ea3e... differs (component-centric-snapshot-complexmap-group.sql)
    assertion 6e70ea3e... differs (component-centric-snapshot-extendedmap-group.sql)
    assertion 6c37bee7... differs (release-type-snapshot-delta-...-refset.sql)

Those are precisely the three assertions the publisher fix changed today, so
the AMT build is a stale pack - and the merge says which assertions are stale
rather than producing a store that mixes two publisher versions. That is the
mechanism working on the first real pair it was given.

It did not object to 20 ports against 19: union by macro name, and the AMT
build defines nothing that contradicts the international prelude.

## Implemented: fetch, verify and swap, 2026-09-08

`AssertionPackFetcher` fetches each configured pack and checks its sha256
BEFORE parsing - not after, because parsing attacker-controlled JSON to find
out whether it is the right JSON has the order backwards. An unpinned pack is
refused when the source is built, so a deployment cannot start with one
configured and discover it on the first refresh. `file:` URLs are accepted
deliberately: it is how today's shared-volume layout feeds the engine and how
this is tested without a network.

`DuckAssertionService.reload` builds the whole corpus - merge, then
`DuckAssertionSource` - before publishing anything, and the swap itself is two
volatile writes. A validation already running holds its own reference and
finishes against the corpus it started with, which is what you want: a run that
changed assertion sets halfway would produce a report describing neither. The
bundled store is always the base, so a pack set cannot quietly replace the
international corpus.

Configuration is `rvf.assertion.packs`, one entry per pack:

    name=amtv4;version=2026.09.1;uri=https://...;sha256=<hex>;authHeader=<ref>

Semicolons because a URL contains commas far more often, and Spring splits list
properties on commas. The token is a header supplied by configuration and never
part of the URL, because a URL ends up in logs.

Endpoints: `GET /assertions/packs` reports name, version, digest and assertion
count per pack; `POST /assertions/packs/refresh` re-reads what is configured
and swaps. 409 with every conflict listed for a merge conflict - not a server
fault, two packs that cannot be combined - and 502 for a fetch or digest
failure. Both leave the corpus untouched.

`AssertionPackReloadTest` drives all of it against a real `HttpServer`, because
the failures that matter here are the ones a mock cannot show: a body that
arrives and is not what was pinned; a 404 from a repository that moved a
release asset; a pack that parses, verifies, and then redefines the base pack's
macro. In all three the previous corpus is still serving afterwards, which is
the invariant - the failure to design against is not an exception but an engine
serving a half-applied assertion set, because that reports a release as clean
for assertions it no longer holds.

## Proven with the real extension pack, 2026-09-08

An AMT-only pack was published from the 200 extension scripts and merged with
the bundled store:

    bundled store          360 assertions
    amtv4 pack             200 assertions, 217KB, sha256 eee2c389...
    merged                 560 assertions, 20 ports, no conflicts
    amtv4-keyworded        200 in the merged store
    provenance             both packs recorded, with the pack's digest

So the mechanism does what the hand-staged volume does today, with three
differences that are the entire point: the pack is a published artefact rather
than mutable state outside git, its digest is pinned so a run is reproducible,
and the report says which packs produced it.

## The base has an identity now, 2026-09-09

It reported as the literal `bundled`, with `bundled` for a version and for a
digest, so a report could say only that the assertions came from something
bundled — not which assertions — and nothing could be *required* of it:

    pack   international 2026.07.27 sha256:d6f0a930e8acd55f (corpus 0160dd2ee830)

Version is the corpus's own commit date, so a rebuild of unchanged inputs
produces the same identity, and dates are orderable where a sha is not — which
is what "requires at least" needs. The digest covers every assertion's source
hash and the prerequisites, and the engine **recomputes it on load**: a digest a
runtime repeats is a claim, one it recomputes is a fact. Editing either an
assertion's hash or the prerequisite's in the real 360-assertion store is
refused, naming both digests.

**The gap this closed was on the NORMAL deployment.** `loadedPacks()` is
configuration, so a deployment pinning no packs answered `[]` — the report and
`GET /assertions/packs` said nothing at all about the assertions that ran,
precisely where nothing else could. Both now read `DuckStore.provenance()`,
which is the merged pack list when there is one and the store's own identity
when there is not. One owner of the answer, and it is the artefact.

One ordering trap, since the endpoint hit it: `loadedProvenance()` reads what is
serving and never forces a load, so asking it before `findAll()` on a cold
server reports no provenance beside a count of 360.

**The pack itself is not committed here, and must not be.** This repository is
public; the AMT assertions are not. That is not a hypothetical constraint - the
SQL and the 200 assertion names were briefly committed here on 2026-09-07 and
had to be purged from history. The pack lives at `/data/work/amt-pack.json` on
the build host and belongs in `aehrc/rvf` beside the scripts it was built from.

### Publishing it, when someone decides to

    # in a checkout of aehrc/rvf, from the AMT scripts and manifest
    publish_store.py --scripts testscripts/scripts/amtv4 \
                     --manifest-root testscripts \
                     --prerequisites <rvf>/duck/prerequisites \
                     --ddl <rvf>/duck/create-tables-mysql.sql \
                     --no-derive-uuids --out amtv4-pack.json
    sha256sum amtv4-pack.json          # this is what gets pinned

Attach it to a release in that repository, then point a deployment at it:

    rvf.assertion.packs=name=amtv4;version=<tag>;uri=<asset url>;sha256=<hex>

and `POST /assertions/packs/refresh`. The corpus overlay on the shared volume
and the private-image-layering plan both stop being necessary at that point.

Not done here on purpose: publishing requires a branch on a private repository,
which is a decision rather than a step.


## The reload proves the corpus EXECUTES, 2026-09-08

Digest verification proves a pack is what was approved; the merge proves two
packs can be combined. Neither proves the combination runs. A pack's SQL can
call a macro its base defines, and the base can stop defining it - which is not
hypothetical, because a publisher change dropped `substring_index` and four
international assertions died with "Scalar Function with name substring_index
does not exist". Inside one store a test caught that. Across packs fetched at
runtime, nothing would have: the swap would succeed and the assertions would
fail hours later, in a validation, as findings that never appeared.

So `reload` applies the merged store to a throwaway in-memory DuckDB with EMPTY
tables in the shapes it declares, and executes every assertion. **560
assertions verify in 2.15s; the whole reload is 3.0s.** On any failure the swap
does not happen and the previous corpus keeps serving.

Three things it has to reproduce about production, each of which it got wrong
first and each of which is now commented where it matters:

* **`resource` assertions run FIRST**, as `DuckDbValidationService` runs them.
  They build the shared intermediate tables the rest select from, so in store
  order four real assertions fail on a table nothing had created.
* **All three release schemas exist** - prospective, previous, dependency. An
  assertion may mix statements that read the previous release with statements
  that do not; with no previous schema the binder skips the first kind and the
  rest fail on a temporary table the skipped statement would have built.
* **A skip is not a failure.** An assertion needing a release the check does
  not supply is skipped by the binder, exactly as production skips it when no
  previous release is configured.

What it deliberately does NOT catch is anything data-dependent. With no rows,
`mapGroup = ''` against a SMALLINT never evaluates its cast and passes here -
and that exact assertion errored in production for years. Empty tables answer
"is this corpus coherent"; a real release answers "is it correct", which is
`AssertionCorpusDigestTest`'s job against a committed fixture at build time.
Two questions, two checks: conflating them would make this one slow enough to
skip.

### A production behaviour this surfaced

An assertion that mixes previous-release-dependent statements with independent
ones does not cleanly skip when no previous release is supplied - it FAILS,
because the later statements reference a temporary table the skipped statement
would have created. `component-centric-snapshot-description-active-inactive-term-match.sql`
is one. Runs in this configuration report it as an execution error rather than
as not-run.

Still to build: publishing the AMT pack from its own repository.
