/*
 * ============================================================================
 * HỆ THỐNG KHÓA CỬA THÔNG MINH AIoT - ESP32-S3 WROOM N16R8 CAM
 * Bo mạch: ESP32-S3 WROOM N16R8 CAM (16MB Flash, 8MB Octal PSRAM, OV2640)
 * Tích hợp: 
 *   - Camera OV2640: Web Server cổng 80 (/capture snapshot live + /status)
 *   - Đồng bộ dữ liệu mở cửa 2 chiều với Backend Core (:8080) và Kiosk
 *   - AI Face Recognition (Backend Core :8080 proxy sang FastAPI :8000)
 *   - Bàn phím ma trận Keypad 4x4 (Mở khóa mã PIN 6 số, Phím 'D' gọi chuông)
 *   - Cảm biến PIR (Tự bật LED và chụp ảnh xác thực khuôn mặt)
 *   - Cảnh báo kẻ lạ khi quét mặt thất bại > 5 lần (Buzzer + chụp ảnh gửi Admin)
 *   - Cảnh báo khi nhập sai mã PIN 3 lần liên tiếp (Buzzer + chụp ảnh gửi Admin)
 *   - Khóa khẩn cấp (Emergency Lockdown): Vô hiệu hóa mở cửa khi Admin ra lệnh
 *   - Cơ cấu chấp hành: Động cơ Servo 90 độ + Module Relay 5V + Còi Buzzer 5V
 * ============================================================================
 */

#include <Arduino.h>
#include <WiFi.h>
#include <WebServer.h>
#include <HTTPClient.h>
#if __has_include("PubSubClient.h")
#include "PubSubClient.h"
#else
#include <PubSubClient.h>
#endif
#include "esp_camera.h"
#include <Keypad.h>
#include <ESP32Servo.h>

#if __has_include("config.h")
#include "config.h"
#elif __has_include("../config.h")
#include "../config.h"
#else
#include "config.example.h"
#endif

// ============================================================================
// 1. CẤU HÌNH MẠNG WI-FI, BACKEND VÀ MQTT
// ============================================================================
#ifndef WIFI_SSID
#define WIFI_SSID "YOUR_WIFI_SSID"
#endif

#ifndef WIFI_PASSWORD
#define WIFI_PASSWORD "YOUR_WIFI_PASSWORD"
#endif

#ifndef MQTT_SERVER
#define MQTT_SERVER "192.168.1.100"
#endif

const char* wifi_ssid     = WIFI_SSID;
const char* wifi_password = WIFI_PASSWORD;
const char* server_ip     = MQTT_SERVER;

// Endpoint Backend Core (Spring Boot cổng 8080 - đồng bộ CSDL và nhật ký)
String backendVerifyUrl   = String("http://") + server_ip + ":8080/api/verify-face";
String backendPinUrl      = String("http://") + server_ip + ":8080/api/verify-pin";
String backendAlertUrl    = String("http://") + server_ip + ":8080/api/security-alerts";

// Topic MQTT
const char* MQTT_TOPIC_SUB = "iot/lock/commands"; // Lắng nghe lệnh từ Web Kiosk / Admin
const char* MQTT_TOPIC_PUB = "iot/lock/events";   // Gửi sự kiện mở cửa, PIR, cảnh báo

// Mã PIN dự phòng offline trên Keypad 4x4
const String MASTER_PIN = "123456";

// Thời gian điều khiển
const unsigned long UNLOCK_DURATION_MS     = 7000;  // Mở cửa 7 giây theo yêu cầu rồi tự đóng
const unsigned long PIR_COOLDOWN_MS        = 10000; // Khoảng cách giữa 2 lần quét thử liên tiếp (10 giây)
const unsigned long PIR_POST_UNLOCK_MS     = 15000; // Thời gian nghỉ sau khi mở cửa thành công (15 giây)
const unsigned long PIR_ALERT_LOCKOUT_MS   = 30000; // Thời gian nghỉ sau khi cảnh báo kẻ lạ 5 lần (30 giây)
const unsigned long LOCKOUT_DURATION_MS    = 15000; // Khóa bàn phím 15 giây nếu nhập sai PIN 3 lần

// ============================================================================
// 2. ĐỊNH NGHĨA CHÂN CAMERA OV2640 (CỐ ĐỊNH TRÊN ESP32-S3 N16R8 CAM)
// ============================================================================
#define PWDN_GPIO_NUM     -1
#define RESET_GPIO_NUM    -1
#define XCLK_GPIO_NUM     15
#define SIOD_GPIO_NUM      4
#define SIOC_GPIO_NUM      5
#define Y9_GPIO_NUM       16
#define Y8_GPIO_NUM       17
#define Y7_GPIO_NUM       18
#define Y6_GPIO_NUM       12
#define Y5_GPIO_NUM       10
#define Y4_GPIO_NUM        8
#define Y3_GPIO_NUM        9
#define Y2_GPIO_NUM       11
#define VSYNC_GPIO_NUM     6
#define HREF_GPIO_NUM      7
#define PCLK_GPIO_NUM     13

// ============================================================================
// 3. ĐỊNH NGHĨA CHÂN NGOẠI VI
// ============================================================================
#define PIN_LED           19 // Chân nối bóng LED F5 5mm (trợ sáng và báo trạng thái)
#define PIN_BUZZER        20 // Chân cực dương (+) của Chip Buzzer 5V
#define PIN_PIR           21 // Chân OUT của cảm biến chuyển động PIR
#define PIN_SERVO         47 // Chân PWM của Servo Motor
#define PIN_RELAY         48 // Chân IN của Module Relay 5V

#define RELAY_ON          HIGH
#define RELAY_OFF         LOW
#define SERVO_LOCK_ANGLE  0
#define SERVO_UNLOCK_ANGLE 90

