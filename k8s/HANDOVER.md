# RVF on AKS — handover

For the person applying this to a real cluster. Everything here was prepared and
tested off-cluster; nothing in it has been applied to AKS, and the parts that
cannot be proven without a cluster are marked as such rather than implied.

Written 2026-09-03 against `catchup-upgraded`.

---

## 1. What this deployment is

An RVF that validates a SNOMED release using **DuckDB instead of MySQL**. Same
assertions, same report shape, no database to run. On the AU edition the full
scope — SQL, structural, Drools and MRCM — takes about **13 minutes**, against
**181 minutes** for the current `daily-rvf` pipeline.

Two pods, because the two halves want very different machines:

    rvf-api      2 replicas    accepts validations, serves reports, never executes
    rvf-worker   1..6 (KEDA)   executes validations, needs 16Gi
    activemq     1 replica     the queue between them

The split needs no code: `rvf.execution.isWorker` is RVF's own pre-existing
property and gates the JMS listener. `false` gives an API that enqueues and
never executes; `true` gives a headless consumer.

**If you would rather not run a broker and a shared volume at all**, a single
container does both halves with zero configuration — see `k8s/README.md`. That
is the honest recommendation for one nightly run; the split earns its keep when
a second concurrent consumer exists.

---

## 2. Push the image

**Prefer CI.** `az/azure-pipeline.image.yml` in this repo builds and pushes it,
tagged by both version and commit, so a running pod is traceable to a tree and
nobody's laptop is in the supply chain. Create it as a definition against this
repo and branch and run it manually - it is `trigger: none` deliberately, since
an image push is not something a pull request should do.

Two other places build an image and neither publishes THIS one, which is worth
knowing before assuming CI already covers it:

* `azure-pipeline.engine-ab.yml` runs `jib:buildTar` and attaches the tarball as
  a pipeline artifact. Deliberate: it is a PR gate, and a gate that pushes to a
  shared registry on every pull request is how `latest` gets broken.
* ADO definition **63 `rvf-duck-image` does push** - but it builds the **Python**
  DuckDB engine from `aehrc/rvf@rvf-duck`, a different codebase, and it pushes
  `aehrc-rvf/rvf-duck`.

**That last point mattered.** The manifests here used to pull
`aehrc-rvf/rvf-duck:latest`, which is definition 63's Python engine image, not
this Java server. Applying them would have deployed the wrong artefact, or
whichever of the two pushed last.

Now they pull **`aehrc-rvf/release-validation-framework:9.0.1-duckdb`** - the
repository the pom's own coordinates produce, which is also where MySQL-engine
builds of RVF live. **The engine is therefore in the TAG**, and a bare `9.0.1`
must never be pushed here: it would be ambiguous at best and would shadow an
existing build at worst. `latest` is left alone for the same reason.

Every push also gets an immutable `9.0.1-duckdb.<sha12>` companion, so a running
pod is traceable to a tree after the primary tag moves. Because the primary tag
moves, both manifests set `imagePullPolicy: Always` - the default for any tag
other than `latest` is `IfNotPresent`, which would quietly keep an old layer set
after a re-push.

### Doing it by hand instead

    ./duck/push-image.sh              # resolves the subscription, pushes both tags
    DRY_RUN=1 ./duck/push-image.sh    # resolve and report, push nothing

The script exists because three things here are easy to get wrong: `az acr
login` wants a Docker daemon while `--expose-token` does not; the registry may
not be in the subscription you are defaulted to; and the repository is shared
with MySQL-engine builds, so it refuses to push a tag that does not name the
engine. It prints the tags that already exist before pushing, and fails if the
immutable tag for the current commit is already there.

Note that a tenant named for the registry is not the same thing as a
subscription named similarly, and the two can differ in whether MFA or
conditional access blocks token issuance. The script says which subscription it
found the registry in, and what to do if it finds none.



Built with jib, so **no Docker daemon is required** — it assembles and pushes
layers itself. Verified: a 480 MB amd64 tarball builds from a clean tree.

Note two things the command has to override, because the defaults are wrong for
us: the pom's coordinates produce `.../release-validation-framework`, while the
manifests pull `.../rvf-server`, and the default registry is Docker Hub.

    # daemon-free: ACR issues a token, jib uses it directly
    TOKEN=$(az acr login --name ontoserver --expose-token \
              --output tsv --query accessToken)

    mvn -B -ntp package -DskipTests jib:build \
        -Djib.from.platforms=linux/amd64 \
        -Djib.to.image=ontoserver.azurecr.io/aehrc-rvf/release-validation-framework:9.0.1-duckdb \
        -Djib.to.tags=latest \
        -Djib.to.auth.username=00000000-0000-0000-0000-000000000000 \
        -Djib.to.auth.password="$TOKEN"

