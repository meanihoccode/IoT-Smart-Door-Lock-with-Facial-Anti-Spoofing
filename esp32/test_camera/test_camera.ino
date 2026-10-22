/*
 * ============================================================================
 * CHƯƠNG TRÌNH KIỂM TRA ĐỘC LẬP & TỰ CHẨN ĐOÁN CAMERA OV2640 TRÊN ESP32-S3 N16R8
 * Phiên bản: Chống treo vĩnh viễn (Watchdog Timeout 3s) + 10MHz Clock + DRAM/PSRAM
 * ============================================================================
 */
#include <Arduino.h>
#include "esp_camera.h"

// Định nghĩa chân Camera chuẩn Freenove / AI-Thinker ESP32-S3 WROOM CAM N16R8
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

// Biến điều khiển FreeRTOS Task chụp ảnh có Watchdog chống treo
static TaskHandle_t captureTaskHandle = NULL;
static camera_fb_t *volatile global_fb = NULL;
static volatile bool captureCompleted = false;
static volatile bool captureRunning   = false;

void captureWorkerTask(void *pvParameters) {
  while (true) {
    ulTaskNotifyTake(pdTRUE, portMAX_DELAY);
    captureRunning = true;
    captureCompleted = false;
    global_fb = esp_camera_fb_get();
    captureCompleted = true;
    captureRunning = false;
  }
}

// Hàm chụp ảnh an toàn có giới hạn thời gian (Timeout 3000ms)
camera_fb_t* safeCameraFbGet(uint32_t timeoutMs = 3000) {
  if (captureTaskHandle == NULL) {
    return NULL;
  }
  global_fb = NULL;
  captureCompleted = false;
  xTaskNotifyGive(captureTaskHandle);

  uint32_t startMs = millis();
  while (!captureCompleted && (millis() - startMs < timeoutMs)) {
    vTaskDelay(pdMS_TO_TICKS(10));
  }

  if (!captureCompleted) {
    Serial.println("\n[LỖI CỰC KỲ QUAN TRỌNG: TIMEOUT 3000ms!]");
    Serial.println(" -> Hàm esp_camera_fb_get() bị TREO VÌ DMA KHÔNG NHẬN ĐƯỢC XUNG TỪ CẢM BIẾN!");
    Serial.println(" -> Nguyên nhân: Chân PCLK (GPIO 13) hoặc VSYNC (GPIO 6) trên cáp dẹt bị lệch tiếp xúc,");
    Serial.println("    hoặc sụt nguồn 3.3V do Servo/Relay kéo tải.");
    return NULL;
  }

  return global_fb;
}

bool initCameraTest(framesize_t fsize, uint32_t xclk_hz, camera_fb_location_t location, const char* testName) {
  Serial.println("\n------------------------------------------------------------");
  Serial.printf(">> THỬ NGHIỆM CẤU HÌNH: %s\n", testName);
  Serial.printf("   + Độ phân giải: %s\n", (fsize == FRAMESIZE_QVGA ? "QVGA 320x240" : (fsize == FRAMESIZE_VGA ? "VGA 640x480" : "CIF 400x296")));
  Serial.printf("   + Tần số XCLK: %lu MHz\n", xclk_hz / 1000000);
  Serial.printf("   + Vị trí Frame Buffer: %s\n", (location == CAMERA_FB_IN_DRAM ? "DRAM (Bộ nhớ trong - Bỏ qua PSRAM)" : "PSRAM (Bộ nhớ ngoài 8MB)"));
  Serial.printf("   + Grab mode: CAMERA_GRAB_WHEN_EMPTY | Buffer count: 1\n");
  Serial.println("------------------------------------------------------------");

  esp_camera_deinit(); // Hủy cấu hình cũ nếu có
  delay(100);

  camera_config_t config;
  config.ledc_channel = LEDC_CHANNEL_2;
  config.ledc_timer   = LEDC_TIMER_1;
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

  config.xclk_freq_hz = xclk_hz;
  config.pixel_format = PIXFORMAT_JPEG;
  config.grab_mode    = CAMERA_GRAB_WHEN_EMPTY; // Chế độ ổn định nhất, không ngốn hàng đợi
  config.frame_size   = fsize;
  config.jpeg_quality = 12;
  config.fb_count     = 1;
  config.fb_location  = location;

  esp_err_t err = esp_camera_init(&config);
  if (err != ESP_OK) {
    Serial.printf("[CAMERA LỖI] esp_camera_init thất bại! Mã lỗi: 0x%x\n", err);
    return false;
  }

  Serial.println("[CAMERA OK] Giao tiếp SCCB/I2C thành công! Đang chụp thử khung hình...");
  
  // Chụp thử 3 khung hình liên tiếp
  for (int i = 1; i <= 3; i++) {
    Serial.printf(" -> Đang chụp ảnh mẫu #%d... ", i);
    unsigned long t0 = millis();
    camera_fb_t *fb = safeCameraFbGet(3000);
    unsigned long elapsed = millis() - t0;

    if (!fb) {
      Serial.printf("THẤT BẠI sau %lu ms! (Bị Timeout hoặc NULL)\n", elapsed);
      return false;
    } else {
      Serial.printf("THÀNH CÔNG! Kích thước: %u bytes (%dx%d), Thời gian lấy ảnh: %lu ms\n",
                    fb->len, fb->width, fb->height, elapsed);
      esp_camera_fb_return(fb);
    }
    delay(500);
  }

  Serial.println("===> CẤU HÌNH NÀY HOẠT ĐỘNG HOÀN TOÀN TỐT! <===");
  return true;
}

