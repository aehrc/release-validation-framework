# release-validation-framework

![Version: 0.2.2](https://img.shields.io/badge/Version-0.2.2-informational?style=flat-square) ![Type: application](https://img.shields.io/badge/Type-application-informational?style=flat-square) ![AppVersion: 9.0.1-duckdb](https://img.shields.io/badge/AppVersion-9.0.1--duckdb-informational?style=flat-square)

A Helm chart for Release Validation Framework (RVF) on AKS

**Homepage:** <https://github.com/aehrc/release-validation-framework>

## Maintainers

| Name | Email | Url |
| ---- | ------ | --- |
| AEHRC |  |  |

## Source Code

* <https://github.com/aehrc/release-validation-framework>

## Architecture

RVF validates SNOMED CT release packages using an embedded DuckDB execution engine, splitting execution across an API layer and auto-scaled workers:

- **RVF API (`rvf-api`)**: Accepts validation job submissions and serves generated validation reports. Does not execute validations.
- **RVF Worker (`rvf-worker`)**: Headless worker pods scaled by KEDA based on ActiveMQ queue depth. Materialises DuckDB schemas, runs SQL assertions, structural checks, and MRCM validations.
- **ActiveMQ (`activemq`)**: Message broker queueing validation jobs between API and worker pods.
- **Shared Storage (`azurefile-csi-premium`)**: ReadWriteMany Azure Files share mounted at `/app/jobs` for release handover and `/app/releases` for prior release comparison.
- **Envoy Gateway & SecurityPolicy**: Ingress gateway handling TLS termination via cert-manager, OIDC authentication, Bearer JWT validation, and claim-to-header mapping (`X-AUTH-username`, `X-AUTH-roles`, `X-AUTH-token`).

## Values

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| activemq | object | `{"affinity":{},"enabled":true,"image":{"pullPolicy":"IfNotPresent","repository":"apache/activemq-classic","tag":"6.1.4"},"nameOverride":"activemq","nodeSelector":{},"podAnnotations":{},"podLabels":{},"replicaCount":1,"resources":{"limits":{"memory":"1Gi"},"requests":{"cpu":"200m","memory":"512Mi"}},"service":{"consolePort":8161,"openwirePort":61616,"type":"ClusterIP"},"tolerations":[]}` | ActiveMQ broker configuration |
| activemq.affinity | object | `{}` | Affinity rules for ActiveMQ pods |
| activemq.enabled | bool | `true` | Whether to deploy ActiveMQ |
| activemq.image | object | `{"pullPolicy":"IfNotPresent","repository":"apache/activemq-classic","tag":"6.1.4"}` | ActiveMQ container image |
| activemq.nameOverride | string | `"activemq"` | Override name for ActiveMQ Deployment and Service |
| activemq.nodeSelector | object | `{}` | Node selector for ActiveMQ pods |
| activemq.podAnnotations | object | `{}` | Pod annotations for ActiveMQ |
| activemq.podLabels | object | `{}` | Pod labels for ActiveMQ |
| activemq.replicaCount | int | `1` | Number of ActiveMQ broker replicas |
| activemq.resources | object | `{"limits":{"memory":"1Gi"},"requests":{"cpu":"200m","memory":"512Mi"}}` | Resource requests and limits for ActiveMQ |
| activemq.service | object | `{"consolePort":8161,"openwirePort":61616,"type":"ClusterIP"}` | ActiveMQ Service ports |
| activemq.tolerations | list | `[]` | Tolerations for ActiveMQ pods |
| api | object | `{"affinity":{},"extraEnv":[],"nameOverride":"rvf-api","nodeSelector":{},"podAnnotations":{},"podLabels":{},"readinessProbe":{"enabled":true,"initialDelaySeconds":20,"path":"/version","periodSeconds":10,"port":8080},"replicaCount":2,"resources":{"limits":{"memory":"2Gi"},"requests":{"cpu":"250m","memory":"1Gi"}},"retentionDays":"7","service":{"port":8080,"type":"ClusterIP"},"tolerations":[]}` | RVF API Deployment & Service configuration |
| api.affinity | object | `{}` | Affinity rules for API pods |
| api.extraEnv | list | `[]` | Extra environment variables for API container |
| api.nameOverride | string | `"rvf-api"` | Override name for API Deployment and Service |
| api.nodeSelector | object | `{}` | Node selector for API pods |
| api.podAnnotations | object | `{}` | Pod annotations for API pods |
| api.podLabels | object | `{}` | Pod labels for API pods |
| api.readinessProbe | object | `{"enabled":true,"initialDelaySeconds":20,"path":"/version","periodSeconds":10,"port":8080}` | Readiness probe configuration |
| api.readinessProbe.enabled | bool | `true` | Enable readiness probe |
| api.readinessProbe.initialDelaySeconds | int | `20` | Seconds before initial probe execution |
| api.readinessProbe.path | string | `"/version"` | HTTP endpoint path |
| api.readinessProbe.periodSeconds | int | `10` | Frequency in seconds between probes |
| api.readinessProbe.port | int | `8080` | HTTP endpoint port |
| api.replicaCount | int | `2` | Number of API pod replicas |
| api.resources | object | `{"limits":{"memory":"2Gi"},"requests":{"cpu":"250m","memory":"1Gi"}}` | Resource requests and limits for the API container |
| api.retentionDays | string | `"7"` | Days to retain uploaded release files in job storage before reaping (0 disables reaping) |
| api.service | object | `{"port":8080,"type":"ClusterIP"}` | Kubernetes Service configuration for the API |
| api.service.port | int | `8080` | Service port |
| api.service.type | string | `"ClusterIP"` | Service type |
| api.tolerations | list | `[]` | Tolerations for API pods |
| certmanager | object | `{"clusterIssuerName":"","enabled":true,"issuer":{"annotations":{},"create":true,"email":"","privateKeySecretRef":"","server":"https://acme-v02.api.letsencrypt.org/directory"},"issuerName":""}` | cert-manager integration for automated TLS certificate provisioning via Let's Encrypt |
| certmanager.clusterIssuerName | string | `""` | Name of a ClusterIssuer to reference on the Gateway (used when issuer.create is false and issuerName is empty) |
| certmanager.enabled | bool | `true` | Enable cert-manager integration on the Gateway |
| certmanager.issuer | object | `{"annotations":{},"create":true,"email":"","privateKeySecretRef":"","server":"https://acme-v02.api.letsencrypt.org/directory"}` | Configuration for a namespaced ACME Issuer hooked into the Gateway's port-80 HTTP listener |
| certmanager.issuer.annotations | object | `{}` | Optional annotations for the Issuer resource |
| certmanager.issuer.create | bool | `true` | Whether to create a namespaced ACME Issuer resource with gatewayHTTPRoute HTTP-01 solver |
| certmanager.issuer.email | string | `""` | Email address to register with Let's Encrypt for certificate expiry notices |
| certmanager.issuer.privateKeySecretRef | string | `""` | Name of secret to store ACME account private key (defaults to <issuerName>-key) |
| certmanager.issuer.server | string | `"https://acme-v02.api.letsencrypt.org/directory"` | ACME server directory URL |
| certmanager.issuerName | string | `""` | Custom name for the Issuer resource or reference (defaults to letsencrypt-<fullname>) |
| env | object | `{"assertionResourceLocalPath":"/app/snomed-release-validation-assertions","awsRegion":"us-east-1","brokerUrl":"","droolsRuleDirectory":"/app/snomed-drools-rules","executionEngine":"duckdb","jobStorageLocalPath":"jobs/","releaseStorageLocalPath":"releases/","releaseStorageUseCloud":"false"}` | Common environment variables shared by API and Worker |
| env.assertionResourceLocalPath | string | `"/app/snomed-release-validation-assertions"` | Path to assertion resources inside the container |
| env.awsRegion | string | `"us-east-1"` | AWS region required by the S3 client during eager initialization |
| env.brokerUrl | string | `""` | ActiveMQ broker URL. If empty, automatically computed as tcp://<activemq-service>:61616 |
| env.droolsRuleDirectory | string | `"/app/snomed-drools-rules"` | Path to Drools rule directory inside the container |
| env.executionEngine | string | `"duckdb"` | RVF execution engine ("duckdb" or "mysql") |
| env.jobStorageLocalPath | string | `"jobs/"` | Relative path for local validation job storage (relative to working dir /app) |
| env.releaseStorageLocalPath | string | `"releases/"` | Relative path for local release storage (relative to working dir /app) |
| env.releaseStorageUseCloud | string | `"false"` | Whether cloud storage is used for releases |
| externalSecret | object | `{"annotations":{},"data":[{"remoteRef":{"conversionStrategy":"Default","decodingStrategy":"None","key":"acl_vlt_od225632/kv/data/rvf","metadataPolicy":"None","property":"client_secret"},"secretKey":"client-secret"}],"dataFrom":[],"enabled":true,"nameOverride":"rvf-oidc","refreshInterval":"1h","secretStoreRef":{"kind":"ClusterSecretStore","name":"vault-backend"},"syncWave":"-1","target":{"creationPolicy":"Owner","name":"rvf-oidc"}}` | ExternalSecret configuration for Keycloak OIDC client secret |
| externalSecret.annotations | object | `{}` | Additional custom annotations on ExternalSecret |
| externalSecret.data | list | `[{"remoteRef":{"conversionStrategy":"Default","decodingStrategy":"None","key":"acl_vlt_od225632/kv/data/rvf","metadataPolicy":"None","property":"client_secret"},"secretKey":"client-secret"}]` | Individual secret key mappings |
| externalSecret.dataFrom | list | `[]` | Bulk secret mappings (optional) |
| externalSecret.enabled | bool | `true` | Enable ExternalSecret creation |
| externalSecret.nameOverride | string | `"rvf-oidc"` | Override name for ExternalSecret |
| externalSecret.refreshInterval | string | `"1h"` | Refresh interval for syncing secret from Vault |
| externalSecret.secretStoreRef | object | `{"kind":"ClusterSecretStore","name":"vault-backend"}` | Reference to the SecretStore or ClusterSecretStore |
| externalSecret.syncWave | string | `"-1"` | ArgoCD sync-wave annotation (ensures secret is materialized before deployment) |
| externalSecret.target | object | `{"creationPolicy":"Owner","name":"rvf-oidc"}` | Target Secret creation options |
| fullnameOverride | string | `"rvf"` | String to fully override release-validation-framework.fullname template |
| gateway | object | `{"annotations":{},"clientTrafficPolicy":{"bufferLimit":"32Mi","enabled":true,"nameOverride":"rvf-client"},"enabled":true,"gatewayClassName":"envoy-gateway-class","infrastructure":{"annotations":{"service.beta.kubernetes.io/azure-dns-label-name":"ncts-rvf"}},"listeners":{"http":{"allowedRoutes":{"namespaces":{"from":"Same"}},"enabled":true,"name":"public-http","port":80,"protocol":"HTTP"},"https":{"allowedRoutes":{"namespaces":{"from":"Same"}},"enabled":true,"hostname":"ncts-rvf.australiaeast.cloudapp.azure.com","name":"websecure","port":443}},"nameOverride":"rvf-gw","tls":{"certificateSecret":"rvf-tls","enabled":true,"mode":"Terminate"}}` | Envoy Gateway configuration |
| gateway.annotations | object | `{}` | Additional annotations on the Gateway |
| gateway.clientTrafficPolicy | object | `{"bufferLimit":"32Mi","enabled":true,"nameOverride":"rvf-client"}` | ClientTrafficPolicy for Gateway connection settings |
| gateway.clientTrafficPolicy.bufferLimit | string | `"32Mi"` | Per-connection buffer limit for large uploads |
| gateway.clientTrafficPolicy.enabled | bool | `true` | Enable ClientTrafficPolicy |
| gateway.clientTrafficPolicy.nameOverride | string | `"rvf-client"` | Override name for ClientTrafficPolicy |
| gateway.enabled | bool | `true` | Enable Gateway resource creation |
| gateway.gatewayClassName | string | `"envoy-gateway-class"` | GatewayClass name (must match installed Envoy Gateway class) |
| gateway.infrastructure | object | `{"annotations":{"service.beta.kubernetes.io/azure-dns-label-name":"ncts-rvf"}}` | Infrastructure settings for Envoy Gateway data plane (e.g. cloud LoadBalancer annotations) |
| gateway.infrastructure.annotations | object | `{"service.beta.kubernetes.io/azure-dns-label-name":"ncts-rvf"}` | Annotations placed on the Gateway infrastructure (Envoy proxy Service / LoadBalancer) |
| gateway.listeners | object | `{"http":{"allowedRoutes":{"namespaces":{"from":"Same"}},"enabled":true,"name":"public-http","port":80,"protocol":"HTTP"},"https":{"allowedRoutes":{"namespaces":{"from":"Same"}},"enabled":true,"hostname":"ncts-rvf.australiaeast.cloudapp.azure.com","name":"websecure","port":443}}` | Listener configurations |
| gateway.listeners.http | object | `{"allowedRoutes":{"namespaces":{"from":"Same"}},"enabled":true,"name":"public-http","port":80,"protocol":"HTTP"}` | Plaintext HTTP listener (used by ACME http-01 challenge) |
| gateway.listeners.https | object | `{"allowedRoutes":{"namespaces":{"from":"Same"}},"enabled":true,"hostname":"ncts-rvf.australiaeast.cloudapp.azure.com","name":"websecure","port":443}` | Secure HTTPS listener |
| gateway.nameOverride | string | `"rvf-gw"` | Override name for Gateway resource |
| gateway.tls | object | `{"certificateSecret":"rvf-tls","enabled":true,"mode":"Terminate"}` | TLS configuration for HTTPS listener |
| gateway.tls.certificateSecret | string | `"rvf-tls"` | Secret name containing TLS certificate |
| gateway.tls.enabled | bool | `true` | Enable TLS termination on port 443 |
| gateway.tls.mode | string | `"Terminate"` | TLS termination mode |
| httpRoute | object | `{"backendTrafficPolicy":{"connectionIdleTimeout":"120s","enabled":true,"nameOverride":"rvf-timeouts","requestTimeout":"600s"},"enabled":true,"hostnames":["ncts-rvf.australiaeast.cloudapp.azure.com"],"nameOverride":"rvf-route","rules":[{"matches":[{"path":{"type":"PathPrefix","value":"/"}}]}]}` | Gateway API HTTPRoute configuration |
| httpRoute.backendTrafficPolicy | object | `{"connectionIdleTimeout":"120s","enabled":true,"nameOverride":"rvf-timeouts","requestTimeout":"600s"}` | BackendTrafficPolicy for request timeouts |
| httpRoute.backendTrafficPolicy.connectionIdleTimeout | string | `"120s"` | Connection idle timeout |
| httpRoute.backendTrafficPolicy.enabled | bool | `true` | Enable BackendTrafficPolicy |
| httpRoute.backendTrafficPolicy.nameOverride | string | `"rvf-timeouts"` | Override name for BackendTrafficPolicy |
| httpRoute.backendTrafficPolicy.requestTimeout | string | `"600s"` | Request timeout duration (accommodates 850MB+ release uploads) |
| httpRoute.enabled | bool | `true` | Enable HTTPRoute resource creation |
| httpRoute.hostnames | list | `["ncts-rvf.australiaeast.cloudapp.azure.com"]` | Hostnames matched by this route |
| httpRoute.nameOverride | string | `"rvf-route"` | Override name for HTTPRoute |
| httpRoute.rules | list | `[{"matches":[{"path":{"type":"PathPrefix","value":"/"}}]}]` | Routing rules mapping to backend service |
| image | object | `{"pullPolicy":"Always","repository":"ontoserver.azurecr.io/aehrc-rvf/release-validation-framework","tag":""}` | Common container image settings for RVF (API and Worker) |
| image.pullPolicy | string | `"Always"` | Image pull policy |
| image.repository | string | `"ontoserver.azurecr.io/aehrc-rvf/release-validation-framework"` | Image repository |
| image.tag | string | `""` | Image tag (defaults to Chart appVersion if empty) |
| imagePullSecrets | list | `[]` | References to Kubernetes Secret names for container registry credentials |
| keda | object | `{"enabled":true,"scaledObject":{"advanced":{"horizontalPodAutoscalerConfig":{"behavior":{"scaleDown":{"stabilizationWindowSeconds":900}}}},"cooldownPeriod":900,"enabled":true,"maxReplicaCount":6,"minReplicaCount":0,"nameOverride":"rvf-worker","pollingInterval":30,"triggers":{"brokerName":"localhost","destinationName":"rvf-validation-queue","targetQueueSize":"1"}},"triggerAuthentication":{"enabled":true,"externalSecret":{"enabled":false,"refreshInterval":"1h","remoteRef":{"key":"acl_vlt_od225632/kv/data/rvf","passwordProperty":"activemq_password","usernameProperty":"activemq_username"},"secretStoreRef":{"kind":"ClusterSecretStore","name":"vault-backend"}},"nameOverride":"activemq-auth","passwordKey":"password","secretName":"activemq-credentials","usernameKey":"username"}}` | KEDA Autoscaling configuration |
| keda.enabled | bool | `true` | Enable KEDA autoscaling resources |
| keda.scaledObject | object | `{"advanced":{"horizontalPodAutoscalerConfig":{"behavior":{"scaleDown":{"stabilizationWindowSeconds":900}}}},"cooldownPeriod":900,"enabled":true,"maxReplicaCount":6,"minReplicaCount":0,"nameOverride":"rvf-worker","pollingInterval":30,"triggers":{"brokerName":"localhost","destinationName":"rvf-validation-queue","targetQueueSize":"1"}}` | ScaledObject configuration for Worker Deployment |
| keda.scaledObject.advanced | object | `{"horizontalPodAutoscalerConfig":{"behavior":{"scaleDown":{"stabilizationWindowSeconds":900}}}}` | Advanced HPA scale down stabilization window |
| keda.scaledObject.cooldownPeriod | int | `900` | Cooldown period in seconds before scaling down |
| keda.scaledObject.enabled | bool | `true` | Enable ScaledObject |
| keda.scaledObject.maxReplicaCount | int | `6` | Maximum replica count |
| keda.scaledObject.minReplicaCount | int | `0` | Minimum replica count (0 enables scale-to-zero when idle) |
| keda.scaledObject.nameOverride | string | `"rvf-worker"` | Override name for ScaledObject |
| keda.scaledObject.pollingInterval | int | `30` | Polling interval in seconds to inspect queue depth |
| keda.scaledObject.triggers | object | `{"brokerName":"localhost","destinationName":"rvf-validation-queue","targetQueueSize":"1"}` | ActiveMQ trigger parameters |
| keda.triggerAuthentication | object | `{"enabled":true,"externalSecret":{"enabled":false,"refreshInterval":"1h","remoteRef":{"key":"acl_vlt_od225632/kv/data/rvf","passwordProperty":"activemq_password","usernameProperty":"activemq_username"},"secretStoreRef":{"kind":"ClusterSecretStore","name":"vault-backend"}},"nameOverride":"activemq-auth","passwordKey":"password","secretName":"activemq-credentials","usernameKey":"username"}` | TriggerAuthentication configuration |
| keda.triggerAuthentication.enabled | bool | `true` | Enable TriggerAuthentication |
| keda.triggerAuthentication.externalSecret | object | `{"enabled":false,"refreshInterval":"1h","remoteRef":{"key":"acl_vlt_od225632/kv/data/rvf","passwordProperty":"activemq_password","usernameProperty":"activemq_username"},"secretStoreRef":{"kind":"ClusterSecretStore","name":"vault-backend"}}` | Optional ExternalSecret to automatically create activemq-credentials from Vault |
| keda.triggerAuthentication.externalSecret.enabled | bool | `false` | Enable ExternalSecret creation for ActiveMQ credentials |
| keda.triggerAuthentication.nameOverride | string | `"activemq-auth"` | Override name for TriggerAuthentication |
| keda.triggerAuthentication.passwordKey | string | `"password"` | Key within secret for ActiveMQ password |
| keda.triggerAuthentication.secretName | string | `"activemq-credentials"` | Secret name containing ActiveMQ credentials |
| keda.triggerAuthentication.usernameKey | string | `"username"` | Key within secret for ActiveMQ username |
| nameOverride | string | `""` | String to partially override release-validation-framework.name template (will maintain the release name) |
| namespace | object | `{"create":false,"name":"rvf"}` | Namespace configuration |
| namespace.create | bool | `false` | Whether to create the Namespace resource |
| namespace.name | string | `"rvf"` | Name of the Namespace to create |
| networkPolicy | object | `{"enabled":true,"ingress":[{"from":[{"namespaceSelector":{"matchLabels":{"kubernetes.io/metadata.name":"envoy-gateway-system"}}}],"ports":[{"port":8080}]}],"nameOverride":"rvf-api-gateway-only"}` | NetworkPolicy restricting direct access to API pods |
| networkPolicy.enabled | bool | `true` | Enable NetworkPolicy |
| networkPolicy.ingress | list | `[{"from":[{"namespaceSelector":{"matchLabels":{"kubernetes.io/metadata.name":"envoy-gateway-system"}}}],"ports":[{"port":8080}]}]` | Ingress rules allowing traffic exclusively from Envoy Gateway proxies |
| networkPolicy.nameOverride | string | `"rvf-api-gateway-only"` | Override name for NetworkPolicy |
| persistence | object | `{"jobs":{"accessModes":["ReadWriteMany"],"claimName":"rvf-jobs","create":true,"mountPath":"/app/jobs","size":"100Gi","storageClassName":"azurefile-csi-premium"},"releases":{"accessModes":["ReadWriteMany"],"claimName":"rvf-releases","create":true,"mountPath":"/app/releases","size":"50Gi","storageClassName":"azurefile-csi-premium"}}` | Persistent Volume Claims configuration |
| persistence.jobs | object | `{"accessModes":["ReadWriteMany"],"claimName":"rvf-jobs","create":true,"mountPath":"/app/jobs","size":"100Gi","storageClassName":"azurefile-csi-premium"}` | PVC for shared validation jobs (upload handover and reports) |
| persistence.jobs.accessModes | list | `["ReadWriteMany"]` | Access modes for the PVC |
| persistence.jobs.claimName | string | `"rvf-jobs"` | Claim name for the jobs share |
| persistence.jobs.create | bool | `true` | Whether to create the jobs PVC |
| persistence.jobs.mountPath | string | `"/app/jobs"` | Container mount path for jobs storage |
| persistence.jobs.size | string | `"100Gi"` | Storage capacity requested |
| persistence.jobs.storageClassName | string | `"azurefile-csi-premium"` | Storage class name (azurefile-csi-premium provides ReadWriteMany) |
| persistence.releases | object | `{"accessModes":["ReadWriteMany"],"claimName":"rvf-releases","create":true,"mountPath":"/app/releases","size":"50Gi","storageClassName":"azurefile-csi-premium"}` | PVC for published release archives |
| persistence.releases.accessModes | list | `["ReadWriteMany"]` | Access modes for the PVC |
| persistence.releases.claimName | string | `"rvf-releases"` | Claim name for the releases share |
| persistence.releases.create | bool | `true` | Whether to create the releases PVC |
| persistence.releases.mountPath | string | `"/app/releases"` | Container mount path for releases storage |
| persistence.releases.size | string | `"50Gi"` | Storage capacity requested |
| persistence.releases.storageClassName | string | `"azurefile-csi-premium"` | Storage class name (azurefile-csi-premium provides ReadWriteMany) |
| securityPolicy | object | `{"authorization":{"defaultAction":"Deny","rules":[{"action":"Allow","principal":{"jwt":{"claims":[{"name":"groups","valueType":"StringArray","values":["rvf-users"]}],"provider":"oidc"}}}]},"enabled":true,"jwt":{"providers":[{"claimToHeaders":[{"claim":"preferred_username","header":"X-AUTH-username"},{"claim":"rvf_roles","header":"X-AUTH-roles"},{"claim":"jti","header":"X-AUTH-token"}],"issuer":"https://auth.ontoserver.csiro.au/auth/realms/aehrc","name":"oidc","remoteJWKS":{"cacheDuration":"300s","uri":"https://auth.ontoserver.csiro.au/auth/realms/aehrc/protocol/openid-connect/certs"}}]},"nameOverride":"rvf-auth-policy","oidc":{"clientID":"rvf","clientSecret":{"name":"rvf-oidc"},"forwardAccessToken":true,"provider":{"issuer":"https://auth.ontoserver.csiro.au/auth/realms/aehrc"},"redirectURL":"https://ncts-rvf.australiaeast.cloudapp.azure.com/oauth2/callback","scopes":["openid","profile","email"]}}` | Envoy Gateway SecurityPolicy configuration (OIDC and JWT) |
| securityPolicy.authorization | object | `{"defaultAction":"Deny","rules":[{"action":"Allow","principal":{"jwt":{"claims":[{"name":"groups","valueType":"StringArray","values":["rvf-users"]}],"provider":"oidc"}}}]}` | Authorization policy (default deny, allow rvf-users) |
| securityPolicy.enabled | bool | `true` | Enable SecurityPolicy resource creation |
| securityPolicy.jwt | object | `{"providers":[{"claimToHeaders":[{"claim":"preferred_username","header":"X-AUTH-username"},{"claim":"rvf_roles","header":"X-AUTH-roles"},{"claim":"jti","header":"X-AUTH-token"}],"issuer":"https://auth.ontoserver.csiro.au/auth/realms/aehrc","name":"oidc","remoteJWKS":{"cacheDuration":"300s","uri":"https://auth.ontoserver.csiro.au/auth/realms/aehrc/protocol/openid-connect/certs"}}]}` | JWT configuration for machine/Bearer token flow |
| securityPolicy.nameOverride | string | `"rvf-auth-policy"` | Override name for SecurityPolicy |
| securityPolicy.oidc | object | `{"clientID":"rvf","clientSecret":{"name":"rvf-oidc"},"forwardAccessToken":true,"provider":{"issuer":"https://auth.ontoserver.csiro.au/auth/realms/aehrc"},"redirectURL":"https://ncts-rvf.australiaeast.cloudapp.azure.com/oauth2/callback","scopes":["openid","profile","email"]}` | OIDC configuration for browser login flow |
| worker | object | `{"affinity":{},"duckdb":{"cacheDirectory":"/work/release-cache","cacheMaxGb":"0","threads":"8","workDirectory":"/work"},"extraEnv":[],"nameOverride":"rvf-worker","nodeSelector":{"agentpool":"large"},"podAnnotations":{},"podLabels":{},"replicaCount":1,"resources":{"limits":{"cpu":"8","memory":"16Gi"},"requests":{"cpu":"4","memory":"12Gi"}},"retentionDays":"0","terminationGracePeriodSeconds":600,"tolerations":[{"effect":"NoSchedule","key":"node-type","operator":"Equal","value":"large-production"}],"workVolume":{"emptyDir":{"sizeLimit":"40Gi"},"mountPath":"/work"}}` | RVF Worker Deployment configuration |
| worker.affinity | object | `{}` | Affinity rules for worker pods |
| worker.duckdb | object | `{"cacheDirectory":"/work/release-cache","cacheMaxGb":"0","threads":"8","workDirectory":"/work"}` | DuckDB engine performance tuning |
| worker.duckdb.cacheDirectory | string | `"/work/release-cache"` | Node-local release cache directory |
| worker.duckdb.cacheMaxGb | string | `"0"` | Max cache size in GB (0 disables cache) |
| worker.duckdb.threads | string | `"8"` | Number of DuckDB worker threads (must match cpu limit) |
| worker.duckdb.workDirectory | string | `"/work"` | Scratch working directory (node-local) |
| worker.extraEnv | list | `[]` | Extra environment variables for worker container |
| worker.nameOverride | string | `"rvf-worker"` | Override name for Worker Deployment |
| worker.nodeSelector | object | `{"agentpool":"large"}` | Node selector for worker pods (pinned to large 8-core nodes) |
| worker.podAnnotations | object | `{}` | Pod annotations for worker pods |
| worker.podLabels | object | `{}` | Pod labels for worker pods |
| worker.replicaCount | int | `1` | Initial number of worker pod replicas (autoscaled by KEDA when enabled) |
| worker.resources | object | `{"limits":{"cpu":"8","memory":"16Gi"},"requests":{"cpu":"4","memory":"12Gi"}}` | Resource requests and limits for the Worker container |
| worker.retentionDays | string | `"0"` | Reaping is the API's responsibility, so retention is set to 0 on workers |
| worker.terminationGracePeriodSeconds | int | `600` | Grace period in seconds before pod termination (covers long validations) |
| worker.tolerations | list | `[{"effect":"NoSchedule","key":"node-type","operator":"Equal","value":"large-production"}]` | Tolerations for tainted node pools |
| worker.workVolume | object | `{"emptyDir":{"sizeLimit":"40Gi"},"mountPath":"/work"}` | Ephemeral work volume configuration |
| worker.workVolume.emptyDir.sizeLimit | string | `"40Gi"` | Size limit for ephemeral scratch disk |
| worker.workVolume.mountPath | string | `"/work"` | Mount path for ephemeral scratch disk |
