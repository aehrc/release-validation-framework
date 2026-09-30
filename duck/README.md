# The precompiled assertion store

`src/main/resources/duck/store.json` is the assertion corpus already transpiled
from MySQL to DuckDB. It is a **checked-in build output**, and the RVF artefact
ships it: with `rvf.execution.engine=duckdb` nothing needs to be mounted,
generated or configured for a validation to run.

Transpilation is the only part of the DuckDB engine that needs Python. sqlglot
parses each MySQL assertion, rewrites it and prints DuckDB; everything after
that is textual substitution of a run's values into sentinels, which `DuckBinder`
does in Java. Precompiling is what keeps the dialect work out of the server
entirely — the Java runtime never parses SQL, and the transpiled corpus becomes
a reviewable, diffable, version-stamped artefact rather than something
regenerated invisibly on every run.

## Why it is checked in rather than built

The publisher needs Python with pinned `duckdb` and `sqlglot`. Putting that in
the Maven build would make the image build two-language and, worse, would import
a specific hazard: `rvfsql.duckdb_statement` swallows `ImportError` and returns
the **untranspiled MySQL**, so a build on a machine without sqlglot produces a
store that looks fine and is silently wrong. A checked-in artefact is produced
once, deliberately, on a machine known to have the pinned versions.

The cost is that the store and the corpus are two inputs that must move
together. `DuckStoreLocator` and `BundledStoreMatchesCorpusTest` are what make
them: every assertion's source `sha256` is compared against the corpus on disk,
and a mismatch fails the build and refuses to run. Without that check a stale
store is undetectable — nothing at run time reads the corpus SQL any more, so a
run would execute the previous corpus's assertions and report them under the
current corpus's text, uuids and groups, producing a complete and plausible
report of the wrong thing.

## Republishing

Required whenever `ASSERTIONS_REF` in `checkout-resources.sh` moves, and
whenever a file under `assertions-au/scripts` changes (the AU patch set that
`checkout-resources.sh` overlays on the pinned clone; see
`assertions-au/README.md`). The build will tell you: `mvn test` fails in
`BundledStoreMatchesCorpusTest` naming the scripts that differ.

The publisher lives in `aehrc/rvf` under `duck/`. Build its pinned environment
first — this is not optional, see above:

    uv venv --python 3.12 /tmp/duckenv
    uv pip install --python /tmp/duckenv/bin/python duckdb==1.5.5 sqlglot==30.18.0 defusedxml

sqlglot is pinned to what `aehrc/rvf`'s `publish-pack.yml` uses; a pack built by
a different transpiler is refused at merge.

Then, from a checkout of `aehrc/rvf`:

    ./checkout-resources.sh          # in THIS repo: corpus at the pin, AU overlay applied

    cd <aehrc/rvf>/duck
    /tmp/duckenv/bin/python publish_store.py \
      --scripts        <this repo>/snomed-release-validation-assertions/scripts \
      --prerequisites  <this repo>/duck/prerequisites \
      --ddl            <this repo>/src/main/resources/sql/create-tables-mysql.sql \
      --manifest-root  <this repo>/snomed-release-validation-assertions \
      --no-derive-uuids \
      --pack-version   2026.09.30 \
      --out            <this repo>/src/main/resources/duck/store.json

    cd <this repo> && mvn -o test -Dtest=BundledStoreMatchesCorpusTest

Expect it to report `assertions 360 / statements 820`, a first line naming the
store's identity, and ~93 scripts listed as "not in manifest, skipped". Those
are corpus scripts no manifest entry declares, so RVF never runs them on either
engine. The publisher **refuses to write a store with zero assertions** — an
empty store reports no findings and therefore passes every validation.

    pack         international 2026.09.30 sha256:ce0bab23187f45f9 (corpus not a checkout)

The version is the **assertion corpus's own commit date**, not the build's: a
rebuild of unchanged inputs must produce the same identity, and dates are
orderable in a way a commit sha is not. The digest covers every assertion's
source hash *and* the prerequisites, because those build the tables each
assertion reads — so it answers "would this run the same SQL", and the engine
**recomputes** it on load rather than repeating it. A store edited after
publication is refused, naming both digests. Pass `--pack-version` when the
corpus is not a git checkout; the publisher refuses to invent one.

**Always pass `--pack-version` for this repository's store.** Left to derive
it, the publisher takes the clone's commit date - `2026.07.27` at this pin -
which the AU overlay does not change, so the patched store would claim the
same version as the unpatched one while running different SQL for 7
assertions. It is `2026.09.30`, the date of the AU patch set, and must stay
later than `2026.07.27`: the pack is ordered by version ("requires at least"),
so reusing or predating it would let the unpatched store count as the same or
newer pack. With an explicit version the identity line reads
`corpus not a checkout`, because no commit ref is recorded. Bump the version
again on the next change to `assertions-au/`.

## The two inputs that are not the corpus

Both are vendored here because the assertion corpus does not ship them:

* **`prerequisites/pre-requisites.sql`** — builds the `*_active` views and other
  setup tables the assertions select from. The corpus at `0160dd2` does not
  contain it and its `manifest.xml` does not reference it, so
  `AssertionsDatabaseImporter`'s pre-requisite branch never fires on the current
  pin; this copy came from `aehrc/rvf`'s `testscripts/pre_requisites`, which is
  itself a copy of an older corpus. Vendored so the store has one definite
  source rather than depending on a second checkout.
* **`create-tables-mysql.sql`** — the DDL the store's `tableColumns` and
  `knownTables` come from. `DuckMaterialiser` uses those to create an EMPTY
  placeholder for a table the release under test does not ship, which is what
  turns "no rows to be bad" into a pass instead of `Table with name ccsRefset_f
  does not exist`.

If either changes, republish. Neither is covered by the corpus hash check —
there is nothing on the corpus side to compare them against.

## Overriding the bundled store

`rvf.duck.store=/path/to/store.json` takes precedence, which is how a different
corpus gets tried without a rebuild. The corpus check still applies: point
`rvf.assertion.resource.local.path` at the matching corpus, or at nothing, in
which case the store loads unverified and says so at WARN.
