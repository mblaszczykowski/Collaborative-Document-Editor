#!/usr/bin/env bash
#
# Czyści bazę przed nagraniem demo.
#
# Testy end-to-end zostawiają po sobie dziesiątki dokumentów („Zbieżność", „Offline",
# „Kursory"...). Na nagraniu wyglądają jak śmieci w interfejsie i odwracają uwagę od tego,
# co demo ma pokazać.
#
# CASCADE, bo operacje, snapshoty i uczestnicy wiszą na dokumentach kluczem obcym.
set -euo pipefail

docker exec collabedit-postgres psql -U collabedit -d collabedit -q \
  -c 'TRUNCATE documents CASCADE;' \
  -c 'TRUNCATE users CASCADE;'

echo "baza wyczyszczona — pamiętaj o restarcie backendu, żeby zwolnił repliki z pamięci"
