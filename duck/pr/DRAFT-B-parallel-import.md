# PR B — snomed-query-service

**Title:** `Build index documents in parallel, writing them in iteration order`

**Base:** `develop` · **Branch:** `pr/b-parallel-import` · **+266/-29, 4 files** · upstream suite **126 pass, 0 fail**

Independent of the out-of-range PR. Touches no query path.


## Dependencies

**Depends on:** nothing. Independent of [the out-of-range PR](https://github.com/dionmcm/snomed-query-service/pull/1) and [the member-of PR](https://github.com/dionmcm/snomed-query-service/pull/3); touches no query path.

**Depended on by:** nothing.

**Merge order:** any time.

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

An effective time that is not `yyyyMMdd` is still rejected, and still aborts
the import. The old code rejected it as a side effect of `SimpleDateFormat`
failing to parse; this checks the shape explicitly, which is what the string
comparison depends on anyway. There is no findings channel in an index builder,
so the alternative would be indexing a silently wrong effective time that
nothing downstream ever checks. `EffectiveTimeValidationTest` pins it.

**`ReleaseWriter.addConcept` no longer declares `throws ParseException`.**
Nothing on the build path can throw it once the date parsing is gone, so the
dead carrier that propagated it out of the parallel stream is removed too. This
narrows a public signature.
