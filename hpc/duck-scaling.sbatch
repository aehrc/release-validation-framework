#!/bin/bash
#SBATCH --job-name=duck-scaling
#SBATCH --nodes=1
#SBATCH --exclusive
#SBATCH --time=08:00:00
#SBATCH --output=duck-scaling-%j.out
#
# Extend the local core/thread scaling curve to extreme width, to test whether
# the 22.2% serial fraction fitted on 10 cores holds, and where the curve really
# flattens. See k8s/SCALING.md for the local results this extends.
#
# --exclusive because the whole point is to control how many cores the process
# can see; a shared node makes every number meaningless.
#
# Usage:
#   sbatch --cpus-per-task=<max cores on the node> hpc/duck-scaling.sbatch
# Environment (all optional):
#   TREE     checkout of this repo, default $SLURM_SUBMIT_DIR
#   REL      unpacked RF2 release directory  (REQUIRED)
#   STORE    store.json, default $TREE/src/main/resources/duck/store.json
#   CORPUS   assertion corpus, default $TREE/snomed-release-validation-assertions
#   WORK     scratch for DuckDB files, default $TMPDIR (use node-local disk!)
#   XMX      JVM heap, default 16g
set -eu

TREE=${TREE:-${SLURM_SUBMIT_DIR:-$PWD}}
STORE=${STORE:-$TREE/src/main/resources/duck/store.json}
CORPUS=${CORPUS:-$TREE/snomed-release-validation-assertions}
WORK=${WORK:-${TMPDIR:-/tmp}/duckwork-${SLURM_JOB_ID:-$$}}
XMX=${XMX:-16g}
OUT=${OUT:-duck-scaling-${SLURM_JOB_ID:-$$}.tsv}
: "${REL:?set REL to the unpacked RF2 release directory}"

# Java 25 is required (snomed-drools 6.0.0 / BOM 4.x). Prefer a module if the
# site provides one, else JAVA_HOME must already be a 25 JDK.
if command -v module >/dev/null 2>&1; then
  module load java/25 2>/dev/null || module load openjdk/25 2>/dev/null || true
fi
JAVA=${JAVA:-${JAVA_HOME:+$JAVA_HOME/bin/java}}
JAVA=${JAVA:-java}
"$JAVA" -version 2>&1 | head -1

CP="$TREE/target/classes:$TREE/target/test-classes:$(cat "$TREE/drools-cp.txt" 2>/dev/null || cat /data/work/drools-cp.txt)"
NPROC=${SLURM_CPUS_PER_TASK:-$(nproc)}
echo "node cores available: $NPROC   heap: $XMX   scratch: $WORK"

mkdir -p "$WORK"
printf 'curve\tcores\tthreads\tmem_limit\twall_s\tmaterialise_s\trows\tpeak_rss_gb\ttests\tfails\n' > "$OUT"

one() {
  local curve="$1" cores="$2" threads="$3" mem="${4:-}"
  local log="scale-${curve}-${cores}c-${threads}t${mem:+-$mem}.log"
  rm -rf "$WORK"/* 2>/dev/null || true

  # Pin to a contiguous core range. On a NUMA machine this deliberately fills
  # socket 0 first: if the curve degrades exactly where the range crosses a
  # socket boundary, that is the NUMA effect rather than Amdahl's law.
  taskset -c "0-$((cores-1))" \
    env AWS_REGION=${AWS_REGION:-us-east-1} \
    "$JAVA" -Xmx"$XMX" -Daws.region=${AWS_REGION:-us-east-1} \
      -Djava.io.tmpdir="$WORK" \
      --add-opens=java.base/java.lang=ALL-UNNAMED \
      -Dprobe.store="$STORE" -Dprobe.corpus="$CORPUS" \
      -Dprobe.prospective="$REL" -Dprobe.work="$WORK" \
      -Dprobe.edition=true -Dprobe.threads="$threads" \
      ${mem:+-Dprobe.memory=$mem} \
      -cp "$CP" org.ihtsdo.rvf.DuckValidationProbe > "$log" 2>&1 &
  local jvm=$! peak=0 r
  while kill -0 "$jvm" 2>/dev/null; do
    r=$(ps -o rss= -p "$jvm" 2>/dev/null | tr -d ' ' || true)
    [ -n "${r:-}" ] && [ "$r" -gt "$peak" ] && peak=$r
    sleep 2
  done
  wait "$jvm" 2>/dev/null || true

  local wall mat rows tests fails
  wall=$(grep -oE '=== [0-9.]+s wall clock' "$log" | grep -oE '[0-9.]+' | head -1 || true)
  mat=$(grep -oE 'in [0-9]+ms' "$log" | grep -oE '[0-9]+' | head -1 || true)
  rows=$(grep -oE '\([0-9]+ rows' "$log" | grep -oE '[0-9]+' | head -1 || true)
  tests=$(grep -oE 'tests run +[0-9]+' "$log" | grep -oE '[0-9]+' | head -1 || true)
  fails=$(grep -oE 'failures +[0-9]+' "$log" | grep -oE '[0-9]+' | head -1 || true)

  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    "$curve" "$cores" "$threads" "${mem:-default}" "${wall:-NA}" \
    "$(( ${mat:-0} / 1000 ))" "${rows:-NA}" \
    "$(awk -v p="$peak" 'BEGIN{printf "%.2f", p/1048576}')" \
    "${tests:-NA}" "${fails:-NA}" >> "$OUT"
  echo "  ${curve} ${cores}c/${threads}t ${mem:-default} -> ${wall:-FAILED}s"
}

# A: right-sized, doubling to the width of the node. This is the curve that
# tests the Amdahl prediction (local fit: ceiling 4.50x at 58s).
c=1
while [ "$c" -le "$NPROC" ]; do one A "$c" "$c"; c=$((c*2)); done
[ "$c" -ne $((NPROC*2)) ] && one A "$NPROC" "$NPROC"

# B: oversubscription at full node width. Locally the optimum was threads==8 on
# 10 cores and 32 threads was 13% WORSE; does that still hold at this width?
for t in 1 2 4 8 16 32 64 128 256; do
  [ "$t" -le $((NPROC*4)) ] && one B "$NPROC" "$t"
done

# C: does DuckDB's memory heuristic hurt on a huge node? Locally peak RSS was
# flat at ~4 GB, so an explicit limit should change nothing - if it does here,
# rvf.duck.memory.limit belongs in production config.
for m in 4GB 16GB 64GB; do one C "$NPROC" "$NPROC" "$m"; done

echo "=== RESULTS ==="
column -t < "$OUT" || cat "$OUT"
echo
echo "Fit the serial fraction from curve A and compare with the local 22.2%:"
echo "  s = (1/S - 1/n) / (1 - 1/n)   where S = wall(1 core) / wall(n cores)"