If you have a daemon and prefer it, `az acr login --name ontoserver` then the
same command without the two `auth` flags.

`linux/amd64` only, deliberately. The pom declares arm64 as well for local Macs;
building both doubles push time for a platform no node runs.

**What is inside:** the jar, the Drools rules at `/app/snomed-drools-rules`, the
assertion corpus at `/app/snomed-release-validation-assertions`, and the
precompiled assertion store at `/app/resources/duck/store.json`. Nothing needs
provisioning afterwards — on the first run the log says
`Assertion store bundled /duck/store.json verified against 360 corpus files`.

---

## 2a. Building it on a different machine first

**A fresh checkout cannot build.** The pom pins three libraries to
`-aehrc-perf` versions that exist in **no remote repository** - they are built
from IHTSDO sources plus the patches in `duck/` and installed into the local
Maven repo. `mvn package` on a clean laptop fails to resolve them.

One command fixes that, from the repo root:

    ./duck/build-pinned-forks.sh

It reads the three versions out of the pom, clones each upstream at a pinned
commit, applies the patch, installs, and then gates on the artefacts actually
resolving. Verified from clean clones: about 30 seconds with a warm Maven repo.

It exists because the individual scripts each carry their own default version
and those had drifted behind the pom - 6.1.1 against 6.1.3, 4.0.3 against 4.0.4
- so running them with no argument installs artefacts the pom does not want and
the build still fails, one version number away from working. The wrapper takes
the pom as the single source of truth.

Needs JDK 25, maven, git, and network to github.com and Maven Central. Honours
`MAVEN_REPO_LOCAL` and `FORKS_BUILD_DIR`; defaults to `~/.m2/repository` and a
temporary directory.

**The corpus and rules are fetched, not committed.** `snomed-drools-rules/` and
`snomed-release-validation-assertions/` are gitignored and cloned by
`checkout-resources.sh`, which the pom runs during the build. That script is
**pinned to explicit commits**, deliberately: upstream's version clones a branch
with no ref, and on 2026-08-07 the first rebuild in two years picked up two
years of drift and RVF failed to start - IHTSDO had removed the `_EDITION` SQL
variants while the manifest still referenced four of them, and the k8s job was
killed after 12 minutes having produced no report. Because it is pinned, a
laptop build gets the same corpus this repo's baked assertion store was compiled
against, and the image's startup check
(`verified against 360 corpus files`) passes.

So the whole sequence on a laptop is:

    git clone -b catchup-upgraded <this repo> && cd release-validation-framework
    ./duck/build-pinned-forks.sh
    TOKEN=$(az acr login --name ontoserver --expose-token --output tsv --query accessToken)
    mvn -B -ntp package -DskipTests jib:build \
        -Djib.from.platforms=linux/amd64 \
        -Djib.to.image=ontoserver.azurecr.io/aehrc-rvf/release-validation-framework:9.0.1-duckdb \
        -Djib.to.tags=latest \
        -Djib.to.auth.username=00000000-0000-0000-0000-000000000000 \
        -Djib.to.auth.password="$TOKEN"

## 3. What `rvf-aks.yaml` is, object by object

    Namespace rvf

    PVC rvf-jobs       100Gi  ReadWriteMany  azurefile-csi-premium
    PVC rvf-releases    50Gi  ReadWriteMany  azurefile-csi-premium

`rvf-jobs` is the **handoff**, and it is the whole reason the split works: the
API writes an uploaded release there and later serves a report it never
produced; a worker reads that release and writes the report. `ReadWriteMany` is
therefore a hard requirement, and Premium is deliberate — an 853 MB release goes
across it twice per run.

`rvf-releases` holds published releases so a nightly can validate against the
last one. Under the DuckDB engine this is the entirety of "RVF already has last
month's release": `previousRelease` resolves a **filename** from here.

    Deployment activemq + Service activemq       the queue
    Deployment rvf-api  + Service rvf-api        ClusterIP :8080
    Deployment rvf-worker                        no service; consumes the queue
    ScaledObject rvf-worker                      KEDA, on ActiveMQ queue depth
    TriggerAuthentication activemq-auth          reads secret activemq-credentials

