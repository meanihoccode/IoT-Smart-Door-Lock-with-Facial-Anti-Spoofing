/*
 * ============================================================================
 * HỆ THỐNG KHÓA CỬA THÔNG MINH AIoT - ESP32-S3 WROOM N16R8 CAM (IoT Level 3)
 * Bo mạch: ESP32-S3 WROOM N16R8 CAM (16MB Flash, 8MB Octal PSRAM, OV2640)
 * Vai trò: nút local duy nhất. Thu ảnh/PIN/cảm biến và điều khiển chấp hành;
 * liveness, nhận diện và quyết định nghiệp vụ chạy trên cloud.
 *   - REST qua HTTPS (xác thực thiết bị): gửi ảnh khuôn mặt, PIN keypad, cảnh báo có ảnh
 *   - WebSocket bảo mật (wss, kết nối giữ liên tục): nhận command JSON (commandId/seq/TTL),
 *     gửi ACK, trạng thái, sự kiện — đúng sơ đồ IoT Level 3 "REST/WebSocket"
 *   - Chỉ mở khóa khi nhận command hợp lệ qua WebSocket (docs/DEVICE_CONTRACT.md)
 *   - Bàn phím 4x4 (PIN 6 số, phím 'D' gọi chuông/quét mặt), cảm biến PIR
 *   - Khóa khẩn cấp lưu NVS; timer khóa lại chạy bằng esp_timer độc lập vòng loop
 *   - Không có master PIN, không có web server LAN, không log bí mật
 * ============================================================================
 */

#include <Arduino.h>
#include <WiFi.h>
#include <WiFiClientSecure.h>
#include <HTTPClient.h>
#include <WebSocketsClient.h> // thư viện "WebSockets" (Markus Sattler) 2.7.2
#include "esp_camera.h"
#include "esp_timer.h"
#include "esp_system.h"
#include <Keypad.h>
#include <ESP32Servo.h>
#include <Preferences.h>
#include <time.h>

#if __has_include("config.h")
#include "config.h"
#elif __has_include("../config.h")
#include "../config.h"
#elif __has_include("config.example.h")
#include "config.example.h"
#else
#include "../config.example.h"
#endif

// ============================================================================
// 1. CẤU HÌNH MẠNG, CLOUD VÀ DANH TÍNH THIẾT BỊ (giá trị thật nằm trong config.h)
// ============================================================================
#ifndef WIFI_SSID
#define WIFI_SSID "YOUR_WIFI_SSID"
#endif
#ifndef WIFI_PASSWORD
#define WIFI_PASSWORD "YOUR_WIFI_PASSWORD"
#endif
// Mạng dự phòng (tùy chọn), ví dụ hotspot điện thoại khi demo ở nơi khác. Để trống nếu không dùng.
#ifndef WIFI_SSID_2
#define WIFI_SSID_2 ""
#endif
#ifndef WIFI_PASSWORD_2
#define WIFI_PASSWORD_2 ""
#endif
// Một tên miền cloud cho cả REST (https://CLOUD_HOST:CLOUD_PORT/api/...) và WebSocket (wss://.../ws/device).
#ifndef CLOUD_HOST
#define CLOUD_HOST "smartlock.example.com"
#endif
#ifndef CLOUD_PORT
#define CLOUD_PORT 443
#endif
#ifndef WS_PATH
#define WS_PATH "/ws/device"
#endif
#ifndef DEVICE_ID
#define DEVICE_ID "door-01"
#endif
#ifndef DEVICE_TOKEN
#define DEVICE_TOKEN ""
#endif
#ifndef NTP_SERVER
#define NTP_SERVER "pool.ntp.org"
#endif
#ifndef SMARTLOCK_HAS_ROOT_CA
static const char ROOT_CA_PEM[] = "";
#endif

#define FW_VERSION "2.1.0-l3-ws"

// Thời gian điều khiển
const unsigned long DEFAULT_UNLOCK_MS      = 7000;  // Dùng khi command không ghi unlockMs
const unsigned long MAX_UNLOCK_MS          = 15000; // Giới hạn cứng, không tin giá trị lớn hơn từ mạng
const unsigned long PIR_COOLDOWN_MS        = 10000; // Khoảng cách giữa 2 lần quét thử liên tiếp
const unsigned long PIR_POST_UNLOCK_MS     = 15000; // Thời gian nghỉ sau khi cửa khóa lại
const unsigned long PIR_ALERT_LOCKOUT_MS   = 30000; // Thời gian nghỉ sau khi cảnh báo kẻ lạ 5 lần
const unsigned long LOCKOUT_DURATION_MS    = 15000; // Khóa bàn phím 15 giây nếu nhập sai PIN 3 lần
const unsigned long HEARTBEAT_MS           = 60000; // Trạng thái định kỳ để cloud phân biệt online/stale
const unsigned long AWAIT_COMMAND_MS       = 15000; // Chờ command sau khi cloud chấp thuận
const long          CLOCK_SKEW_S           = 5;     // Sai lệch đồng hồ chấp nhận khi kiểm TTL
const uint32_t      FACE_HTTP_TIMEOUT_MS   = 15000;
const uint32_t      PIN_HTTP_TIMEOUT_MS    = 15000; // Cloud quét BCrypt mọi hồ sơ (D04)

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
// 3. ĐỊNH NGHĨA CHÂN NGOẠI VI (chưa xác minh wiring thực tế - không tự đổi)
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

