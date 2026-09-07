# The worker's memory ceiling, and what it would take to lower it

Written 2026-09-08. **A proposal with a measurement plan, not a change.** The
values file is untouched.

## Where the number comes from today

    worker.resources.limits.memory   24Gi
    MaxRAMPercentage=75              baked into the image (pom.xml jvmFlag)
    -> JVM heap ceiling              ~18Gi
    worker.duckdb.memoryLimit        4GB
    remainder                        metaspace, thread stacks, the JVM itself

24Gi was sized when MRCM peaked at 13.9GiB and was OOMKilled at 16Gi. Both of
those facts are still true of the code that was running then; neither is a fact
about the code running now.

## What the fork patches changed, measured

    MRCM, AU edition, sequential, patched forks   peak sampled heap 5.58 GiB
    (MrcmSoloProbe, 2026-09-08, 307s validate)

The earlier 13.9GiB figure is NOT comparable: it was a concurrent run, and it
was `Runtime.totalMemory()` - what the JVM had taken - rather than sampled
usage. Two different measurements of two different things, so the honest
statement is "MRCM now demands about 5.6GiB in this configuration", not "MRCM
demand fell by 8GiB".

## Why the saving is invisible on the cluster

Nightly 16246, worker pod, after the fork patches:

    memory.current   19.04 GiB   (includes page cache)
    anon             18.92 GiB
    page-cache        0.02 GiB
    oom_kill 0, restarts 0

`anon` is 18.92GiB because the heap ceiling is ~18GiB and G1 fills it and keeps
it. The container reads as nearly full whatever the workload demands, so the
saving cannot be observed from the cluster's own numbers - only by lowering the
ceiling and seeing whether anything breaks.

## The experiment that would justify a lower ceiling

The worker runs four phases in one JVM, and MRCM is only one of them. What
matters is the peak DEMAND across the whole run, which nothing has measured -
in particular the Drools phase holds a full snapshot's object graph on the heap
deliberately (10,949 findings in 636s; the batched DuckDB alternative was 54x
slower), and that is the other multi-GB consumer.

So:

1. Deploy the worker with `limits.memory: 16Gi` and nothing else changed. The
   image's `MaxRAMPercentage=75` gives a 12GiB heap, which is above the
   measured MRCM demand with room for Drools.
2. Run the nightly at full scope - Drools and MRCM both enabled, previous
   release supplied - and record `memory.max`, `memory.current`, `anon`,
   `oom_kill` and `restarts` from the pod's cgroup, plus the phase timings.
3. Compare the findings against build 16246 record for record. A ceiling that
   changes what a validation FINDS is not a saving; the risk is a phase that
   quietly degrades rather than fails, which is why the comparison is the
   deliverable and the memory number is not.
4. Only then lower the request from 12Gi, which is what actually frees capacity.

## The consequence to decide before running it

**KEDA's arithmetic changes.** The worker is scaled on queue depth, and the
`large` node has 29Gi allocatable with a CPU request that puts one worker on a
node. A 16Gi limit does not fit two workers either, so the first change buys
resilience rather than density; getting two workers per node needs the request
down near 8Gi as well, and that is a different question from whether the
ceiling is safe.

Until that is settled the 24Gi ceiling is wrong but harmless - it costs
headroom on a node nothing else uses.
