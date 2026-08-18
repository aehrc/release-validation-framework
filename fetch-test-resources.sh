#!/bin/bash
# Fetch the Drools test resources from the canonical bucket.
#
# RVF reads these at runtime from S3 (application.properties:
# test-resources.cloud.bucketName=validation-resources.ihtsdo, path
# prod/international). A local run has no cloud credentials, so it needs a local
# copy - and the copy that was lying around was a stale partial snapshot from the
# old fork: 5 files, missing us-to-gb-terms-map.txt entirely and carrying the
# superseded usTerms.txt/gbTerms.txt pair instead.
#
# snomed-drools 6.0.0 fails hard on that: "Failed to load test resources ...
# FileNotFoundException: us-to-gb-terms-map.txt". Older versions read the pair.
#
# The bucket is publicly readable over the REST endpoint, so no credentials are
# needed. Note the virtual-host form (validation-resources.ihtsdo.s3.amazonaws.com)
# does NOT resolve - the bucket name contains dots, which breaks TLS wildcard
# matching. Use the path-style endpoint.
set -euo pipefail

BUCKET=${BUCKET:-validation-resources.ihtsdo}
PREFIX=${PREFIX:-prod/international}
DEST=${1:-./test-resources}
BASE="https://s3.amazonaws.com/$BUCKET"

mkdir -p "$DEST"
echo "==> listing $BUCKET/$PREFIX"
KEYS=$(curl -sf "$BASE?list-type=2&prefix=$PREFIX/" \
       | tr '<' '\n' | grep -oE "^Key>$PREFIX/.*" | sed "s|^Key>||" | grep -v '/$')
N=$(echo "$KEYS" | grep -c . || true)
echo "    $N objects"
[ "$N" -gt 0 ] || { echo "FATAL: listing returned nothing - bucket or prefix wrong?"; exit 1; }

FETCHED=0
for k in $KEYS; do
    f="$DEST/$(basename "$k")"
    if curl -sf -o "$f.part" "$BASE/$k"; then
        mv "$f.part" "$f"; FETCHED=$((FETCHED+1))
    else
        rm -f "$f.part"; echo "    WARN could not fetch $k"
    fi
done
echo "==> fetched $FETCHED/$N into $DEST"

# The three snomed-drools 6.0.0 hard-requires. It tolerates a missing
# semantic-tag-hierarchies (loads 0) but not a missing terms map.
for f in semantic-tags.txt cs_words.txt us-to-gb-terms-map.txt; do
    [ -s "$DEST/$f" ] || { echo "FATAL: $DEST/$f missing or empty"; exit 1; }
done
echo "==> required files present"
ls -1 "$DEST" | wc -l | xargs echo "   total files:"
