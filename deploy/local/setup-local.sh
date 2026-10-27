#!/bin/sh
# Generates everything the local staging overlay needs: a test CA + server certificate for localhost,
# random throwaway secrets and .env.local. Safe to re-run; it never touches deploy/.env.
# The test CA is for this machine only; never flash it into a production device or trust it in a browser profile you keep.
set -eu
# Git Bash on Windows would rewrite "/CN=..." into a Windows path.
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'
cd "$(dirname "$0")"
INSIGHTFACE_DIR="${1:-$HOME/.insightface}"
[ -d "$INSIGHTFACE_DIR/models/buffalo_l" ] || { echo "Missing $INSIGHTFACE_DIR/models/buffalo_l" >&2; exit 1; }
mkdir -p certs secrets
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
for file in secrets/token.txt secrets/admin.txt; do
  [ -f "$file" ] && { tr -d '\r' < "$file" > "$file.tmp" && mv "$file.tmp" "$file"; }
done
if [ ! -f .env.local ]; then
  secret 32 > secrets/token.txt
  printf 'Local-%s\n' "$(secret 12)" > secrets/admin.txt
  cat > .env.local <<EOF
SMARTLOCK_DOMAIN=localhost
MYSQL_DATABASE=btl_iot
MYSQL_USER=smartlock
MYSQL_PASSWORD=$(secret 16)
MYSQL_ROOT_PASSWORD=$(secret 16)
INSIGHTFACE_MODELS_DIR=$INSIGHTFACE_DIR
DEVICE_ID=door-01
DEVICE_TOKEN_SHA256=$(tr -d '\r\n' < secrets/token.txt | openssl dgst -sha256 -r | cut -d' ' -f1 | tr -d '\r*')
ADMIN_USERNAME=localadmin
ADMIN_PASSWORD=$(cat secrets/admin.txt)
EOF
fi
echo "Local staging files ready in deploy/local (secrets/ holds the throwaway credentials)."
