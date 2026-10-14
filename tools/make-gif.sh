#!/usr/bin/env bash
set -euo pipefail

# Run from the repository root after a successful `cd frontend && npm run demo`.
# Usage: tools/make-gif.sh core docs/demo.gif
#        tools/make-gif.sh product docs/demo-v2.gif
# Set DEMO_VIDEO=/path/to/the/current-tested-clip.webm to select an exact recording.
# Requires ffmpeg and gifski. Without an override, the newest scene WebM is selected;
# inspect the printed filename and use only a successful test recording.
# This converter does not run the app, browser tests, or reset any database.

SCENE="${1:-core}"
case "$SCENE" in
  core) DEFAULT_OUTPUT="docs/demo.gif" ;;
  product) DEFAULT_OUTPUT="docs/demo-v2.gif" ;;
  *) echo "scene must be core or product"; exit 1 ;;
esac
OUTPUT="${2:-$DEFAULT_OUTPUT}"
RECORDINGS="frontend/demo-recordings/$SCENE"

command -v ffmpeg >/dev/null || { echo "ffmpeg is missing - brew install ffmpeg"; exit 1; }
command -v gifski >/dev/null || { echo "gifski is missing - brew install gifski"; exit 1; }

VIDEO="${DEMO_VIDEO:-}"
if [ -z "$VIDEO" ]; then
  [ -d "$RECORDINGS" ] || { echo "no recordings in $RECORDINGS - run first: cd frontend && npm run demo"; exit 1; }
  VIDEO=$(find "$RECORDINGS" -name '*.webm' -type f -exec ls -t {} + 2>/dev/null | sed -n '1p')
fi
[ -n "$VIDEO" ] && [ -f "$VIDEO" ] || { echo "recording not found: ${VIDEO:-$RECORDINGS}"; exit 1; }
echo "recording: $VIDEO"

FRAMES=$(mktemp -d)
trap 'rm -rf "$FRAMES"' EXIT

ffmpeg -loglevel error -i "$VIDEO" -vf "fps=12,scale=1000:-1:flags=lanczos" "$FRAMES/%05d.png"

mkdir -p "$(dirname "$OUTPUT")"
gifski --fps 12 --width 1000 --quality 80 -o "$OUTPUT" "$FRAMES"/*.png

echo "done: $OUTPUT ($(du -h "$OUTPUT" | cut -f1))"
