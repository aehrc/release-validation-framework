#!/usr/bin/env bash
#
# End-to-end smoke test against a DEPLOYED RVF.
#
# Proves, in one run, the whole chain a nightly depends on:
#
#   Keycloak client_credentials -> Envoy SecurityPolicy -> API pod
#     -> ActiveMQ -> KEDA wakes a worker from zero -> DuckDB assertions
#     -> report readable back through the gateway
#
# It posts api-demo/SnomedCT_test1_INT_20140131.zip - 59 KB, already in this
# repository - to /run-post, so it needs no Azure Files share name and no
# staged release. A full edition takes ~13 minutes; this takes seconds. The
# point is to prove the path works, not to measure it.
#
# Usage:
#   RVF_CLIENT_SECRET=... ./ci/smoke_test.sh [base-url]
#
# Never pass the secret as an argument: it would be visible in `ps` and in a
# pipeline log. In ADO, map it from the ncts-release variable group as
# `env: { RVF_CLIENT_SECRET: $(rvf.si.client.secret) }`.
#
set -euo pipefail

BASE_URL="${1:-https://ncts-rvf.australiaeast.cloudapp.azure.com}"
CLIENT_ID="${RVF_CLIENT_ID:-si-rvf-client}"
TOKEN_URL="${RVF_TOKEN_URL:-https://auth.ontoserver.csiro.au/auth/realms/aehrc/protocol/openid-connect/token}"
# NOT named GROUPS: bash defines that as a builtin array of the caller's unix
# group ids, so `${GROUPS:-default}` silently yields a gid. It did, and the
# server answered 412 for assertion group "1103459".
GROUP_LIST="${RVF_GROUPS:-file-centric-validation}"
TIMEOUT="${RVF_TIMEOUT:-900}"
PACKAGE="${RVF_PACKAGE:-$(cd "$(dirname "$0")/.." && pwd)/api-demo/SnomedCT_test1_INT_20140131.zip}"

fail() { echo "FAIL: $*" >&2; exit 1; }
step() { echo; echo "=== $* ==="; }

[ -f "$PACKAGE" ] || fail "test package not found at $PACKAGE"
[ -n "${RVF_CLIENT_SECRET:-}" ] || fail "RVF_CLIENT_SECRET is not set"

# ---------------------------------------------------------------- 1. the gate
#
# Before authenticating, prove the gateway still refuses a forged identity.
# RVF trusts X-AUTH-* headers unconditionally, so if these ever reach it the
# deployment is open to anyone. This is the check that must never be skipped.
step "1. the gateway rejects forged headers"
SPOOF=$(curl -s -o /dev/null -m 30 -w '%{http_code}' \
    -H 'X-AUTH-username: attacker' \
    -H 'X-AUTH-roles: ROLE_ihtsdo-ops-admin' \
    -H 'X-AUTH-token: totally-made-up-token' \
    "$BASE_URL/version")
[ "$SPOOF" = "200" ] && fail "forged X-AUTH-* headers returned 200 - the deployment is spoofable"
echo "  forged headers -> $SPOOF (not 200), correct"

# ------------------------------------------------------------- 2. a real token
step "2. client_credentials as $CLIENT_ID"
TOKEN=$(curl -s -m 60 -X POST "$TOKEN_URL" \
    -d grant_type=client_credentials \
    -d "client_id=$CLIENT_ID" \
    --data-urlencode "client_secret=$RVF_CLIENT_SECRET" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin).get("access_token",""))')
[ -n "$TOKEN" ] || fail "no access_token - check the secret, and that service accounts are on for $CLIENT_ID"
echo "  token acquired, $(printf %s "$TOKEN" | wc -c) chars"

# The gateway authorises on the groups claim, not on roles. A valid token with
# no rvf-users membership gets a 403 that looks like a broken gateway, so name
# the real cause here rather than leave it to be debugged later.
python3 - "$TOKEN" <<'PY'
import base64, json, sys
payload = sys.argv[1].split('.')[1]
claims = json.loads(base64.urlsafe_b64decode(payload + '=' * (-len(payload) % 4)))
groups = claims.get('groups') or []
print(f"  groups claim : {groups or 'ABSENT'}")
print(f"  rvf_roles    : {claims.get('rvf_roles', 'ABSENT')}")
print(f"  subject      : {claims.get('preferred_username', claims.get('sub'))}")
if not any('rvf-users' in str(g) for g in groups):
    print("  WARNING: rvf-users absent from the groups claim; the gateway will answer 403")
