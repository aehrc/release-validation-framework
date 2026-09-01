# Engine A/B, and how to deploy what it proves

Two engines, one release, one jar. `rvf.execution.engine` is the only variable,
so a divergence means something.

## What was measured

AU edition (853MB, 45.3M rows), previous release supplied, 8 cores, both legs on
one host:

```
assertions joined on uuid   149
identical failureCount      147  (98.7%)
divergent                     2   both accounted for, see below
RVF/MySQL 1380s   DuckDB 120s   11.5x
```

## Running it

Locally, with the release already in the job store and the previous release in
the release store:

```
ci/engine_ab_stack.sh --release au/amtv4.zip \
    --previous SnomedCT_AU_20260630.zip \
    --groups "release-type-validation file-centric-validation"
```

The script brings up MySQL and both RVF instances and then calls
`ci/engine_ab.py`, which submits, waits, and gates. Exit status IS the gate.

`az/azure-pipeline.engine-ab.yml` does the same thing with pipeline inputs. It
contains no comparison logic of its own on purpose: the tested code is the code
a developer runs.

### Two things the harness does deliberately

**A separate in-JVM broker per instance.** Both instances are workers. On one
shared broker they would compete for the same queue and each leg would validate
whichever message it happened to win.

**Form-encoded submission, not multipart.** `/run-post-via-s3` takes no file,
only `@RequestParam` values. An earlier version hand-rolled a multipart body and
Tomcat sat waiting for a part that never came rather than answering 400. And not
`/run-post` either: a real edition is 853MB, which the 1GB multipart limit and
any ingress body cap both apply to, and pushing it twice would dominate the run.

**MySQL is the generic Linux tarball, run unprivileged.** No Docker daemon, no
sudo, so this runs on a k8s build agent. It is also how the test suite is run
here, since `TestMySQLContainer` binds a fixed port that a real MySQL can serve.

## The two divergences it found

Recorded in `known-engine-divergences.json` with their evidence. Recording is
not endorsement - both entries carry a `notThePlan` note.

**MySQL abandons an assertion because `identifier_d` has no `id` column.**
`validate_inactivated_component_module` cursors over every `%_d` table in
`information_schema` and selects `t1.id`. RF2 identifies identifier rows by
`alternateidentifier`, so MySQL raises `Unknown column t1.id in field list` and
validates nothing for that assertion. The DuckDB engine is unaffected because
`publish_store.py` unrolls the procedure at publish time over the 52 tables that
have an `id`.

**This one is ours.** `identifier_d` exists in MySQL only because
`create-tables-mysql.sql` now creates it - one of the 12 tables added by the
RF2-file-types change (fork PR #23, upstream IHTSDO #74). The fix belongs in the
assertions corpus: the cursor should skip tables with no `id` column.

**DuckDB reports not-run where MySQL passes.** `MySqlQueryTransformer` drops a
statement naming a release the run does not hold, so the assertion is reported
PASSED for work never attempted. `DuckBinder` mirrors the drop but reports `-1`,
not executed. DuckDB is the honest side; the risk is that
Release-Dashboard-UI renders `-1` literally to users.

## Deployment

`k8s/rvf-aks.yaml`. The difference from `k8s/rvf.yaml` - the Docker Desktop
proof - is storage: that one uses `hostPath`, so every pod must land on one node
and the worker pool cannot span the cluster. On AKS the job store and release
store are Azure Files `ReadWriteMany`, which is what makes the split real. Proven
locally with two JVMs sharing one directory and one broker: the worker read a
release the API staged, and the API served the report the worker wrote.

Not on the share, on purpose:

* the DuckDB scratch directory - 1.57GB per materialised edition plus spill, and
  nothing else ever reads it;
* the release cache - derived, evictable, rebuilt in ~21s.

Scaling is KEDA on **queue depth**. A validation runs for minutes after its
request returned 201, so anything scaling on request rate or retiring idle pods
kills runs in flight; and the worker has no `CommandLineRunner` and never exits,
so a Job per validation needs a one-shot mode that does not exist.

`RVF_DUCK_THREADS` matches the CPU limit because DuckDB sizes its pool from the
machine, not the cgroup quota: on a 64-core node an unset value gave 64 threads
inside a 2-core quota, 674s against 259s once bounded.

### The image

`jib-maven-plugin` is already in the pom and needs no Docker daemon:

```
mvn -B -ntp jib:buildTar -DskipTests -Djib.from.platforms=linux/amd64 \
    -Ddocker.registry=ontoserver.azurecr.io \
    -Ddocker.image.prefix=aehrc-rvf \
    -Ddocker.image.tag=<sha>
```

Verified here: 459MB tarball, 8 layers, entrypoint
`java ... org.ihtsdo.rvf.App`, workdir `/app`, port 8080, and the assertion
corpus, the Drools rules and `app/resources/duck/store.json` all present in the
layers. Swap `jib:buildTar` for `jib:build` to push straight to ACR.

## Not proven here

Anything that needs the cluster: Azure Files throughput against node-local disk,
KEDA actually scaling, and the ingress that supplies the `X-AUTH-*` headers RVF
trusts. This host has no SMB mount and no cluster, so those are asserted from the
measurements above, not measured.