// ============================================================================
// 4. CẤU HÌNH BÀN PHÍM KEYPAD 4x4 (8 CHÂN)
// ============================================================================
const byte ROWS = 4;
const byte COLS = 4;
char keys[ROWS][COLS] = {
  {'2', '1', '3', 'A'},
  {'5', '4', '6', 'B'},
  {'8', '7', '9', 'C'},
  {'0', '*', '#', 'D'}
};
byte rowPins[ROWS] = {38, 39, 40, 41}; // 4 Hàng R1-R4 (Dây 1->38, Dây 2->39, Dây 3->40, Dây 4->41)
byte colPins[COLS] = { 2, 42,  1, 45}; // Đảo thứ tự quét: Cột GPIO 2 quét trước GPIO 42 để phím '0' được nhận diện ưu tiên trước '*'

// Lớp Keypad tối ưu cho ESP32-S3:
// Khắc phục triệt để đặc tính chân GPIO 2 trên ESP32-S3 khi ở chế độ INPUT thuần bị rò mức LOW,
// giữ điện áp 3.3V cho các cột chưa quét và chống sụt áp chéo.
class ESP32Keypad : public Keypad {
public:
  ESP32Keypad(char *userKeymap, byte *row, byte *col, byte numRows, byte numCols)
    : Keypad(userKeymap, row, col, numRows, numCols) {}

  void pin_mode(byte pinNum, byte mode) override {
    if (mode == INPUT) {
      pinMode(pinNum, INPUT_PULLUP);
    } else {
      pinMode(pinNum, mode);
    }
  }

  void pin_write(byte pinNum, boolean level) override {
    digitalWrite(pinNum, level);
    if (level == LOW) {
      delayMicroseconds(10);
    }
  }
};

ESP32Keypad keypad = ESP32Keypad(makeKeymap(keys), rowPins, colPins, ROWS, COLS);

// Đối tượng Servo, MQTT & Web Server
Servo doorServo;
WiFiClient espClient;
PubSubClient mqttClient(espClient);

WebServer server(80);
bool cameraReady = false;

// Biến trạng thái hệ thống
String inputPinBuffer          = "";
int failedPinAttempts          = 0;
int consecutivePirFaceFails    = 0; // Đếm số lần quét mặt thất bại liên tiếp của PIR
bool isDoorUnlocked            = false;
bool isSystemLockedOut         = false;
bool isEmergencyLockdown       = false; // Chế độ vô hiệu hóa mở cửa từ Admin
unsigned long doorUnlockTime   = 0;
unsigned long lockoutStartTime = 0;
unsigned long lastPirTrigger   = 0;
unsigned long lastMqttReconnect= 0;
unsigned long pirWarmupEnd     = 0;
bool pirRequiresRelease        = false; // Bắt buộc cảm biến PIR phải hạ về LOW trước khi kích hoạt đợt quét mới

static SemaphoreHandle_t camMutex = NULL;

// Khai báo trước các hàm xử lý
void handleKeypadInput(char key);
bool captureAndSendToBackend(const String& targetUrl, const String& eventType);
bool sendSecurityAlertWithPhoto(const String& alertType, const String& description);
bool verifyPinWithBackend(const String& pin);

// ============================================================================
// HÀM PHÁT ÂM THANH TRÊN CHIP BUZZER 5V
// ============================================================================
void beep(int times, int onMs, int offMs = 80) {
  for (int i = 0; i < times; i++) {
    digitalWrite(PIN_BUZZER, HIGH);
    delay(onMs);
    digitalWrite(PIN_BUZZER, LOW);
    if (i < times - 1) delay(offMs);
  }
}

// ============================================================================
// HÀM MỞ KHÓA CỬA (SERVO 90 ĐỘ + RELAY + LED SÁNG + BUZZER)
// ============================================================================
void unlockDoor(const String& reason) {
  if (isEmergencyLockdown) {
    Serial.printf("\n[TỪ CHỐI MỞ CỬA] Cửa đang bị VÔ HIỆU HÓA KHẨN CẤP! Lý do yêu cầu: %s\n", reason.c_str());
    beep(4, 100, 60);
    return;
  }

  // Nếu cửa đã đang mở, chỉ cần gia hạn thêm 7 giây (tránh bị reset/đóng ngay do lệnh MQTT lặp)
  if (isDoorUnlocked) {
    Serial.printf("[MỞ CỬA] Cửa đang mở, gia hạn thêm 7 giây (%s)\n", reason.c_str());
    doorUnlockTime = millis();
    return;
  }

  Serial.printf("\n>>> [MỞ CỬA] Lý do: %s <<<\n", reason.c_str());
  isDoorUnlocked = true;
  doorUnlockTime = millis();
  failedPinAttempts = 0;
  consecutivePirFaceFails = 0;
  pirRequiresRelease = true; // Yêu cầu cảm biến PIR phải nhả về LOW

  digitalWrite(PIN_LED, HIGH); // Bật LED trong suốt thời gian mở cửa
  digitalWrite(PIN_RELAY, RELAY_ON);
  doorServo.write(SERVO_UNLOCK_ANGLE);
  beep(2, 120, 80);

  // ĐỒNG BỘ SỰ KIỆN MỞ CỬA LÊN MQTT BROKER
  if (mqttClient.connected()) {
    String method = "HARDWARE";
    if (reason.indexOf("PIN") >= 0) method = "PIN";
    else if (reason.indexOf("khuôn mặt") >= 0 || reason.indexOf("PIR") >= 0 || reason.indexOf("AI") >= 0) method = "FACE";
    String evt = "DOOR_UNLOCKED:" + method;
    mqttClient.publish(MQTT_TOPIC_PUB, evt.c_str());
    Serial.printf("[MQTT PUB] Đã đồng bộ sự kiện: %s\n", evt.c_str());
  }

  // Khởi động đếm ngược 7 giây sau khi đã hoàn tất kích hoạt phần cứng & phát âm thanh
  doorUnlockTime = millis();
}

