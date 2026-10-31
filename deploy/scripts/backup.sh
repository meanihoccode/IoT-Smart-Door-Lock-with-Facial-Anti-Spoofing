#!/bin/sh
# Consistent MySQL dump plus the alert-photo volume. Run before every upgrade or schema change.
# The DB password is read inside the container, so it never appears on the host command line.
set -eu
cd "$(dirname "$0")/.."
STAMP="$(date +%Y%m%d-%H%M%S)"
mkdir -p backups
umask 077

docker compose exec -T db sh -c 'exec mysqldump --single-transaction --routines --triggers -uroot -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE"' \
  | gzip > "backups/db-$STAMP.sql.gz"
docker compose run --rm --no-deps --user root -v "$(pwd)/backups:/backup" --entrypoint sh core \
  -c "tar czf /backup/alerts-$STAMP.tar.gz -C /data alerts"

gzip -t "backups/db-$STAMP.sql.gz"
ls -l "backups/db-$STAMP.sql.gz" "backups/alerts-$STAMP.tar.gz"
echo "Backup written. Copy it off the VM; a backup on the same disk does not survive losing the VM."