**Scaling is on queue depth, not CPU or requests.** A validation runs for
minutes after its HTTP request returned 201, so anything scaling on request rate
would retire a pod mid-run. `terminationGracePeriodSeconds: 600` covers a full
edition.

### What you must create that is not in the file

1. **Secret `activemq-credentials`** with keys `username` and `password`, matching
   whatever the ActiveMQ deployment is configured to accept. KEDA reads the
   broker's management endpoint with them.
2. **KEDA itself**, if the cluster does not already run it.
3. **An ingress**, only if the API must be reachable from outside the cluster —
   see the auth section, and read it before deciding.

### Two questions only you can answer

- **The `rvf-jobs` share name.** Both PVCs are *dynamically* provisioned, so
  Azure Files generates the share name. The nightly pipeline
  (`az/azure-pipeline.nightly.yml`) has to upload the release into that same
  share, and its `jobStoreShare` parameter is a placeholder. Either bind a
  **static** share and set the parameter, or apply as-is and send back the
  generated name. This is the one thing blocking the pipeline from running.
- **Node ephemeral disk.** The worker's `/work` `emptyDir` has
  `sizeLimit: 40Gi` — two materialised editions plus spill plus an unpacked
  release. If the node SKU cannot back that, the run fails on a full disk.

---

## 4. Traps that have already cost time

**Every storage path is relative, even if it looks absolute.**
`ResourceConfiguration.normalisePath()` strips a leading `/`, so
`rvf.validation.job.storage.local.path` is always relative to the working
directory (`/app`). Setting it to `/jobs/` starts cleanly and then silently
gives each pod its own private directory: the API returns 201, the worker
consumes the message, and the run dies on `Prospective file can't be null`. The
manifest therefore sets `jobs/` and mounts the volume at **`/app/jobs`**.

**`AWS_REGION` is required to start, with no cloud storage and no Drools.**
`DroolsRulesValidationService.init()` builds an S3 client in `@PostConstruct`
and the AWS SDK refuses to construct one without a region.

**`RVF_DUCK_THREADS` must match the CPU limit.** DuckDB sizes its thread pool
from the machine, not the cgroup quota. On a 64-core node with the value unset
it starts 64 threads inside a 2-core quota: measured **674 s against 259 s**
once bounded. It is set to 8 to match `limits.cpu: 8`.

**Do not put `-Xmx` in the manifest.** Fixed 2026-09-03, and worth knowing why:
the image used to bake `-Xmx8g` into its entrypoint, and a flag on argv **beats
`JAVA_TOOL_OPTIONS`** because the launcher prepends the environment's flags and
the JVM takes the last `-Xmx`. Both manifests set the heap through
`JAVA_TOOL_OPTIONS`, so it was silently ignored — the API ran a JVM believing it
had 8 GB inside a 2Gi limit and survived only because it never grew. The image
now uses `-XX:MaxRAMPercentage=75`, so **`limits.memory` is the only knob**:
2Gi gives a 1.5 GB heap, 16Gi gives 12 GB.

**Worker memory is sized by MRCM, not by SQL.** SQL-only runs peak under 4.8 GB,
which is what the old 10Gi limit was measured against. MRCM holds the whole
722,404-concept map on the heap while building two Lucene indexes and peaks at
**11.3 GB**. Hence 16Gi. Drop back to 10Gi only if MRCM is switched off.

---

## 5. The smoke test that actually proves something

Cross-node handoff is the only thing Docker Desktop could not prove, because
`hostPath` forced both pods onto one node. So test exactly that:

    kubectl -n rvf get pods -o wide          # confirm api and worker differ by NODE
    kubectl -n rvf port-forward svc/rvf-api 8080:8080 &

    RUN=$(date +%s)
    curl -X POST http://127.0.0.1:8080/run-post \
      -H "X-AUTH-username: smoke" -H "X-AUTH-roles: ROLE_USER" -H "X-AUTH-token: t" \
      -F file=@release.zip -F runId=$RUN -F storageLocation=smoke \
      -F groups=file-centric-validation -F releaseAsAnEdition=true \
      -F enableDrools=false -F enableMRCMValidation=false

    curl -H "X-AUTH-username: smoke" -H "X-AUTH-roles: ROLE_USER" -H "X-AUTH-token: t" \
      "http://127.0.0.1:8080/result/$RUN?storageLocation=smoke"

