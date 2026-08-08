# Building the image

The image is built by the **jib-maven-plugin**, not a Dockerfile. Upstream
deleted theirs in `ef01033` (*"Delete Dockerfile as not used when using jib
maven plugin"*) and this fork follows, so there is one build mechanism rather
than two that can drift apart.

## The two cloned directories

Jib copies these into the image from the build workspace:

```xml
<extraDirectories><paths>
  <path><from>${project.basedir}/snomed-drools-rules</from>                  <into>/app/snomed-drools-rules</into></path>
  <path><from>${project.basedir}/snomed-release-validation-assertions</from> <into>/app/snomed-release-validation-assertions</into></path>
</paths></extraDirectories>
```

They are **not** in git (see `.gitignore`) — `./checkout-resources.sh` clones
them. That moves a responsibility out of the Dockerfile and into the build, so
it has to be carried deliberately:

> **Both clones MUST be pinned to an explicit commit.**
>
> Upstream's version of this script clones a *branch*, unpinned. That makes the
> image non-reproducible: what you get depends on when it was built, so an image
> that works can stop working with no change to this repository. On 2026-08-07
> the first rebuild in two years picked up two years of drift and RVF failed to
> start — `FileNotFoundException` on an assertion script IHTSDO had deleted,
> while the mounted `manifest.xml` still referenced it. The job was killed after
> ~12 minutes and the nightly produced no report.
>
> Our `checkout-resources.sh` pins both by default and takes
> `DROOLS_RULES_REF` / `ASSERTIONS_REF` to move deliberately. This is worth
> offering upstream — their images have the same defect.

```bash
./checkout-resources.sh                      # pinned defaults
ASSERTIONS_REF=<sha> ./checkout-resources.sh # move one deliberately
```

**When bumping either pin, check it against the other side of its pairing:**

| pin | must satisfy |
|---|---|
| `snomed-drools-rules` | every service method **and domain type** the rules use must exist in `${snomed-drools.version}` |
| `snomed-release-validation-assertions` | every `sqlFile` in the mounted `manifest.xml` must resolve |

Both fail *silently*. Bad rules pin → rule compilation dies, Drools contributes
nothing, the build still reports success. Bad assertions pin → RVF fails to
start and the job dies on a timeout that looks like infrastructure.

Checking method names alone is not sufficient for the rules pin: the rules also
reference domain **types**, and DRL will resolve a missing one to an unrelated
class of the same simple name rather than erroring usefully.

## Publishing

Everything is a Maven property, so nothing in the pom needs editing per target:

```bash
mvn clean install jib:build \
  -Ddocker.registry=ontoserver.azurecr.io \
  -Ddocker.image.prefix=aehrc-rvf \
  -Ddocker.image.tag=catchup
```

| property | default | ours |
|---|---|---|
| `docker.registry` | `docker.io` | `ontoserver.azurecr.io` |
| `docker.image.prefix` | `snomedinternational` | `aehrc-rvf` |
| `docker.base-image` | `amazoncorretto:25` | unchanged |
| `docker.image.tag` | — | **never `latest` for anything experimental** |

`:latest` is what the deployed helm chart pulls, with no `imagePullPolicy`, so
Kubernetes defaults to `Always`. Pushing an untested image to that tag puts it
straight into the next nightly. Parallel and experimental builds get their own
tag; `known-good-<date>` tags exist in ACR so a rollback is one
`az acr import`.

## Java

`amazoncorretto:25` — `snomed-parent-bom` 4.0.0 targets Java 25, so the build
JDK must be 25 or newer. A 17 or 21 JDK fails with `release version 25 not
supported` before compiling a single file.