// Đối tượng Servo, WebSocket bảo mật và NVS
Servo doorServo;
WebSocketsClient ws;
Preferences prefs;
bool wsStarted   = false; // begin() chỉ gọi một lần; thư viện tự kết nối lại
bool wsConnected = false;
bool cameraReady = false;
bool caConfigured = false;

// Biến trạng thái hệ thống
String inputPinBuffer          = "";
int failedPinAttempts          = 0;
int consecutivePirFaceFails    = 0; // Đếm số lần cloud từ chối khuôn mặt liên tiếp của PIR
volatile bool isDoorUnlocked   = false;
volatile bool relockPending    = false; // esp_timer đã khóa lại; loop phát tiếng bíp và sự kiện
bool isSystemLockedOut         = false;
bool isEmergencyLockdown       = false; // Lưu NVS, áp dụng lại sau reboot
bool bootEventSent             = false;
unsigned long doorUnlockTime   = 0;
unsigned long activeUnlockMs   = DEFAULT_UNLOCK_MS;
unsigned long lockoutStartTime = 0;
unsigned long lastPirTrigger   = 0;
unsigned long lastHeartbeat    = 0;
unsigned long awaitCommandUntil = 0;
unsigned long previewUntil     = 0; // Cloud yêu cầu xem trước camera (chỉ hiển thị, không dùng để mở cửa)
unsigned long lastPreviewFrame = 0;
const unsigned long PREVIEW_INTERVAL_MS = 333; // ~3 khung hình/giây
const unsigned long PREVIEW_MAX_MS      = 20000;
unsigned long pirWarmupEnd     = 0;
bool pirRequiresRelease        = false; // Bắt buộc cảm biến PIR phải hạ về LOW trước khi kích hoạt đợt quét mới

// Chống phát lại: seq lớn nhất đã chấp nhận và ACK cuối, lưu NVS trước khi chấp hành
uint64_t lastSeq   = 0;
String lastCmdId   = "";
String lastAckJson = "";
String activeUnlockCommandId = "";

static SemaphoreHandle_t camMutex = NULL;
static esp_timer_handle_t relockTimer = NULL;
static portMUX_TYPE doorMux = portMUX_INITIALIZER_UNLOCKED;

enum class Decision { Granted, Denied, Throttled, Lockdown, Unavailable };

// Khai báo trước các hàm xử lý
void handleKeypadInput(char key);
Decision captureAndVerifyFace();
bool sendSecurityAlertWithPhoto(const String& alertType, const String& description);
Decision verifyPinWithBackend(const String& pin);
void publishStatus();

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
// TIỆN ÍCH: THỜI GIAN, ID NGẪU NHIÊN, JSON PHẲNG
// ============================================================================
bool timeSynced() { return time(nullptr) > 1700000000; }

String randomId() {
  char out[33];
  for (int i = 0; i < 4; i++) snprintf(out + i * 8, 9, "%08lx", (unsigned long)esp_random());
  return String(out);
}

// Command từ Core là JSON phẳng không escape (UUID, enum, số). Không dùng cho dữ liệu tùy ý.
bool jsonString(const String& json, const char* key, String& out) {
  String pattern = String("\"") + key + "\":\"";
  int start = json.indexOf(pattern);
  if (start < 0) return false;
  start += pattern.length();
  int end = json.indexOf('"', start);
  if (end < 0 || end - start > 64) return false;
  out = json.substring(start, end);
  return true;
}

bool jsonUInt64(const String& json, const char* key, uint64_t& out) {
  String pattern = String("\"") + key + "\":";
  int start = json.indexOf(pattern);
  if (start < 0) return false;
  start += pattern.length();
  uint64_t value = 0;
  int i = start;
  while (i < (int)json.length() && isDigit(json[i])) {
    if (i - start >= 19) return false;
    value = value * 10 + (uint64_t)(json[i] - '0');
    i++;
  }
  if (i == start) return false;
  out = value;
  return true;
}

String u64ToString(uint64_t value) {
  char buffer[21];
  snprintf(buffer, sizeof(buffer), "%llu", (unsigned long long)value);
  return String(buffer);
}

// ============================================================================
// CHẤP HÀNH: MỞ KHÓA / KHÓA LẠI (timer khóa lại độc lập với HTTPS/WebSocket blocking)
// ============================================================================
void actuateLocked() {
  doorServo.write(SERVO_LOCK_ANGLE);
  digitalWrite(PIN_RELAY, RELAY_OFF);
  digitalWrite(PIN_LED, LOW);
}

// Chạy trong task esp_timer: chỉ điều khiển chấp hành, không gọi mạng hay delay.
void relockTimerCallback(void* arg) {
  bool wasUnlocked;
  portENTER_CRITICAL(&doorMux);
  wasUnlocked = isDoorUnlocked;
  isDoorUnlocked = false;
  if (wasUnlocked) relockPending = true;
  portEXIT_CRITICAL(&doorMux);
  if (wasUnlocked) actuateLocked();
}

