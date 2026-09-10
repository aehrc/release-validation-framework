# Engine A/B, and how to deploy what it proves

Two engines, one release, one jar. `rvf.execution.engine` is the only variable,
so a divergence means something.

## What was measured

AU edition (853MB, 45.3M rows), previous release supplied, both legs on one
host. Re-run 2026-09-08 on 10 cores, and this time the agreement is broken down
by whether the assertions actually ran:

```
assertions joined on uuid   149
identical failureCount      147  (98.7%)
  both ran, same failures    12
  both ran, found nothing   135
  NEITHER ran                 0
  ONE ran, other did not      0
divergent                     2   both accounted for, see below
RVF/MySQL 1380s   DuckDB 180s   7.7x
```

**The 12 is the number that means something.** 135 of the 147 agreements are
both engines finding nothing, which is worth having but is not the same
evidence, and the flat "98.7%" hid the difference. See
`ci/compare_reports_selftest.py` for why that distinction is enforced rather
than merely printed.

An earlier measurement here read 11.5x on 8 cores. This one is 7.7x on 10, with
the same release: the MySQL leg is disk-bound loading 45.3M rows and the host
differs, so treat both numbers as the same finding - roughly an order of
magnitude - rather than as a trend.

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

### What it needs from the host, learned by hitting each one

**Disk: about 40GB free.** The MySQL leg writes ~25GB of MyISAM tables and
indexes for one AU edition, plus the unzipped release. A full disk does not
fail cleanly - mysqld logs `Disk is full writing ...MYI` and retries, so the
run hangs rather than stops.

**Memory: leave the JVMs about 3GB each.** Two RVF instances at `HEAP=6g`
alongside MySQL's 4GB buffer pool got the MySQL leg killed by the kernel on a
22GB host, mid-load, with no exception in its log - the log simply stops. If a
leg dies with the last line being a `load data local infile`, that is this.
`HEAP=3g` completes.

**A first run must initialise the datadir** (`mysqld --initialize-insecure`),
which the pipeline does in its install step. The script then sets the root
password and creates `rvf_master` itself: an insecure datadir has no password,
every statement below authenticates with one, and the version query discards
stderr - so its failure took the whole script down with a log showing only a
SHUTDOWN sent by the script's own cleanup.

### What a CI AGENT needs, each learned from a red build

The pipeline had never had a definition. Registering one and running it found
nine things a developer's host had been quietly providing. Every one presented
as something other than its cause:

| build | symptom | cause |
|---|---|---|
| 16302 | `Checkpoint.Authorization` pending forever | a new pipeline is not authorised for the variable group or the pool |
| 16302 | `Could not find artifact snomed-drools-engine:6.1.3-aehrc-perf in ihtsdo-releases` | four `-aehrc-perf` forks exist in no remote repository; `duck/build-pinned-forks.sh` builds them |
| 16305 | `Fatal error compiling: release version 25 not supported` | the pool ships JDK 17 and the parent BOM sets `java.version=25` |
| 16306 | `tar (child): xz: Cannot exec` | no xz-utils on the agent image; python's `lzma` needs no package |
| 16307 | `E: Unable to locate package libaio1` | apt lists are empty until `apt-get update`; a missing index reading as a missing package |
| 16308 | `error while loading shared libraries: libaio.so.1`, exit 127 | this pool is 22.04, where `libaio1t64` does NOT exist - and `libnuma.so.1` was missing too |
| 16308 | `Unable to load symbolic/hard linked file` | `PublishPipelineArtifact` cannot follow symlinks inside the published directory |
| 16310 | `The designated data directory ... is unusable` | `mysqldata` and `mysqltmp` absent, and `--initialize-insecure` needs the datadir empty |
| 16312 | azcopy `NoAuthenticationInformation` | an ACCOUNT KEY appended to a URL as though it were a SAS, against a blob container the account does not have |
| 16313 | `unrecognized arguments: --recursive` | `az storage file list` on this CLI has no recursion; the layout is `<run>/output-files/<zip>` |
| 16314 | `ResourceNotFound` on `<run>/output-files` | the share holds 684 directories, timestamp-named plus GUIDs, and GUIDs sort LAST |
| 16315 | mysqld ready, then SHUTDOWN with no reason | the fresh datadir had no root password (above) |
| 16318, 16323 | `Access denied for user 'root'@'localhost' (using password: YES)` | **`mysqladmin ping` exits 0 on "Access denied"** - it answers "did a server respond", not "are these credentials good". Every credential check here used it, so all of them concluded the password worked and the fresh-datadir branch never ran. Measured: `mysqladmin -uroot -pWRONG ping` exits 0, `mysql -uroot -pWRONG -e 'select 1'` exits 1 |

Two general lessons in that list. Every listing the resolver makes is now
printed, because four of those cost a 25-minute round trip to learn one line of
fact. And the release is chosen by walking the share the nightly already reads
with the key it already has, rather than from a layout nobody had checked.

### The previous release is not optional, 2026-09-10

Build 16325 ran without one and reported 99/149 identical with 49 unexplained
divergences - all `rvf=<n> duck=-1` on release-type assertions. MySQL answers
against an empty schema (7,015,456 findings on one) and DuckDB says not-run, so
an absent previous release invents disagreement on a third of the corpus rather
than losing coverage.

The fetch step resolves it now: the newest **published** edition dated strictly
before the build under test, from `snomed-versioned-content` under
`prod/AU_32506021000036107/<date>/`, which is the layout
`azure-pipeline.keep-release.yml` already reads. Published, not yesterday's
daily build - a build's delta is cumulative from the last publication, so two
builds share no delta boundary and the derivation assertions would fail on
correct content. Unresolvable means the run fails, because 66% agreement is not
a parity measurement.

### Two bugs this found by being run

**The group list was never sent.** `GROUPS` is a bash special variable holding
the caller's group ids, so assigning to it did nothing and the submission
carried `--groups 1103459` - a gid - which RVF answers with HTTP 412. Renamed
to `ASSERTION_GROUPS`. A pipeline, a gate and this README all described a
harness whose submission was malformed.

**libaio.so.1 does not exist on Ubuntu 24.04.** It is `libaio.so.1t64` after
the time_t rename, and mysqld dies before logging anything, which reads like a
corrupt download. The script now symlinks it into `$WORK/libs`.

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
