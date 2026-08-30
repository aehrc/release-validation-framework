# How the DuckDB engine scales, and how to size a worker

Measured 2026-08-30 on a 10-core Xeon 8358 / 23 GB Linux host,
`catchup-upgraded@6b8a6997`, via `DuckValidationProbe` so that one JVM
invocation is exactly one data point: no upload, no queue, no unzip. Release is
the unpacked AU daily build (45,298,828 rows, 66 files). **Every configuration
below produced an identical report — 220 tests, 60 failures — so these are
timings of the same work, not of different work.**

Reproduce with `/data/work/scripts/duck-scaling.sh`.

## First, a defect this exposed

DuckDB sizes its thread pool from the **machine**, ignoring affinity and cgroup
quotas. Under `taskset -c 0-1` on this 10-core host, `nproc` says 2 and the JVM
says 2, but DuckDB said `threads=10`.

    1 core, DuckDB left to its own devices (10 threads)   674 s
    1 core, threads bounded to what the process owns       259 s

**2.6x slower purely from oversubscription**, and on Kubernetes it is worse than
this test: a pod with `limits.cpu: 2` on a 64-core node would run 64 DuckDB
threads inside a two-core CFS quota. Fixed in `6b8a6997`; threads now come from
`Runtime.availableProcessors()`, which does observe both, with
`rvf.duck.threads` to override. **Any sizing exercise before that fix was
measuring contention, not capacity.**

## Curve A — right-sized worker (cores == threads)

| cores | wall | materialise | speedup | efficiency | gain vs previous |
|---|---|---|---|---|---|
| 1 | 259.1 s | 77.6 s | 1.00x | 100% | — |
| 2 | 162.1 s | 44.2 s | 1.60x | 80% | 1.60x |
| 4 | 114.4 s | 30.1 s | 2.26x | 57% | 1.42x |
| 8 | 82.8 s | 16.7 s | 3.13x | 39% | 1.38x |

It scales, but sub-linearly and predictably. Fitting Amdahl's law to the 8-core
point gives a **serial fraction of 22.2%**, so:

    theoretical ceiling            4.50x single core  =  58 s
     16 cores  predicted   70 s   (3.69x)
     32 cores  predicted   64 s   (4.05x)
     64 cores  predicted   61 s   (4.26x)
    128 cores  predicted   59 s   (4.38x)

**Going from 8 to 128 cores is predicted to buy 82.8s -> 59s for 16x the CPU.**

## Curve B — threads varied against fixed 10 cores

| threads | wall | materialise | vs best |
|---|---|---|---|
| 1 | 258.3 s | 75.1 s | +225% |
| 2 | 154.4 s | 42.7 s | +94% |
| 4 | 102.1 s | 25.3 s | +29% |
| **8** | **79.4 s** | 17.1 s | **best** |
| 16 | 82.2 s | 15.6 s | +4% |
| 32 | 89.7 s | 15.3 s | +13% |

Two things worth noting. **Past 8 threads it gets worse, not flat** — 32 threads
is 13% slower than 8. And the materialise phase keeps *improving* slightly
(17.1 -> 15.3 s) while the total degrades, which locates the damage in the
assertion phase: DuckDB already parallelises within a query, so extra threads
there contend rather than help.

Optimum is `threads ≈ cores`, slightly under. That is exactly what
`availableProcessors()` gives, so the default needs no tuning.

## Cold start (matters only for a job-per-run model)

Time from process launch to serving a request, 253 MB fat jar, warm page cache,
no image pull:

    1 core  19.0 s        4 cores   9.0 s
    2 cores 10.0 s        8 cores   8.9 s

Flat from 4 cores up at about **9 s**. Add pod scheduling and, on a cold node,
an image pull.

## Sizing recommendation for AKS

**4 cores / 6 GB is the value sweet spot; 8 cores / 8 GB if latency matters.**

- 4 cores buys 2.26x for 4x the CPU (57% efficiency) and finishes a full edition
  in ~114 s.
- 8 cores buys 3.13x for 8x (39%) and finishes in ~83 s.
- Beyond 16 cores you are paying for the 22% that cannot be parallelised.
- Memory is flat across every configuration: peak RSS 3.47–4.04 GB, and it
  *rises* only slightly with thread count. `-Xmx8g` with a 6–8 GB request is
  ample; there is no need for the 12–15 GB the MySQL-era worker manifest asks
  for.
