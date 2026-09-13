# PR E — release-mrcm-validator

**Title:** `Allow the MRCM index to be built on disk`

**Base:** PR D · **Branch:** `pr/e-disk-index` · **+89/-4, 2 files** · upstream suite **24 pass, 0 fail**

Last of the three; nothing depends on it.

---

The MRCM Lucene index is always built in RAM. The build peaks near 7.8 GB on an
853 MB edition, and both content forms are indexed in the same run, so the heap
has to hold the whole thing.

`mrcm.validator.index.directory` builds it under the named directory instead. A
temporary subdirectory is created per run and deleted when the run ends. Unset,
the index stays in memory exactly as now.

## The lifecycle, which is the only risky part

A RAM store is garbage; a disk store is a directory that nothing else deletes.
So the store is now destroyed on the way out of `executeValidation`, on both
paths:

- the import fails — the store is destroyed before the exception propagates,
  rather than being stranded because it never reached the caller;
- `new SnomedQueryService(...)` throws after the store is built — the `finally`
  re-reads the handoff rather than trusting the assignment that never ran.

`RamReleaseStore.destroy()` is a no-op, so the unset case is unaffected.

The store reaches `executeValidation` through a `ThreadLocal` because
`getSnomedQueryService` is `protected` and overridden in tests; changing its
signature would break them, and the two content forms validate on separate
threads so a plain field would race. Happy to change the signature instead if
you would rather not have the thread local.

A bad root path fails with a message naming the property, not a bare
`NoSuchFileException`. Documented in the README.
