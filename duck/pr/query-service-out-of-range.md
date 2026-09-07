# Answer an out-of-range clause with one TermInSetQuery

**Repo:** IHTSDO/snomed-query-service · **base:** `develop` · **files:** `ExpressionConstraintToLuceneConverter`, `SnomedQueryService`

## What is slow today

An ECL clause that excludes a set - `<< 123 MINUS << 456`, and every
`attributeName != value` in an MRCM range - is rendered as the *complement* of
the set: one exclusive `TermRangeQuery` per member, chained. The classic query
parser then compiles an automaton per range clause.

Measured on an AU release through `mrcm-validator`:

| | |
|---|---|
| `TermRangeQuery` instances constructed | **4,004,000** |
| peak retained by their automaton transition tables | **10.94 GB** |
| phase time spent parsing query text | 12.9% |

The heap figure is the one that matters: it is not the concept map or the index,
it is `sun.misc`-adjacent `int[][]` inside Lucene's `Automaton`, live only
because the range chain is still being built.

## What this changes

The converter emits a complement sentinel, and the service turns it into a
single `TermInSetQuery` over the terms actually present in that field, computed
by subtracting the excluded set from the field's term dictionary. One query, one
automaton, no chain.

`conceptsWithAnyAncestor(Collection<String>)` is added alongside it - the same
`TermInSetQuery` shape over `ANCESTOR` - because the per-concept ECL loop it
replaces is the other half of this cost. IHTSDO/release-mrcm-validator uses it
(see the companion PR).

## Evidence it is equivalent

* **134 real out-of-range expressions** taken from an AU MRCM run, both forms,
  **identical result sets** - not identical counts, identical concept ids.
* `-Dsqs.notin.rangeform=true` restores the old rendering, so the two can be
  compared on any release without rebuilding, and a site that hits a difference
  has an escape hatch rather than a rollback.
* Serial timing on the same 134: **724.0s -> 170.6s (4.2x)**.
* Upstream's own suite on `develop` with this applied: **125 tests, 0 failures**
  (including the 58-case `ExamplesExpressionConstraintToLuceneConverterTest`,
  updated here where the expected Lucene string changes).

## The second half: reading ids at scale

`conceptsWithAnyAncestor` returns the whole domain - hundreds of thousands of
ids - and reading each through a stored field is the cost that would replace
the one this removes. So the concept id is also written as a
`NumericDocValuesField` and read back per segment, which is what makes the new
query shape worth having rather than merely different. It is in the same PR
because the query is not usable without it, not because they are the same idea.

Index format: the speed arrives with an index rebuild.
`ReleaseImportManager` handles the field being absent, so an existing index
still opens.

## What a reviewer should push back on

**The term-dictionary subtraction** is only equivalent because the field is a
single-valued id field, so "terms present" is exactly the domain. It would NOT
be equivalent for an analysed text field, and the code says so at the point it
matters.

**The sentinel/ThreadLocal handoff is the ugly part.** The converter cannot
build a `TermInSetQuery` itself - it emits Lucene query *text* - so it writes a
`__notinset__` token and parks the excluded terms in a `ThreadLocal` the
service drains when it sees the token. That is hidden coupling between two
classes, and it leaks if the token is ever emitted without being consumed.

It is done this way because the alternative is changing the converter's return
type from `String` to a query object, which touches every caller and all 83
converter tests. If you would rather have that, say so and I will do it: the
measurement does not depend on this detail, and I would rather carry a bigger
diff than invisible state.

**Instrumentation.** `termSetQueriesBuilt()` counts the new queries so a run can
prove which path it took. Two of my own parity results in this area were wrong
because both arms ran the old code and agreed; a counter is how that stops
being possible. Easy to drop if you would rather not have counters here.
