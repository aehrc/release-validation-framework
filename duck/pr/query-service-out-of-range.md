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

## What a reviewer should push back on

The term-dictionary subtraction is only equivalent because the field is a
single-valued id field, so "terms present" is exactly the domain. It would NOT
be equivalent for an analysed text field, and the code says so at the point it
matters.
