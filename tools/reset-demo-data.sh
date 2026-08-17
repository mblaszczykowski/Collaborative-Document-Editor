#!/usr/bin/env bash
set -euo pipefail

docker exec collabedit-postgres psql -U collabedit -d collabedit -q \
  -c 'TRUNCATE documents CASCADE;' \
  -c 'TRUNCATE users CASCADE;'

echo "database cleared - remember to restart the backend so it releases its in-memory replicas"
