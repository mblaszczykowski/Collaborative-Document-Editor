#!/usr/bin/env bash
#
# Turns a Playwright recording into the GIF for the README.
#
# The recording comes from a script (frontend/e2e/demo.spec.ts) rather than manual clicking — so it
# can be reproduced after every interface change, and the GIF in the README does not silently go
# stale.
#
#   cd frontend && npm run demo && cd .. && ./tools/make-gif.sh
#
# There are two scenes and each has its own GIF — one would be either too long for anyone to watch
# to the end, or too cursory to show anything:
#
#   ./tools/make-gif.sh core    docs/demo.gif      # concurrent editing, offline, history
#   ./tools/make-gif.sh product docs/demo-v2.gif   # formatting, comments, the bin
#
set -euo pipefail

SCENE="${1:-core}"
OUTPUT="${2:-docs/demo.gif}"
RECORDINGS="frontend/demo-recordings/$SCENE"

command -v ffmpeg >/dev/null || { echo "ffmpeg is missing — brew install ffmpeg"; exit 1; }
command -v gifski >/dev/null || { echo "gifski is missing — brew install gifski"; exit 1; }

# Playwright records one video per browser context. We take the LARGEST — that is Anna's
# recording, which runs the full scenario including the history panel.
VIDEO=$(find "$RECORDINGS" -name '*.webm' -type f -exec ls -S {} + 2>/dev/null | head -1)
[ -n "$VIDEO" ] || { echo "no recordings in $RECORDINGS — run first: cd frontend && npm run demo"; exit 1; }
echo "recording: $VIDEO"

FRAMES=$(mktemp -d)
trap 'rm -rf "$FRAMES"' EXIT

# 12 frames per second and a width of 1000 px: a compromise between legible text and file size. At
# 24 fps the GIF exceeds 10 MB and GitHub stops showing it in the preview, and at a width of 800 px
# the text in the editor becomes unreadable.
ffmpeg -loglevel error -i "$VIDEO" -vf "fps=12,scale=1000:-1:flags=lanczos" "$FRAMES/%05d.png"

mkdir -p "$(dirname "$OUTPUT")"
gifski --fps 12 --width 1000 --quality 80 -o "$OUTPUT" "$FRAMES"/*.png

echo "done: $OUTPUT ($(du -h "$OUTPUT" | cut -f1))"
