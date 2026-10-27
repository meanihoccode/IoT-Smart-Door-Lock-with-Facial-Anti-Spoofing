#!/bin/sh
# Generates the secret for one door node. Prints it once; store it in firmware config.h and its hash in deploy/.env.
# The server keeps only the token's SHA-256. The same token authenticates REST calls and the WebSocket handshake.
set -eu
DEVICE_ID="${1:-door-01}"
case "$DEVICE_ID" in
  *[!a-z0-9-]*|-*|"") echo "Device ID must match [a-z0-9][a-z0-9-]{1,39}" >&2; exit 1 ;;
esac

# tr strips the CR that OpenSSL emits under Git Bash; a stray \r would silently change the hash.
TOKEN="$(openssl rand -hex 32 | tr -d '\r\n')"
TOKEN_SHA256="$(printf %s "$TOKEN" | openssl dgst -sha256 -r | cut -d' ' -f1 | tr -d '\r*')"

cat <<EOF
== Firmware esp32/config.h (private, never commit) ==
#define DEVICE_ID "$DEVICE_ID"
#define DEVICE_TOKEN "$TOKEN"

== deploy/.env ==
DEVICE_ID=$DEVICE_ID
DEVICE_TOKEN_SHA256=$TOKEN_SHA256
EOF
