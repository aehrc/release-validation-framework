# PR B — snomed-query-service
**Title:** `Build index documents in parallel, writing them in iteration order`

Index construction builds one Lucene `Document` per concept on a single thread.
Construction is the expensive half — cardinality grouping per relationship,
722,404 concepts for an 853 MB AU edition — and it is independent per concept.

This builds documents across cores in batches of 4,096 and writes them in the
original iteration order.

## Measured on the 20260801 International release

| | total | building | writing |
|---|---|---|---|
| serial, as today | 46.0s | 14.8s | 11.3s |
| this change | **33.2s** | 2.5s | 11.9s |
| if the write were concurrent too | 25.1s | 3.3s | 5.2s |

Document construction drops 14.8s to 2.5s. The write stays serial, which costs
about 8s an index — the third row is what dropping the ordering would buy.

## The ordering is preserved, and we are not sure it should be

Write order fixes the docids, and Lucene returns equal-scoring hits in docid
order. Writing concurrently leaves every result *set* identical and reorders it,
and that order becomes thread scheduling rather than anything repeatable.

That currently matters because consumers truncate. RVF reports the first N
failing concepts of an assertion, so an unordered write would change *which*
failures a user sees between two runs of the same release — same content,
different sample, nothing in the report to say so.

So this change preserves the existing order, at that 8s. We are not claiming
that is the right trade. A consumer that sorted before trimming would get
consistent reporting regardless of what this library does, and that is probably
where the guarantee belongs — it would be robust against any future change in
here, and would let the write go parallel as well.

We did not do that because it changes behaviour consumers are relying on today,
and this PR is about index construction. If you would rather this library made
no ordering promise, say so and we will drop the ordered write and fix the
consumers instead.

`IndexWriteOrderTest` pins the promise as it stands: the same taxonomy indexed
with a batch size of 4 and as a single batch must produce the same docid order.
It fails if the ordered write is ever replaced by a concurrent one.

## Behaviour changes

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

## Dependencies

Independent of [the out-of-range PR](https://github.com/dionmcm/snomed-query-service/pull/1) and [the member-of PR](https://github.com/dionmcm/snomed-query-service/pull/3); touches no query path.
