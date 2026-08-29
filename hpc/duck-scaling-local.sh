#!/bin/bash
# How does a DuckDB RVF validation scale with cores and DuckDB threads?
#
# Uses DuckValidationProbe, so one JVM invocation is exactly one data point:
# no upload, no queue, no unzip - the release is already unpacked, so what is
# measured is materialise + prepare + assertions, which is the part that scales.
#
# Two curves:
#   A  cores == threads      the "right-sized worker" curve; what AKS sizing means
#   B  cores fixed, threads varied   isolates oversubscription from real capacity
set -u
TREE=/data/Projects/rvf-catchup
JAVA=/usr/lib/jvm/java-25-openjdk-amd64/bin/java
REL=${REL:-/data/work/bundle/SnomedCT_ManagedServiceAU_DAILYBUILD_BETA_AU1000036_20260831T120000Z}
CP="$TREE/target/classes:$TREE/target/test-classes:$(cat /data/work/drools-cp.txt)"
OUT=${OUT:-/data/work/duck-scaling.tsv}
XMX=${XMX:-8g}

printf 'curve\tcores\tthreads\twall_s\tmaterialise_s\trows\tpeak_rss_gb\ttests\tfails\n' > "$OUT"

one() {
  local curve="$1" cores="$2" threads="$3"
  local log=/data/work/scale-${curve}-${cores}c-${threads}t.log
  rm -rf /data/work/duckwork/* 2>/dev/null

  taskset -c "0-$((cores-1))" env AWS_REGION=us-east-1 aws_region=us-east-1 \
    "$JAVA" -Djava.io.tmpdir=/data/work/tmp -Daws.region=us-east-1 -Xmx"$XMX" \
    --add-opens=java.base/java.lang=ALL-UNNAMED \
    -Dprobe.store="$TREE/src/main/resources/duck/store.json" \
    -Dprobe.corpus="$TREE/snomed-release-validation-assertions" \
    -Dprobe.prospective="$REL" \
    -Dprobe.work=/data/work/duckwork \
    -Dprobe.edition=true \
    -Dprobe.threads="$threads" \
    -cp "$CP" org.ihtsdo.rvf.DuckValidationProbe > "$log" 2>&1 &
  local jvm=$!

  local peak=0 r
  while kill -0 "$jvm" 2>/dev/null; do
    r=$(ps -o rss= -p "$jvm" 2>/dev/null | tr -d ' ')
    [ -n "$r" ] && [ "$r" -gt "$peak" ] && peak=$r
    sleep 2
  done
  wait "$jvm" 2>/dev/null

  local wall mat rows tests fails
  wall=$(grep -oE '=== [0-9.]+s wall clock' "$log" | grep -oE '[0-9.]+' | head -1)
  mat=$(grep -oE 'materialised [0-9]+ tables \([0-9]+ rows\).* in [0-9]+ms' "$log" \
        | grep -oE 'in [0-9]+ms' | grep -oE '[0-9]+' | head -1)
  rows=$(grep -oE 'materialised [0-9]+ tables \(([0-9]+) rows\)' "$log" \
        | grep -oE '\([0-9]+ rows' | grep -oE '[0-9]+' | head -1)
  tests=$(grep -oE 'tests run +[0-9]+' "$log" | grep -oE '[0-9]+' | head -1)
  fails=$(grep -oE 'failures +[0-9]+' "$log" | grep -oE '[0-9]+' | head -1)

  printf '%s\t%s\t%s\t%s\t%.1f\t%s\t%.2f\t%s\t%s\n' \
    "$curve" "$cores" "$threads" "${wall:-NA}" \
    "$(echo "scale=1; ${mat:-0}/1000" | bc)" "${rows:-NA}" \
    "$(echo "scale=2; $peak/1048576" | bc)" "${tests:-NA}" "${fails:-NA}" >> "$OUT"
  echo "  ${curve}: ${cores}c/${threads}t -> ${wall:-FAILED}s"
}

NPROC=$(nproc)
# A: right-sized
for c in 1 2 4 8; do [ "$c" -le "$NPROC" ] && one A "$c" "$c"; done
# B: oversubscription at full width
for t in 1 2 4 8 16 32; do one B "$NPROC" "$t"; done

echo "=== RESULTS ==="; column -t < "$OUT"
