# TÀI LIỆU KỸ THUẬT & BÁO CÁO CẬP NHẬT DỰ ÁN AIoT SMART LOCK
**Đề tài:** Hệ thống Khóa cửa Thông minh tích hợp AIoT (AIoT Smart Door Lock with Facial Anti-Spoofing)  
**Ngày cập nhật:** 23/10/2026  
**Người thực hiện & Bàn giao:** Nhóm phát triển dự án IoT  
**Mục đích tài liệu:** Tổng hợp toàn bộ các tính năng mới, lỗi kỹ thuật đã giải quyết và hướng dẫn vận hành cho tất cả thành viên trong nhóm.

---

## 1. TỔNG QUAN KIẾN TRÚC HỆ THỐNG TOÀN DIỆN

Hệ thống hoạt động theo mô hình phân tán 4 tầng (Edge - AI - Core - Client) đồng bộ thời gian thực qua giao thức MQTT và RESTful API:

```
[ ESP32-S3 CAM N16R8 ]  <--- MQTT (iot/lock/events, iot/lock/commands) --->  [ Mosquitto Broker :1883 ]
  | (Chụp ảnh & gửi POST)                                                               ^
  v                                                                                     |
[ Backend Core :8080 ]  <--- HTTP REST (Proxy nhận diện) ---> [ FastAPI AI :8000 ]      |
  |                                                                                     |
  +--- WebSocket / REST API ------------------------------------------------------------+
  |
  v
[ Frontend React :5173 ] (Web Kiosk Chuông cửa & Dashboard Quản trị Admin)
```

1. **Edge Device (ESP32-S3 WROOM N16R8 CAM):**
   * Xử lý ngoại vi: Bàn phím ma trận Keypad 4x4, Cảm biến chuyển động PIR, Động cơ Servo 90°, Module Relay 5V, Còi báo Chip Buzzer 5V, Đèn LED trạng thái F5.
   * Camera OV2640 (VGA 640x480): Phục vụ Web Kiosk xem trực tiếp và chụp ảnh gửi AI xác thực / cảnh báo kẻ lạ.
2. **Backend Core (Spring Boot :8080):**
   * Quản lý Cơ sở dữ liệu MySQL (`btl_iot`): Người dùng, Nhật ký an ninh (Access Log), Danh sách cảnh báo kẻ lạ (Security Alert).
   * Điều phối MQTT hai chiều: Đồng bộ tức thì trạng thái mở cửa, lệnh khóa khẩn cấp, cảnh báo đột nhập.
   * Cung cấp API lưu trữ và phục vụ hình ảnh tĩnh kẻ lạ (`/api/security-alerts`).
3. **AI Face Anti-Spoofing Service (FastAPI :8000):**
   * Nhận diện khuôn mặt độ chính xác cao kết hợp kiểm tra độ sống (Anti-Spoofing / Liveness Detection) chống giả mạo bằng ảnh in hoặc màn hình điện thoại.
4. **Client Interface (React + Vite + TailwindCSS :5173):**
   * **Web Kiosk (`/kiosk`):** Giao diện chuông cửa đặt trước nhà, hiển thị camera trực tiếp từ ESP32, thông báo chào mừng khi mở cửa.
   * **Admin Portal (`/admin`):** Dashboard quản trị, xem danh sách cảnh báo kẻ lạ kèm ảnh chụp, nhật ký mở cửa thời gian thực, nút **Khóa khẩn cấp** vô hiệu hóa cửa từ xa.

---

## 2. CHI TIẾT CÁC CÔNG VIỆC VÀ TÍNH NĂNG ĐÃ HOÀN TẤT HÔM NAY

### A. Tối ưu Camera OV2640 & Web Kiosk
* **Triệt tiêu hoàn toàn lỗi `cam_hal: FB-OVF` (Frame Buffer Overflow):**
  * Giảm xung nhịp `xclk_freq_hz` từ 20MHz xuống **12MHz** (Golden Frequency), giúp giảm tải hơn 40% băng thông DMA mà vẫn giữ nguyên độ phân giải VGA 640x480 sắc nét.
  * Đổi cơ chế lấy ảnh sang `CAMERA_GRAB_WHEN_EMPTY`, chỉ nạp ảnh khi có yêu cầu (từ Web Kiosk `/capture` hoặc khi quét khuôn mặt AI), không chạy nền dồn dập gây nghẽn RAM.