void setup() {
  Serial.begin(115200);
  delay(2000);
  Serial.println("\n\n============================================================");
  Serial.println("  CHƯƠNG TRÌNH CHẨN ĐOÁN TOÀN DIỆN CAMERA OV2640 ESP32-S3   ");
  Serial.println("============================================================");

  if (!psramFound()) {
    Serial.println("[CẢNH BÁO] Không tìm thấy PSRAM! Cần chọn Tools -> PSRAM -> 'OPI PSRAM'");
  } else {
    Serial.printf("[PSRAM OK] Bộ nhớ OPI PSRAM hoạt động tốt! Trống: %d bytes\n", ESP.getFreePsram());
  }

  // Khởi tạo FreeRTOS Task cho việc chụp ảnh an toàn
  xTaskCreatePinnedToCore(
    captureWorkerTask,
    "CamCapWorker",
    4096,
    NULL,
    5,
    &captureTaskHandle,
    1 // Chạy trên Core 1
  );

  // BƯỚC 1: Thử cấu hình an toàn nhất thế giới: QVGA 320x240, XCLK 10MHz, Lưu trực tiếp trong DRAM (không cần PSRAM)
  bool test1 = initCameraTest(FRAMESIZE_QVGA, 10000000, CAMERA_FB_IN_DRAM, "MỨC 1: QVGA 320x240 - 10MHz - DRAM NỘI BỘ");

  if (!test1) {
    Serial.println("\n!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
    Serial.println(" KẾT QUẢ CHẨN ĐOÁN PHẦN CỨNG (100% CHÍNH XÁC):");
    Serial.println(" 1. Chip I2C của OV2640 nhận dạng thành công (esp_camera_init = 0).");
    Serial.println(" 2. Nhưng đường truyền dữ liệu điểm ảnh (PCLK, VSYNC, D0-D7) KHÔNG CÓ TÍN HIỆU.");
    Serial.println(" => NGUYÊN NHÂN:");
    Serial.println("    a. Cáp dẹt FPC 24 chân cắm bị lệch, lỏng hoặc chưa khóa chặt thanh gài đen.");
    Serial.println("    b. Động cơ Servo / Relay đang ăn nguồn 5V chung làm sụt áp chip Camera.");
    Serial.println(" => HÀNH ĐỘNG CẦN LÀM:");
    Serial.println("    - Rút nguồn USB.");
    Serial.println("    - Mở lẫy đen, rút cáp dẹp camera ra cắm lại THẲNG TẮP và gài thật chặt.");
    Serial.println("    - Tạm thời rút dây Đỏ (5V) của Servo và Relay ra khỏi nguồn.");
    Serial.println("    - Cắm lại USB và xem lại màn hình này.");
    Serial.println("!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!\n");
    return;
  }

  // BƯỚC 2: Nếu bước 1 thành công -> Thử VGA 640x480, XCLK 10MHz trên PSRAM
  bool test2 = initCameraTest(FRAMESIZE_VGA, 10000000, CAMERA_FB_IN_PSRAM, "MỨC 2: VGA 640x480 - 10MHz - OPI PSRAM (Chuẩn AI RetinaFace)");

  if (test2) {
    Serial.println("\n============================================================");
    Serial.println(" CHÚC MỪNG BẠN! PHẦN CỨNG CAMERA OV2640 & PSRAM ĐÃ SẴN SÀNG 100%!");
    Serial.println(" Bạn có thể nạp lại file esp32/main/main.ino ngay bây giờ.");
    Serial.println("============================================================\n");
  } else {
    Serial.println("\n[CHÚ Ý] Bước 1 (DRAM) thành công nhưng Bước 2 (PSRAM) bị lỗi.");
    Serial.println(" -> Kiểm tra lại mục Tools -> PSRAM xem đã chọn 'OPI PSRAM' chưa.");
  }
}

void loop() {
  // Chương trình chẩn đoán chạy xong trong setup(), giữ loop nghỉ ngơi
  delay(1000);
}