// ============================================================================
// HÀM TỰ ĐỘNG ĐÓNG KHÓA CỬA (TẮT ĐÈN LED + RELAY OFF + SERVO 0 ĐỘ)
// ============================================================================
void lockDoor() {
  Serial.println(">>> [ĐÓNG CỬA] Hết thời gian mở (7 giây), tự động khóa lại cửa. <<<\n");
  isDoorUnlocked = false;
  doorServo.write(SERVO_LOCK_ANGLE);
  digitalWrite(PIN_RELAY, RELAY_OFF);
  digitalWrite(PIN_LED, LOW); // Đảm bảo đèn LED tắt khi cửa đóng
  beep(1, 150);

  // Sau khi đóng cửa, nghỉ 15 giây và yêu cầu PIR nhả về LOW để không quét lại người vừa vào
  lastPirTrigger = millis() + PIR_POST_UNLOCK_MS;
  pirRequiresRelease = true;
}

// ============================================================================
// GIAO DIỆN WEB SERVER CỔNG 80 (LIVE SNAPSHOT VIEW TRỰC TIẾP)
// ============================================================================
void sendRootHtml(WebServer &srv) {
  String html = "<!DOCTYPE html><html><head><meta charset='utf-8'>";
  html += "<meta name='viewport' content='width=device-width,initial-scale=1'>";
  html += "<title>ESP32-S3 CAM Viewer</title>";
  html += "<style>body{font-family:Arial,sans-serif;text-align:center;background:#0f172a;color:#f8fafc;padding:20px;}";
  html += ".card{background:#1e293b;border-radius:12px;padding:20px;max-width:680px;margin:0 auto;box-shadow:0 4px 6px rgba(0,0,0,0.3);}";
  html += "img{max-width:100%;width:640px;border:3px solid #38bdf8;border-radius:8px;margin:15px 0;background:#000;}";
  html += "a.btn,button.btn{padding:10px 18px;margin:6px;font-size:14px;font-weight:600;border:none;border-radius:6px;cursor:pointer;text-decoration:none;display:inline-block;color:#fff;}";
  html += ".btn-cap{background:#0284c7;}.btn-kiosk{background:#4f46e5;}</style></head><body>";
  html += "<div class='card'>";
  html += "<h2>Camera OV2640 - ESP32-S3 WROOM N16R8</h2>";
  html += "<p id='status'>Trang thai: Dang truyen anh truc tiep</p>";
  html += "<img id='liveImg' src='/capture' alt='Live View'><br>";
  html += "<button class='btn btn-cap' onclick='fetchFrame()'>Chup 1 anh moi</button>";
  html += "<a href='http://localhost:5173/kiosk' target='_blank' class='btn btn-kiosk'>Mo Web Kiosk</a>";
  html += "<script>";
  html += "var img = document.getElementById('liveImg');";
  html += "function fetchFrame(){ img.src = '/capture?t=' + new Date().getTime(); }";
  html += "setInterval(fetchFrame, 150);";
  html += "</script></div></body></html>";

  srv.sendHeader("Access-Control-Allow-Origin", "*");
  srv.send(200, "text/html", html);
}

void handleStatus(WebServer &srv) {
  char json[320];
  snprintf(json, sizeof(json),
    "{\"psram\":%s,\"free_psram\":%u,\"free_heap\":%u,\"wifi_rssi\":%d,\"ip\":\"%s\",\"unlocked\":%s,\"lockdown\":%s}",
    psramFound() ? "true" : "false",
    psramFound() ? ESP.getFreePsram() : 0,
    ESP.getFreeHeap(),
    WiFi.RSSI(),
    WiFi.localIP().toString().c_str(),
    isDoorUnlocked ? "true" : "false",
    isEmergencyLockdown ? "true" : "false"
  );
  srv.sendHeader("Access-Control-Allow-Origin", "*");
  srv.send(200, "application/json", json);
}

// Chụp ảnh JPEG trực tiếp từ Camera OV2640 (Hỗ trợ CORS đầy đủ cho Web Kiosk)
void handleCaptureJpg(WebServer &srv) {
  if (!cameraReady) {
    srv.sendHeader("Access-Control-Allow-Origin", "*");
    srv.send(500, "text/plain", "Camera not initialized");
    return;
  }

  if (camMutex && xSemaphoreTake(camMutex, pdMS_TO_TICKS(1000)) == pdTRUE) {
    camera_fb_t *fb = esp_camera_fb_get();
    if (fb) esp_camera_fb_return(fb); // Xả khung hình cũ

    fb = esp_camera_fb_get(); // Lấy khung hình mới nhất

    if (!fb) {
      xSemaphoreGive(camMutex);
      srv.sendHeader("Access-Control-Allow-Origin", "*");
      srv.send(500, "text/plain", "Camera capture failed");
      return;
    }

    srv.sendHeader("Access-Control-Allow-Origin", "*");
    srv.sendHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
    srv.sendHeader("Access-Control-Allow-Headers", "*");
    srv.sendHeader("Cache-Control", "no-cache, no-store, must-revalidate");
    srv.sendHeader("Pragma", "no-cache");
    srv.sendHeader("Expires", "0");
    srv.setContentLength(fb->len);
    srv.send(200, "image/jpeg", "");
    WiFiClient client = srv.client();
    client.write(fb->buf, fb->len);
    esp_camera_fb_return(fb);
    xSemaphoreGive(camMutex);
  } else {
    srv.sendHeader("Access-Control-Allow-Origin", "*");
    srv.send(503, "text/plain", "Camera busy");
  }
}

