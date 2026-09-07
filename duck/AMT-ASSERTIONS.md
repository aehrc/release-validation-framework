# The AMT assertions, and why they are not wired in here

Established 2026-09-07.

## They do not run in the nightly

Build 16226's report holds 1282 assertion records - `MRCM 979`, `SQL 225`,
`DROOL_RULES 78` - and **zero** AMT assertions. The 225 SQL are international.

`ADRS ...` failures visible in the console come from a different run (a UI
submission against an AU package), not from the nightly.

Two things that look like they say otherwise, and do not:

* **`rf2-scripts` is not an AMT group.** It is the manifest `useCase` id, and it
  carries the same UUID in both the international corpus manifest and the AMT
  one because the latter was copied from the former - which is also why they
  share most of their entries.
* **`AustralianEdition` is not the AMT set.** It is a composition of the
  international categories, structurally identical to `DutchEdition`, which is
  why the server reports it as 200 assertions that are not the AMT 200.

## Why the wiring does not live in this repository

The AMT assertions live in **`aehrc/rvf`, which is private**. This repository is
**public**. Folding them in here would mean either publishing their SQL and
their names, or making a public repository's build depend on a private one so
that nobody outside AEHRC could reproduce it.

Decided 2026-09-07: they stay in their own repository. They may become public
one day, and that is a separate decision to make deliberately rather than as a
side effect of a build convenience.

An attempt at the fold-in was made and reverted. What it proved is worth
keeping, because it is what the work in `aehrc/rvf` needs:

* Their identities are **deterministic**. The chart derives each assertion's id
  by hashing the group, category and filename at Helm render time, so nothing
  outside a render knows they exist - but the derivation reproduces exactly,
  verified against `helm template` on all 200, and against an independent
  implementation already present in the DuckDB publisher.
* **Ids are a function of the FILENAME.** Renaming an assertion retires one and
  creates another, orphaning whitelist entries keyed on the old id and returning
  suppressed failures as apparent regressions. Nothing detects this today
  because the directory is simply re-globbed on each render. A committed
  id ledger, diffed on every build, is what makes a rename visible in review.
* **Group membership can be a rule, not a list.** RVF resolves a group by
  matching `includeStandaloneCategories` against an assertion's keywords, and an
  assertion's keywords come from its manifest category - so one group entry
  covers the whole category and a new assertion needs no edit to `groups.xml`.
* **Placement is dictated by the importer**, which resolves a script as
  `<sqlFileDir>/<category>/<sqlFile>`.
* **The engine can already run them.** The DuckDB publisher carries ports of the
  AMT prerequisite functions and the transitive-closure procedure that most of
  those assertions call, and the AU corpus has been through it - 518 statements
  executed, 0 failed. Scanning the 200 for constructs the duck path cannot take
  as-is: 154 clean, 43 using `REGEXP`/`RLIKE`, 4 with backtick identifiers, 4
  calling a procedure. The first two are transpilation, and the mechanism for
  the last exists.
* `pre_requisites/pre-requisites.sql` there is byte-identical to
  `duck/prerequisites/pre-requisites.sql` here, which is why the DuckDB path
  already consumes them.

The tooling written for the fold-in - id derivation, the ledger and its gate,
and the corpus splice - was moved to `aehrc/rvf`, where the assertions are.

## What this repository keeps

Only the part that is engine-side and carries no AMT content:

`BundledStoreMatchesCorpusTest` now checks BOTH directions. It used to verify
only the assertions the store already held - that each carried a source hash and
that there were more than 300 - so it could not notice assertions the corpus
declared and the store lacked. Adding 200 to the corpus left it green while none
of them existed to the engine.

That is the dangerous shape of failure here: a missing assertion does not error,
it never runs, and a report with a whole category absent still reads as a
healthy green run. So the store now has to report its own denominator, against
`duck/known-store-omissions.json` - which ships empty, and should stay that way.

## Still open

* Wiring the AMT assertions into a nightly, in `aehrc/rvf`.
* Per-assertion parity for them, especially the 43 `REGEXP` transpilations: a
  regex that silently matches nothing yields zero findings and reads as a pass.
* Two sources of truth for their group membership - a declarative group versus
  the legacy job's runtime `POST /groups/{id}/assertions` - whichever way it is
  wired, one of them has to go.