* **Tích hợp xem Camera tại Web Kiosk:** Giao diện Web Kiosk tự động lấy ảnh tĩnh thời gian thực từ endpoint `http://<IP_ESP32>/capture` của ESP32, không cần luồng stream riêng phức tạp.

### B. Khắc phục triệt để lỗi Cửa tự đóng ngay lập tức
* **Nguyên nhân cũ:** Do biến thời gian bị lỗi tràn số (*unsigned long underflow*) khi có lệnh mở cửa kèm độ trễ còi buzzer, làm thời gian trôi qua bị tính thành hàng triệu giây và kích hoạt lệnh `lockDoor()` ngay trong cùng 1 chu kỳ vòng lặp (< 1ms).
* **Giải pháp:** Hệ thống lấy `millis()` mới nhất sau khi phần cứng hoàn tất kích hoạt; đồng thời kiểm tra chống tràn số đảm bảo đếm đủ chính xác **7000 ms (7 giây)** mới đóng cửa lại.

### C. Khắc phục lỗi Bàn phím Keypad 4x4 (Lỗi bấm phím `0` bị nhận diện thành `*`)
* **Chuẩn hóa chân cắm phần cứng:**
  * Dây 1 – 4 (Hàng R1 – R4): Nối vào chân **GPIO 38, 39, 40, 41**.
  * Dây 5 – 8 (Cột C1 – C4): Nối vào chân **GPIO 42, 2, 1, 45**. *(Lưu ý: Dây 8 cắm vào chân 45, tuyệt đối không cắm vào chân 0 BOOT)*.
* **Tối ưu tầng driver phần mềm (`ESP32Keypad`):**
  * Viết lớp kế thừa `ESP32Keypad` ghi đè hàm `pin_mode` sang `INPUT_PULLUP` cho các cột chưa quét, triệt tiêu hiện tượng rò mức thấp của chân GPIO 2 trên ESP32-S3.
  * **Đảo thứ tự ưu tiên quét cột:** Đưa GPIO 2 quét trước GPIO 42 (`colPins = { 2, 42, 1, 45 }`). Nhờ đó, khi bấm phím `0` (Hàng 4, Cột GPIO 2), hệ thống bắt dính ngay phím `0`, không bị quét nhầm sang phím `*` (GPIO 42).
  * **Kết quả:** Toàn bộ **16/16 phím** (`0`–`9`, `*`, `#`, `A`, `B`, `C`, `D`) đều hoạt động chuẩn xác 100%.

### D. Hệ thống Cảnh báo An ninh & Kẻ lạ (Security Alerts)
1. **Quét khuôn mặt thất bại > 5 lần qua PIR:**
   * Khi phát hiện chuyển động trước cửa, nếu quét khuôn mặt không khớp người quen liên tiếp 5 lần, ESP32 tự động hú còi báo động, chụp 1 ảnh tĩnh chất lượng cao gửi về endpoint `/api/security-alerts` của Backend Core và phát tin nhắn MQTT `INTRUDER_ALERT_PIR_5_TIMES`.
   * Kích hoạt thời gian nghỉ an toàn (Lockout) 30 giây để tránh còi hú liên tục.
2. **Nhập sai mã PIN 3 lần liên tiếp:**
   * Nếu người đứng trước cửa bấm sai mã PIN 3 lần trên Keypad 4x4: ESP32 hú còi báo động 8 tiếng, chụp ngay ảnh khuôn mặt kẻ gian gửi về Backend, tạm khóa bàn phím trong 15 giây.
3. **Cơ chế Chặn bấm nhầm phím `#` rỗng:**
   * Nếu người dùng chưa nhập số nào mà bấm `#`, hệ thống chỉ phát tiếng bíp nhắc nhở chứ không tính là một lần nhập sai mã PIN (tránh bị khóa bàn phím oan).