void handleOptions(WebServer &srv) {
  srv.sendHeader("Access-Control-Allow-Origin", "*");
  srv.sendHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
  srv.sendHeader("Access-Control-Allow-Headers", "*");
  srv.send(200, "text/plain", "OK");
}

void handleRoot80() { sendRootHtml(server); }
void handleCapture80() { handleCaptureJpg(server); }
void handleStatus80() { handleStatus(server); }
void handleOptions80() { handleOptions(server); }

// ============================================================================
// HÀM KHỞI TẠO CAMERA OV2640 (CẤU HÌNH ĐÃ TEST THÀNH CÔNG 100%)
// ============================================================================
bool setupCamera() {
  Serial.println("\n[CAMERA] Đang kiểm tra bộ nhớ và khởi tạo Camera...");

  if (!psramFound()) {
    Serial.println("************************************************************");
    Serial.println(" [CẢNH BÁO NGUY HIỂM] KHÔNG TÌM THẤY BỘ NHỚ PSRAM!");
    Serial.println(" -> Hãy vào Arduino IDE: Tools -> PSRAM -> chọn 'OPI PSRAM'!");
    Serial.println("************************************************************");
  } else {
    Serial.printf(" [PSRAM OK] Đã nhận diện OPI PSRAM! Dung lượng trống: %d bytes\n", ESP.getFreePsram());
  }

  camera_config_t config = {};
  memset(&config, 0, sizeof(camera_config_t));

  config.pin_d0       = Y2_GPIO_NUM;
  config.pin_d1       = Y3_GPIO_NUM;
  config.pin_d2       = Y4_GPIO_NUM;
  config.pin_d3       = Y5_GPIO_NUM;
  config.pin_d4       = Y6_GPIO_NUM;
  config.pin_d5       = Y7_GPIO_NUM;
  config.pin_d6       = Y8_GPIO_NUM;
  config.pin_d7       = Y9_GPIO_NUM;
  config.pin_xclk     = XCLK_GPIO_NUM;
  config.pin_pclk     = PCLK_GPIO_NUM;
  config.pin_vsync    = VSYNC_GPIO_NUM;
  config.pin_href     = HREF_GPIO_NUM;
  config.pin_sccb_sda = SIOD_GPIO_NUM;
  config.pin_sccb_scl = SIOC_GPIO_NUM;
  config.pin_pwdn     = PWDN_GPIO_NUM;
  config.pin_reset    = RESET_GPIO_NUM;

  config.xclk_freq_hz = 12000000; // Giảm từ 20MHz xuống 12MHz để DMA ổn định, triệt tiêu lỗi tràn bộ đệm FB-OVF
  config.ledc_channel = LEDC_CHANNEL_0;
  config.ledc_timer   = LEDC_TIMER_0;
  config.pixel_format = PIXFORMAT_JPEG;
  config.grab_mode    = CAMERA_GRAB_WHEN_EMPTY; // Chỉ nạp frame khi buffer trống, chống tràn DMA khi CPU bận

  config.frame_size   = psramFound() ? FRAMESIZE_VGA : FRAMESIZE_CIF;
  config.jpeg_quality = 12; // 12 cho chất lượng ảnh rất đẹp và nhẹ, truyền qua Wi-Fi nhanh hơn
  config.fb_count     = psramFound() ? 2 : 1;
  config.fb_location  = psramFound() ? CAMERA_FB_IN_PSRAM : CAMERA_FB_IN_DRAM;
  config.sccb_i2c_port    = -1;
  config.jpeg_buffer_size = 0; // 0 = tự động tính từ frame_size

  esp_err_t err = esp_camera_init(&config);
  if (err != ESP_OK) {
    Serial.printf("[CAMERA] VGA (2 buffer) chưa thành công (0x%x). Chuyển sang 1 buffer...\n", err);
    config.fb_count = 1;
    err = esp_camera_init(&config);
  }
  if (err != ESP_OK) {
    Serial.printf("[CAMERA] Thử lại với DRAM nội bộ (CIF)...\n");
    config.frame_size  = FRAMESIZE_CIF;
    config.fb_location = CAMERA_FB_IN_DRAM;
    config.fb_count    = 1;
    err = esp_camera_init(&config);
  }

  if (err != ESP_OK) {
    Serial.printf("[CAMERA LỖI] Khởi tạo Camera thất bại! Mã lỗi: 0x%x\n", err);
    cameraReady = false;
    return false;
  }

  cameraReady = true;
  Serial.println("[CAMERA OK] Khởi tạo Camera OV2640 thành công!");
  
  camera_fb_t *fb = esp_camera_fb_get();
  if (fb) esp_camera_fb_return(fb);

  return true;
}

// ============================================================================
// HÀM KẾT NỐI WI-FI (TỐI ƯU CÔNG SUẤT SÓNG & TỰ ĐỘNG KẾT NỐI LẠI)
// ============================================================================
void connectWiFi() {
  Serial.printf("\n[WIFI] Đang kết nối tới: %s (Mật khẩu: %s)...", wifi_ssid, wifi_password);
  
  WiFi.persistent(false);
  WiFi.disconnect(true);
  delay(200);
  WiFi.mode(WIFI_STA);
  WiFi.setSleep(false); // Tắt sleep để module RF giữ công suất tối đa
  WiFi.setAutoReconnect(true);
  WiFi.begin(wifi_ssid, wifi_password);

  int retry = 0;
  while (WiFi.status() != WL_CONNECTED && retry < 40) {
    delay(500);
    Serial.print(".");
    retry++;
  }

  if (WiFi.status() == WL_CONNECTED) {
    Serial.printf("\n[WIFI OK] Đã kết nối thành công! IP ESP32-S3: %s (RSSI: %d dBm)\n",
                  WiFi.localIP().toString().c_str(), WiFi.RSSI());
    beep(2, 80);
  } else {
    Serial.printf("\n[WIFI CHƯA KẾT NỐI] Mã trạng thái: %d. Hệ thống sẽ tự động thử lại trong nền...\n", WiFi.status());
    Serial.println("[HỆ THỐNG] Bạn vẫn có thể nhập mã PIN trên Keypad 4x4 bình thường.");
  }
}

