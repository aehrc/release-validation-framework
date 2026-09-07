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

Still to build: fetch by pinned digest, the atomic reload endpoint, and
recording the pack list in the validation report.
