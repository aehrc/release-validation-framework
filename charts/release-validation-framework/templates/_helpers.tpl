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