// ============================================================================
// HÀM CHỤP VÀ GỬI ẢNH LÊN BACKEND XÁC THỰC KHUÔN MẶT
// ============================================================================
bool captureAndSendToBackend(const String& targetUrl, const String& eventType) {
  if (WiFi.status() != WL_CONNECTED) {
    Serial.println("[WIFI] Chưa kết nối, hủy gửi ảnh sang AI.");
    return false;
  }

  camera_fb_t *fb = NULL;
  if (camMutex && xSemaphoreTake(camMutex, pdMS_TO_TICKS(1200)) == pdTRUE) {
    fb = esp_camera_fb_get();
    if (fb) esp_camera_fb_return(fb);
    fb = esp_camera_fb_get();
    if (!fb) {
      xSemaphoreGive(camMutex);
      Serial.println("[CAMERA] Lỗi: Không thu nhận được khung hình!");
      return false;
    }
  } else {
    Serial.println("[CAMERA] Đang bận, không thể chụp ảnh AI!");
    return false;
  }

  HTTPClient http;
  http.begin(targetUrl);
  http.setTimeout(12000);

  String boundary = "----ESP32S3SmartLockBoundary";
  http.addHeader("Content-Type", "multipart/form-data; boundary=" + boundary);

  String head = "--" + boundary + "\r\n";
  head += "Content-Disposition: form-data; name=\"file\"; filename=\"face.jpg\"\r\n";
  head += "Content-Type: image/jpeg\r\n\r\n";
  String tail = "\r\n--" + boundary + "--\r\n";

  size_t totalLen = head.length() + fb->len + tail.length();
  uint8_t *payload = (uint8_t *)malloc(totalLen);
  if (!payload) {
    Serial.println("[MEM] Không đủ bộ nhớ RAM để gửi ảnh!");
    esp_camera_fb_return(fb);
    if (camMutex) xSemaphoreGive(camMutex);
    http.end();
    return false;
  }

  memcpy(payload, head.c_str(), head.length());
  memcpy(payload + head.length(), fb->buf, fb->len);
  memcpy(payload + head.length() + fb->len, tail.c_str(), tail.length());
  esp_camera_fb_return(fb);
  if (camMutex) xSemaphoreGive(camMutex);

  Serial.printf("[HTTP] Đang gửi ảnh (%u bytes) tới Backend: %s ...\n", totalLen, targetUrl.c_str());
  int httpCode = http.POST(payload, totalLen);
  free(payload);

  bool authorized = false;
  if (httpCode == 200) {
    String response = http.getString();
    Serial.printf("[HTTP 200] Kết quả Backend: %s\n", response.c_str());

    if (response.indexOf("\"status\":\"success\"") >= 0 ||
        response.indexOf("FACE_VERIFIED") >= 0 ||
        response.indexOf("\"match\":true") >= 0 ||
        response.indexOf("UNLOCK") >= 0) {
      authorized = true;
    }
  } else {
    Serial.printf("[HTTP LỖI] Mã phản hồi: %d, Chi tiết: %s\n", httpCode, http.getString().c_str());
  }

  http.end();
  return authorized;
}

// ============================================================================
// HÀM CHỤP ẢNH KẺ LẠ VÀ GỬI CẢNH BÁO AN NINH VỀ CHO ADMIN
// ============================================================================
bool sendSecurityAlertWithPhoto(const String& alertType, const String& description) {
  if (WiFi.status() != WL_CONNECTED) {
    Serial.println("[ALERT] Wi-Fi chưa kết nối, không thể gửi ảnh cảnh báo.");
    return false;
  }

  camera_fb_t *fb = NULL;
  if (camMutex && xSemaphoreTake(camMutex, pdMS_TO_TICKS(1500)) == pdTRUE) {
    fb = esp_camera_fb_get();
    if (fb) esp_camera_fb_return(fb);
    fb = esp_camera_fb_get();
    if (!fb) {
      xSemaphoreGive(camMutex);
      Serial.println("[ALERT] Lỗi lấy khung hình camera!");
      return false;
    }
  } else {
    Serial.println("[ALERT] Camera đang bận!");
    return false;
  }

  HTTPClient http;
  http.begin(backendAlertUrl);
  http.setTimeout(10000);

  String boundary = "----ESP32AlertBoundary" + String(millis());
  http.addHeader("Content-Type", "multipart/form-data; boundary=" + boundary);

  String head = "--" + boundary + "\r\n";
  head += "Content-Disposition: form-data; name=\"alertType\"\r\n\r\n";
  head += alertType + "\r\n";

  head += "--" + boundary + "\r\n";
  head += "Content-Disposition: form-data; name=\"description\"\r\n\r\n";
  head += description + "\r\n";

  head += "--" + boundary + "\r\n";
  head += "Content-Disposition: form-data; name=\"file\"; filename=\"intruder.jpg\"\r\n";
  head += "Content-Type: image/jpeg\r\n\r\n";

  String tail = "\r\n--" + boundary + "--\r\n";

  size_t totalLen = head.length() + fb->len + tail.length();
  uint8_t *payload = (uint8_t *)malloc(totalLen);
  if (!payload) {
    Serial.println("[ALERT] Không đủ RAM tạo payload ảnh cảnh báo!");
    esp_camera_fb_return(fb);
    if (camMutex) xSemaphoreGive(camMutex);
    http.end();
    return false;
  }

  memcpy(payload, head.c_str(), head.length());
  memcpy(payload + head.length(), fb->buf, fb->len);
  memcpy(payload + head.length() + fb->len, tail.c_str(), tail.length());

  esp_camera_fb_return(fb);
  if (camMutex) xSemaphoreGive(camMutex);

  Serial.printf("[ALERT] Đang gửi ảnh cảnh báo kẻ lạ (%u bytes) tới Server: %s ...\n", totalLen, backendAlertUrl.c_str());
  int code = http.POST(payload, totalLen);
  free(payload);

  bool ok = (code == 200 || code == 201);
  if (ok) {
    Serial.printf("[ALERT OK] Đã gửi cảnh báo kẻ lạ thành công (HTTP %d)!\n", code);
  } else {
    Serial.printf("[ALERT LỖI] Gửi ảnh thất bại! Mã: %d, Chi tiết: %s\n", code, http.getString().c_str());
  }

  http.end();
  return ok;
}

