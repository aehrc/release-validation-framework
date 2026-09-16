{{/*
Expand the name of the chart.
*/}}
{{- define "release-validation-framework.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Create a default fully qualified app name.
We truncate at 63 chars because some Kubernetes name fields are limited to this (by the DNS naming spec).
If release name contains chart name it will be used as a full name.
*/}}
{{- define "release-validation-framework.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{/*
Create chart name and version as used by the chart label.
*/}}
{{- define "release-validation-framework.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Common labels
*/}}
{{- define "release-validation-framework.labels" -}}
helm.sh/chart: {{ include "release-validation-framework.chart" . }}
{{ include "release-validation-framework.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{/*
Selector labels
*/}}
{{- define "release-validation-framework.selectorLabels" -}}
app.kubernetes.io/name: {{ include "release-validation-framework.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/*
RVF Image
*/}}
{{- define "release-validation-framework.image" -}}
{{- $tag := .Values.image.tag | default .Chart.AppVersion }}
{{- printf "%s:%s" .Values.image.repository $tag }}
{{- end }}

{{/*
API component fullname
*/}}
{{- define "release-validation-framework.api.fullname" -}}
{{- .Values.api.nameOverride | default (printf "%s-api" (include "release-validation-framework.fullname" .)) | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
API labels
*/}}
{{- define "release-validation-framework.api.labels" -}}
{{ include "release-validation-framework.labels" . }}
app.kubernetes.io/component: api
{{- end }}

