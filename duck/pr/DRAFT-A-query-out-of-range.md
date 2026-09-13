# PR A — snomed-query-service

**Title:** `Answer an out-of-range ECL clause with one TermInSetQuery`

**Base:** `develop` · **Branch:** `pr/a-query-semantics` · **+490/-16, 8 files** · upstream suite **127 pass, 0 fail**

---

An `attributeName != value` clause in an MRCM attribute range is currently
rendered as the complement of the excluded set: one exclusive `TermRangeQuery`
per member, chained. The classic query parser compiles an automaton per range
clause.

Measured on an 853 MB AU edition driven through `release-mrcm-validator`'s 134
attribute ranges, single run, 8-core box:

| | before | after |
|---|---|---|
| `TermRangeQuery` instances constructed | 4,004,000 | 0 |
| peak retained by their automaton transition tables | 10.94 GB | — |
| wall clock, 134 ranges, serial | 724.0s | 170.6s |
| results | — | identical on all 134 |

The heap figure is the cost that matters: `int[][]` inside Lucene's `Automaton`,
live only while the range chain is built.

Only terms present in the index can match anything, so naming the field's own
terms and subtracting the excluded set selects the same documents.

## Two supporting changes

- **`conceptsWithAnyAncestor(ids)`** — one term-set query over the ancestor
  field. Proper ancestors, so the queried ids are not returned; javadoc says so.
  Used by a companion PR in `release-mrcm-validator`.
- **Concept id as `NumericDocValues`.** Queries return hundreds of thousands of
  hits and only want the id back. Reading it from a stored field measured
  24.7µs per hit across 3,885,244 hits — 95.8s single-threaded, 86% of it in the
  200 expressions returning more than a hundred hits.
  **An index built by the previous version still reads correctly**: when the
  field is absent `readConceptIds` falls back to the stored field per hit, at
  the old cost. No reindex required.

## The part to attack

`ExpressionConstraintToLuceneConverter` returns query *text*, so it cannot
construct a `TermInSetQuery`. It emits a `__notinset__` sentinel and parks the
terms in a `ThreadLocal` that `SnomedQueryService` drains during parse.

That is hidden state. It is contained: the sentinel cannot be injected through
user ECL, the slot is overwritten before every emit, and it is cleared in a
`finally` around the parse so a parse failure cannot strand the terms.

It is also why the fast path is taken **only when the query carries exactly one
such clause**. The pattern that finds `(* NOT ...)` is greedy and matches once,
so with two clauses the text substitution rewrites both while only one field's
complement is parked. Multi-clause queries keep taking the range chain, which
substitutes the same text for both and stays correct.

That guard costs nothing in practice. Every out-of-range expression the MRCM
refsets of the 20260801 International release produce — 147 of them — carries
exactly one such clause, because the rule is built as
`domainConstraint + attributeId + " != " + rangeConstraint`, and the excluded
set is one clause however many concepts are OR'd inside it. A second clause
needs a domain constraint that itself contains `!=`; none of the 19 active
domains has one. `IntegrationTest` covers both the correctness and the routing:
the single-clause case must build a term set, the multi-clause case must not.

The alternative is changing the converter's return type from `String` to a
query object. That touches every caller and all 83 converter tests.