// ============================================================================
// HÀM XÁC THỰC MÃ PIN VỚI BACKEND CORE (HỖ TRỢ OFFLINE MASTER_PIN)
// ============================================================================
bool verifyPinWithBackend(const String& pin) {
  if (WiFi.status() != WL_CONNECTED) {
    return (pin == MASTER_PIN);
  }

  HTTPClient http;
  http.begin(backendPinUrl);
  http.addHeader("Content-Type", "application/json");
  http.setTimeout(4000);

  String json = "{\"pinCode\":\"" + pin + "\"}";
  int code = http.POST(json);
  bool authorized = false;

  if (code == 200) {
    String resp = http.getString();
    if (resp.indexOf("\"status\":\"success\"") >= 0) {
      authorized = true;
    }
  } else if (code < 0) {
    // Nếu rớt mạng backend -> kiểm tra MASTER_PIN offline
    authorized = (pin == MASTER_PIN);
  }

  http.end();
  return authorized;
}

// ============================================================================
// HÀM XỬ LÝ LỆNH TỪ MQTT BROKER
// ============================================================================
void mqttCallback(char* topic, byte* payload, unsigned int length) {
  String message;
  for (unsigned int i = 0; i < length; i++) message += (char)payload[i];
  Serial.printf("[MQTT] Nhận lệnh [%s]: %s\n", topic, message.c_str());

  if (message == "OPEN_DOOR" || message == "UNLOCK") {
    unlockDoor("Lệnh mở cửa từ Web Dashboard / Kiosk qua MQTT");
  } else if (message == "EMERGENCY_LOCKDOWN_ON" || message == "LOCKDOWN_ON") {
    isEmergencyLockdown = true;
    Serial.println("!!! [KHẨN CẤP] ADMIN ĐÃ BẬT VÔ HIỆU HÓA CỬA !!!");
    beep(5, 120, 80);
  } else if (message == "EMERGENCY_LOCKDOWN_OFF" || message == "LOCKDOWN_OFF") {
    isEmergencyLockdown = false;
    Serial.println(">>> [KHẨN CẤP] ADMIN ĐÃ TẮT VÔ HIỆU HÓA CỬA. HOẠT ĐỘNG BÌNH THƯỜNG. <<<");
    beep(2, 200, 100);
  }
}

void reconnectMqtt() {
  if (!mqttClient.connected() && (millis() - lastMqttReconnect > 5000)) {
    lastMqttReconnect = millis();
    Serial.print("[MQTT] Đang kết nối tới Broker...");
    if (mqttClient.connect("ESP32S3_SmartLock")) {
      Serial.println(" Thành công!");
      mqttClient.subscribe(MQTT_TOPIC_SUB);
    } else {
      Serial.printf(" Thất bại, mã lỗi=%d\n", mqttClient.state());
    }
  }
}