// Trả về "" nếu đã điều khiển chấp hành, ngược lại là mã lý do từ chối.
String executeUnlock(unsigned long unlockMs, const String& commandId) {
  // Kiểm tra lockdown ngay trước khi chấp hành.
  if (isEmergencyLockdown) {
    Serial.println("[TỪ CHỐI MỞ CỬA] Cửa đang bị VÔ HIỆU HÓA KHẨN CẤP!");
    beep(4, 100, 60);
    return "LOCKDOWN";
  }
  // Không gia hạn timer khi cửa đang mở (contract: không gia hạn do lệnh trùng/lặp).
  if (isDoorUnlocked) return "ALREADY_UNLOCKED";

  Serial.printf("\n>>> [MỞ CỬA] Command %s, %lu ms <<<\n", commandId.c_str(), unlockMs);
  portENTER_CRITICAL(&doorMux);
  isDoorUnlocked = true;
  portEXIT_CRITICAL(&doorMux);
  doorUnlockTime = millis();
  activeUnlockMs = unlockMs;
  activeUnlockCommandId = commandId;
  failedPinAttempts = 0;
  consecutivePirFaceFails = 0;
  awaitCommandUntil = 0;
  pirRequiresRelease = true;

  digitalWrite(PIN_LED, HIGH);
  digitalWrite(PIN_RELAY, RELAY_ON);
  doorServo.write(SERVO_UNLOCK_ANGLE);
  esp_timer_stop(relockTimer);
  esp_timer_start_once(relockTimer, (uint64_t)unlockMs * 1000ULL);
  beep(2, 120, 80);
  return "";
}

// Khóa ngay (lockdown hoặc dự phòng khi timer chưa chạy).
void lockNow() {
  esp_timer_stop(relockTimer);
  relockTimerCallback(nullptr);
}

// ============================================================================
// WEBSOCKET: ACK, SỰ KIỆN, TRẠNG THÁI (mọi bản tin có "kind"; Core lấy danh tính từ token lúc bắt tay)
// ============================================================================
bool wsSend(String& json) {
  if (!wsConnected) return false;
  return ws.sendTXT(json);
}

void publishEvent(const char* type, const String& commandId = "") {
  String json = String("{\"v\":1,\"kind\":\"event\",\"deviceId\":\"") + DEVICE_ID + "\",\"eventId\":\"" + randomId()
              + "\",\"type\":\"" + type + "\",\"ts\":" + String((uint32_t)time(nullptr));
  if (commandId.length() > 0) json += ",\"commandId\":\"" + commandId + "\"";
  json += "}";
  wsSend(json);
}

void publishStatus() {
  String json = String("{\"v\":1,\"kind\":\"status\",\"deviceId\":\"") + DEVICE_ID + "\",\"lockdown\":"
              + (isEmergencyLockdown ? "true" : "false") + ",\"unlocked\":" + (isDoorUnlocked ? "true" : "false")
              + ",\"fw\":\"" FW_VERSION "\",\"rssi\":" + String(WiFi.RSSI()) + "}";
  wsSend(json);
  lastHeartbeat = millis();
}

String ackJson(const String& commandId, uint64_t seq, const char* ack, const char* reason) {
  String json = String("{\"v\":1,\"kind\":\"ack\",\"deviceId\":\"") + DEVICE_ID + "\",\"commandId\":\"" + commandId
              + "\",\"seq\":" + u64ToString(seq) + ",\"ack\":\"" + ack + "\",\"eventId\":\"" + randomId()
              + "\",\"ts\":" + String((uint32_t)time(nullptr));
  if (reason && reason[0]) json += String(",\"reason\":\"") + reason + "\"";
  return json + "}";
}

void sendAck(const String& commandId, uint64_t seq, const char* ack, const char* reason = "") {
  String json = ackJson(commandId, seq, ack, reason);
  wsSend(json);
  Serial.printf("[ACK] %s %s %s\n", commandId.c_str(), ack, reason ? reason : "");
  // Chỉ lưu ACK cuối của command đã chấp nhận; ACK từ chối REPLAY/MALFORMED không được ghi đè.
  if (strcmp(ack, "RECEIVED") != 0 && commandId == lastCmdId) {
    lastAckJson = json;
    prefs.putString("lastAck", lastAckJson);
  }
}