PY

AUTH=(-H "Authorization: Bearer $TOKEN")

# ------------------------------------------------------------ 3. the API is up
step "3. the API answers through the gateway"
VERSION=$(curl -s -m 60 "${AUTH[@]}" "$BASE_URL/version")
echo "  /version -> $VERSION"
case "$VERSION" in
    *'<html'*|'') fail "expected JSON from /version, got HTML or nothing - the token was rejected" ;;
esac

# --------------------------------------------------------- 4. a real validation
RUN_ID=$(date +%s)
STORAGE="smoke_$RUN_ID"
step "4. submit a validation (runId=$RUN_ID, groups=$GROUP_LIST)"
SUBMIT=$(curl -s -m 300 -w '\n%{http_code}' "${AUTH[@]}" -X POST "$BASE_URL/run-post" \
    -F "file=@$PACKAGE" \
    -F "runId=$RUN_ID" \
    -F "storageLocation=$STORAGE" \
    -F "groups=$GROUP_LIST" \
    -F "writeSuccesses=false" \
    -F "failureExportMax=10")
CODE=$(printf '%s' "$SUBMIT" | tail -1)
echo "  POST /run-post -> $CODE"
# Any 2xx. The controller is annotated 200 but returns 201 Created when it
# enqueues, and treating that as a failure would be this script being wrong
# about the API rather than the API being wrong.
case "$CODE" in
    2??) ;;
    *) fail "submit returned $CODE: $(printf '%s' "$SUBMIT" | head -3)" ;;
esac

# ------------------------------------------------------------- 5. wait for it
#
# The worker may be scaled to zero, so the first poll can wait on a cold start:
# KEDA polls the queue every 30s, then the pod pulls and boots. That wait IS the
# test - it proves the queue and the autoscaler are wired to each other.
step "5. poll for the report (the worker may be cold-starting from zero)"
DEADLINE=$(( $(date +%s) + TIMEOUT ))
REPORT="${TMPDIR:-/tmp}/rvf-smoke-$RUN_ID.json"
while :; do
    HTTP=$(curl -s -m 60 -o "$REPORT" -w '%{http_code}' "${AUTH[@]}" \
        "$BASE_URL/result/$RUN_ID?storageLocation=$STORAGE" || true)
    STATE=$(python3 -c '
import json, sys
try:
    d = json.load(open(sys.argv[1]))
except Exception:
    print("no-report-yet"); raise SystemExit
r = d.get("rvfValidationResult") or {}
print(d.get("status") or r.get("status") or ("complete" if r.get("TestResult") else "running"))
' "$REPORT" 2>/dev/null || echo no-report-yet)
    printf '  %s  http=%s state=%s\n' "$(date +%H:%M:%S)" "$HTTP" "$STATE"
    case "$STATE" in
        complete|COMPLETE) break ;;
        FAILED) fail "the validation reported FAILED - see $REPORT" ;;
    esac
    [ "$(date +%s)" -lt "$DEADLINE" ] || fail "no report within ${TIMEOUT}s"
    sleep 15
done

# ------------------------------------------------------------- 6. the findings
step "6. the report"
python3 - "$REPORT" <<'PY'
import json, sys
d = json.load(open(sys.argv[1]))
r = d.get('rvfValidationResult') or d
tr = r.get('TestResult') or {}
print(f"  assertions run : {tr.get('totalTestsRun')}")
print(f"  failures       : {tr.get('totalFailures')}")
print(f"  skipped        : {tr.get('totalSkips')}")
for item in (tr.get('assertionsFailed') or [])[:5]:
    print(f"    FAILED  {str(item.get('assertionText'))[:88]}")
if not tr.get('totalTestsRun'):
    print("  FAIL: the report contains no executed assertions")
    raise SystemExit(1)
PY

echo
echo "PASS: a real validation ran on the deployed RVF and returned a report."
echo "      report saved at $REPORT"