{{/*
API selector labels
*/}}
{{- define "release-validation-framework.api.selectorLabels" -}}
app.kubernetes.io/name: {{ include "release-validation-framework.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/component: api
app: {{ include "release-validation-framework.api.fullname" . }}
{{- end }}

{{/*
Worker component fullname
*/}}
{{- define "release-validation-framework.worker.fullname" -}}
{{- .Values.worker.nameOverride | default (printf "%s-worker" (include "release-validation-framework.fullname" .)) | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Worker labels
*/}}
{{- define "release-validation-framework.worker.labels" -}}
{{ include "release-validation-framework.labels" . }}
app.kubernetes.io/component: worker
{{- end }}

{{/*
Worker selector labels
*/}}
{{- define "release-validation-framework.worker.selectorLabels" -}}
app.kubernetes.io/name: {{ include "release-validation-framework.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/component: worker
app: {{ include "release-validation-framework.worker.fullname" . }}
{{- end }}

{{/*
ActiveMQ component fullname
*/}}
{{- define "release-validation-framework.activemq.fullname" -}}
{{- .Values.activemq.nameOverride | default (printf "%s-activemq" (include "release-validation-framework.fullname" .)) | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
ActiveMQ labels
*/}}
{{- define "release-validation-framework.activemq.labels" -}}
{{ include "release-validation-framework.labels" . }}
app.kubernetes.io/component: activemq
{{- end }}

{{/*
ActiveMQ selector labels
*/}}
{{- define "release-validation-framework.activemq.selectorLabels" -}}
app.kubernetes.io/name: {{ include "release-validation-framework.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/component: activemq
app: {{ include "release-validation-framework.activemq.fullname" . }}
{{- end }}

{{/*
ActiveMQ image
*/}}
{{- define "release-validation-framework.activemq.image" -}}
{{- printf "%s:%s" .Values.activemq.image.repository .Values.activemq.image.tag }}
{{- end }}

{{/*
ActiveMQ Broker URL
*/}}
{{- define "release-validation-framework.brokerUrl" -}}
{{- if .Values.env.brokerUrl -}}
{{- .Values.env.brokerUrl -}}
{{- else -}}
{{- printf "tcp://%s:%d" (include "release-validation-framework.activemq.fullname" .) (int .Values.activemq.service.openwirePort) -}}
{{- end -}}
{{- end }}

{{/*
Jobs PVC name
*/}}
{{- define "release-validation-framework.jobsPvcName" -}}
{{- .Values.persistence.jobs.claimName | default (printf "%s-jobs" (include "release-validation-framework.fullname" .)) }}
{{- end }}

{{/*
Releases PVC name
*/}}
{{- define "release-validation-framework.releasesPvcName" -}}
{{- .Values.persistence.releases.claimName | default (printf "%s-releases" (include "release-validation-framework.fullname" .)) }}
{{- end }}

{{/*
KEDA TriggerAuthentication name
*/}}
{{- define "release-validation-framework.triggerAuthName" -}}
{{- .Values.keda.triggerAuthentication.nameOverride | default "activemq-auth" }}
{{- end }}

{{/*
KEDA ScaledObject name
*/}}
{{- define "release-validation-framework.scaledObjectName" -}}
{{- .Values.keda.scaledObject.nameOverride | default (include "release-validation-framework.worker.fullname" .) }}
{{- end }}

{{/*
ExternalSecret name
*/}}
{{- define "release-validation-framework.externalSecretName" -}}
{{- .Values.externalSecret.nameOverride | default "rvf-oidc" }}
{{- end }}

{{/*
Gateway name
*/}}
{{- define "release-validation-framework.gatewayName" -}}
{{- .Values.gateway.nameOverride | default (printf "%s-gw" (include "release-validation-framework.fullname" .)) }}
{{- end }}

{{/*
HTTPRoute name
*/}}
{{- define "release-validation-framework.httpRouteName" -}}
{{- .Values.httpRoute.nameOverride | default (printf "%s-route" (include "release-validation-framework.fullname" .)) }}
{{- end }}

{{/*
BackendTrafficPolicy name
*/}}
{{- define "release-validation-framework.backendTrafficPolicyName" -}}
{{- .Values.httpRoute.backendTrafficPolicy.nameOverride | default (printf "%s-timeouts" (include "release-validation-framework.fullname" .)) }}
{{- end }}

{{/*
ClientTrafficPolicy name
*/}}
{{- define "release-validation-framework.clientTrafficPolicyName" -}}
{{- .Values.gateway.clientTrafficPolicy.nameOverride | default (printf "%s-client" (include "release-validation-framework.fullname" .)) }}
{{- end }}

{{/*
SecurityPolicy name
*/}}
{{- define "release-validation-framework.securityPolicyName" -}}
{{- .Values.securityPolicy.nameOverride | default (printf "%s-auth-policy" (include "release-validation-framework.fullname" .)) }}
{{- end }}

{{/*
NetworkPolicy name
*/}}
{{- define "release-validation-framework.networkPolicyName" -}}
{{- .Values.networkPolicy.nameOverride | default (printf "%s-api-gateway-only" (include "release-validation-framework.fullname" .)) }}
{{- end }}

{{/*
cert-manager Issuer name
*/}}
{{- define "release-validation-framework.issuerName" -}}
{{- .Values.certmanager.issuerName | default (printf "letsencrypt-%s" (include "release-validation-framework.fullname" .)) }}
{{- end }}

{{/*
Assertion pack pins.

Renders the token env vars first and RVF_ASSERTION_PACKS after them, because
the spec references each token with $(NAME) and Kubernetes only expands a
reference to a variable declared EARLIER in the same container. The token is
never written into the spec itself: a pack URL and its spec both end up in logs
and in `kubectl describe`, and a PAT there is a PAT leaked.

Specs are comma separated because Spring splits list properties on commas, and
the fields inside one spec are semicolon separated because a URL contains
commas far more often than semicolons.
*/}}
{{- define "release-validation-framework.assertionPackEnv" -}}
{{- range $i, $pack := .Values.assertionPacks }}
{{- with $pack.tokenSecret }}
- name: RVF_PACK_TOKEN_{{ $i }}
  valueFrom:
    secretKeyRef:
      name: {{ .name | quote }}
      key: {{ .key | quote }}
{{- end }}
{{- end }}
{{- range $i, $channel := .Values.assertionChannels }}
{{- with $channel.tokenSecret }}
- name: RVF_CHANNEL_TOKEN_{{ $i }}
  valueFrom:
    secretKeyRef:
      name: {{ .name | quote }}
      key: {{ .key | quote }}
{{- end }}
{{- end }}
{{- if .Values.assertionChannels }}
- name: RVF_ASSERTION_CHANNELS
  value: {{ include "release-validation-framework.assertionChannelSpecs" . | quote }}
{{- end }}
{{- if .Values.assertionPacks }}
- name: RVF_ASSERTION_PACKS
  value: {{ include "release-validation-framework.assertionPackSpecs" . | quote }}
{{- end }}
{{- end }}

{{/*
One spec per channel: a trusted location, its index, and the pack names it may
serve.

`packs` bounds what the channel is allowed to offer. Without it a channel that
served a different pack under a familiar name would be believed, so the list is
the trust boundary rather than documentation. It defaults to the channel's own
name, which is the only sane default and the common case.
*/}}
{{- define "release-validation-framework.assertionChannelSpecs" -}}
{{- $specs := list }}
{{- range $i, $channel := .Values.assertionChannels }}
{{/* No check for a missing index here: values.schema.json declares it
     required, so helm refuses such values before this renders. A second check
     would be unreachable, and an unreachable check reads as a live one. */}}
{{- $spec := printf "name=%s;index=%s;packs=%s" $channel.name $channel.index ($channel.packs | default $channel.name) }}
{{- if $channel.tokenSecret }}
{{- $spec = printf "%s;authHeader=%s $(RVF_CHANNEL_TOKEN_%d)" $spec ($channel.tokenScheme | default "Bearer") $i }}
{{- end }}
{{- $specs = append $specs $spec }}
{{- end }}
{{- join "," $specs }}
{{- end }}

{{/*
One spec per pinned pack - the corpus a request gets when it names none.

Two forms. A pack carrying `uri` and `sha256` is spelled out in full, which is
how this worked before channels and still works for a location no channel
serves. A pack carrying only a version is written as name@version and resolved
by the server from its channel's index: same pin, same digest check, but the
digest is discovered rather than transcribed - and transcribing it is what put
the wrong one of two sha256 values into a production pin.

So sha256 is mandatory UNLESS a configured channel serves that pack. An
unpinned pack with nowhere to resolve it from is still refused here, at helm
template time, rather than at fetch time on a rolling deployment.
*/}}
{{- define "release-validation-framework.assertionPackSpecs" -}}
{{- $specs := list }}
{{- $channels := .Values.assertionChannels | default list }}
{{- range $i, $pack := .Values.assertionPacks }}
{{- $served := false }}
{{- range $channel := $channels }}
{{- if contains $pack.name ($channel.packs | default $channel.name) }}
{{- $served = true }}
{{- end }}
{{- end }}
{{- if and (not $pack.sha256) (not $served) }}
{{- fail (printf "assertionPacks[%d] (%s) has no sha256 and no channel serves it: an unpinned pack cannot be reproduced, so a report naming it would mean nothing. Give it a uri and sha256, or configure a channel that carries it and name only the version." $i ($pack.name | default "unnamed")) }}
{{- end }}
{{- if $pack.sha256 }}
{{- $spec := printf "name=%s;version=%s;uri=%s;sha256=%s" $pack.name $pack.version $pack.uri $pack.sha256 }}
{{- if $pack.tokenSecret }}
{{- $spec = printf "%s;authHeader=%s $(RVF_PACK_TOKEN_%d)" $spec ($pack.tokenScheme | default "Bearer") $i }}
{{- end }}
{{- $specs = append $specs $spec }}
{{- else }}
{{- $specs = append $specs (printf "%s@%s" $pack.name $pack.version) }}
{{- end }}
{{- end }}
{{- join "," $specs }}
{{- end }}
