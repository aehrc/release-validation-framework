# RVF on AKS — handover

For the person applying this to a real cluster. Everything here was prepared and
tested off-cluster; nothing in it has been applied to AKS, and the parts that
cannot be proven without a cluster are marked as such rather than implied.

Written 2026-09-03 against `catchup-upgraded`.

---

## 0. What is done, and what is yours

**Done, nothing to set up:**

* The image is built, tested and pushed by CI - ADO definition **65
  `rvf-duckdb-server-image`**. A run with default parameters builds the three
  pinned libraries, runs 340 tests, and pushes. See §2.
* The nightly pipeline is written and wired as ADO definition **66
  `rvf-duckdb-nightly`**, created **disabled** on purpose: it triggers off
  `daily-rvf`'s `RvfStage`, so enabling it before the API exists would fail
  every night. Enable it once §8's answers are in.
* Every manifest references the image tag CI produces, so no YAML needs editing
  before you apply it.

**Yours, in order:**

| # | do | detail |
|---|---|---|
| 1 | decide **split or single container** | §1 and `k8s/README.md`. Single needs no broker or shared volume and is the honest choice if only the nightly runs |
| 2 | create secret `activemq-credentials`, confirm KEDA | §3 |
| 3 | `kubectl apply -f k8s/rvf-aks.yaml` | §3 |
| 4 | run the **cross-node** smoke test | §5. Pass condition is a report written by a worker being served by an API replica that never ran it |
| 5 | set up Keycloak, fill 5 placeholders, apply `k8s/rvf-auth-ingress.yaml` | §6 |
| 6 | run all three acceptance tests | §6. **Do not skip the header-spoofing one** |
| 7 | send back the six answers | §8 |

**The five placeholders**, all in `k8s/rvf-auth-ingress.yaml`:
`client-secret`, `cookie-secret`, `KEYCLOAK_HOST`, `REALM`, `RVF_PUBLIC_HOST`.

**Nothing here has been applied to a cluster**, because there was no cluster
access from where it was written. Everything checkable without one has been
checked, and where something is unverified it says so rather than implying
otherwise.

## 0a. Which cluster, and why

There is no cluster called `ncts-dev-k8s`. The estate is:

| cluster | resource group | 8-core pools | Argo repo |
|---|---|---|---|
| **`ncts-k8s-cluster`** | `ncts` | `large` (D8a_v4), `largespot`, `largespotv5` | **`aehrc/ncts-argo`** |
| `prod-ncts-k8s-cluster` | `ncts-prod` | `larv59b7e` (D8as_v5) x **1** | `aehrc/ncts-argocd-prod` |
| `ontoserver-dev-k8s`, `dev-terminology-k8s-cluster`, `ontoserver-prod-aks` | — | — | different product |

`ncts-argo`'s README calls `ncts-k8s-cluster` "the NCTS **development** AKS
cluster", and it manages only `clusters/ncts-cluster`.

**Target `ncts-k8s-cluster` first.** Three reasons, not one:

* `daily-rvf` already runs its ephemeral RVF job there, in the `rvf-tests`
  namespace. Replacing that pipeline means running the replacement beside it, on
  the same cluster, which is the entire point of the parallel period.
* Every fact in §0b was read from it. Nothing here has been verified against
  prod - not KEDA, not cert-manager, not the storage classes, not the ingress
  arrangement. Do not assume §0b transfers.
* It is the natural first step of the promotion path this shop already uses:
  Kargo promotes Snomio dev -> uat, and Argo has separate repos per cluster.

**The SI-facing, internet-exposed deployment is a SECOND decision, and probably
prod.** Exposing a service for SI to run releases against is not a development
concern, and it would move to `prod-ncts-k8s-cluster` via `ncts-argocd-prod`.
Worth knowing before that day:

**Worker concurrency is capped by hardware, not by KEDA.** The worker needs
8 CPU and a 16Gi limit, so it only fits a 32GB 8-core node. Dev has **one**
non-spot `large` node; prod has **one** `larv59b7e` (D8as_v5). So
`maxReplicaCount: 6` in the ScaledObject is aspirational - in practice you get
one worker, maybe two, until a pool scales out. That is fine for one nightly and
is worth saying out loud before anyone reads 6 as capacity.

## 0b. What the live cluster already told us

Read read-only from **`ncts-k8s-cluster`** - the dev cluster, see §0a -
(subscription `9a9e2788-…`, resource group `ncts`) on 2026-09-03. **None of it
has been checked against `prod-ncts-k8s-cluster`.** This closed four of §8's
questions and found four errors in these manifests before anyone applied them.

**Already there, nothing to install:**

| | |
|---|---|
| KEDA | installed since 2024-12-02 (`scaledobjects`, `scaledjobs` CRDs) |
| cert-manager | ClusterIssuer **`letsencrypt`** - note the name, not `letsencrypt-prod` |
| `azurefile-csi-premium` | present, so both PVCs are valid |
| namespace `rvf-tests` | exists, 447 days - that is where `daily-rvf`'s ephemeral job runs |

**Four things these manifests had wrong, now fixed:**

1. **The worker could never have scheduled.** The pools are
   `nctspool` (D4as_v5, 4 CPU / 16GB, **untainted, the default**), `large`
   (D8a_v4, 8 CPU / 32GB, tainted `node-type=large-production:NoSchedule`), and
   two spot pools. The worker asks for 8 CPU and a 16Gi limit, which cannot fit
   a 16GB node - so with no `nodeSelector` it would have sat **Pending forever**
   on `nctspool`. Now pinned to `large` with the matching toleration, and
   deliberately not the spot pools: an eviction mid-validation loses a
   13-minute run.
2. **The ingress annotations were the wrong family.** Every ingressclass here
   reports `controller=nginx.org/ingress-controller` - **NGINX Inc's**
   controller, not community `ingress-nginx`. The
   `nginx.ingress.kubernetes.io/*` annotations it carried are silently ignored
   by it, which would have left the default 1MB body limit quietly rejecting an
   853MB release. Now `nginx.org/client-max-body-size` and friends.
3. **The ClusterIssuer name was wrong** - `letsencrypt`, not `letsencrypt-prod`.
4. **There is no default ingress class.** This cluster runs **one nginx
   controller per application** (`ontoserver-amtv4-nginx`,
   `ontoserver-fhirpit-r4-nginx`, `llm2hse-nginx-hse`, …), so RVF needs its own
   class and controller following that pattern. Left as
   `REPLACE_WITH_RVF_INGRESS_CLASS` because it cannot be guessed.

**And one thing that changes how you deploy at all: this cluster is ArgoCD-
managed.** `argocd` has been running two years, every Application reports
`Synced`, Kargo handles promotion, and each Ingress carries an
`argocd.argoproj.io/tracking-id`. So `kubectl apply` is not the house
mechanism - these manifests should land in whatever repository drives Argo for
this cluster, as an Application. That is the owner's call, not something to work
around, and it is why §0's step 3 says "apply" only as shorthand.

**Still genuinely open:** §8's KEDA question - whether its ActiveMQ scaler
counts in-flight unacknowledged messages - is a behaviour, not a fact about the
cluster, so it still needs observing under load.

Ephemeral disk is fine everywhere for the 40Gi `emptyDir`: 124GB on the small
pools, 248GB on `large`, 992GB on `largespot`.

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

Built and pushed **by CI**, ADO definition **65 `rvf-duckdb-server-image`**,
build 16157:

    ontoserver.azurecr.io/aehrc-rvf/release-validation-framework:9.0.1-duckdb
    ontoserver.azurecr.io/aehrc-rvf/release-validation-framework:9.0.1-duckdb.f405b6f2036e

    digest  sha256:3a9a4a04e2b99e9db5ba49cae0623e6f69a0f96650b72dadb0b9e3f1ae3fef75