// ============================================================================
// HÀM XỬ LÝ NHẬP MÃ PIN TRÊN BÀN PHÍM KEYPAD 4x4
// ============================================================================
void handleKeypadInput(char key) {
  beep(1, 25); // Tiếng bíp ngắn 25ms êm tai và không chặn CPU quá lâu gây tràn buffer Camera
  Serial.printf("[KEYPAD] Phím vừa nhấn: '%c'\n", key);

  if (key == '*' || key == 'C') {
    inputPinBuffer = "";
    Serial.println("[KEYPAD] Đã xóa mã PIN.");
    return;
  }

  if (key == 'A' || key == 'B') {
    return; // Bỏ qua phím phụ
  }

  // Phím 'D' = Chuông cửa & Quét khuôn mặt AI
  if (key == 'D') {
    if (isEmergencyLockdown) {
      Serial.println("[CHUÔNG CỬA] Cửa đang bị vô hiệu hóa khẩn cấp!");
      beep(3, 80, 50);
      return;
    }
    Serial.println("[CHUÔNG CỬA] Bấm phím 'D' -> Bật LED và quét khuôn mặt AI...");
    digitalWrite(PIN_LED, HIGH); // Bật LED trợ sáng
    beep(2, 100, 60);

    if (captureAndSendToBackend(backendVerifyUrl, "DOORBELL_FACE_SCAN")) {
      consecutivePirFaceFails = 0;
      unlockDoor("Xác thực khuôn mặt AI thành công (Bấm chuông 'D')");
    } else {
      Serial.println("[AI] Không khớp người quen. Tắt LED.");
      digitalWrite(PIN_LED, LOW);
      beep(3, 80, 60);
    }
    return;
  }

  // Phím '#' = Xác nhận mã PIN
  if (key == '#') {
    if (isEmergencyLockdown) {
      Serial.println("[KEYPAD] Cửa đang bị vô hiệu hóa khẩn cấp!");
      inputPinBuffer = "";
      beep(4, 80, 50);
      return;
    }

    if (inputPinBuffer.length() == 0) {
      Serial.println("[KEYPAD] Chưa có số nào được nhập! Vui lòng bấm dãy số (ví dụ: 123456) rồi mới bấm '#'.");
      beep(2, 60, 40);
      return;
    }

    Serial.printf("[KEYPAD] Kiểm tra mã PIN (%d ký tự)...\n", inputPinBuffer.length());
    String pinToCheck = inputPinBuffer;
    inputPinBuffer = "";

    if (verifyPinWithBackend(pinToCheck)) {
      failedPinAttempts = 0;
      unlockDoor("Nhập đúng mã PIN trên Keypad 4x4");
    } else {
      failedPinAttempts++;
      Serial.printf("[CẢNH BÁO] Sai mã PIN lần %d/3!\n", failedPinAttempts);
      beep(3, 180, 100);

      if (failedPinAttempts >= 3) {
        Serial.println("!!! [BÁO ĐỘNG] Sai PIN 3 lần liên tiếp! Báo động còi, chụp ảnh kẻ lạ gửi Admin & Khóa 15s !!!");
        isSystemLockedOut = true;
        lockoutStartTime = millis();
        beep(8, 250, 100);
        if (mqttClient.connected()) {
          mqttClient.publish(MQTT_TOPIC_PUB, "INTRUDER_WRONG_PIN_3_TIMES");
        }
        sendSecurityAlertWithPhoto("WRONG_PIN_3_TIMES", "Nhập sai mã PIN 3 lần liên tiếp trên bàn phím Keypad 4x4");
        failedPinAttempts = 0;
      }
    }
    return;
  }

  // Nhập ký tự số (0 - 9)
  if (key >= '0' && key <= '9') {
    if (inputPinBuffer.length() < 10) {
      inputPinBuffer += key;
      Serial.printf("[PIN] Đã nhập ký tự: '%c' (Độ dài: %d/10)\n", key, inputPinBuffer.length());
    }
  }
}

// ============================================================================
// SETUP & MAIN LOOP
// ============================================================================
void setup() {
  Serial.begin(115200);
  delay(1000);
  Serial.println("\n============================================================");
  Serial.println(" HỆ THỐNG KHÓA CỬA THÔNG MINH AIoT - ESP32-S3 N16R8 CAM ");
  Serial.println("============================================================");

  if (camMutex == NULL) {
    camMutex = xSemaphoreCreateMutex();
  }

  // 1. Khởi tạo Camera OV2640 chuẩn Freenove N16R8
  setupCamera();

  pinMode(PIN_LED, OUTPUT);
  pinMode(PIN_BUZZER, OUTPUT);
  pinMode(PIN_RELAY, OUTPUT);
  pinMode(PIN_PIR, INPUT);

  // Trạng thái chờ: ĐÈN LED TẮT HOÀN TOÀN
  digitalWrite(PIN_LED, LOW);
  digitalWrite(PIN_BUZZER, LOW);
  digitalWrite(PIN_RELAY, RELAY_OFF);

  // Khởi tạo Servo trên Timer 3 riêng biệt (không ảnh hưởng Timer 0 của Camera)
  ESP32PWM::allocateTimer(3);
  doorServo.setPeriodHertz(50);
  doorServo.attach(PIN_SERVO, 500, 2400);
  doorServo.write(SERVO_LOCK_ANGLE);

  // 2. Kết nối Wi-Fi
  connectWiFi();

  // 3. Đăng ký các tuyến đường Web Server trên CỔNG 80
  server.on("/", HTTP_GET, handleRoot80);
  server.on("/capture", HTTP_GET, handleCapture80);
  server.on("/capture", HTTP_OPTIONS, handleOptions80);
  server.on("/status", HTTP_GET, handleStatus80);
  server.on("/status", HTTP_OPTIONS, handleOptions80);

  if (WiFi.status() == WL_CONNECTED) {
    server.begin();
    Serial.println("------------------------------------------------------------");
    Serial.printf("[CAMERA WEB VIEWER] Mở trực tiếp tại : http://%s/\n", WiFi.localIP().toString().c_str());
    Serial.printf("[CAMERA CAPTURE]     Chụp ảnh tĩnh:    http://%s/capture\n", WiFi.localIP().toString().c_str());
    Serial.println("------------------------------------------------------------");
  }

  // 4. Cấu hình MQTT Broker
  mqttClient.setServer(server_ip, 1883);
  mqttClient.setCallback(mqttCallback);

  // Ổn định cảm biến PIR trong 10 giây
  pirWarmupEnd = millis() + 10000;
  Serial.println("[PIR] Đang ổn định cảm biến trong 10 giây (Đèn LED tắt)...");
  Serial.println("[HỆ THỐNG] Đã sẵn sàng hoạt động!");
}

