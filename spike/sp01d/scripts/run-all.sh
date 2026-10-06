#!/usr/bin/env bash
set -u
CASES="vd1-refbare-first vd1-refbare-mid vd1-ref-first vd1-guarded-first vd1-guarded-mid vd1-ref-mid vd1-plain-mid vd1-fin-content vd1-guarded-tooldup vd1-ref-tooldup vd1-refbare-tooldup vd1-truncate-rst vd1-truncate-fin vd1-guarded-longtool vd1-ref-longtool vd3-coop-cancel vd3-hard-cancel vd2-fronttool vd2-spi-dup vd1-baseline vd1-plain-hang"
for c in $CASES; do
  echo "=== $c ==="
  timeout 150 node scripts/spike-verify.mjs "$c" 2>&1 | tail -3
  sleep 1
done
