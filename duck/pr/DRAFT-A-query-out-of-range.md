# PR A — snomed-query-service

**Title:** `Answer an out-of-range ECL clause with one TermInSetQuery`

**Base:** `develop` · **Branch:** `pr/a-query-semantics` · **+408/-16, 7 files** · upstream suite **125 pass, 0 fail**

---

An ECL clause that excludes a set — `<< 123 MINUS << 456`, and every
`attributeName != value` in an MRCM range — is currently rendered as the
complement of that set: one exclusive `TermRangeQuery` per member, chained. The
query parser compiles an automaton per range clause.

Measured on an AU release through `mrcm-validator`:

| | before |
|---|---|
| `TermRangeQuery` instances constructed | 4,004,000 |
| peak retained by their automaton transition tables | 10.94 GB |
| serial time, 134 attribute ranges | 724.0s |

The heap figure is the cost that matters: `int[][]` inside Lucene's `Automaton`,
live only while the range chain is built.

This builds a single `TermInSetQuery` from the excluded ids instead.

| | after |
|---|---|
| serial time, same 134 ranges | 170.6s (**4.2x**) |
| results | identical on all 134 |

Two supporting changes, both required by the above:

- `conceptsWithAnyAncestor(ids)` — one term-set query over the ancestor field.
  Used by PR C in `release-mrcm-validator`.
- Concept id as `NumericDocValues`. Queries return hundreds of thousands of hits
  and only want the id back; reading it from doc values avoids decompressing a
  stored-fields block per hit. 24.7µs per hit over a 3.9M-hit corpus.

## Two things to push back on

**A `ThreadLocal` handoff.** `ExpressionConstraintToLuceneConverter` returns
query *text*, so it cannot construct a `TermInSetQuery`. It emits a
`__notinset__` token and parks the terms in a `ThreadLocal` that
`SnomedQueryService` drains. That is hidden state and it leaks if the token is
ever emitted without being consumed.

The alternative is changing the converter's return type from `String` to a query
object. That touches every caller and all 83 converter tests. I will carry that
diff instead if you prefer it.

**An instrumentation counter.** `termSetQueriesBuilt()` exists because two
earlier parity measurements in this area were wrong, through both arms running
the old code and agreeing. Easy to drop if you do not want it in the API.