### E. Tính năng Khóa khẩn cấp từ xa (Emergency Lockdown)
* Khi Admin phát hiện kẻ lạ qua ảnh cảnh báo trên Dashboard, Admin có thể bấm nút **"BẬT KHÓA KHẨN CẤP"**.
* Backend Core bắn lệnh MQTT `EMERGENCY_LOCKDOWN_ON` tới ESP32:
  * ESP32 lập tức phát 5 tiếng bíp cảnh báo và đưa cờ `isEmergencyLockdown = true`.
  * Cửa bị **vô hiệu hóa hoàn toàn**: Cả nhận diện khuôn mặt AI, bấm chuông 'D', và nhập mã PIN trên Keypad đều bị từ chối mở cửa.
  * Cửa chỉ được phép mở trở lại khi Admin bấm **"TẮT KHÓA KHẨN CẤP"** trên Web Dashboard.

### F. Đồng bộ dữ liệu Nhật ký An ninh & Lọc trùng lặp
* Cập nhật `MqttSubscriber.java` và `DoorAccessService.java`:
  * Khi ESP32 phát sự kiện mở cửa `DOOR_UNLOCKED:<METHOD>`, Backend Core kiểm tra thời gian mở cửa gần nhất (`DoorStateService.getLastUnlockTimestamp()`). Nếu dưới 6 giây, bỏ qua để không tạo các bản ghi nhật ký trùng lặp giữa lệnh Web và phản hồi phần cứng.
  * Phân loại nhật ký rõ ràng: `FACE` (Khuôn mặt AI), `PIN` (Keypad 4x4), `OFFLINE_PIN` (Mã PIN master ngoại tuyến), `ADMIN_REMOTE` (Mở từ xa qua Web).

---

## 3. SƠ ĐỒ ĐẤU DÂY PHẦN CỨNG CHUẨN (ESP32-S3 WROOM N16R8 CAM)

Tất cả linh kiện được bố trí gọn gàng trên dãy chân bên phải bo mạch ESP32-S3:

| Linh kiện | Chân linh kiện | Nối vào chân ESP32-S3 / Nguồn | Ghi chú |
| :--- | :--- | :--- | :--- |
| **Nguồn chung** | 5V / VIN & GND | Chân 5V & GND ESP32 xuống thanh Breadboard | Cấp nguồn nuôi Servo, Relay, Buzzer, PIR |
| **Bàn phím 4x4** | Dây 1, 2, 3, 4 (Hàng R1–R4) | **GPIO 38, 39, 40, 41** | Hàng ma trận từ trên xuống dưới |
| **Bàn phím 4x4** | Dây 5, 6, 7, 8 (Cột C1–C4) | **GPIO 42, 2, 1, 45** | Cột ma trận từ trái sang phải *(Dây 8 cắm chân 45)* |
| **Cảm biến PIR** | VCC, GND, OUT | 5V, GND, **GPIO 21** | Phát hiện chuyển động kích hoạt Camera |
| **Động cơ Servo** | Dây Cam (PWM), Đỏ, Nâu | **GPIO 47**, 5V, GND | Quay 90° mở cửa, về 0° khóa cửa (Timer 3) |
| **Module Relay 5V**| VCC, GND, IN (Tín hiệu) | 5V, GND, **GPIO 48** | Đóng/ngắt khóa từ hoặc đèn trợ sáng |
| **Chip Buzzer 5V** | Cực (+), Cực (-) | **GPIO 20**, GND | Còi báo thao tác phím và hú báo động |
| **Đèn LED F5** | Anode (+), Cathode (-) | **GPIO 19** (qua trở 220Ω), GND | Đèn trạng thái và trợ sáng ban đêm |

---

## 4. HƯỚNG DẪN CẤU HÌNH VÀ VẬN HÀNH CHO THÀNH VIÊN NHÓM

