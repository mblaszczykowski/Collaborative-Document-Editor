#!/usr/bin/env bash
set -euo pipefail

SCENE="${1:-core}"
OUTPUT="${2:-docs/demo.gif}"
RECORDINGS="frontend/demo-recordings/$SCENE"

command -v ffmpeg >/dev/null || { echo "ffmpeg is missing - brew install ffmpeg"; exit 1; }
command -v gifski >/dev/null || { echo "gifski is missing - brew install gifski"; exit 1; }

VIDEO=$(find "$RECORDINGS" -name '*.webm' -type f -exec ls -S {} + 2>/dev/null | head -1)
[ -n "$VIDEO" ] || { echo "no recordings in $RECORDINGS - run first: cd frontend && npm run demo"; exit 1; }
echo "recording: $VIDEO"

FRAMES=$(mktemp -d)
trap 'rm -rf "$FRAMES"' EXIT

ffmpeg -loglevel error -i "$VIDEO" -vf "fps=12,scale=1000:-1:flags=lanczos" "$FRAMES/%05d.png"

mkdir -p "$(dirname "$OUTPUT")"
gifski --fps 12 --width 1000 --quality 80 -o "$OUTPUT" "$FRAMES"/*.png

echo "done: $OUTPUT ($(du -h "$OUTPUT" | cut -f1))"