- **N small workers beat one big worker.** Two 4-core workers do two editions in
  114 s; one 8-core worker does them in 166 s. Throughput per core is the thing
  to maximise, and it is maximised at small worker sizes.

## The two server/worker models, with these numbers

**Listener claims work (what RVF does today).** `rvf.execution.isWorker=true`
runs a `@JmsListener`; workers are long-lived and pull from ActiveMQ. Scaling is
KEDA `ScaledObject` on queue depth. Pays cold start once per pod, not per job,
and `queuePrefetch=1` already gives even distribution across a pool. Weakness: a
pod outlives its jobs, so a leak or a poisoned state persists, and scale-in
during a 83–259 s run kills that run unless `terminationGracePeriodSeconds`
covers it.

**Server starts and tasks a worker (job per run).** Cleaner isolation, per-run
sizing, retry/backoff for free. Costs ~9 s of JVM boot per job — **11% overhead
on an 83 s job, and that is acceptable**. The blocker is that RVF's worker never
exits (see `HOSTING.md`), so this needs a one-shot mode first.

**On your "5 minutes to scale for a 1 minute job" concern:** the JVM is not the
problem at 9 s. The risk is entirely in the layers underneath —
KEDA's polling interval (default 30 s, tunable to seconds), and the cluster
autoscaler adding a node, which is minutes. **Keep a warm node pool sized for
one or two concurrent runs** and the scale decision costs seconds; let the
cluster scale nodes on demand and it costs minutes regardless of which worker
model you choose.

## Answered on 64 cores (Petrichor `c157`, 2026-08-30)

Job 30698623, `--exclusive` on a 64-core / 502 GB node, release unpacked on
node-local disk, Temurin 25 shipped because Petrichor offers only Java 8/11/16.
20 configurations, **every one produced 220 tests / 60 failures / 45,298,828
rows**, so these are timings of identical work. Raw data:
`hpc/results-petrichor-64core.tsv`.

### There is an optimum, and it is 16 cores

| cores (= threads, pinned) | wall | speedup | efficiency | vs best |
|---|---|---|---|---|
| 1 | 175.2s | 1.00x | 100% | +252% |
| 2 | 105.9s | 1.65x | 83% | +113% |
| 4 | 69.9s | 2.51x | 63% | +40% |
| 8 | 57.0s | 3.07x | 38% | +14% |
| **16** | **49.8s** | **3.52x** | 22% | **fastest** |
| 32 | 51.4s | 3.41x | 11% | +3% |
| 48 | 57.8s | 3.03x | 6% | +16% |
| 64 | 57.9s | 3.03x | 5% | +16% |

**Past 16 cores it gets slower, not merely flatter.** 64 cores is 16% worse than
16. The serial fraction re-fits at 23.7% against the 22.2% predicted from ten
cores, so the model was right about the shape - but the Amdahl ceiling of 4.23x
(41s) is never reached, because contention overtakes parallelism first.

### Giving a worker the whole node is worse than pinning it

The single most useful number here:

    16 cores PINNED with taskset          49.8s
    64 cores available, 16 DuckDB threads 56.6s

**14% slower for having 4x the cores available**, at the same thread count. With
64 cores visible the OS scatters 16 threads across sockets; pinned to 0-15 they
share one. On Kubernetes the equivalent lever is a CPU *limit* plus
`rvf.duck.threads` matching it - and note DuckDB cannot see either by itself,
which is what `6b8a6997` fixes.

### Oversubscription is milder at width, but never helps

64 cores available: 16t 56.6s, 32t 56.7s, 64t 58.2s, 128t 59.1s, 256t 60.4s.
Only +7% at 256 threads, against +13% for 32 threads on a 10-core box - a big
node absorbs it. It still costs memory: peak RSS climbs 3.58 GB at 8 threads to
7.10 GB at 256.

### Memory is not a lever, confirmed on a 502 GB node

