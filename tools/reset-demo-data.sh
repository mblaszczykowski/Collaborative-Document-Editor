#!/usr/bin/env bash
#
# Clears the database before recording the demo.
#
# The end-to-end tests leave dozens of documents behind ("Convergence", "Offline", "Cursors"...).
# In a recording they look like clutter in the interface and distract from what the demo is meant
# to show.
#
# CASCADE, because operations, snapshots and members hang off documents by foreign key.
set -euo pipefail

docker exec collabedit-postgres psql -U collabedit -d collabedit -q \
  -c 'TRUNCATE documents CASCADE;' \
  -c 'TRUNCATE users CASCADE;'

echo "database cleared — remember to restart the backend so it releases its in-memory replicas"
