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


## 2. The image

Already in ACR, pushed 2026-09-03 from `catchup-upgraded@6393b937dcca`:

    ontoserver.azurecr.io/aehrc-rvf/release-validation-framework:9.0.1-duckdb
    ontoserver.azurecr.io/aehrc-rvf/release-validation-framework:9.0.1-duckdb.6393b937dcca

    digest  sha256:e866fa2cad9f81762763c7fdba98f96e52e6f2fbf791aff8e697bf76a078e894

Both tags are that one digest. **The manifests already reference
`:9.0.1-duckdb`, so nothing needs editing** - though pinning the
`.6393b937dcca` tag makes the deployed image immutable, which is the better
choice for anything long-lived.

Nothing else in that repository was disturbed. It already held nine tags
(`production-20260818`, `known-good-*`, `drools-*`, `latest`). `latest` still
points at its 2023-05-03 image and was deliberately left alone: the repository
is shared with other RVF builds, so `latest` there does not mean "the DuckDB
engine". The registry has no webhooks - checked before pushing - so the push
could not have redeployed anything.

**What is inside:** the jar, the Drools rules at `/app/snomed-drools-rules`, the
assertion corpus at `/app/snomed-release-validation-assertions`, and the
precompiled assertion store at `/app/resources/duck/store.json`. Nothing needs
provisioning afterwards — on the first run the log says
`Assertion store bundled /duck/store.json verified against 360 corpus files`.

---

### Rebuilding it

**That first push was by hand, and that is the last one that should be.** The
image must be rebuildable from a committed tree by CI, or the thing running in
production is whatever happened to be on somebody's machine that afternoon.

`az/azure-pipeline.image.yml` does it: it resolves the version from the pom,
builds the three pinned forks, runs the test suite, and pushes tagged by both
version and commit. It needs a pipeline definition creating against this repo
and branch. `trigger: none` is deliberate - an image push is not something a
pull request should do - so it is run manually or from a release process.

**It does not work yet, and the reason matters.** Definition 65
`rvf-duckdb-server-image` exists, is authorised for the ACR connection, the
variable group and the pool, and has been run twice:

* On `ncts-k8s-dedicated-pool` (build 16149):
  `Cannot access ihtsdo-releases (https://nexus3.ihtsdotools.org/...)`. That
  agent reaches Maven Central - it fetched plexus artifacts in the same step -
  but not SI's Nexus.
* On a hosted agent (build 16150): `artifacts could not be resolved`.

Same root cause both times. `org.snomed:snomed-parent-bom` is **not on Maven
Central** - every version 404s - and this project and all three forked libraries
inherit from it. Fetching that POM from nexus3 by hand takes **1.4 seconds**,
but a full Maven resolution against an **empty** local repository hangs: 40
minutes with no output, then an HTTP reactor error.

The build scripts previously passed `-o` (offline), which hid this entirely -
they only ever worked on a machine with a warm local repository, which is to say
only on the machine that had already built them. Removed, so the failure is
visible rather than latent.

**What that means for a committed, rebuildable image.** The *source* is
committed and pinned: the patches in `duck/`, the upstream commit each script
checks out, and `checkout-resources.sh` on explicit corpus and rules commits.
What is not reproducible is resolving SI's parent POMs from scratch. The fix is
to stop rebuilding the forks in CI and publish the three artefacts to the
org-scoped Azure Artifacts feed (`OD225632-NCTS-ContentAndTooling`, which
already exists), then resolve them as ordinary versioned dependencies - which
also removes a 40-minute fork build from every image build.

**The image already in ACR is unaffected.** It was built from this tree and its
provenance is the commit in its tag.

`duck/push-image.sh` remains for break-glass use and documents the
subscription, the token dance and the tagging rules, but prefer the pipeline.

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

`RequestHeaderAuthenticationDecorator` builds a
`PreAuthenticatedAuthenticationToken` out of `X-AUTH-username` / `-roles` /
`-token` and **validates none of them**. `SecurityConfig` is
`.anyRequest().authenticated()` with **zero** `@PreAuthorize`, `@Secured` or
`hasRole` anywhere in the codebase - so `X-AUTH-roles` gates nothing inside RVF.

That is not a defect to work around: it is the design. SI runs RVF behind an
identity service that sets those headers, and what follows is that service's
replacement.

Unauthenticated by design: `/version`, `/swagger-ui.html`, `/swagger-ui/**`,
`/v3/api-docs/**`. Everything else needs the three headers and nothing more.

### Decision: Keycloak in front, via oauth2-proxy