**Pass condition:** a report produced by the worker pod is served by an API
replica that never executed it. Confirm from the logs that the API shows no
`ntContainer` consumer thread and the worker shows no `nio-8080-exec` request
thread for that run.

Note the parameter spelling if you enable MRCM: **`enableMRCMValidation`**,
capital MRCM. A misspelling binds silently to the `false` default and yields a
COMPLETE report in 94 s with 62 assertions instead of 1037 — green, fast, and
validating almost nothing. The nightly pipeline now asserts against this.

---

## 6. Authentication — read this before exposing anything

**RVF has no authentication and no authorisation.** This is measured, not
inferred:

| test against a live RVF | result |
|---|---|
| `X-AUTH-token: local` | 200 |
| `X-AUTH-token: totally-made-up-token` | **200** |
| `X-AUTH-token: ''` (empty) | **200** |
| no `X-AUTH-*` headers | 401 |
| `X-AUTH-username` only | 401 |

`RequestHeaderAuthenticationDecorator` builds a `PreAuthenticatedAuthentication‐
Token` out of `X-AUTH-username` / `-roles` / `-token` and **validates none of
them**. `SecurityConfig` is `.anyRequest().authenticated()` with **zero**
`@PreAuthorize`, `@Secured` or `hasRole` anywhere in the codebase — so
`X-AUTH-roles` is decorative. The roles you send gate nothing.

Unauthenticated by design: `/version`, `/swagger-ui.html`, `/swagger-ui/**`,
`/v3/api-docs/**`.

So the security model is simply: **anyone who can reach port 8080 can do
anything** — submit runs, read every report, delete releases. There is no
authorisation layer to harden, only reachability.

### The options

**A. Cluster-internal only. No ingress.** Leave the Service as `ClusterIP`. The
only caller is the nightly pipeline, and its agents already run in this cluster
(`ncts-k8s-dedicated-pool`), so nothing needs to be exposed. Cost: nothing.
Humans read results in the Azure DevOps test tab, which is already built.

**B. Ingress with an identity-aware proxy.** Put OAuth2 Proxy or equivalent in
front, authenticate against Entra, and have the proxy **inject** the `X-AUTH-*`
headers and — critically — **strip any the client supplied**. Needed only when
humans need the RVF UI or the Release Dashboard directly.

**C. Ingress with the headers accepted as-is.** **Do not.** It makes every
reader an administrator, and the 200s in the table above are why.

**D. Make RVF validate the token.** A real fix and upstream-worthy, but it is a
change to `ihtsdo-spring-sso`'s contract and blocks the deployment on a code
change nobody has scoped.

### Recommendation

**Start with A, and treat B as the trigger for exposing anything.**

The parallel run needs no ingress at all: the pipeline is the only client and it
is in-cluster. That gets the nightly running against zero new attack surface,
and defers the identity work to the point where a human actually needs a URL.

If B becomes necessary, the non-negotiable part is the **stripping**, not the
injecting. An ingress that adds `X-AUTH-username` while passing through a
client-supplied one is option C wearing a hat.

Two things to write down whichever way you go: `/version` is unauthenticated and
will answer to anyone who can route to the pod, and network policy is doing all
the work here, so treat namespace-level egress/ingress rules as part of the
security control rather than as tidiness.

---

## 7. What comes back to us

1. **The `rvf-jobs` share name** (or confirmation of a static share) — this
   unblocks `az/azure-pipeline.nightly.yml`, which cannot run without it.
2. **The API's in-cluster URL**, for that pipeline's `rvfBaseUrl`.
3. **Whether KEDA's ActiveMQ scaler counts in-flight unacknowledged messages.**
   Unknown and unprovable off-cluster. If it does, queue depth stays non-zero
   for the duration of a run and scale-in never targets a busy pod. If it does
   not, the only thing preventing a worker being killed mid-run is
   `terminationGracePeriodSeconds: 600` — which covers a 13-minute DuckDB run
   but would not have covered a 23-minute MySQL one.
4. **Whether the node SKU can back a 40Gi `emptyDir`** per worker.

Once 1 and 2 are back, the nightly can run alongside `daily-rvf` and the two
reports can be compared night by night with `ci/compare_reports.py --gate`.

One caveat on that comparison, found 2026-09-02: **RVF's exported failure
instances are not reproducible.** One assertion names a different sample of
failing concepts on every run, including between two otherwise identical runs.
Failure counts and buckets are stable, so compare at count level; an
instance-level diff will show spurious differences.
