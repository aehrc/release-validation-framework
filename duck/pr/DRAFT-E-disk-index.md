# PR E — release-mrcm-validator
**Title:** `Allow the MRCM index to be built on disk`

The MRCM Lucene index is always built in RAM. The build peaks near 7.8 GB on an
853 MB edition, and both content forms are indexed in the same run, so the heap
has to hold the whole thing.

`mrcm.validator.index.directory` builds it under the named directory instead. A
temporary subdirectory is created per run and deleted when the run ends. Unset,
the index stays in memory exactly as now.

## The lifecycle, which is the only risky part

An in-memory index needs no cleanup: drop the reference and the garbage
collector reclaims it. An on-disk index is a directory, and nothing deletes it
unless this code does. Left behind, each one is gigabytes, once per content
form per run.

So the store is now destroyed on the way out of `executeValidation`, including
on the two paths where it would otherwise be stranded:

- the import throws — the store is destroyed before the exception propagates,
  rather than leaking because it never reached the caller that owns it;
- `new SnomedQueryService(...)` throws after the store is built — the `finally`
  re-reads the handoff instead of trusting an assignment that never ran.

`RamReleaseStore.destroy()` is a no-op, so leaving the property unset behaves
exactly as before.

The store reaches `executeValidation` through a `ThreadLocal` because
`getSnomedQueryService` is `protected` and overridden in tests; changing its
signature would break them, and the two content forms validate on separate
threads so a plain field would race. Happy to change the signature instead if
you would rather not have the thread local.

A bad root path fails with a message naming the property, not a bare
`NoSuchFileException`. Documented in the README.

## Dependencies

**Depends on:** [Run the attribute checks in parallel](https://github.com/dionmcm/release-mrcm-validator/pull/2), and transitively [the lateralizable PR](https://github.com/dionmcm/release-mrcm-validator/pull/1) and [the out-of-range PR](https://github.com/dionmcm/snomed-query-service/pull/1).

**Depended on by:** nothing.

**Merge order:** last. Rejecting it costs nothing in the others.

Stacked on the parallel-checks branch; the diff shown is only this change.
