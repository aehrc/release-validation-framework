# PR D — release-mrcm-validator

**Title:** `Run the domain and attribute checks in parallel`

**Base:** PR C · **Branch:** `pr/d-parallel-checks` · **+321/-140, 2 files** · upstream suite **24 pass, 0 fail**

Stacked on PR C. Raise and review after it.

---

The domain and attribute checks run serially and are independent of each other.
This runs them across cores.

Measured on a real nightly against the same input release, with PR C applied:

| | before | after |
|---|---|---|
| MRCM tail beyond the SQL phase | 443s | 55s |
| whole validation | 816s | 464s |
| MRCM findings | 4 inferred / 4 stated | unchanged |

## Two things the parallelism requires

**`ValidationRun`'s three assertion lists are now synchronized.** A plain
`ArrayList` loses entries under concurrent `add`, and a lost entry here is a
lost assertion — the run would report *fewer* results rather than fail. That is
the failure mode worth stating: silent under-reporting, not a crash.

**A `ReleaseStore` per thread.** The store is not thread-safe.

## Also here

`mrcm.validator.index.directory` makes the index location configurable rather
than fixed. One property, no behaviour change when it is unset.

## Risk

This is the half of the original change that deserves scrutiny, which is why it
is separated from PR C. C is a dozen lines of query semantics with a measured
729x on its phase; D is threads. Rejecting D costs nothing in C.
