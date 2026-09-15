#include <WiFi.h>
#include <PubSubClient.h>
#if __has_include("config.h")
#include "config.h"
#else
#include "config.example.h"
#endif

// --- Configuration ---
const char* ssid = WIFI_SSID;
const char* password = WIFI_PASSWORD;
const char* mqtt_server = MQTT_SERVER;

// --- Pins ---
const int PIR_PIN = 14;
const int SERVO_PIN = 13;
const int RELAY_PIN = 12;
const int BUZZER_PIN = 15;

WiFiClient espClient;
PubSubClient client(espClient);

void setup_wifi() {
  delay(10);
  Serial.println();
  Serial.print("Connecting to ");
  Serial.println(ssid);
  WiFi.begin(ssid, password);
  while (WiFi.status() != WL_CONNECTED) {
    delay(500);
    Serial.print(".");
  }
  Serial.println("WiFi connected");
}

void callback(char* topic, byte* payload, unsigned int length) {
  String message;
  for (int i = 0; i < length; i++) {
    message += (char)payload[i];
  }
  Serial.print("Message arrived [");
  Serial.print(topic);
  Serial.print("] ");
  Serial.println(message);

  // Xử lý lệnh mở cửa từ Server
  if (message == "OPEN_DOOR") {
    Serial.println("Opening door...");
    // TODO: Write HIGH to Relay, rotate Servo 90 degrees, beep Buzzer
    digitalWrite(RELAY_PIN, HIGH);
    delay(5000); // Mở 5 giây
    digitalWrite(RELAY_PIN, LOW);
  }
}

void reconnect() {
  while (!client.connected()) {
    Serial.print("Attempting MQTT connection...");
    if (client.connect("ESP32Client")) {
      Serial.println("connected");
      client.subscribe("iot/lock/commands");
    } else {
      Serial.print("failed, rc=");
      Serial.print(client.state());
      delay(5000);
    }
  }
}

void setup() {
  Serial.begin(115200);
  pinMode(PIR_PIN, INPUT);
  pinMode(RELAY_PIN, OUTPUT);
  pinMode(BUZZER_PIN, OUTPUT);
  
  setup_wifi();
  client.setServer(mqtt_server, 1883);
  client.setCallback(callback);
}

void loop() {
  if (!client.connected()) {
    reconnect();
  }
  client.loop();

  // Đọc cảm biến PIR
  int pirState = digitalRead(PIR_PIN);
  if (pirState == HIGH) {
    Serial.println("Motion detected!");
    client.publish("iot/lock/events", "PIR_MOTION_DETECTED");
    delay(5000); // Chờ 5 giây tránh gửi liên tục
  }
}
