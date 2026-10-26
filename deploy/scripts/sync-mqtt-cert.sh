#!/bin/sh
# Copies the Let's Encrypt certificate Caddy obtained for SMARTLOCK_DOMAIN to Mosquitto's TLS listener and
# restarts the broker only when it changed. Run from deploy/ after the first certificate, then daily (cron).
# Devices and Core reconnect automatically; commands are never retained, so nothing is replayed.
set -eu
cd "$(dirname "$0")/.."
DOMAIN="$(grep '^SMARTLOCK_DOMAIN=' .env | cut -d= -f2-)"
[ -n "$DOMAIN" ] || { echo "SMARTLOCK_DOMAIN missing in .env" >&2; exit 1; }
SRC="/data/caddy/certificates/acme-v02.api.letsencrypt.org-directory/$DOMAIN"

docker compose exec -T web cat "$SRC/$DOMAIN.crt" > mosquitto/certs/fullchain.pem.new
docker compose exec -T web cat "$SRC/$DOMAIN.key" > mosquitto/certs/privkey.pem.new
[ -s mosquitto/certs/fullchain.pem.new ] && [ -s mosquitto/certs/privkey.pem.new ] || { echo "certificate not found" >&2; exit 1; }

if cmp -s mosquitto/certs/fullchain.pem.new mosquitto/certs/fullchain.pem 2>/dev/null; then
  rm -f mosquitto/certs/*.new
  echo "MQTT certificate unchanged."
  exit 0
fi
mv mosquitto/certs/fullchain.pem.new mosquitto/certs/fullchain.pem
mv mosquitto/certs/privkey.pem.new mosquitto/certs/privkey.pem
# The broker runs as uid 1883 inside the container.
chmod 644 mosquitto/certs/fullchain.pem
chmod 600 mosquitto/certs/privkey.pem
chown 1883:1883 mosquitto/certs/privkey.pem mosquitto/certs/fullchain.pem 2>/dev/null || echo "warning: run as root to chown the key to uid 1883"
docker compose restart mosquitto
echo "MQTT certificate updated."
