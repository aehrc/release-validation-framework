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

Required whenever `ASSERTIONS_REF` in `checkout-resources.sh` moves. The build
will tell you: `mvn test` fails in `BundledStoreMatchesCorpusTest` naming the
scripts that differ.

The publisher lives in `aehrc/rvf` under `duck/`. Build its pinned environment
first — this is not optional, see above:

    uv venv --python 3.12 /tmp/duckenv
    uv pip install --python /tmp/duckenv/bin/python duckdb==1.5.5 sqlglot==30.15.0 defusedxml

Then, from a checkout of `aehrc/rvf`:

    ./checkout-resources.sh          # in THIS repo, so the corpus is at the pin

    cd <aehrc/rvf>/duck
    /tmp/duckenv/bin/python publish_store.py \
      --scripts        <this repo>/snomed-release-validation-assertions/scripts \
      --prerequisites  <this repo>/duck/prerequisites \
      --ddl            <this repo>/duck/create-tables-mysql.sql \
      --manifest-root  <this repo>/snomed-release-validation-assertions \
      --no-derive-uuids \
      --out            <this repo>/src/main/resources/duck/store.json

    cd <this repo> && mvn -o test -Dtest=BundledStoreMatchesCorpusTest

Expect it to report `assertions 360 / statements 819`, and to list ~93 scripts
as "not in manifest, skipped". Those are corpus scripts no manifest entry
declares, so RVF never runs them on either engine. The publisher **refuses to
write a store with zero assertions** — an empty store reports no findings and
therefore passes every validation.

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