RVF is to be **internet-facing so SI can run releases against it**, which rules
out the cheap options. `k8s/rvf-auth-ingress.yaml` implements it.

    internet -> ingress-nginx (TLS) -> oauth2-proxy -> rvf-api (ClusterIP)
                                          |
                                      Keycloak (OIDC)

**oauth2-proxy sits IN the request path, not beside it.** ingress-nginx's
`auth-url` pattern would leave nginx forwarding the original request, so
translating oauth2-proxy's `X-Auth-Request-*` response headers into RVF's
`X-AUTH-*` needs a `configuration-snippet` - and snippet annotations have been
disabled by default since ingress-nginx 1.9 (CVE-2021-25742). Proxying instead
lets oauth2-proxy inject the exact header names RVF wants, with no snippet and
no cluster-wide policy change.

**Header mapping**, via oauth2-proxy's alpha config, which is the only form that
can inject arbitrary header names:

| RVF header | source | note |
|---|---|---|
| `X-AUTH-username` | `preferred_username` | |
| `X-AUTH-roles` | a `rvf_roles` claim | **must be comma separated** - RVF passes it straight to `AuthorityUtils.commaSeparatedStringToAuthorityList` |
| `X-AUTH-token` | the access token | RVF ignores it but FORWARDS it to the traceability and acceptance-gateway clients |

**Both a browser flow and a machine flow**, because SI submitting a release from
their CI cannot follow a login redirect. `--skip-jwt-bearer-tokens=true` (badly
named: it *enables* Bearer validation) accepts a Keycloak-issued JWT on the
`Authorization` header, while browsers get the ordinary OIDC cookie flow.
`--bearer-token-login-fallback=false` makes a bad token a 401 rather than a 302
to a page an API client cannot use. If SI's service-account tokens carry a
different audience, `--extra-jwt-issuers` is the flag.

**`--allowed-group=rvf-users` is not optional.** It is the *entire*
authorisation model: RVF checks no roles, so every authenticated realm user
would otherwise be able to submit runs, read every report and delete releases.
Authorisation lives at the proxy or it does not exist.

**Two things that make an 853MB upload work**, and are 413s or timeouts
otherwise: `proxy-body-size: 2g` (the nginx default is 1m, and it fails *after*
the client has sent the whole body) and `proxy-request-buffering: off`, so the
ingress does not spool hundreds of megabytes to disk before forwarding a byte.
Better still, have SI stage releases into the job store and use
`/run-post-via-s3`, which moves no body through the ingress at all.

**A NetworkPolicy** restricts `rvf-api` to the proxy, so an Ingress later
pointed at `rvf-api` by mistake does not become an open door. Note it has
deliberately no second rule: an ingress rule with `from: []` means "from
anywhere" and would silently negate the first, which looks like a policy and is
not one. If your CNI blocks kubelet probes, add the node CIDR rather than
widening it.

### What to create in Keycloak

1. A confidential client `rvf` with the redirect URI
   `https://RVF_PUBLIC_HOST/oauth2/callback`.
2. A group `rvf-users`, and a `groups` claim mapper so membership reaches the
   token.
3. A `rvf_roles` claim mapper emitting a **comma-separated** string. If a
   multi-valued mapper is used instead, verify how oauth2-proxy joins it before
   trusting the result - see the acceptance tests.
4. For SI: a service-account client with the `rvf-users` group, so their CI can
   fetch a token by client credentials.

### The acceptance tests that matter

Three, and the third is the one people skip:

    # 1. anonymous is refused
    curl -si https://RVF_PUBLIC_HOST/releases | head -1
    # expect 302 to Keycloak (browser flow) or 401

    # 2. a service-account token works
    TOKEN=$(curl -s -X POST \
      https://KEYCLOAK_HOST/realms/REALM/protocol/openid-connect/token \
      -d grant_type=client_credentials -d client_id=si-rvf-client \
      -d client_secret=... | python3 -c 'import json,sys;print(json.load(sys.stdin)["access_token"])')
    curl -si -H "Authorization: Bearer $TOKEN" https://RVF_PUBLIC_HOST/releases | head -1
    # expect 200

    # 3. HEADER SPOOFING IS REFUSED - the one that decides whether any of this works
    curl -si https://RVF_PUBLIC_HOST/releases \
      -H 'X-AUTH-username: attacker' \
      -H 'X-AUTH-roles: ROLE_ihtsdo-ops-admin' \
      -H 'X-AUTH-token: anything' | head -1
    # expect 302/401. A 200 means the proxy passed the client's own headers
    # through and the whole deployment is wide open, because those three headers
    # are all RVF has ever required.

