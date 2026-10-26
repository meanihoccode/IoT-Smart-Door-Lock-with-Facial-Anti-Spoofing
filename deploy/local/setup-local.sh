#!/bin/sh
# Generates everything the local staging overlay needs: a test CA + server certificate for localhost,
# random throwaway secrets, broker password file and .env.local. Safe to re-run; it never touches deploy/.env.
# The test CA is for this machine only; never flash it into a production device or trust it in a browser profile you keep.
set -eu
# Git Bash on Windows would rewrite "/CN=..." and container paths into Windows paths.
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'
cd "$(dirname "$0")"
INSIGHTFACE_DIR="${1:-$HOME/.insightface}"
[ -d "$INSIGHTFACE_DIR/models/buffalo_l" ] || { echo "Missing $INSIGHTFACE_DIR/models/buffalo_l" >&2; exit 1; }
mkdir -p certs secrets mosquitto
umask 077

if [ ! -f certs/ca.pem ]; then
  openssl req -x509 -newkey rsa:2048 -nodes -days 30 -subj "/CN=SmartLock Local Test CA" \
    -keyout secrets/ca.key -out certs/ca.pem 2>/dev/null
  openssl req -newkey rsa:2048 -nodes -subj "/CN=localhost" -keyout certs/privkey.pem -out secrets/server.csr 2>/dev/null
  printf 'subjectAltName=DNS:localhost,IP:127.0.0.1\nextendedKeyUsage=serverAuth\n' > secrets/server.ext
  openssl x509 -req -in secrets/server.csr -CA certs/ca.pem -CAkey secrets/ca.key -CAcreateserial \
    -days 30 -extfile secrets/server.ext -out certs/server.pem 2>/dev/null
  cat certs/server.pem certs/ca.pem > certs/fullchain.pem
  chmod 644 certs/*.pem
fi

# OpenSSL in Git Bash ends lines with CRLF; a stray \r would silently change the token hash and passwords.
secret() { openssl rand -hex "$1" | tr -d '\r\n'; echo; }
for file in secrets/token.txt secrets/mqtt.txt secrets/mqtt-core.txt secrets/admin.txt; do
  [ -f "$file" ] && { tr -d '\r' < "$file" > "$file.tmp" && mv "$file.tmp" "$file"; }
done
if [ ! -f .env.local ]; then
  secret 32 > secrets/token.txt
  secret 24 > secrets/mqtt.txt
  secret 24 > secrets/mqtt-core.txt
  printf 'Local-%s\n' "$(secret 12)" > secrets/admin.txt
  cat > .env.local <<EOF
SMARTLOCK_DOMAIN=localhost
ACME_EMAIL=local@example.invalid
MYSQL_DATABASE=btl_iot
MYSQL_USER=smartlock
MYSQL_PASSWORD=$(secret 16)
MYSQL_ROOT_PASSWORD=$(secret 16)
INSIGHTFACE_MODELS_DIR=$INSIGHTFACE_DIR
MQTT_CORE_PASSWORD=$(cat secrets/mqtt-core.txt)
DEVICE_ID=door-01
DEVICE_TOKEN_SHA256=$(tr -d '\r\n' < secrets/token.txt | openssl dgst -sha256 -r | cut -d' ' -f1 | tr -d '\r*')
ADMIN_USERNAME=localadmin
ADMIN_PASSWORD=$(cat secrets/admin.txt)
EOF
fi

cp ../mosquitto/config/mosquitto.conf ../mosquitto/config/acl mosquitto/
docker run --rm -v "$(pwd -W 2>/dev/null || pwd)/mosquitto:/m" eclipse-mosquitto:2.0.20 sh -c \
  "mosquitto_passwd -c -b /m/passwd core '$(cat secrets/mqtt-core.txt)' && mosquitto_passwd -b /m/passwd door-01 '$(cat secrets/mqtt.txt)'"
echo "Local staging files ready in deploy/local (secrets/ holds the throwaway credentials)."
