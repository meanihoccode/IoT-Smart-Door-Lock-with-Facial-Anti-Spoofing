#!/bin/sh
# Generates the secrets for one door node. Prints them once; store them in firmware config.h and deploy/.env.
# The server keeps only the token's SHA-256 and a hashed MQTT password (mosquitto_passwd).
set -eu
DEVICE_ID="${1:-door-01}"
case "$DEVICE_ID" in
  *[!a-z0-9-]*|-*|"") echo "Device ID must match [a-z0-9][a-z0-9-]{1,39}" >&2; exit 1 ;;
esac

# tr strips the CR that OpenSSL emits under Git Bash; a stray \r would silently change the hash.
TOKEN="$(openssl rand -hex 32 | tr -d '\r\n')"
TOKEN_SHA256="$(printf %s "$TOKEN" | openssl dgst -sha256 -r | cut -d' ' -f1 | tr -d '\r*')"
MQTT_PASSWORD="$(openssl rand -hex 24 | tr -d '\r\n')"

cat <<EOF
== Firmware esp32/config.h (private, never commit) ==
#define DEVICE_ID "$DEVICE_ID"
#define DEVICE_TOKEN "$TOKEN"
#define MQTT_USERNAME DEVICE_ID
#define MQTT_PASSWORD "$MQTT_PASSWORD"

== deploy/.env ==
DEVICE_ID=$DEVICE_ID
DEVICE_TOKEN_SHA256=$TOKEN_SHA256

== Register the MQTT password (run in deploy/) ==
docker compose run --rm mosquitto mosquitto_passwd -b /mosquitto/config/passwd $DEVICE_ID '$MQTT_PASSWORD'
EOF
