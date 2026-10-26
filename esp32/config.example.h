#pragma once

// Copy to config.h (same folder) and fill in before flashing. config.h is private and ignored by Git.
// Never commit real Wi-Fi passwords, device tokens or MQTT passwords. See deploy/README.md.
#define WIFI_SSID "YOUR_WIFI_SSID"
#define WIFI_PASSWORD "YOUR_WIFI_PASSWORD"

// Cloud endpoints (HTTPS app/API and MQTT over TLS). No trailing slash on API_BASE_URL.
#define API_BASE_URL "https://smartlock.example.com"
#define MQTT_HOST "smartlock.example.com"
#define MQTT_PORT 8883

// Device identity. DEVICE_TOKEN: 64 hex chars; the server stores only its SHA-256.
// MQTT_PASSWORD: separate secret registered in the broker password file for user MQTT_USERNAME.
#define DEVICE_ID "door-01"
#define DEVICE_TOKEN ""
#define MQTT_USERNAME DEVICE_ID
#define MQTT_PASSWORD ""

#define NTP_SERVER "pool.ntp.org"

// Root CA(s) that sign the server certificates. With Let's Encrypt, paste ISRG Root X1 and X2 from
// https://letsencrypt.org/certificates/ (verify the fingerprints there). Without a certificate here the
// firmware refuses all cloud connections; it never falls back to insecure TLS.
#define SMARTLOCK_HAS_ROOT_CA 1
static const char ROOT_CA_PEM[] = R"PEM(
PASTE_ROOT_CA_PEM_HERE
)PEM";
