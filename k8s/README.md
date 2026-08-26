# DuckDB RVF on Kubernetes — API node + worker pool

Proof-of-concept manifests for the split deployment (task #27). Verified on
Docker Desktop k8s, 2026-08-26, image `rvf-duck:local` built from
`rvf-catchup@b9f56899` with store published from `rvf@2846637`.

## Shape

    rvf-api      replicas=1   RVF_EXECUTION_ISWORKER=false   512Mi-2Gi
    rvf-worker   replicas=N   RVF_EXECUTION_ISWORKER=true    4Gi-15Gi, -Xmx12g
    activemq     replicas=1   apache/activemq-classic:6.1.4

`rvf.execution.isWorker` is RVF's own pre-existing property, not something
added for this. It gates `@JmsListener` on `ValidationMessageListener`, so
`false` gives an API that accepts and enqueues but never executes, and `true`
gives a headless consumer. Scaling the pool is `kubectl scale deploy/rvf-worker`.

## The one non-obvious requirement

**Shared job storage must be mounted at `/app/jobs`, not `/jobs`.**

`ResourceConfiguration.normalisePath()` in `org.ihtsdo.otf.common:resource-manager`
strips a leading `/`, so `rvf.validation.job.storage.local.path` is ALWAYS
relative to the process CWD (`/app` in the jib image). Setting it to `/jobs/`
starts cleanly and then silently gives each pod its own private directory: the
API returns HTTP 201, the worker consumes the message, and the run dies with
`Prospective file can't be null` and a bare NPE. The manifest therefore sets
the path to the relative `jobs/` and mounts the volume at `/app/jobs`.

For a real cluster use `rvf.validation.job.storage.useCloud=true` with S3
instead of a shared filesystem — a `hostPath` only works because both pods
land on the one Docker Desktop node.

## Auth

`RequestHeaderAuthenticationDecorator` (ihtsdo-spring-sso), NOT basic auth.
Every request needs `X-AUTH-username`, `X-AUTH-roles`, `X-AUTH-token`.
`/version` is the only unauthenticated endpoint.

## Store provisioning

None needed. The precompiled assertion store is baked into the artefact at
`src/main/resources/duck/store.json`, and both pods verify it against the
corpus they ship — look for `Assertion store bundled /duck/store.json verified
against 360 corpus files` at startup of the first run. See `duck/README.md`.

`RVF_DUCK_STORE` still overrides it if you want to mount a different corpus's
store without rebuilding.

## Loading the image without a registry

Docker Desktop's kubelet reads containerd, not the Docker daemon's image store,
and the daemon (in the VM) cannot reach a `kubectl port-forward`ed registry.
The route that worked:

    kubectl cp <image>.tar <dind-pod>:/tmp/   # privileged docker:27-dind pod
    ctr -n k8s.io images import /tmp/<image>.tar   # with /run/containerd mounted

## Verified end to end

    POST /run-post  (rvf-api)      -> HTTP 201, file to shared storage, JMS enqueue
    queue                          -> rvf-worker consumes; rvf-api executes nothing
    DuckDB run     (rvf-worker)    -> materialise, prepare schema, run assertions
    GET /result/{runId} (rvf-api)  -> HTTP 200, report the OTHER pod produced

## Known gaps

- `hostPath` shared storage; needs S3 (`rvf.validation.job.storage.useCloud=true`)
  before replicas can span nodes.
- Drools rules path: the image ships them at `/app/snomed-drools-rules` but the
  default property is `../snomed-drools-rules`; the manifest overrides
  `RVF_DROOLS_RULE_DIRECTORY`. Drools itself is still disabled per request.
- No autoscaling yet (KEDA on ActiveMQ queue depth is the intended shape).

## Measured, 2026-08-26, Docker Desktop (8 CPU / 20GB)

Real AU release `amtv4-15912.zip`, 894MB, `releaseAsAnEdition=true`, no previous
release supplied, Drools/MRCM off, groups file+component+release-type:

    upload (through kubectl port-forward)   14.7s
    structural: 75 files, 45,311,214 lines  66.5s
    unpack + materialise 66 tables,
      45,298,828 rows                       75.3s
    prepare schema (45 statements) +
      191 assertions                        ~53s
    ---------------------------------------------
    total, submit to COMPLETE               240s

    totalTestsRun 191, totalFailures 58, totalWarnings 1,
    totalSkips 0, totalTestsIncomplete 54

The 54 incomplete are the known baseline: no previous or dependency release was
supplied, so `MySqlQueryTransformer`'s rule (mirrored by `DuckBinder`) drops any
statement still holding an unbound `<PREVIOUS>`/`<DEPENDENCY>`.

### Pool distribution

3 workers, 6 small validations submitted back to back: ActiveMQ round-robins
them 2/2/2 and all six reach COMPLETE within ~12s. `spring.activemq.queuePrefetch`
is 1, which is what makes the distribution even rather than one worker grabbing
the lot.

## Reproducing

    # 1. build the image (Java 25)
    export JAVA_HOME=$(/usr/libexec/java_home -v 25)
    mvn -o package -DskipTests jib:dockerBuild \
        -Djib.from.platforms=linux/arm64 -Djib.to.image=rvf-duck:local

    # 2. load the image into the node's containerd (see above), then
    kubectl apply -f k8s/rvf.yaml
    # the store ships inside the image - nothing to provision

    # 3. submit
    kubectl -n rvf port-forward svc/rvf-api 8080:8080 &
    curl -X POST http://127.0.0.1:8080/run-post \
      -H "X-AUTH-username: me" -H "X-AUTH-roles: ROLE_USER" -H "X-AUTH-token: t" \
      -F file=@release.zip -F runId=$(date +%s) -F storageLocation=demo \
      -F groups=file-centric-validation -F releaseAsAnEdition=true \
      -F enableDrools=false -F enableMRCMValidation=false
    curl -H "X-AUTH-username: me" -H "X-AUTH-roles: ROLE_USER" -H "X-AUTH-token: t" \
      "http://127.0.0.1:8080/result/<runId>?storageLocation=demo"