### Bước 1: Khởi động Mosquitto MQTT Broker
Mở Command Prompt và kiểm tra service Mosquitto hoạt động tại cổng `1883`:
```cmd
netstat -ano | findstr 1883
```

### Bước 2: Chạy AI Face Anti-Spoofing Service (Python FastAPI)
```bash
cd ai-service
# Kích hoạt virtualenv (nếu có)
uvicorn app:app --host 0.0.0.0 --port 8000 --reload
```

### Bước 3: Chạy Backend Core (Spring Boot)
```bash
cd backend-core
# Biên dịch và chạy server
./gradlew bootRun
```
*Server khởi chạy thành công tại `http://localhost:8080`*.

### Bước 4: Chạy Giao diện Quản trị & Kiosk (Frontend React)
```bash
cd frontend
npm install
npm run dev
```
* Truy cập Web Kiosk: `http://localhost:5173/kiosk`
* Truy cập Admin Portal: `http://localhost:5173/admin`
* Xem cảnh báo kẻ lạ: `http://localhost:5173/admin/alerts`

### Bước 5: Cấu hình và Nạp Firmware cho ESP32-S3
1. Mở thư mục `esp32/` trong **Arduino IDE**.
2. Sao chép file mẫu `esp32/config.example.h` thành `esp32/config.h`:
   ```cpp
   #define WIFI_SSID "TÊN_WIFI_CỦA_BẠN"
   #define WIFI_PASSWORD "MẬT_KHẨU_WIFI"
   #define MQTT_SERVER "IP_MÁY_TÍNH_CHẠY_BACKEND"
   ```
3. Cấu hình thông số bo mạch trên Arduino IDE (Menu **Tools**):
   * **Board:** `"ESP32S3 Dev Module"`
   * **USB CDC On Boot:** `"Disabled"`
   * **Flash Size:** `"16MB (128Mb)"`
   * **Partition Scheme:** `"16M Flash (3MB APP/9.9MB FATFS)"`
   * **PSRAM:** `"OPI PSRAM"` *(Bắt buộc chọn OPI PSRAM cho chip N16R8)*
4. Bấm nút **Upload** để nạp code vào mạch.
5. Mở Serial Monitor (Baudrate `115200`) để theo dõi nhật ký hoạt động.

---

## 5. THÔNG TIN QUY TRÌNH KIỂM THỬ ĐÃ NGHIỆM THU

| Kịch bản kiểm thử | Hành động | Kết quả đạt được | Trạng thái |
| :--- | :--- | :--- | :--- |
| **Mở cửa bằng PIN** | Nhập `123456#` trên Keypad | Còi kêu vui, Servo quay 90°, Relay nhảy, LED sáng 7s rồi tự đóng | **PASS** |
| **Xóa mã PIN** | Bấm `*` hoặc `C` | Đã xóa trắng bộ đệm mã PIN | **PASS** |
| **Chống bấm nhầm `#`** | Bấm `#` khi chưa gõ số | Bíp nhắc nhở, không tính là lần đoán sai mã PIN | **PASS** |
| **Nhập sai PIN 3 lần** | Nhập sai 3 lần liên tiếp | Còi báo động hú 8 tiếng, chụp ảnh kẻ gian gửi về Admin, khóa 15s | **PASS** |
| **Quét mặt qua PIR** | Đứng trước cảm biến chuyển động | Bật LED, chụp ảnh gửi AI, mở cửa 7s, nghỉ 15s không quét lặp | **PASS** |
| **Cảnh báo kẻ lạ 5 lần** | Quét mặt người lạ 5 lần qua PIR | Hú còi 6 tiếng, chụp ảnh lưu Admin Dashboard, nghỉ 30s | **PASS** |
| **Khóa khẩn cấp** | Admin bật "Khóa khẩn cấp" | Toàn bộ cửa bị vô hiệu hóa (cả PIN lẫn Face đều bị từ chối) | **PASS** |
| **Độ ổn định Camera** | Gõ Keypad liên tục và xem Kiosk | Không còn xuất hiện cảnh báo `cam_hal: FB-OVF`, hoạt động mượt mà | **PASS** |
