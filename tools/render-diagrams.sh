#!/usr/bin/env bash
#
# Renders the README diagrams from their mermaid sources in docs/diagrams/.
#
#   ./tools/render-diagrams.sh
#
# The source of truth is the .mmd file, not the .svg. The SVG is a build artefact that happens to be
# committed, because GitHub has to serve it without running a toolchain — so after editing a .mmd you
# have to run this, and both files go in the same commit.
#
# Why files rather than the ```mermaid fences GitHub renders by itself: a fence only works on GitHub.
# An <img> works in an IDE preview, in a local markdown viewer, and anywhere the repository is
# mirrored. The cost is that source and image can drift, which is what this script exists to prevent.
#
# Why --htmlLabels false is not optional: by default mermaid puts every node label inside a
# <foreignObject> containing XHTML. GitHub sanitises SVG it serves as an image and drops that element,
# so the diagram arrives with every box empty — a failure that does not show up locally, because a
# browser opening the file directly renders it fine. With the flag, labels are real <text> elements.
# The check at the end of this script fails the build rather than trusting that to stay true.
#
set -euo pipefail

cd "$(dirname "$0")/.."
DIR=docs/diagrams
CONFIG="$DIR/mermaid.config.json"

# Not a dependency of the build, so it is not in package.json: this runs when a diagram changes, which
# is rare, and pinning it here keeps a Chromium download out of every `npm install`.
MMDC=(npx -y @mermaid-js/mermaid-cli@11)

for src in "$DIR"/*.mmd; do
    out="${src%.mmd}.svg"
    printf '%-34s -> %s\n' "$src" "$out"
    # -b white: the SVG carries no background of its own, and a transparent one is illegible against
    # GitHub's dark theme. Mermaid's palette assumes a light ground, so paint it explicitly.
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
