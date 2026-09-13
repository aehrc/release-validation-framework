# PR B — snomed-query-service

**Title:** `Build index documents in parallel, writing them in iteration order`

**Base:** `develop` · **Branch:** `pr/b-parallel-import` · **+119/-23, 2 files** · upstream suite **122 pass, 0 fail**

Independent of PR A. Touches no query path.

---

Index construction builds one Lucene `Document` per concept on a single thread.
Construction is the expensive half — cardinality grouping per relationship,
722,404 concepts for an AU edition — and it is embarrassingly parallel.

This builds documents across cores in batches of 4,096 and writes them **in the
original iteration order**.

## Why the write order is not an implementation detail

Write order fixes docids, and Lucene returns equal-scoring hits in docid order.
Writing concurrently would leave every result *set* identical and still reorder
it. A validation report samples failing concepts, so the sample would change
between runs of the same release against the same index. Batching bounds the
pending documents; the ordered write is what keeps reports reproducible.

## Also here

`ReleaseWriter` gains a `later()` helper and tracks the maximum effective time
seen while writing, so the index records the release it was built from without a
second pass over the input.

## Risk

This is the concurrency half of the original change and the half worth
scrutinising. It is separated from PR A for that reason: A carries the
measurement and no threads, B carries the threads.
