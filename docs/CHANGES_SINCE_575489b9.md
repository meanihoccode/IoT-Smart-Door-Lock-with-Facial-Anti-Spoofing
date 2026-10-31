# Thay đổi từ commit `575489b9` đến bản hiện tại

Ngày lập: 28/10/2026 · Nhánh: `level3-cloud` · Mốc so sánh: `575489b9` (Merge PR #6, trước khi bắt đầu nâng cấp IoT Level 3) · Bản hiện tại: `c228d5cd`.

Tài liệu này giải thích **từng nhóm thay đổi**: trước đây hệ thống làm gì, bây giờ làm gì, vì sao đổi và nằm ở file nào. Chi tiết giao thức xem [DEVICE_CONTRACT](DEVICE_CONTRACT.md), các lựa chọn đã chốt xem [DECISIONS](DECISIONS.md), bằng chứng nghiệm thu xem [ACCEPTANCE](ACCEPTANCE.md).

## Tổng quan

| Commit | Nội dung |
| --- | --- |
| `4b32d7ae` | Chuẩn bị kiến trúc cloud Level 3: xác thực thiết bị, command có ID/TTL/ACK, bỏ đường mở cửa từ trình duyệt, Docker Compose. Giai đoạn này còn dùng MQTT TLS. |
| `084f4238` | Đổi kênh thiết bị từ MQTT sang **REST + WebSocket** theo sơ đồ IoT Level 3 của tài liệu môn học (D05). |
| `e9823c20` | Xem camera ESP32 trực tiếp trên trang admin, ảnh lần quét mặt gần nhất, Wi-Fi dự phòng, triển khai cloud không cần email ACME, smoke test chế độ cloud. |
| `c228d5cd` | Hiện camera trực tiếp công khai trên trang kiosk (D11). |

Quy mô: 94 file, +4276 / −4562 dòng. Phần bị xóa nhiều chủ yếu là thư viện PubSubClient chép tay và bản firmware trùng lặp.

### Kiến trúc trước và sau

**Trước (`575489b9`)**: mọi thứ chạy trong mạng LAN, laptop đóng vai máy chủ.

- ESP32 gọi `http://<IP laptop>:8080/api/verify-face` và `/verify-pin` bằng HTTP thường, không mã hóa, không xác thực thiết bị.
- Backend gửi lệnh `OPEN_DOOR` dạng chuỗi qua MQTT `tcp://...:1883`, không TLS, không mật khẩu, không ID lệnh và không hạn dùng.
- ESP32 mở web server cổng 80 (`/capture`, `/status`) cho ai trong LAN cũng xem được camera.
- Trang kiosk mở cửa bằng webcam laptop hoặc ảnh lấy từ `http://<IP ESP32>/capture`, và nhận PIN nhập trên web.
- Firmware có **master PIN `123456`**, cho mở cửa offline khi mất kết nối backend.

**Sau (bản hiện tại)**: đúng mô hình IoT Level 3, gồm một nút local và phần lưu trữ, phân tích, ứng dụng nằm trên cloud.

```
ESP32-S3 (camera, PIR, keypad, relay/servo)
  ├─ REST HTTPS  → https://smartlock-hung.duckdns.org/api/device/{face|pin|alerts|state}
  └─ WebSocket   ↔ wss://smartlock-hung.duckdns.org/ws/device   (lệnh xuống; ACK/sự kiện/trạng thái/khung xem trước lên)
AWS EC2 (Docker Compose): Caddy (TLS Let's Encrypt, web tĩnh) → Core Spring Boot → AI FastAPI, MySQL (mạng nội bộ)
Trình duyệt: trang admin (đăng nhập), trang kiosk (công khai, chỉ xem)
```

## 1. Kênh thiết bị: từ HTTP + MQTT trong LAN sang REST + WebSocket bảo mật

**Trước**
- ESP32 gửi ảnh và PIN tới các endpoint công khai `/api/verify-face` và `/api/verify-pin`. Trình duyệt cũng gọi được hai endpoint này.
- Lệnh xuống ESP32 là chuỗi `OPEN_DOOR` hoặc `LOCKDOWN_ON` publish QoS 1 lên topic `iot/lock/commands`. Sự kiện đi lên qua `iot/lock/events`.

**Sau**
- **REST (thiết bị gửi lên)** qua `DeviceController`:
  - `POST /api/device/face`: gửi ảnh nhận diện.
  - `POST /api/device/pin`: gửi PIN nhập từ keypad.
  - `POST /api/device/alerts`: gửi cảnh báo kèm ảnh.
  - `GET /api/device/state`: lấy trạng thái lockdown và giờ máy chủ khi khởi động.
- **WebSocket `/ws/device`**: một kết nối giữ liên tục.
  - Core gửi lệnh `kind:"cmd"` xuống.
  - Thiết bị gửi lên `ack`, `event`, `status` (JSON có `v:1`) và khung ảnh xem trước dạng nhị phân.
- Tách thành ba thành phần:
  - `DeviceChannel` giữ phiên kết nối của mỗi thiết bị. Thiết bị kết nối lại thì phiên cũ bị đóng.
  - `DeviceSocketHandler` xử lý lúc kết nối, ngắt kết nối và lúc nhận dữ liệu.
  - `DeviceMessageHandler` kiểm tra kích thước (≤ 2048), phiên bản và `kind`. Nếu `deviceId` trong nội dung khác danh tính đã xác thực, Core ghi audit `DEVICE_ID_MISMATCH`.
- Thiết bị **không** tự tạo được lệnh: Core bỏ qua mọi bản tin `kind:"cmd"` gửi từ thiết bị lên.
- Socket đóng thì Core coi thiết bị offline, không bao giờ suy ra rằng lockdown đã được áp dụng.

**Lý do**: tài liệu môn học (trang 43 và 47) vẽ kết nối nút local với cloud là "REST/WebSocket". Người dùng chọn phương án này ở D05. Bỏ broker còn giúp giảm một dịch vụ phải vận hành và bảo mật.

**File**
- Mới: `channel/DeviceChannel.java`, `channel/DeviceSocketHandler.java`, `channel/DeviceMessageHandler.java`, `config/WebSocketConfig.java`, `controller/DeviceController.java`.
- Xóa: `config/MqttConfig.java`, `mqtt/MqttPublisher.java`, `mqtt/MqttSubscriber.java`, `MqttPublisherTests.java`.
- Sửa: `build.gradle` (thay Paho bằng `spring-boot-starter-websocket`), `application.properties`.

## 2. Xác thực thiết bị và chống phát lại request

**Trước**: không có. Ai gọi được endpoint thì gửi được ảnh hoặc PIN.

**Sau**
- **`DeviceAuthFilter`**:
  - Đọc `Authorization: Bearer <token>` và `X-Device-Id`.
  - So sánh SHA-256 của token với hash lưu trong bảng `devices`. Máy chủ chỉ lưu hash, không lưu token gốc.
  - Áp dụng cho cả `/api/device/**` lẫn bước bắt tay WebSocket.
- **Từ chối trình duyệt**: bắt tay WebSocket có header `Origin` bị trả **403**. ESP32 không gửi Origin, còn trang web thì luôn gửi, nên dù token bị lộ cũng không dùng được từ một tab trình duyệt.
- **`DeviceRequestGuard`**:
  - Mỗi request phải có `X-Request-Id` dùng một lần. Gửi trùng ID bị trả **409** (lưu ở bảng `device_requests`).
  - Phải có `X-Request-Time` lệch không quá ±120 giây so với giờ máy chủ.
- **`DeviceBootstrap`**: lúc khởi động, tạo hoặc cập nhật bản ghi thiết bị từ `SMARTLOCK_DEVICE_ID` và `SMARTLOCK_DEVICE_TOKEN_SHA256`.
- **`DeviceTokens`**: tiện ích băm và so sánh token trong thời gian không đổi.
- **`SecurityConfig`** tách thành hai chuỗi bảo mật:
  - Chuỗi thiết bị: stateless, không CSRF, chỉ có vai trò `DEVICE`.
  - Chuỗi admin: phiên đăng nhập, có CSRF.
- Các endpoint trước đây công khai nay chỉ cho admin: `/api/security-alerts` (POST) và ảnh cảnh báo.
- Công khai còn lại: `/api/door-status` và `/api/kiosk/live`.
- Script `deploy/scripts/new-device-credentials.sh` sinh token 256 bit kèm hash của nó.

**File**: `security/DeviceAuthFilter.java`, `DeviceBootstrap.java`, `DevicePrincipal.java`, `DeviceRequestGuard.java`, `DeviceTokens.java`, `config/SecurityConfig.java`, `entity/Device.java`, `entity/DeviceRequest.java`, `repository/DeviceRepository.java`, `repository/DeviceRequestRepository.java`.

## 3. Lệnh điều khiển có ID, hạn dùng, ACK và chống thực thi trùng

**Trước**: lệnh là chuỗi `OPEN_DOOR`. Publish xong là coi như cửa đã mở. Không có cách biết thiết bị đã nhận hay đã làm.

**Sau** (bảng `door_commands`, xử lý trong `CommandService`):

**Cấu trúc lệnh**
- Mỗi lệnh có `commandId` (UUID) và `seq = max(lastSeq + 1, epoch_ms)`, tăng dần theo từng thiết bị.
- Có thời điểm phát `issuedAt` và hạn dùng `expiresAt`: lệnh mở cửa hết hạn sau 15 giây, lệnh lockdown sau 60 giây.
- Thời gian mở `unlockMs` mặc định 7000 ms.

**Vòng đời lệnh**
- Các trạng thái: `PENDING → PUBLISHED → RECEIVED → EXECUTED`. Ngoài ra có `REJECTED`, `FAILED` và `UNKNOWN`.
- `PUBLISHED` chỉ có nghĩa là lệnh đã được ghi vào WebSocket của thiết bị.
- `EXECUTED` nghĩa là thiết bị đã điều khiển relay hoặc servo. Hệ thống **không có cảm biến cửa**, nên không bao giờ khẳng định cửa đã mở.

**Chống thực thi trùng**
- Chuyển trạng thái theo kiểu compare-and-set nên không ghi đè nhầm.
- Lệnh hết hạn **không bao giờ được gửi lại**.
- Tác vụ định kỳ (`@Scheduled`, `@EnableScheduling`) chuyển lệnh quá hạn mà chưa có ACK thực thi sang `UNKNOWN`.
- Khóa hàng của thiết bị giữ thứ tự cấp `seq` và kiểm tra lockdown trong cùng giao dịch.

**Một đường thực thi duy nhất (D10)**: phản hồi REST chỉ trả quyết định kèm `commandId`. Cửa chỉ mở khi firmware nhận được lệnh hợp lệ qua WebSocket.

**Lockdown**
- Trạng thái mong muốn (`desiredLockdown`) được lưu vào DB trước.
- Trạng thái đã áp dụng (`lockdownApplied`) chỉ thành true khi thiết bị gửi ACK `EXECUTED`.
- Khi thiết bị kết nối lại, Core đồng bộ lại lockdown nếu hai bên lệch nhau (`DeviceStateService`).
- `DoorStateService` cũ, vốn giữ trạng thái trong RAM, đã bị xóa.

**Nhật ký**: `access_logs` có thêm cột `device_id`, `command_id`, `request_id`. Trạng thái lệnh được đồng bộ vào nhật ký truy cập.

**File**
- Mới: `service/CommandService.java`, `service/DeviceStateService.java`, `entity/DoorCommand.java`, `repository/DoorCommandRepository.java`, `controller/DoorController.java` (`/api/admin/door`, `/api/admin/door/open`, `/api/lockdown/*`, `/api/door-status`), `controller/AccessResponses.java`.
- Sửa: `entity/AccessLog.java`, `repository/AccessLogRepository.java`.
- Xóa: `service/DoorStateService.java`.

## 4. Quy tắc mở cửa: nguồn ảnh, PIN và mở từ xa

**Trước**
- Trình duyệt kiosk gửi ảnh từ webcam laptop tới `/api/verify-face` để mở cửa.
- PIN nhập được trên web, có thể kèm mã hồ sơ.
- Firmware có master PIN `123456` và cho mở cửa offline.

**Sau**

**Khuôn mặt (D02)**
- Chỉ ảnh từ **ESP32 đã xác thực** (`/api/device/face`) mới tạo được lệnh mở.
- Webcam laptop chỉ dùng để **đăng ký** khuôn mặt (admin đã đăng nhập).
- Endpoint `/api/verify-face` và `/api/verify-pin` đã bị bỏ khỏi `ApiController`. Test xác nhận trình duyệt có CSRF hợp lệ vẫn không mở được cửa.

**AI không có quyền mở cửa**
- `DoorAccessService.face` chỉ chấp nhận khi AI trả `FACE_VERIFIED` và không bị đánh dấu giả mạo.
- Sau đó Core vẫn kiểm tra hồ sơ dưới khóa hàng: phải đang bật và đã có khuôn mặt đăng ký.

**PIN (D04)**
- Chỉ nhập trên keypad, không kèm username.
- Core quét các hồ sơ đang bật. Sai tối đa 5 lần trong 5 phút cho mỗi thiết bị.
- Một PIN trùng với nhiều hồ sơ thì bị từ chối và ghi audit `PIN_AMBIGUOUS`.

**Mở từ xa**: admin đăng nhập bấm `POST /api/admin/door/open`. Mỗi lần bấm tạo đúng một lệnh, có audit `REMOTE_OPEN_REQUESTED`, không tự thử lại.

**Bỏ master PIN và chế độ mở offline (D09)**: mất mạng hoặc AI lỗi thì cửa giữ nguyên khóa. Timer khóa lại vẫn hoạt động.

**File**
- `service/DoorAccessService.java`, `controller/ApiController.java`, `controller/SecurityAlertController.java` (phần nhận cảnh báo chuyển sang `DeviceController`).
- `service/AlertImageStore.java`: lưu ảnh cảnh báo trên volume bền vững, chỉ admin xem.
- `service/FaceGateway.java`: URL của AI lấy từ cấu hình `SMARTLOCK_AI_URL` thay vì cố định `localhost`; `validateImage` dùng chung.
- `config/HttpClientConfig.java`: timeout gọi AI có thể cấu hình.
- `config/ClockConfig.java`: dùng `Clock` để test được thời gian.

## 5. Firmware ESP32-S3 (`esp32/main/main.ino`)

**Đã bỏ**
- Web server cổng 80 (`/`, `/capture`, `/status`).
- MQTT/PubSubClient, kèm bốn file thư viện chép tay `PubSubClient.*`.
- Bản firmware trùng `esp32/main.ino` ở thư mục gốc.
- Master PIN và chế độ mở offline.
- URL `http://` tới laptop.

**Mới hoặc thay đổi**

*Kết nối và cấu hình*
- Cấu hình bí mật nằm trong `config.h`, git bỏ qua. Mẫu điền nằm ở `config.example.h`. Gồm:
  - `CLOUD_HOST`, `CLOUD_PORT`, `DEVICE_ID`, `DEVICE_TOKEN`, `NTP_SERVER`
  - `ROOT_CA_PEM`: ISRG Root X1 và X2 của Let's Encrypt
  - `WIFI_SSID_2`/`WIFI_PASSWORD_2` cho mạng dự phòng
- REST qua HTTPS có kiểm chứng chứng chỉ, gửi kèm các header `Authorization`, `X-Device-Id`, `X-Request-Id`, `X-Request-Time`.
- WebSocket dùng thư viện **WebSockets (Markus Sattler) 2.7.2**:
  - Kết nối bằng `beginSslWithCA`.
  - Ping mỗi 15 giây; tự kết nối lại sau 5 giây.
  - Không có CA thì firmware từ chối kết nối, không bao giờ dùng `setInsecure()`.
- Phải đồng bộ giờ NTP trước khi kết nối, vì TLS và TTL của lệnh đều cần giờ đúng.

*Xử lý lệnh*
- `handleCommand` kiểm tra `v`, `deviceId`, `commandId`, `seq`, `issuedAt`, `expiresAt` trước khi làm gì.
- `seq` và `commandId` được ghi vào NVS **trước** khi chấp hành, nên khởi động lại giữa chừng cũng không chạy lại lệnh cũ.
- Lệnh trùng chỉ được gửi lại ACK cũ, không chấp hành lần nữa.
- Từ chối với lý do cụ thể: `REPLAY`, `EXPIRED`, `CLOCK_SKEW`, `TIME_UNSYNCED`, `MALFORMED`, `UNKNOWN_TYPE`.
- ACK theo thứ tự `RECEIVED` rồi `EXECUTED` hoặc `REJECTED`. Chỉ lưu ACK cuối của lệnh đã chấp nhận (sửa lỗi ACK `REPLAY` ghi đè ACK thật).

*An toàn khi mở và khóa*
- Khóa lại bằng `esp_timer`, độc lập với vòng lặp chính. Ngoài ra có thêm lớp dự phòng trong `loop()`.
- Trạng thái lockdown lưu trong NVS và áp dụng lại khi khởi động.
- Sau khi được chấp thuận, nếu không nhận được lệnh mở đúng hạn thì cửa giữ nguyên khóa.

*Wi-Fi dự phòng*: thử lần lượt mạng chính rồi mạng phụ, mỗi mạng 10 giây lúc khởi động. Khi mất mạng thì cứ 8 giây đổi sang mạng kế tiếp.

*Xem trước camera* (commit `e9823c20`):
- Nhận bản tin `kind:"preview"` thì mở cửa sổ xem trước tối đa 20 giây.
- Trong cửa sổ đó gửi khoảng 3 khung JPEG mỗi giây dạng binary qua WebSocket, mỗi khung không quá 100 KB.
- Bỏ qua khi camera đang bận chụp ảnh xác thực.

*Không đổi*: GPIO, cực tính relay, góc servo, cấu hình camera OV2640, logic PIR và keypad.

**Kiểm tra trên phần cứng thật**
- Board ESP32-S3 rev v0.2, PSRAM 8 MB, flash 16 MB, chip USB CH343.
- FQBN: `esp32:esp32:esp32s3:FlashMode=qio,FlashSize=16M,PSRAM=opi,PartitionScheme=app3M_fat9M_16MB`.
- Đã sao lưu toàn bộ flash cũ trước khi nạp. File backup nằm ngoài repo.

**File**: `esp32/main/main.ino`, `esp32/config.example.h`, `scripts/Compile-Esp32.ps1` (bắt buộc `-Fqbn`, chỉ biên dịch, không nạp).

## 6. Giao diện web

**Trang kiosk (`Kiosk.jsx`)**
- **Trước**:
  - Chọn nguồn camera "ESP32" (lấy hình từ IP trong LAN) hoặc "Webcam Laptop".
  - Chụp ảnh và gửi đi để mở cửa; có ô nhập mã hồ sơ + PIN.
  - Lưu IP của ESP32 trong `localStorage`.
- **Sau**: trang chỉ để xem, không có đường mở cửa nào. Hiển thị:
  - Trạng thái kết nối của thiết bị và trạng thái lockdown.
  - Lệnh gần nhất.
  - Kết quả lần quét mặt gần nhất, dạng chữ, không có ảnh.
  - **Camera cửa trực tiếp**: công khai theo D11, chỉ hiển thị.
- Đã xóa `kiosk-feedback.js` và `pin-credentials.js` cùng test của chúng.

**Trang admin**
- `DoorPanel.jsx` (mới):
  - Kết nối và lockdown, phân biệt "đã lưu" với "thiết bị đã xác nhận".
  - Nút **Mở cửa từ xa** có hỏi xác nhận.
  - Danh sách lệnh gần đây kèm trạng thái.
  - Camera trực tiếp.
  - Ảnh lần quét PIR gần nhất kèm mã kết quả của AI.
- `LiveCamera.jsx` (mới):
  - Lấy khung hình mỗi 350 ms và dừng khi tab bị ẩn.
  - Có nút bật/tắt; dùng chung cho admin (`/api/admin/door/live`) và kiosk (`/api/kiosk/live`).
- `door-status.js` (mới): nhãn tiếng Việt cho trạng thái kết nối, lệnh, phương thức, lockdown và nhật ký truy cập. Có test riêng.
- `Overview.jsx`: thêm `DoorPanel`, nhãn nhật ký lấy từ `door-status.js`.
- `SecurityAlerts.jsx`: hiển thị lockdown đã được áp dụng hay đang chờ.
- `api.js`: bỏ các ngoại lệ cho `/verify-pin` và `/verify-face`.

## 7. Xem camera và ảnh quét gần nhất (commit `e9823c20`, `c228d5cd`)

**Ảnh lần quét PIR gần nhất**
- `LastCaptureStore` giữ ảnh `/api/device/face` mới nhất của mỗi thiết bị, chỉ trong RAM, kèm mã kết quả, thông điệp, thời gian và `commandId`.
- Admin xem qua `GET /api/admin/door/last-capture` và `/image`.
- `GET /api/door-status` công khai chỉ có `lastScan` dạng chữ.

**Camera trực tiếp**
- Có người xem thì `LivePreview` gửi `{"kind":"preview","ms":15000}` xuống thiết bị, tối đa mỗi 5 giây một lần.
- Core chỉ nhận dữ liệu JPEG (bắt đầu bằng `FFD8`, không quá 100 KB) từ phiên thiết bị đã xác thực.
- Chỉ trả khung hình nếu nó mới trong vòng 3 giây. Thiết bị ngắt kết nối thì khung hình bị xóa.
- `WebSocketConfig` nâng bộ đệm nhị phân lên 100 KB.

**Ranh giới an toàn**: khung xem trước **không** gửi tới AI, không tạo lệnh và không thay thế ảnh xác thực. Mở cửa bằng mặt vẫn chỉ qua PIR → `/api/device/face`.

**D11**: người dùng chọn hiện camera trực tiếp công khai trên kiosk, sau khi được nêu rủi ro: ai có URL cũng xem được hình trước cửa và tốn băng thông. Phương án thay thế là kiosk ghép nối bằng cookie riêng, chưa làm.

## 8. Triển khai cloud (`deploy/`) và đóng gói

**Container**
- Thêm `Dockerfile` và `.dockerignore` cho Core (Java), AI (Python) và frontend (build tĩnh).
- AI: gỡ `opencv-python` bản có giao diện mà insightface kéo vào, cài `opencv-python-headless` 5.0.0.93 để tránh lỗi thiếu `libxcb`.
- `requirements.lock.txt` khóa phiên bản thư viện.
- `model_runtime.py` đọc model từ thư mục gắn sẵn `SMARTLOCK_INSIGHTFACE_ROOT`, không tải về lúc chạy.

**`docker-compose.yml`**
- Bốn dịch vụ: `db` (mysql:8.4), `ai`, `core`, `web` (Caddy 2.8).
- DB và AI nằm trong mạng nội bộ; chỉ mở cổng 80 và 443.
- Volume bền vững cho DB và ảnh cảnh báo.
- Cookie `Secure` và `forward-headers` cho proxy.

**`Caddyfile`**
- `/api/*` và `/ws/*` chuyển tới Core; phần còn lại là web tĩnh.
- Chứng chỉ Let's Encrypt tự động, **không dùng email ACME** theo lựa chọn của người dùng.

**Cấu hình và vận hành**
- `.env.example`: biến bí mật (mật khẩu DB, hash token thiết bị, admin ban đầu). File `.env` thật bị git bỏ qua.
- `scripts/backup.sh`: sao lưu MySQL. `scripts/new-device-credentials.sh`: sinh token thiết bị.
- `README.md` là runbook gồm: dựng VM, khởi động, đổi token, cập nhật, khôi phục, quyền thư mục model (`chmod -R a+rX`).

**Staging trên máy** (`deploy/local/`)
- Dùng CA thử nghiệm, cổng 8443, script `setup-local.sh` (xử lý ký tự CR của Windows).
- `smoke_test.py` kiểm thử đầu-cuối, chạy được ở chế độ local và cloud (`SMOKE_CLOUD`).

**Cloud thực tế (D06)**
- AWS EC2 `c7i-flex.large`, region Sydney, Ubuntu 24.04, Elastic IP.
- Tên miền `smartlock-hung.duckdns.org`.

**Bộ giả lập thiết bị**: `tools/device-simulator/device_simulator.py` đóng vai ESP32: gửi PIN và ảnh, nghe lệnh, gửi ACK, không gửi Origin. Có 9 test cho phần xử lý lệnh.

**Cấu hình repo**
- `.gitattributes`: script và file deploy dùng xuống dòng LF.
- `.gitignore`: bỏ qua `deploy/.env`, backup, chứng chỉ và bí mật staging, `config.h`.

## 9. Tài liệu

- `docs/LEVEL3_GAP_ANALYSIS.md`: đánh giá khoảng trống so với Level 3, trạng thái từng mục kèm bằng chứng.
- `docs/DEVICE_CONTRACT.md`: hợp đồng v2 (REST + WebSocket), định dạng lệnh, ACK, sự kiện, hành vi khi lỗi, xem trước camera.
- `docs/DECISIONS.md`: D01–D11 kèm nguồn và trạng thái.
- `docs/ACCEPTANCE.md`: tiêu chí tối thiểu và bằng chứng nghiệm thu.
- `README.md`: cập nhật kiến trúc và cách chạy.

## 10. Kiểm thử và bằng chứng

| Hạng mục | Kết quả | Mức |
| --- | --- | --- |
| Core (`gradlew test`) | 124/124 đạt, gồm 7 test WebSocket thật (bắt tay 401/403, REST → lệnh → ACK, thiết bị không tự phát lệnh được, kết nối lại, đồng bộ lockdown, xem trước) | Giả lập |
| Frontend (`npm test`, `lint`, `build`) | 10/10 đạt; lint chỉ có cảnh báo, không có lỗi; build thành công | Giả lập |
| Simulator | 9/9 test xử lý lệnh | Giả lập |
| Smoke test staging trên máy | 28/28 | Staging với simulator |
| Smoke test cloud AWS | 29/29 (trước commit `e9823c20`, chưa chạy lại cho bản mới nhất) | Cloud staging với simulator |
| Firmware | Biên dịch thành công 1.216.318 byte (38%) với FQBN ở mục 5 | Biên dịch |
| Phần cứng thật | ESP32 kết nối `wss` lên cloud (ONLINE); mở từ xa: relay chạy; PIR → ảnh → AI cloud; có lần mở bằng khuôn mặt đạt `EXECUTED` | Cloud với phần cứng thật |

Test bị đổi tên hoặc xóa:
- `ApiControllerFaceVerificationTests` đổi thành `DeviceFaceDecisionTests`, vì quyết định khuôn mặt giờ chỉ đi qua thiết bị.
- `MqttPublisherTests` bị xóa cùng với MQTT.

## 11. Giới hạn còn lại

- Camera trực tiếp và ảnh quét gần nhất chỉ nằm trong RAM: khởi động lại Core là mất, và chỉ hỗ trợ một nút.
- Camera trực tiếp trên kiosk là công khai (D11), nên ai có URL đều xem được.
- Firmware có phần xem trước (1024 dòng) phải được nạp lại lên board thì mới có hình. Việc này đã có thông báo cho người dùng.
- Chưa có cảm biến cửa: `EXECUTED` chỉ xác nhận thiết bị đã điều khiển khóa.
- Cloud đang dùng một VM; chưa có HA, outbox, xoay vòng credential tự động hay framework migration (thuộc nhóm P1 hoặc ngoài phạm vi).
- Giảng viên chưa xác nhận trực tiếp việc chọn REST + WebSocket (D05).
- Số liệu độ chính xác AI chưa được đo lại trên ảnh từ camera ESP32.