// ============================================================================
// XỬ LÝ COMMAND TỪ CLOUD (một đường thực thi duy nhất)
// ============================================================================
void handleCommand(const String& message) {
  String type, deviceId, commandId;
  uint64_t version = 0, seq = 0, issuedAt = 0, expiresAt = 0, unlockMs = DEFAULT_UNLOCK_MS;
  jsonString(message, "commandId", commandId);
  if (!jsonUInt64(message, "v", version) || version != 1 || !jsonString(message, "type", type)
      || !jsonString(message, "deviceId", deviceId) || deviceId != DEVICE_ID || commandId.length() == 0
      || !jsonUInt64(message, "seq", seq) || seq == 0
      || !jsonUInt64(message, "issuedAt", issuedAt) || !jsonUInt64(message, "expiresAt", expiresAt)) {
    Serial.println("[CMD] Bỏ qua command sai định dạng.");
    if (commandId.length() > 0) sendAck(commandId, seq, "REJECTED", "MALFORMED");
    return;
  }
  jsonUInt64(message, "unlockMs", unlockMs);

  // Trùng commandId: chỉ gửi lại ACK cũ, không chấp hành lại, không gia hạn.
  if (commandId == lastCmdId) {
    Serial.println("[CMD] Command trùng, gửi lại ACK cũ.");
    if (lastAckJson.length() > 0) wsSend(lastAckJson);
    return;
  }
  if (seq <= lastSeq) {
    sendAck(commandId, seq, "REJECTED", "REPLAY");
    return;
  }
  // Lưu trước khi chấp hành: reboot giữa chừng không được chạy lại command này.
  lastSeq = seq;
  lastCmdId = commandId;
  lastAckJson = "";
  prefs.putULong64("lastSeq", lastSeq);
  prefs.putString("lastCmd", lastCmdId);
  prefs.putString("lastAck", "");
  sendAck(commandId, seq, "RECEIVED");

  if (!timeSynced()) { sendAck(commandId, seq, "REJECTED", "TIME_UNSYNCED"); return; }
  uint64_t now = (uint64_t)time(nullptr);
  if (now > expiresAt + CLOCK_SKEW_S) { sendAck(commandId, seq, "REJECTED", "EXPIRED"); return; }
  if (issuedAt > now + 30) { sendAck(commandId, seq, "REJECTED", "CLOCK_SKEW"); return; }

  if (type == "UNLOCK") {
    unsigned long ms = (unsigned long)unlockMs;
    if (ms < 1000) ms = 1000;
    if (ms > MAX_UNLOCK_MS) ms = MAX_UNLOCK_MS;
    String reason = executeUnlock(ms, commandId);
    // EXECUTED = đã điều khiển relay/servo, không phải cảm biến xác nhận cửa đã mở.
    if (reason.length() == 0) sendAck(commandId, seq, "EXECUTED");
    else sendAck(commandId, seq, "REJECTED", reason.c_str());
  } else if (type == "LOCKDOWN_ON" || type == "LOCKDOWN_OFF") {
    isEmergencyLockdown = (type == "LOCKDOWN_ON");
    prefs.putBool("lockdown", isEmergencyLockdown);
    if (isEmergencyLockdown) lockNow();
    sendAck(commandId, seq, "EXECUTED");
    Serial.println(isEmergencyLockdown ? "!!! [KHẨN CẤP] ĐÃ BẬT VÔ HIỆU HÓA CỬA !!!"
                                       : ">>> [KHẨN CẤP] ĐÃ TẮT VÔ HIỆU HÓA CỬA <<<");
    if (isEmergencyLockdown) beep(5, 120, 80); else beep(2, 200, 100);
    publishStatus();
  } else {
    sendAck(commandId, seq, "REJECTED", "UNKNOWN_TYPE");
  }
}

void wsEvent(WStype_t type, uint8_t* payload, size_t length) {
  switch (type) {
    case WStype_CONNECTED:
      // Socket mở = online với Core; báo ngay lockdown hiện tại để Core đồng bộ lại nếu lệch.
      wsConnected = true;
      Serial.println("[WS] Đã kết nối wss tới cloud.");
      publishStatus();
      if (!bootEventSent) { publishEvent("BOOT"); bootEventSent = true; }
      break;
    case WStype_DISCONNECTED:
      if (wsConnected) Serial.println("[WS] Mất kết nối cloud; thư viện sẽ tự kết nối lại.");
      wsConnected = false;
      break;
    case WStype_TEXT: {
      if (length > 700) return;
      String message;
      message.reserve(length);
      for (size_t i = 0; i < length; i++) message += (char)payload[i];
      String kind;
      // Chỉ bản tin "cmd" từ Core mới được xử lý như lệnh.
      if (jsonString(message, "kind", kind) && kind == "cmd") handleCommand(message);
      // "preview": admin đang xem camera; chỉ gửi khung hình, không chấp hành gì.
      else if (kind == "preview") {
        uint64_t ms = 0;
        jsonUInt64(message, "ms", ms);
        if (ms > PREVIEW_MAX_MS) ms = PREVIEW_MAX_MS;
        previewUntil = millis() + (unsigned long)ms;
      }
      break;
    }
    default:
      break;
  }
}

// TLS cần CA và đồng hồ đúng để kiểm chứng chứng chỉ; chưa đủ điều kiện thì chưa kết nối.
// Thư viện chỉ setInsecure() khi không có CA, nên tuyệt đối không gọi begin khi thiếu CA.
void startWebSocket() {
  if (wsStarted || !caConfigured || !timeSynced() || strlen(DEVICE_TOKEN) == 0) return;
  // Thay header mặc định "Origin: file://" bằng danh tính thiết bị (không log ra Serial).
  static String headers = String("Authorization: Bearer ") + DEVICE_TOKEN + "\r\nX-Device-Id: " + DEVICE_ID;
  ws.setExtraHeaders(headers.c_str());
  ws.onEvent(wsEvent);
  ws.setReconnectInterval(5000);
  ws.enableHeartbeat(15000, 5000, 2); // ping 15s; 2 lần không có pong thì đóng và kết nối lại
  ws.beginSslWithCA(CLOUD_HOST, CLOUD_PORT, WS_PATH, ROOT_CA_PEM, "");
  wsStarted = true;
  Serial.println("[WS] Bắt đầu kết nối wss://" CLOUD_HOST WS_PATH);
}

