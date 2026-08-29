# Hosting a DuckDB RVF for SNOMED International to try

Purpose: a URL SI can point at a release package and get a report from, without
installing anything or standing up MySQL. Everything below is sized from
measurements on this codebase, not estimates.

## Measured envelope (2026-08-29, `catchup-upgraded@b6476d36`)

Single container, DuckDB engine, real AU/international release
`amtv4-15912.zip` (853 MB, 66 RF2 files, 45.3M lines), Drools/MRCM off, groups
file+component+release-type:

    peak RSS                        4.76 GB     (-Xmx8g, host 23 GB)
    CPU actually used               ~2.2 cores  (219.8% of 8)
    submit -> COMPLETE              194 s
    upload, loopback                7.8 s
    report                          191 run / 58 fail / 1 warn / 0 skip / 54 incomplete
    MySQL required                  none
    external broker required        none (in-JVM vm://)
    store provisioning              none (baked into the image)

**4 vCPU / 8 GiB is enough for a full international release.** That matters
because it is exactly the Azure Container Apps Consumption ceiling.

## Three constraints that decide the shape

### 1. The 853 MB upload will not go through a serverless ingress

Azure Container Apps has no configurable request-body limit and returns
`413 Payload Too Large` on large uploads; overriding it in the app is ignored
because the limit is enforced at the ingress proxy.

**This is already solved in RVF and needs no code change.** `/run-post-via-s3`
plus `isProspectiveFileInS3` exist precisely for this: the client PUTs the zip
to object storage with a pre-signed URL and the API is handed a key, not a body.
Any hosted deployment should treat `/run-post` multipart as the local-only
convenience path and the S3/blob path as the real one.

### 2. The work outlives the HTTP request, so request-based autoscaling can kill runs

`POST /run-post` returns `201` in seconds; the validation then runs for ~194 s
inside the same container. Container Apps scales replicas on its own rules, not
on request completion, and a replica chosen for scale-in gets `SIGTERM` with a
30 s grace period by default — far less than a run.

A single container with `minReplicas=0` therefore survives a 194 s run only
because the ~5 minute inactivity cooldown happens to be longer than the run. A
bigger edition, or an international release with a previous release supplied,
breaks that coincidence. **Do not rely on it.** Either pin `minReplicas=1`, or
scale on queue depth (which stays non-zero for the duration of the work), or use
a job whose lifetime *is* the work's lifetime.

### 3. There is no authentication to expose

`RequestHeaderAuthenticationDecorator` builds a `PreAuthenticatedAuthenticationToken`
directly from `X-AUTH-username` / `X-AUTH-roles` / `X-AUTH-token`. Decompiled,
it never validates the token against IMS or anything else — it only compares it
with the session's existing token to decide whether to re-authenticate. Our own
smoke test authenticated with `X-AUTH-token: local`.

That is the standard trusted-header pattern and it is fine *behind a gateway*.
It means a hosted instance **must** sit behind something that authenticates the
caller and then sets those headers itself, **and strips any client-supplied
`X-AUTH-*`**. Options: Container Apps' built-in Entra ID auth, API Management,
or an Application Gateway. Exposing the container directly gives the world an
authenticated RVF.

## Options

| option | fits our envelope | scale to zero | per-run isolation | upload path | verdict |
|---|---|---|---|---|---|
| ACA app, single container, min=1 | yes, 4 vCPU/8 GiB | no (pinned) | no | blob + `via-s3` | **simplest thing that works** |
| ACA app, single container, min=0 | yes | yes | no | blob + `via-s3` | unsafe: constraint 2 |
| ACA app, split + KEDA on queue depth | yes | yes | no | blob + `via-s3` | correct, needs external broker |
| ACA Jobs (event-driven) | yes | yes | yes | blob + `via-s3` | needs one-shot worker, see below |
| AKS, existing `k8s/rvf.yaml` split | yes, no 8 GiB ceiling | with KEDA | no | either | best if a cluster already exists |
| AKS + KEDA `ScaledJob` | yes | yes | yes | either | ideal shape, needs one-shot worker |

## Workers as Jobs: the right model, with one real blocker

Validation is batch work — consume a message, run, write a report, exit — so a
Job models it better than a Deployment: no idle worker pods, per-run resource
sizing, automatic retry/backoff, and a crashed run cannot poison a long-lived
pod. Both platforms support it (`ScaledJob` on AKS via the KEDA add-on,
event-driven Jobs on Container Apps), so this does **not** require AKS.

**The blocker is in RVF, not the platform: the worker never exits.**
`ValidationMessageListener` is a `@JmsListener` gated on
`rvf.execution.isWorker=true`, and there is no `CommandLineRunner`,
`ApplicationRunner` or `System.exit` anywhere in `src/main/java`. A Job would
run the validation and then sit there forever, so the Job never reaches
`Completed` and a `ScaledJob` would pile up pods indefinitely.

What it would take: a one-shot mode that does a single blocking `receive()` with
a timeout, runs that validation, writes the report, and exits — non-zero if the
run failed, so the Job's backoff and retry mean something. That is a small,
self-contained change, but it is a **new deployment contract** rather than a bug
fix, so it should be proposed on its own merits and not smuggled into the engine
PRs.

Until then, `KEDA ScaledObject` on the existing worker Deployment gives the main
prize — scale to zero on an empty queue — with no code change at all, and is
what `k8s/README.md` already names as the intended shape.

## Recommendation

**If AEHRC already runs an AKS cluster** (the Ontoserver infrastructure is on
Azure and the image already publishes to `ontoserver.azurecr.io`), host there:
`k8s/rvf.yaml` already exists and is verified, the marginal cost is node
capacity that is already paid for, there is no 8 GiB ceiling, and KEDA is a
managed add-on. Fix the one known gap first — `hostPath` job storage must become
`rvf.validation.job.storage.useCloud=true` against blob/S3 before replicas can
span nodes.

**If there is no cluster**, use a single Container App, `minReplicas=1`,
4 vCPU / 8 GiB, with Entra ID auth in front and the blob upload path. One
resource, no broker, no database, no volume, and the store ships inside the
image. Revisit Jobs only when concurrent runs or per-run isolation actually
justify the one-shot change.

Either way the SI-facing surface is the same: a URL, a pre-signed upload
location, and `GET /result/{runId}`.

## Cost drivers, honestly

The dominant term is **whether anything is always on**, not per-run compute: a
run is only ~194 s × ~2.2 cores. ACA Consumption grants 180,000 vCPU-seconds and
360,000 GiB-seconds free per subscription per month; at 4 vCPU / 8 GiB a 194 s
run consumes 776 vCPU-s and 1,552 GiB-s, so **the free grant alone covers on the
order of 230 full international validations a month** — but a single always-on
4 vCPU replica burns 10.4M vCPU-seconds a month and dwarfs all of it. So the
levers, in order: keep the always-on footprint small or zero; then per-run size;
then storage and egress. I have not priced Australia East rate cards here —
compute it from the published rate card once the shape is chosen.

## Not verified

- Nothing has been deployed to Azure. The measurements are from a local
  single-container run on this host; the split numbers in `README.md` are from
  Docker Desktop k8s.
- The blob/`via-s3` upload path has not been exercised against Azure Blob with a
  pre-signed URL, only asserted from the endpoint's existence and parameters.
- Whether a >8 GiB peak is reachable with a previous *and* dependency release
  supplied is unmeasured; 4.76 GB is the one-release figure.