void loop() {
  unsigned long now = millis();

  // Tự động thử kết nối lại Wi-Fi trong nền nếu rớt mạng
  static unsigned long lastWifiRetry = 0;
  static bool serverRunning = false;

  if (WiFi.status() != WL_CONNECTED) {
    serverRunning = false;
    if (now - lastWifiRetry >= 8000) {
      lastWifiRetry = now;
      Serial.printf("[WIFI] Đang thử kết nối lại Wi-Fi '%s'...\n", wifi_ssid);
      WiFi.begin(wifi_ssid, wifi_password);
    }
  } else {
    if (!serverRunning) {
      server.begin();
      serverRunning = true;
      Serial.println("\n------------------------------------------------------------");
      Serial.printf("[WIFI OK] Đã có IP: %s\n", WiFi.localIP().toString().c_str());
      Serial.printf("[CAMERA WEB VIEWER] Mở tại: http://%s/\n", WiFi.localIP().toString().c_str());
      Serial.println("------------------------------------------------------------");
    }

    server.handleClient();

    if (!mqttClient.connected()) reconnectMqtt();
    else mqttClient.loop();
  }

  // 1. Tự động đóng cửa sau khi mở đủ 7 giây (tránh lỗi underflow thời gian khi có lệnh kép)
  if (isDoorUnlocked) {
    unsigned long currentMillis = millis();
    if (currentMillis >= doorUnlockTime && (currentMillis - doorUnlockTime >= UNLOCK_DURATION_MS)) {
      lockDoor();
    }
  }

  // 2. Kiểm tra trạng thái khóa bàn phím do sai PIN 3 lần
  if (isSystemLockedOut) {
    if (now - lockoutStartTime >= LOCKOUT_DURATION_MS) {
      isSystemLockedOut = false;
      failedPinAttempts = 0;
      Serial.println("[HỆ THỐNG] Đã mở lại bàn phím Keypad.");
      beep(1, 100);
    } else {
      if (keypad.getKey()) beep(2, 50, 50);
      return;
    }
  }

  // 3. Đọc bàn phím Keypad 4x4
  char key = keypad.getKey();
  if (key) handleKeypadInput(key);

  // 4. Cảm biến chuyển động PIR: Tự bật LED và chụp ảnh xác thực khuôn mặt
  if (now > pirWarmupEnd && !isDoorUnlocked) {
    int pirState = digitalRead(PIN_PIR);
    
    // Nếu cảm biến đã hạ về LOW, xóa cờ chờ nhả
    if (pirState == LOW) {
      pirRequiresRelease = false;
      if (!isDoorUnlocked) {
        digitalWrite(PIN_LED, LOW);
      }
    }
    
    // ĐIỀU KIỆN KÍCH HOẠT CHỤP ẢNH:
    // 1. pirState == HIGH (phát hiện có người)
    // 2. !pirRequiresRelease (cảm biến đã từng hạ về LOW, tránh quét lặp liên tục khi chân OUT bị treo HIGH)
    // 3. now >= lastPirTrigger (đã hết thời gian nghỉ cooldown)
    if (pirState == HIGH && !pirRequiresRelease && (now >= lastPirTrigger)) {
      Serial.println("\n[PIR] Phát hiện người trước cửa! Bật LED và chụp ảnh gửi AI...");
      
      digitalWrite(PIN_LED, HIGH); // Bật LED trợ sáng êm ái (BỎ tiếng beep gây ồn)

      if (mqttClient.connected()) {
        mqttClient.publish(MQTT_TOPIC_PUB, "PIR_MOTION_DETECTED");
      }

      if (isEmergencyLockdown) {
        Serial.println("[PIR] Cửa đang bị VÔ HIỆU HÓA KHẨN CẤP bởi Admin! Bỏ qua nhận diện.");
        digitalWrite(PIN_LED, LOW);
        beep(3, 100, 60);
        lastPirTrigger = millis() + 10000;
        pirRequiresRelease = true;
      } else {
        // Chụp và gửi ảnh lên Backend Core để xác thực
        bool authOk = captureAndSendToBackend(backendVerifyUrl, "PIR_AUTO_FACE_SCAN");

        if (authOk) {
          consecutivePirFaceFails = 0;
          pirRequiresRelease = true;
          unlockDoor("Xác thực khuôn mặt AI tự động (qua PIR)");
          // Thời gian nghỉ sẽ được gán trong lockDoor() sau khi cửa đóng lại
        } else {
          consecutivePirFaceFails++;
          Serial.printf("[AI] Không khớp người quen (Lần %d/5). Tắt LED.\n", consecutivePirFaceFails);
          digitalWrite(PIN_LED, LOW); // Tắt LED ngay nếu không nhận diện được

          // 2 tiếng bip ngắn nhẹ nhàng báo không nhận diện được (không chói tai)
          beep(2, 60, 40);

          // Cập nhật thời gian nghỉ giữa các lần quét thử: 10 giây tính từ SAU KHI quét xong
          lastPirTrigger = millis() + PIR_COOLDOWN_MS;

          // YÊU CẦU 3: Nếu quét mặt thất bại quá 5 lần -> Cảnh báo + gửi ảnh kẻ lạ về Admin
          if (consecutivePirFaceFails >= 5) {
            Serial.println("!!! [BÁO ĐỘNG KẺ LẠ] Nhận diện khuôn mặt thất bại 5 lần liên tiếp! Báo động và gửi ảnh cho Admin !!!");
            beep(6, 200, 100);
            sendSecurityAlertWithPhoto("PIR_INTRUDER_5_TIMES", "Phát hiện chuyển động: Nhận diện khuôn mặt thất bại 5 lần liên tiếp (Kẻ lạ)");
            if (mqttClient.connected()) {
              mqttClient.publish(MQTT_TOPIC_PUB, "INTRUDER_ALERT_PIR_5_TIMES");
            }
            consecutivePirFaceFails = 0;

            // NGHỈ 30 GIÂY VÀ BẮT BUỘC PIR PHẢI NHẢ VỀ LOW MỚI ĐƯỢC PHÉP HOẠT ĐỘNG TIẾP
            lastPirTrigger = millis() + PIR_ALERT_LOCKOUT_MS;
            pirRequiresRelease = true;
            Serial.println("[PIR] Đã kích hoạt thời gian nghỉ 30 giây sau cảnh báo.");
          }
        }
      }
    }
  }
}