// ============================================================================
// HÀM KHỞI TẠO CAMERA OV2640 (CẤU HÌNH ĐÃ TEST THÀNH CÔNG)
// ============================================================================
bool setupCamera() {
  Serial.println("\n[CAMERA] Đang kiểm tra bộ nhớ và khởi tạo Camera...");

  if (!psramFound()) {
    Serial.println("************************************************************");
    Serial.println(" [CẢNH BÁO NGUY HIỂM] KHÔNG TÌM THẤY BỘ NHỚ PSRAM!");
    Serial.println(" -> Hãy vào Arduino IDE: Tools -> PSRAM -> chọn 'OPI PSRAM'!");
    Serial.println("************************************************************");
  } else {
    Serial.printf(" [PSRAM OK] Đã nhận diện OPI PSRAM! Dung lượng trống: %u bytes\n", (unsigned)ESP.getFreePsram());
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
// HÀM KẾT NỐI WI-FI VÀ ĐỒNG BỘ THỜI GIAN (NTP cần cho TLS và TTL command)
// ============================================================================
struct WifiNetwork { const char* ssid; const char* password; };
const WifiNetwork WIFI_NETWORKS[] = { {WIFI_SSID, WIFI_PASSWORD}, {WIFI_SSID_2, WIFI_PASSWORD_2} };
int wifiIndex = 0;

int wifiNetworkCount() { return strlen(WIFI_SSID_2) > 0 ? 2 : 1; }

// Không in mật khẩu, chỉ in tên mạng.
void beginWifi(int index) {
  wifiIndex = index % wifiNetworkCount();
  Serial.printf("[WIFI] Đang kết nối tới: %s ...\n", WIFI_NETWORKS[wifiIndex].ssid);
  WiFi.begin(WIFI_NETWORKS[wifiIndex].ssid, WIFI_NETWORKS[wifiIndex].password);
}

void connectWiFi() {
  WiFi.persistent(false);
  WiFi.disconnect(true);
  delay(200);
  WiFi.mode(WIFI_STA);
  WiFi.setSleep(false); // Tắt sleep để module RF giữ công suất tối đa
  WiFi.setAutoReconnect(true);

  // Lúc khởi động: thử lần lượt từng mạng, mỗi mạng tối đa 10 giây.
  for (int n = 0; n < wifiNetworkCount() && WiFi.status() != WL_CONNECTED; n++) {
    beginWifi(n);
    for (int retry = 0; retry < 20 && WiFi.status() != WL_CONNECTED; retry++) {
      delay(500);
      Serial.print(".");
    }
    Serial.println();
  }

  if (WiFi.status() == WL_CONNECTED) {
    Serial.printf("[WIFI OK] Mạng: %s, IP ESP32-S3: %s (RSSI: %d dBm)\n", WIFI_NETWORKS[wifiIndex].ssid,
                  WiFi.localIP().toString().c_str(), WiFi.RSSI());
    beep(2, 80);
  } else {
    Serial.printf("[WIFI CHƯA KẾT NỐI] Mã trạng thái: %d. Sẽ thử lại trong nền; không mở khóa khi offline.\n", WiFi.status());
  }
}

void syncClock(unsigned long waitMs) {
  configTime(0, 0, NTP_SERVER, "time.google.com");
  unsigned long start = millis();
  while (!timeSynced() && millis() - start < waitMs) delay(200);
  Serial.println(timeSynced() ? "[NTP] Đã đồng bộ thời gian." : "[NTP] Chưa đồng bộ thời gian; tạm ngừng TLS.");
}

// ============================================================================
// REST QUA HTTPS TỚI CLOUD CORE (Bearer token thiết bị + request ID dùng một lần)
// ============================================================================
String apiUrl(const String& path) {
  return String("https://") + CLOUD_HOST + (CLOUD_PORT == 443 ? String("") : ":" + String(CLOUD_PORT)) + path;
}

int deviceRequest(const String& path, const String& contentType, uint8_t* body, size_t length,
                  uint32_t timeoutMs, String& response) {
  response = "";
  if (WiFi.status() != WL_CONNECTED || !caConfigured || !timeSynced() || strlen(DEVICE_TOKEN) == 0) return -1;
  WiFiClientSecure net;
  net.setCACert(ROOT_CA_PEM); // Kiểm chứng CA và hostname; không dùng setInsecure()
  HTTPClient http;
  if (!http.begin(net, apiUrl(path))) return -1;
  http.setConnectTimeout(5000);
  http.setTimeout(timeoutMs);
  http.addHeader("Authorization", String("Bearer ") + DEVICE_TOKEN);
  http.addHeader("X-Device-Id", DEVICE_ID);
  http.addHeader("X-Request-Id", randomId());
  http.addHeader("X-Request-Time", String((uint32_t)time(nullptr)));
  http.addHeader("Content-Type", contentType);
  int code = http.POST(body, length);
  if (code > 0) response = http.getString();
  http.end();
  return code;
}

Decision decisionFrom(int code, const String& response) {
  if ((code == 200 || code == 202) && response.indexOf("\"decision\":\"GRANTED\"") >= 0) return Decision::Granted;
  if (code == 401 || code == 422) return Decision::Denied;
  if (code == 429) return Decision::Throttled;
  if (code == 403) return Decision::Lockdown;
  return Decision::Unavailable; // Mạng/cloud/AI lỗi: không mở và không tính là kẻ lạ
}

// Gửi một khung JPEG xem trước qua WebSocket (nhị phân). Bỏ qua nếu camera đang bận chụp xác thực.
void sendPreviewFrame() {
  if (!cameraReady || !camMutex || xSemaphoreTake(camMutex, 0) != pdTRUE) return;
  camera_fb_t *fb = esp_camera_fb_get();
  if (fb) {
    if (fb->len <= 100 * 1024) ws.sendBIN(fb->buf, fb->len);
    esp_camera_fb_return(fb);
  }
  xSemaphoreGive(camMutex);
}

// Chụp một khung JPEG mới và gửi multipart kèm các trường văn bản (đã kiểm soát nội dung).
int postPhoto(const String& path, const String& fields, uint32_t timeoutMs, String& response) {
  if (!cameraReady) return -1;
  camera_fb_t *fb = NULL;
  if (camMutex && xSemaphoreTake(camMutex, pdMS_TO_TICKS(1500)) == pdTRUE) {
    fb = esp_camera_fb_get();
    if (fb) esp_camera_fb_return(fb); // Xả khung hình cũ
    fb = esp_camera_fb_get();
    if (!fb) {
      xSemaphoreGive(camMutex);
      Serial.println("[CAMERA] Lỗi: Không thu nhận được khung hình!");
      return -1;
    }
  } else {
    Serial.println("[CAMERA] Đang bận!");
    return -1;
  }

  String boundary = "----ESP32S3SmartLock" + randomId();
  String head = fields;
  head.replace("{B}", boundary);
  head += "--" + boundary + "\r\n";
  head += "Content-Disposition: form-data; name=\"file\"; filename=\"door.jpg\"\r\n";
  head += "Content-Type: image/jpeg\r\n\r\n";
  String tail = "\r\n--" + boundary + "--\r\n";

  size_t totalLen = head.length() + fb->len + tail.length();
  uint8_t *payload = (uint8_t *)ps_malloc(totalLen);
  if (!payload) payload = (uint8_t *)malloc(totalLen);
  if (!payload) {
    Serial.println("[MEM] Không đủ bộ nhớ để gửi ảnh!");
    esp_camera_fb_return(fb);
    xSemaphoreGive(camMutex);
    return -1;
  }
  memcpy(payload, head.c_str(), head.length());
  memcpy(payload + head.length(), fb->buf, fb->len);
  memcpy(payload + head.length() + fb->len, tail.c_str(), tail.length());
  esp_camera_fb_return(fb);
  xSemaphoreGive(camMutex);

  Serial.printf("[HTTPS] Gửi ảnh (%u bytes) tới %s ...\n", (unsigned)totalLen, path.c_str());
  int code = deviceRequest(path, "multipart/form-data; boundary=" + boundary, payload, totalLen, timeoutMs, response);
  free(payload);
  return code;
}

Decision captureAndVerifyFace() {
  String response;
  int code = postPhoto("/api/device/face", "", FACE_HTTP_TIMEOUT_MS, response);
  Serial.printf("[HTTPS] Kết quả khuôn mặt: %d %s\n", code, response.c_str());
  return decisionFrom(code, response);
}

bool sendSecurityAlertWithPhoto(const String& alertType, const String& description) {
  String fields = "--{B}\r\nContent-Disposition: form-data; name=\"alertType\"\r\n\r\n" + alertType + "\r\n"
                + "--{B}\r\nContent-Disposition: form-data; name=\"description\"\r\n\r\n" + description + "\r\n";
  String response;
  int code = postPhoto("/api/device/alerts", fields, 10000, response);
  bool ok = (code == 200 || code == 201);
  Serial.printf(ok ? "[ALERT OK] Đã gửi cảnh báo (HTTP %d)\n" : "[ALERT LỖI] Gửi cảnh báo thất bại (mã %d)\n", code);
  return ok;
}

// D04: PIN không kèm username; không có master PIN hay đường mở offline.
Decision verifyPinWithBackend(const String& pin) {
  String json = "{\"pinCode\":\"" + pin + "\"}";
  String response;
  int code = deviceRequest("/api/device/pin", "application/json", (uint8_t*)json.c_str(), json.length(),
                           PIN_HTTP_TIMEOUT_MS, response);
  Serial.printf("[HTTPS] Kết quả PIN: %d\n", code); // Không in PIN
  return decisionFrom(code, response);
}

// Cloud đã chấp thuận: cửa chỉ mở khi command WebSocket tới; đèn báo đang chờ.
void onGranted() {
  Serial.println("[CLOUD] Đã chấp thuận, chờ command mở khóa qua WebSocket...");
  awaitCommandUntil = millis() + AWAIT_COMMAND_MS;
  digitalWrite(PIN_LED, HIGH);
  beep(1, 60);
}

void onCloudError() {
  Serial.println("[CLOUD] Không xác thực được (mạng/cloud lỗi). Cửa giữ nguyên khóa.");
  digitalWrite(PIN_LED, LOW);
  beep(5, 40, 40);
}

// ============================================================================
// HÀM XỬ LÝ NHẬP MÃ PIN TRÊN BÀN PHÍM KEYPAD 4x4
// ============================================================================
void handleKeypadInput(char key) {
  beep(1, 25); // Tiếng bíp ngắn 25ms êm tai và không chặn CPU quá lâu gây tràn buffer Camera

  if (key == '*' || key == 'C') {
    inputPinBuffer = "";
    Serial.println("[KEYPAD] Đã xóa mã PIN.");
    return;
  }

  if (key == 'A' || key == 'B') {
    return; // Bỏ qua phím phụ
  }

  // Phím 'D' = Chuông cửa & Quét khuôn mặt AI trên cloud
  if (key == 'D') {
    if (isEmergencyLockdown) {
      Serial.println("[CHUÔNG CỬA] Cửa đang bị vô hiệu hóa khẩn cấp!");
      beep(3, 80, 50);
      return;
    }
    if (isDoorUnlocked) return; // Cửa đang mở, không gửi thêm yêu cầu
    Serial.println("[CHUÔNG CỬA] Bấm phím 'D' -> Bật LED và gửi ảnh lên cloud...");
    digitalWrite(PIN_LED, HIGH); // Bật LED trợ sáng
    beep(2, 100, 60);

    Decision decision = captureAndVerifyFace();
    if (decision == Decision::Granted) {
      consecutivePirFaceFails = 0;
      onGranted();
    } else if (decision == Decision::Unavailable) {
      onCloudError();
    } else {
      Serial.println("[AI] Không được chấp thuận. Tắt LED.");
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
      Serial.println("[KEYPAD] Chưa có số nào được nhập.");
      beep(2, 60, 40);
      return;
    }

    Serial.printf("[KEYPAD] Gửi PIN (%d ký tự) lên cloud...\n", inputPinBuffer.length());
    String pinToCheck = inputPinBuffer;
    inputPinBuffer = "";

    Decision decision = verifyPinWithBackend(pinToCheck);
    if (decision == Decision::Granted) {
      failedPinAttempts = 0;
      onGranted();
    } else if (decision == Decision::Unavailable) {
      onCloudError(); // Không tính là sai PIN khi mạng/cloud lỗi
    } else {
      failedPinAttempts++;
      Serial.printf("[CẢNH BÁO] PIN bị từ chối lần %d/3!\n", failedPinAttempts);
      beep(3, 180, 100);

      if (failedPinAttempts >= 3 || decision == Decision::Throttled) {
        Serial.println("!!! [BÁO ĐỘNG] Sai PIN nhiều lần! Báo động, chụp ảnh gửi Admin & khóa bàn phím 15s !!!");
        isSystemLockedOut = true;
        lockoutStartTime = millis();
        beep(8, 250, 100);
        publishEvent("KEYPAD_LOCKOUT");
        sendSecurityAlertWithPhoto("WRONG_PIN_3_TIMES", "Nhập sai mã PIN nhiều lần liên tiếp trên bàn phím Keypad 4x4");
        failedPinAttempts = 0;
      }
    }
    return;
  }

  // Nhập ký tự số (0 - 9). Không log từng phím (bí mật).
  if (key >= '0' && key <= '9') {
    if (inputPinBuffer.length() < 10) {
      inputPinBuffer += key;
      Serial.printf("[PIN] Độ dài: %d/10\n", inputPinBuffer.length());
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
  Serial.println(" HỆ THỐNG KHÓA CỬA THÔNG MINH AIoT - ESP32-S3 N16R8 CAM (L3) ");
  Serial.println("============================================================");

  if (camMutex == NULL) {
    camMutex = xSemaphoreCreateMutex();
  }

  // Trạng thái an toàn trước mọi thứ khác: khóa, relay tắt
  pinMode(PIN_LED, OUTPUT);
  pinMode(PIN_BUZZER, OUTPUT);
  pinMode(PIN_RELAY, OUTPUT);
  pinMode(PIN_PIR, INPUT);
  digitalWrite(PIN_LED, LOW);
  digitalWrite(PIN_BUZZER, LOW);
  digitalWrite(PIN_RELAY, RELAY_OFF);

  // Khởi tạo Servo trên Timer 3 riêng biệt (không ảnh hưởng Timer 0 của Camera)
  ESP32PWM::allocateTimer(3);
  doorServo.setPeriodHertz(50);
  doorServo.attach(PIN_SERVO, 500, 2400);
  doorServo.write(SERVO_LOCK_ANGLE);

  esp_timer_create_args_t timerArgs = {};
  timerArgs.callback = &relockTimerCallback;
  timerArgs.name = "relock";
  esp_timer_create(&timerArgs, &relockTimer);

  // Trạng thái chống phát lại và lockdown sống sót qua reboot
  prefs.begin("smartlock", false);
  lastSeq = prefs.getULong64("lastSeq", 0);
  lastCmdId = prefs.getString("lastCmd", "");
  lastAckJson = prefs.getString("lastAck", "");
  isEmergencyLockdown = prefs.getBool("lockdown", false);
  Serial.printf("[NVS] lastSeq=%s lockdown=%s\n", u64ToString(lastSeq).c_str(), isEmergencyLockdown ? "ON" : "OFF");

  caConfigured = strstr(ROOT_CA_PEM, "BEGIN CERTIFICATE") != nullptr;
  if (!caConfigured) Serial.println("[TLS] CHƯA CẤU HÌNH ROOT_CA_PEM trong config.h - tạm ngừng kết nối cloud.");
  if (strlen(DEVICE_TOKEN) == 0) Serial.println("[AUTH] CHƯA CẤU HÌNH DEVICE_TOKEN trong config.h.");

  // 1. Khởi tạo Camera OV2640 chuẩn Freenove N16R8
  setupCamera();

  // 2. Kết nối Wi-Fi và đồng bộ thời gian
  connectWiFi();
  if (WiFi.status() == WL_CONNECTED) syncClock(10000);

  // 3. Kênh lệnh WebSocket bảo mật (CA + hostname kiểm chứng; không setInsecure)
  startWebSocket();

  // Ổn định cảm biến PIR trong 10 giây
  pirWarmupEnd = millis() + 10000;
  Serial.println("[PIR] Đang ổn định cảm biến trong 10 giây...");
  Serial.println("[HỆ THỐNG] Đã sẵn sàng hoạt động!");
}

void loop() {
  unsigned long now = millis();

  // Hoàn tất khóa lại do esp_timer: tiếng bíp, sự kiện và nghỉ PIR
  if (relockPending) {
    relockPending = false;
    Serial.println(">>> [ĐÓNG CỬA] Hết thời gian mở, đã khóa lại cửa. <<<");
    beep(1, 150);
    publishEvent("DOOR_LOCKED", activeUnlockCommandId);
    lastPirTrigger = millis() + PIR_POST_UNLOCK_MS;
    pirRequiresRelease = true;
    publishStatus();
  }
  // Dự phòng nếu timer không chạy vì lý do nào đó
  if (isDoorUnlocked && now - doorUnlockTime >= activeUnlockMs + 1000) lockNow();

  // Tự động thử kết nối lại Wi-Fi trong nền nếu rớt mạng
  static unsigned long lastWifiRetry = 0;
  static unsigned long lastClockRetry = 0;
  if (WiFi.status() != WL_CONNECTED) {
    // Mỗi lần thử lại chuyển sang mạng kế tiếp; chỉ một mạng thì luôn thử lại mạng đó.
    if (now - lastWifiRetry >= 8000) {
      lastWifiRetry = now;
      beginWifi(wifiIndex + 1);
    }
  } else {
    if (!timeSynced() && now - lastClockRetry >= 30000) { lastClockRetry = now; syncClock(2000); }
    startWebSocket();
    if (wsStarted) {
      ws.loop(); // nhận lệnh, gửi ping, tự kết nối lại
      if (wsConnected && millis() - lastHeartbeat >= HEARTBEAT_MS) publishStatus();
      if (wsConnected && (long)(previewUntil - millis()) > 0 && millis() - lastPreviewFrame >= PREVIEW_INTERVAL_MS) {
        lastPreviewFrame = millis();
        sendPreviewFrame();
      }
    }
  }

  // Hết thời gian chờ command sau khi được chấp thuận
  if (awaitCommandUntil && !isDoorUnlocked && now > awaitCommandUntil) {
    awaitCommandUntil = 0;
    Serial.println("[CLOUD] Không nhận được command mở trong thời hạn. Cửa giữ nguyên khóa.");
    digitalWrite(PIN_LED, LOW);
    beep(3, 60, 60);
  }

  // Kiểm tra trạng thái khóa bàn phím do sai PIN
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

  // Đọc bàn phím Keypad 4x4
  char key = keypad.getKey();
  if (key) handleKeypadInput(key);

  // Cảm biến chuyển động PIR: tự bật LED và gửi ảnh xác thực lên cloud
  if (now > pirWarmupEnd && !isDoorUnlocked && !awaitCommandUntil) {
    int pirState = digitalRead(PIN_PIR);

    // Nếu cảm biến đã hạ về LOW, xóa cờ chờ nhả
    if (pirState == LOW) {
      pirRequiresRelease = false;
      digitalWrite(PIN_LED, LOW);
    }

    if (pirState == HIGH && !pirRequiresRelease && (now >= lastPirTrigger)) {
      Serial.println("\n[PIR] Phát hiện người trước cửa! Bật LED và gửi ảnh lên cloud...");
      digitalWrite(PIN_LED, HIGH);
      publishEvent("PIR_MOTION");

      if (isEmergencyLockdown) {
        Serial.println("[PIR] Cửa đang bị VÔ HIỆU HÓA KHẨN CẤP! Bỏ qua nhận diện.");
        digitalWrite(PIN_LED, LOW);
        beep(3, 100, 60);
        lastPirTrigger = millis() + 10000;
        pirRequiresRelease = true;
      } else {
        Decision decision = captureAndVerifyFace();

        if (decision == Decision::Granted) {
          consecutivePirFaceFails = 0;
          pirRequiresRelease = true;
          onGranted();
        } else if (decision == Decision::Unavailable || decision == Decision::Lockdown || decision == Decision::Throttled) {
          digitalWrite(PIN_LED, LOW);
          lastPirTrigger = millis() + PIR_COOLDOWN_MS;
        } else {
          consecutivePirFaceFails++;
          Serial.printf("[AI] Không được chấp thuận (Lần %d/5). Tắt LED.\n", consecutivePirFaceFails);
          digitalWrite(PIN_LED, LOW);
          beep(2, 60, 40);
          lastPirTrigger = millis() + PIR_COOLDOWN_MS;

          // Cloud từ chối khuôn mặt 5 lần liên tiếp -> Cảnh báo + gửi ảnh kẻ lạ về Admin
          if (consecutivePirFaceFails >= 5) {
            Serial.println("!!! [BÁO ĐỘNG KẺ LẠ] Bị từ chối 5 lần liên tiếp! Gửi ảnh cho Admin !!!");
            beep(6, 200, 100);
            sendSecurityAlertWithPhoto("PIR_INTRUDER_5_TIMES", "Phát hiện chuyển động: khuôn mặt bị từ chối 5 lần liên tiếp");
            publishEvent("FACE_FAIL_STREAK");
            consecutivePirFaceFails = 0;
            lastPirTrigger = millis() + PIR_ALERT_LOCKOUT_MS;
            pirRequiresRelease = true;
            Serial.println("[PIR] Đã kích hoạt thời gian nghỉ 30 giây sau cảnh báo.");
          }
        }
      }
    }
  }
}
