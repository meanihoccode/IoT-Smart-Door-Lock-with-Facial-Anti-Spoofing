#pragma once

// Copy to config.h (same folder) and fill in before flashing. config.h is private and ignored by Git.
// Never commit real Wi-Fi passwords or device tokens. See deploy/README.md.
#define WIFI_SSID "YOUR_WIFI_SSID"
#define WIFI_PASSWORD "YOUR_WIFI_PASSWORD"

// One cloud domain for REST (https://CLOUD_HOST/api/...) and the command WebSocket (wss://CLOUD_HOST/ws/device).
#define CLOUD_HOST "smartlock.example.com"
#define CLOUD_PORT 443

// Device identity. DEVICE_TOKEN: 64 hex chars; the server stores only its SHA-256.
// The same token authenticates REST requests and the WebSocket handshake.
#define DEVICE_ID "door-01"
#define DEVICE_TOKEN ""

#define NTP_SERVER "pool.ntp.org"

// Root CA(s) that sign the server certificate. With Let's Encrypt, paste ISRG Root X1 and X2 from
// https://letsencrypt.org/certificates/ (verify the fingerprints there). Without a certificate here the
// firmware refuses all cloud connections; it never falls back to insecure TLS.
#define SMARTLOCK_HAS_ROOT_CA 1
static const char ROOT_CA_PEM[] = R"PEM(
PASTE_ROOT_CA_PEM_HERE
)PEM";
