# PR B — snomed-query-service

**Title:** `Build index documents in parallel, writing them in iteration order`

**Base:** `develop` · **Branch:** `pr/b-parallel-import` · **+180/-29, 3 files** · upstream suite **123 pass, 0 fail**

Independent of the out-of-range PR. Touches no query path.

---

Index construction builds one Lucene `Document` per concept on a single thread.
Construction is the expensive half — cardinality grouping per relationship,
722,404 concepts for an 853 MB AU edition — and it is independent per concept.

This builds documents across cores in batches of 4,096 and writes them in the
original iteration order.

## Why the write order is load-bearing

Write order fixes docids, and Lucene returns equal-scoring hits in docid order.
Writing concurrently would leave every result *set* identical and still reorder
it, so a validation report that samples failing concepts would name a different
sample between runs of the same release. Batching bounds the pending documents,
which matters because this runs while the whole concept map is still on the
heap.

`IndexWriteOrderTest` pins it: the same taxonomy indexed with a batch size of 4
and with one batch must produce the same docid order. It fails if the ordered
write is ever replaced by a concurrent one.

## Two consequences worth naming

**`SimpleDateFormat` is gone from the write path.** It was shared, it is not
thread-safe, and it was the one real hazard in parallelising this. The
cardinality fields now compare effective times as `yyyyMMdd` strings, where
lexicographic and chronological order coincide.

That changes behaviour on input the old code rejected. A blank or malformed
concept `effectiveTime` previously threw `ParseException` and aborted the import;
it is now indexed as-is. For valid published RF2 there is no difference. Say if
you would rather it kept failing fast and I will add the guard.

**`ReleaseWriter.addConcept` no longer declares `throws ParseException`.**
Nothing on the build path can throw it once the date parsing is gone, so the
dead carrier that propagated it out of the parallel stream is removed too. This
narrows a public signature.