`9.0.1-duckdb` is the moving tag within this version and is what the manifests
reference, so **nothing needs editing**. The `.f405b6f2036e` tag is the same
image pinned to the commit it was built from - use that if you want the deployed
image immutable, which for anything long-lived is the better choice.

An earlier tag `9.0.1-duckdb.6393b937dcca` exists from a manual push and is
superseded. Ignore it.

Nothing else in that repository was disturbed. It already held nine tags
(`production-20260818`, `known-good-*`, `drools-*`, `latest`). `latest` still
points at its 2023-05-03 image and is deliberately left alone: the repository is
shared with other RVF builds, so `latest` there does not mean "the DuckDB
engine". The registry has no webhooks, so a push cannot redeploy anything.

### Rebuilding it

Run definition 65. It resolves the version from the pom, builds the three pinned
forks from their upstream commits plus the patches in `duck/`, packages, and
pushes tagged by version and commit. `trigger: none` is deliberate - an image
push is not something a pull request should do.

It runs on a **hosted** agent, not `ncts-k8s-dedicated-pool`: build 16149 failed
there with `Cannot access ihtsdo-releases (nexus3.ihtsdotools.org)`. That agent
reaches Maven Central but not SI's Nexus. The image job needs internet and ACR,
not the cluster, and every other Maven pipeline in this project already uses the
hosted queue.

Getting a clean agent to build took fixing four things, all of which had been
invisible because the fork scripts passed `mvn -o` (offline) and so only ever
resolved from a warm local repository:

1. **`org.snomed:snomed-parent-bom` is not on Maven Central** - every version
   404s - and this project and all three forks inherit from it. A POM's own
   `<repositories>` cannot resolve that POM's *parent*: Maven reads the parent
   first, when it knows only `settings.xml`. Hence `duck/maven-settings.xml`.
2. **`snomed-parent-bom` binds OWASP dependency-check into the lifecycle.** With
   no cached NVD data and no API key it downloads the entire CVE database -
   385,855 records at the unauthenticated rate limit. That presented as a hang:
   three diagnostic runs sat at 40 minutes with no output before I attached to
   the JVM and found it 3% through the NVD feed. Now skipped explicitly.
3. **`build-query-service.sh` looked for its built jar in a hardcoded
   `$HOME/.m2` and `/data/m2`**, which on an agent is not the repository it had
   just installed into, so a successful install reported "did not install".
4. **`MAVEN_OPTS` as a pipeline variable did not reach the RVF build**, so the
   forks landed in `$(Pipeline.Workspace)/.m2` while RVF resolved against the
   agent's default `~/.m2` and reported all four artefacts missing. Every `mvn`
   call now passes `-Dmaven.repo.local` explicitly.

Verified independently of CI: **318 seconds from a completely empty Maven
repository in a non-default location**, which is what an agent does.

So the image is now genuinely rebuildable from a committed tree - the patches,
the upstream commit each script checks out, and `checkout-resources.sh` pinned
to explicit corpus and rules commits.

**The gate includes the test suite.** `runTests` defaults to true and build
16159 ran with it: **340 tests, 0 failures, 0 errors, 21 skipped**, and
testcontainers spun up real MySQL containers on the way.

Worth recording because I got it wrong first: I had this documented as
"`runTests: false`, because the suite has 14 errors without Docker". Those 14
errors are specific to the machine I develop on, which has no Docker daemon -
hosted agents ship one, so the full suite passes in CI. The earlier green builds
were queued with tests off on an assumption I had not tested.

`duck/push-image.sh` remains for break-glass use and documents the subscription,
the token dance and the tagging rules, but CI is the route.

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

### Confirmed values

    issuer      https://auth.ontoserver.csiro.au/auth/realms/aehrc
    jwks        https://auth.ontoserver.csiro.au/auth/realms/aehrc/protocol/openid-connect/certs
    realm       aehrc
    hostname    ncts-rvf.australiaeast.cloudapp.azure.com
    vault path  acl_vlt_od225632/kv/data/rvf   (client_id, client_secret)