Test 3 is not paranoia: `X-AUTH-token: totally-made-up-token` returns 200
against RVF directly, measured. oauth2-proxy strips headers it sets itself, but
these are custom names, so prove it rather than assume it.

### Rejected, and why

* **ClusterIP with no ingress.** What I would recommend for the nightly alone,
  and it is what the pipeline needs - but it cannot serve SI, who are the point
  of exposing this.
* **Ingress passing `X-AUTH-*` straight through.** Every reader becomes an
  administrator.
* **Keycloak Gatekeeper / Louketo.** Archived upstream.
* **Making RVF validate the token itself.** The right long-term answer and worth
  raising with SI, since it would close the gap for every RVF deployment rather
  than ours. It is a change to `ihtsdo-spring-sso`'s contract, so it does not
  block this. Until then the proxy is a single point of failure for
  authentication, and that is worth stating plainly to whoever signs off.

## 7. The file set, and where it lives

Everything is on `catchup-upgraded` in this repository. **Nothing has been moved
for this handover, deliberately**: the ADO pipeline definitions reference
`az/azure-pipeline.*.yml` by path, and the docs cross-reference each other, so
relocating them into a `deploy/` tree would break both for tidiness alone.

### Yours to apply

| file | what it is |
|---|---|
| `k8s/HANDOVER.md` | this document - start here |
| `k8s/rvf-aks.yaml` | the deployment: PVCs, ActiveMQ, API, worker pool, KEDA |
| `k8s/rvf-auth-ingress.yaml` | Keycloak via oauth2-proxy, ingress, TLS, NetworkPolicy |
| `k8s/rvf-scaledjob.yaml` | job-per-run alternative to the worker Deployment; pick one |

### Read before deciding, do not apply

| file | why it matters to you |
|---|---|
| `k8s/README.md` | the **single-container** option: no broker, no shared volume, zero configuration. Honestly the better choice if only the nightly runs; the split earns its keep with concurrent consumers |
| `k8s/HOSTING.md` | why listener-claims-work was chosen over job-per-run, and what was rejected |
| `k8s/SCALING.md` | sizing rationale. **Superseded on memory by §4 here** - it predates the MRCM measurement and says 10Gi where 16Gi is needed |
| `k8s/rvf.yaml` | the Docker Desktop proof. Uses `hostPath`, so it cannot span nodes - reference only |

### Ours, not yours, but you will be asked about them

| file | why |
|---|---|
| `az/azure-pipeline.image.yml` | rebuilds and pushes the image from a committed tree; needs a definition creating |
| `az/azure-pipeline.nightly.yml` | the nightly against the deployed API. **Blocked on the two answers in §8** |
| `az/azure-pipeline.engine-ab.yml` | PR gate, runs both engines on an agent. Nothing to do with the cluster |
| `duck/build-pinned-forks.sh` | builds the three forked libraries the pom pins; CI calls it |
| `duck/push-image.sh` | break-glass manual push; documents the subscription and tagging rules |
| `duck/ROADMAP.md` | the whole plan this deployment is phase 2 and 3 of |
| `duck/NIGHTLY-PLAN.md` | where the runtime went and what is left; the source of the 16Gi figure |

### Suggested order

1. Read §1 and `k8s/README.md`, and decide **split or single container**. If
   single, most of `rvf-aks.yaml` stops being relevant and the auth layer still
   applies.
2. Create the `activemq-credentials` secret and confirm KEDA is installed.
3. Apply `k8s/rvf-aks.yaml`. Run the §5 smoke test - the cross-node one.
4. Set up Keycloak per §6, apply `k8s/rvf-auth-ingress.yaml`, run all three
   acceptance tests. **Do not skip the spoofing test.**
5. Send back the two answers in §8.

## 8. What comes back to us

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
5. **The public hostname** you give it, and the Keycloak realm and issuer URL,
   so `k8s/rvf-auth-ingress.yaml`'s placeholders can be filled in and the SI
   service-account client can be described to them.
6. **The result of acceptance test 3** - the header-spoofing one. That single
   result is the difference between an internet-facing RVF that is secured and
   one that is wide open, and it is not something we can test from here.

Once 1 and 2 are back, the nightly can run alongside `daily-rvf` and the two
reports can be compared night by night with `ci/compare_reports.py --gate`.

One caveat on that comparison, found 2026-09-02: **RVF's exported failure
instances are not reproducible.** One assertion names a different sample of
failing concepts on every run, including between two otherwise identical runs.
Failure counts and buckets are stable, so compare at count level; an
instance-level diff will show spurious differences.
