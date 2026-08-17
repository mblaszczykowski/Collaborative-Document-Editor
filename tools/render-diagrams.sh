#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
DIR=docs/diagrams
CONFIG="$DIR/mermaid.config.json"

MMDC=(npx -y @mermaid-js/mermaid-cli@11)

for src in "$DIR"/*.mmd; do
    out="${src%.mmd}.svg"
    printf '%-34s -> %s\n' "$src" "$out"
    "${MMDC[@]}" -i "$src" -o "$out" -c "$CONFIG" -b white --quiet
done

failed=0
for svg in "$DIR"/*.svg; do
    if grep -q foreignObject "$svg"; then
        echo "FAIL $svg contains <foreignObject>; GitHub will strip it and the labels will vanish" >&2
        failed=1
    fi
done
[ "$failed" -eq 0 ] || exit 1

echo "$(ls "$DIR"/*.svg | wc -l | tr -d ' ') diagrams rendered, none using foreignObject"
