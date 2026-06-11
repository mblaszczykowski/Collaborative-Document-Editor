#!/usr/bin/env bash
#
# Zamienia nagranie z Playwrighta na GIF do README.
#
# Nagranie powstaje ze skryptu (frontend/e2e/demo.spec.ts), nie z ręcznego klikania —
# dzięki temu da się je odtworzyć po każdej zmianie interfejsu, a GIF w README nie
# zestarzeje się po cichu.
#
#   cd frontend && npm run demo && cd .. && ./tools/make-gif.sh
#
# Scen jest dwie i każda ma własny GIF — jeden byłby albo za długi, żeby ktokolwiek
# obejrzał go do końca, albo za pobieżny, żeby cokolwiek pokazać:
#
#   ./tools/make-gif.sh core    docs/demo.gif      # edycja współbieżna, offline, historia
#   ./tools/make-gif.sh product docs/demo-v2.gif   # formatowanie, komentarze, kosz
#
set -euo pipefail

SCENE="${1:-core}"
OUTPUT="${2:-docs/demo.gif}"
RECORDINGS="frontend/demo-recordings/$SCENE"

command -v ffmpeg >/dev/null || { echo "brak ffmpeg — brew install ffmpeg"; exit 1; }
command -v gifski >/dev/null || { echo "brak gifski — brew install gifski"; exit 1; }

# Playwright nagrywa jedno wideo na kontekst przeglądarki. Bierzemy NAJWIĘKSZE —
# to nagranie Anny, która wykonuje pełny scenariusz łącznie z panelem historii.
VIDEO=$(find "$RECORDINGS" -name '*.webm' -type f -exec ls -S {} + 2>/dev/null | head -1)
[ -n "$VIDEO" ] || { echo "brak nagrań w $RECORDINGS — uruchom najpierw: cd frontend && npm run demo"; exit 1; }
echo "nagranie: $VIDEO"

FRAMES=$(mktemp -d)
trap 'rm -rf "$FRAMES"' EXIT

# 12 klatek na sekundę i szerokość 1000 px: kompromis między czytelnością tekstu
# a rozmiarem pliku. Przy 24 fps GIF przekracza 10 MB i GitHub przestaje go pokazywać
# w podglądzie, a przy szerokości 800 px tekst w edytorze robi się nieczytelny.
ffmpeg -loglevel error -i "$VIDEO" -vf "fps=12,scale=1000:-1:flags=lanczos" "$FRAMES/%05d.png"

mkdir -p "$(dirname "$OUTPUT")"
gifski --fps 12 --width 1000 --quality 80 -o "$OUTPUT" "$FRAMES"/*.png

echo "gotowe: $OUTPUT ($(du -h "$OUTPUT" | cut -f1))"