`memory_limit` 8GB / 32GB / 128GB gave 58.1s / 58.5s / 57.8s - inside noise.
Peak RSS never exceeded 7.1 GB in any configuration. There is no version of this
workload that wants a big-memory node.

### What this means for sizing

**8 cores is the honest recommendation, 16 if latency matters.** 16 cores is the
floor of the curve but buys only 14% over 8 for twice the CPU; 8 cores is within
14% of the best time achievable at any width. Beyond 16 you are paying to go
slower. Pin whatever you allocate.

A validation is **not** an HPC-shaped problem: the fastest this workload can be
made to run on a 64-core node is 49.8s, against 57.0s on eight cores of the same
node. Throughput comes from running many validations at once, not from making one
faster.

## The HPC experiment worth running

The Amdahl fit above is a *prediction* from four points on ten cores. On a
few-hundred-core node with large memory it can be tested directly, and it is
worth testing because the prediction can fail in both directions: NUMA and
memory-bandwidth limits would make it worse than predicted, while a phase that
only parallelises at scale would make it better.

`hpc/duck-scaling.sbatch` runs the same probe across cores/threads/memory. What
to look for:

1. **Where does the curve actually flatten?** Predicted ~16–32 cores.
2. **Does oversubscription keep hurting at scale**, i.e. is `threads = cores`
   still optimal at 64+, or does the optimum fall below the core count?
3. **Is the 22% serial fraction stable**, or does it grow with core count (which
   is what NUMA effects look like)?
4. **Does the materialise phase keep scaling** after total wall time stops
   improving? It was still improving at 32 threads here.
5. **Memory:** peak RSS was flat at ~4 GB regardless of parallelism. If DuckDB's
   default `memory_limit` (about 80% of a huge node's RAM) changes that, it is an
   argument for setting `rvf.duck.memory.limit` in production.

The honest expectation is that this finds a ceiling near 60 s and confirms that
**a validation is not an HPC-shaped problem** — it is a 4-to-8-core job, and the
throughput win comes from running many of them at once rather than any one of
them faster.

## MySQL versus DuckDB on identical resources (2026-08-30)

Both engines, **same host, same 8 cores (`taskset -c 0-7`), same fat jar** with
only `rvf.execution.engine` differing, same release
(`amtv4-15912.zip`, 894 MB), same groups, `releaseAsAnEdition=true`, no previous
release, Drools/MRCM off. Full REST path each time: `POST /run-post` to
`GET /result` reporting COMPLETE.

MySQL is a real MySQL 8.4.6 (generic tarball, run rootless - no Docker and no
sudo on this host), 8 GB InnoDB buffer pool, assertion tables primed with all
360 assertions. **Zero swap for the duration of both runs**, verified each
minute; an earlier attempt on a 14 GB host swapped 943 MB and was discarded.

    MySQL 8.4.6   1215 s
    DuckDB         208 s
    ------------------------
                   5.8x

### Correctness, which matters more than the ratio

    assertions both engines actually executed   137
    identical failure count                    136   (99.3%)
    unexplained divergence                       1

The one divergence is `component-centric-snapshot-refsets-descriptor-validation`
- MySQL 8 findings, DuckDB 0. It joins `ancestors`, a table built by the
resource-category assertions, so the hypothesis to test first is that the two
engines build `ancestors` differently rather than that the assertion itself
differs. It is NOT in `duck/known-divergences.json` and should be added as
unexplained so the nightly gate fails on it.

### The reporting difference worth knowing about

53 assertions carry DuckDB's `-1` sentinel - "not executed", because no previous
release was supplied - and RVF counts them as `totalTestsIncomplete: 54`.
**MySQL reports those same assertions as PASSED**, with
`totalTestsIncomplete: 2`.

MySQL substitutes an empty placeholder schema for the absent release, so the
statements run, compare against nothing, find nothing, and are reported as
passes. DuckDB declines to run them and says so. DuckDB's behaviour is the honest
one and MySQL's is a false pass, but it is a divergence in *reported* numbers
that anyone comparing two reports has to know about - and it explains most of a
naive "58 failures versus 24" reading.

It also means the 5.8x is not flattered by DuckDB skipping work: the 53 it
declined are the ones MySQL executed against empty tables, which is the cheapest
thing MySQL did all run.