Verified against the discovery document 2026-09-03. **Note the `/auth` path
segment** - this is a legacy-path Keycloak, so the issuer is
`…/auth/realms/aehrc`, not `…/realms/aehrc`. Getting that wrong fails JWKS
retrieval and every request 401s with nothing useful in the logs.
`client_credentials` is advertised, so SI's CI can use it, and
`preferred_username` is advertised so it is available for `X-AUTH-username`.

The hostname follows the house pattern used by grafana, kargo, fhirpit and the
indexer (`<name>.australiaeast.cloudapp.azure.com`).

### What to create in Keycloak

Two clients, deliberately - see below for why they are not one.

| # | create | settings |
|---|---|---|
| 1 | client **`rvf`** | confidential, standard flow **on**, service accounts **off**, redirect URI `https://ncts-rvf.australiaeast.cloudapp.azure.com/oauth2/callback` |
| 2 | client **`si-rvf-client`** | confidential, standard flow **off**, service accounts **on**, no redirect URI |
| 3 | group **`rvf-users`** | human users **and** the auto-created `service-account-si-rvf-client` |
| 4 | mapper on both | Group Membership -> claim `groups`, full path **off** (the policy matches it as `valueType: StringArray`) |
| 5 | mapper on both | Hardcoded claim -> `rvf_roles` = `ROLE_ihtsdo-ops-admin`, type String, in the access token |

**Why two clients rather than service accounts on `rvf`:** sharing one client
means handing SI the secret that also protects the human login flow; rotating
after an SI leak would log out every person at the same moment; and every token
would carry the same `azp`, so an SI submission and a person clicking in the UI
would be indistinguishable in the logs. They also want opposite settings -
`rvf` needs a redirect URI and no service account, `si-rvf-client` the reverse.

**Why `rvf_roles` is a hardcoded String and not a role mapper.** RVF passes
`X-AUTH-roles` straight to Spring's `commaSeparatedStringToAuthorityList`, which
splits **one string** on commas. Keycloak's role and group mappers emit JSON
**arrays**, and how Envoy's `claimToHeaders` renders an array into a header is
not something to guess at. It does not matter what the value is: RVF has no
`@PreAuthorize`, `@Secured` or `hasRole` anywhere, so the header only has to be
present and non-empty - absent headers give 401. All real access control is the
SecurityPolicy's `groups` rule.

### The two secrets, and where each lives

They are consumed by different things in different places, so they do not live
together:

| secret | consumer | where |
|---|---|---|
| `rvf` client secret | the gateway's SecurityPolicy, in-cluster | **Vault** `acl_vlt_od225632/kv/data/rvf` as `client_secret`, surfaced by an ExternalSecret via ClusterSecretStore `vault-backend` |
| `si-rvf-client` secret | the nightly ADO pipeline | **ADO**, as a secret variable `rvf.si.client.secret` in the `ncts-release` variable group |

The nightly runs on an ADO agent, not in the cluster, so Vault is not reachable
to it and the secret has to be in the pipeline's own store. Add it as a **secret**
variable, not plain text.

**The nightly authenticates as `si-rvf-client`**, exactly as SI will. It fetches
a token by `client_credentials` and sends `Authorization: Bearer` - it no longer
sends `X-AUTH-*` headers at all, because the gateway injects those from the
token's claims and strips anything the client supplied. Its `rvfBaseUrl` is the
**public** URL for the same reason: it exercises the path SI takes rather than a
shortcut nobody else uses, and it keeps the NetworkPolicy tight.

Verified against the live Keycloak 2026-09-03: the token endpoint answers a
credential-less `client_credentials` request with
`401 unauthorized_client`, which is the shape the pipeline's error handling
expects, so only the client and secret are missing.

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
| `az/azure-pipeline.image.yml` | rebuilds and pushes the image; **already wired as ADO definition 65 `rvf-duckdb-server-image`** - run it, nothing to set up |
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
4. ~~Whether the node SKU can back a 40Gi `emptyDir`~~ - **answered**: 124GB
   on the small pools, 248GB on `large`, 992GB on `largespot`. See §0b.
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
