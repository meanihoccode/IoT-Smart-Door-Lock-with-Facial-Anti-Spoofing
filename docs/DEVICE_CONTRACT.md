# Hợp đồng thiết bị và an toàn điều khiển

Trạng thái: **v2 (REST + WebSocket) đã triển khai trong code (Core, firmware, simulator) ngày 26/10/2026; chưa nghiệm thu trên cloud thật hay phần cứng thật.** Mốc soạn yêu cầu: 25/10/2026. Đọc [DECISIONS](DECISIONS.md) trước khi đổi giao thức (D04, D05, D10 đã chốt). Các mục yêu cầu bên dưới vẫn là tiêu chí; mục [v2 đã triển khai](#v2-đã-triển-khai) mô tả API hiện hành. v1 dùng MQTT; v2 giữ nguyên schema lệnh/ACK, chỉ đổi transport.

## v2 đã triển khai

Code: `backend-core/.../controller/DeviceController.java`, `service/CommandService.java`, `service/DeviceStateService.java`, `channel/*` (WebSocket), `config/WebSocketConfig.java`, `security/Device*.java`; firmware `esp32/main/main.ino`; simulator `tools/device-simulator/`.

Theo sơ đồ IoT Level 3 của môn học: **REST** cho dữ liệu thiết bị gửi lên (request–response), **WebSocket** cho kênh điều khiển hai chiều giữ liên tục (Exclusive Pair). Cả hai qua HTTPS cổng 443, cùng một token thiết bị.

### REST qua HTTPS: thiết bị → Core (chỉ đường này tạo được lệnh mở bằng mặt/PIN)

Mọi request `/api/device/**` phải có: `Authorization: Bearer <token>` (256 bit, Core chỉ lưu SHA-256), `X-Device-Id`, `X-Request-Id` (`[A-Za-z0-9-]{8,64}`, dùng một lần — trùng → `409`), `X-Request-Time` (epoch giây, lệch quá `±120s` → `400`). Chain bảo mật riêng, stateless, không cookie/CSRF. Sai token → `401` + audit `DEVICE_AUTH_FAILED`; 20 lần sai/5 phút/IP → `429`.

| Endpoint | Nội dung | Kết quả |
| --- | --- | --- |
| `POST /api/device/face` | multipart `file` (JPEG/PNG ≤ 5 MB) | Giới hạn 30/5 phút/thiết bị; lockdown → `403` trước khi gọi AI |
| `POST /api/device/pin` | JSON `{"pinCode":"..."}` | D04: không username; 5 lần sai/5 phút/thiết bị → `429` + audit `PIN_THROTTLED`; PIN khớp > 1 hồ sơ → từ chối + `PIN_AMBIGUOUS` |
| `POST /api/device/alerts` | multipart `alertType` ∈ {`WRONG_PIN_3_TIMES`,`PIR_INTRUDER_5_TIMES`,`SECURITY_ALERT`}, `description` ≤ 200, `file` tùy chọn | `201`; ảnh chỉ admin đọc |
| `GET /api/device/state` | — | `lockdown` mong muốn, `serverTime` |

Phản hồi face/PIN: `decision` = `GRANTED`/`DENIED`, `reasonCode`, `requestId` (của thiết bị), `aiRequestId` (nếu có), và khi `GRANTED`: `commandId`, `commandStatus`. HTTP `202` = đã quyết định và đã ghi lệnh vào WebSocket của thiết bị; `503 COMMAND_FAILED` = được phép nhưng thiết bị không có socket mở hoặc ghi thất bại, cửa không mở. **Phản hồi REST không bao giờ làm firmware tự mở.**

Trình duyệt không còn `/api/verify-face`, `/api/verify-pin` (bị `denyAll`). Admin mở từ xa: `POST /api/admin/door/open` (phiên admin + CSRF, audit `REMOTE_OPEN_REQUESTED`).

### WebSocket `wss://<domain>/ws/device` (kênh lệnh, hai chiều)

Bắt tay WebSocket đi qua cùng chain bảo mật với REST: header `Authorization: Bearer <token>` + `X-Device-Id`; sai/thiếu → HTTP `401`, không nâng cấp kết nối. Có header `Origin` khác origin của server (trang web) → `403` dù token đúng; firmware không gửi `Origin`. Danh tính của socket là thiết bị đã xác thực lúc bắt tay; nội dung bản tin không đổi được danh tính. Mỗi thiết bị một socket: kết nối mới thay và đóng socket cũ. Firmware ping 15 s (2 lần mất pong → đóng và tự kết nối lại sau 5 s); Core đóng socket im lặng quá `90s`. Socket mở = `ONLINE`; socket đóng = `OFFLINE` (tương đương LWT), và lần kết nối mới coi lockdown là “chưa rõ” cho tới khi thiết bị báo lại.

Mọi bản tin là JSON text frame `v:1` có trường `kind`:

| `kind` | Hướng | Trường |
| --- | --- | --- |
| `cmd` | Core → thiết bị | `type` (`UNLOCK`/`LOCKDOWN_ON`/`LOCKDOWN_OFF`), `deviceId`, `commandId` (UUID), `seq`, `issuedAt`, `expiresAt` (epoch s), `unlockMs` |
| `ack` | thiết bị → Core | `deviceId`, `commandId`, `seq`, `ack` (`RECEIVED`/`EXECUTED`/`REJECTED`), `reason`, `eventId`, `ts` |
| `event` | thiết bị → Core | `eventId`, `type` (`BOOT`, `DOOR_LOCKED`, `PIR_MOTION`, `KEYPAD_LOCKOUT`, `FACE_FAIL_STREAK`), `commandId?`, `ts` |
| `status` | thiết bị → Core | `lockdown`, `unlocked`, `fw`, `rssi`; gửi khi kết nối và mỗi 60 s |

Core bỏ qua bản tin `cmd` do thiết bị gửi lên, payload có `deviceId` khác danh tính socket (audit `DEVICE_ID_MISMATCH`) và ACK cho command không thuộc thiết bị (audit `ACK_UNKNOWN_COMMAND`). Firmware chỉ xử lý frame `kind:"cmd"`. Không có cơ chế “retain”: lệnh chỉ được ghi vào socket đang mở tại thời điểm gửi; thiết bị offline thì lệnh `FAILED`, không xếp hàng chờ.

Tham số mặc định (cấu hình được, có kiểm tra): TTL mở `15s`, TTL lockdown `60s`, `unlockMs` `7000` (1000–15000; firmware tự kẹp ≤ 15000), ân hạn ACK `5s`, stale sau `180s`, idle WebSocket `90s`.

### Trạng thái command (bảng `door_commands`)

`PENDING → PUBLISHED → RECEIVED → EXECUTED` (`PUBLISHED` giữ tên từ v1 để tương thích dữ liệu; nghĩa là đã ghi vào WebSocket của thiết bị); nhánh `REJECTED` (thiết bị từ chối, có `statusReason`), `FAILED` (`SEND_FAILED` / `EXPIRED_BEFORE_SEND`), `UNKNOWN` (quá `expiresAt + ân hạn` mà chưa có `EXECUTED`). Chuyển trạng thái bằng compare-and-set nên ACK trùng/sai thứ tự không làm lùi trạng thái; `EXECUTED`/`REJECTED` đến muộn vẫn được ghi đè lên `UNKNOWN`. Lệnh hết hạn **không bao giờ được gửi lại**. `access_logs.status` phản chiếu `COMMAND_<STATUS>`.

Core tạo command trong transaction có khóa hàng hồ sơ + thiết bị (kiểm tra lockdown, cấp `seq`), commit rồi mới gửi qua WebSocket (ACK sớm luôn tìm thấy hàng). `seq = max(seq_trước + 1, epoch_ms)` nên đơn điệu cả khi khôi phục DB từ backup.

### Firmware chấp nhận command

1. Sai `v`/`deviceId`/thiếu trường → `REJECTED MALFORMED`. 2. `commandId` trùng lệnh cuối → chỉ gửi lại ACK cuối, không chấp hành, không gia hạn. 3. `seq ≤ lastSeq` → `REJECTED REPLAY`. 4. Lưu `lastSeq`/`lastCmd` vào NVS **trước** khi chấp hành, gửi `RECEIVED`. 5. Chưa đồng bộ NTP → `TIME_UNSYNCED`; quá `expiresAt + 5s` → `EXPIRED`; `issuedAt` vượt hiện tại > 30 s → `CLOCK_SKEW`. 6. `UNLOCK`: lockdown → `LOCKDOWN`; cửa đang mở → `ALREADY_UNLOCKED` (không gia hạn); ngược lại điều khiển relay/servo, hẹn `esp_timer` khóa lại, gửi `EXECUTED`. 7. `LOCKDOWN_*`: lưu NVS, `ON` khóa ngay, `EXECUTED`.

`EXECUTED` nghĩa là đã điều khiển chấp hành, **không** phải cảm biến xác nhận cửa mở. Lockdown sống sót qua reboot (NVS); khi thiết bị báo `status` khác `desired`, Core gửi lệnh lockdown mới (không chồng lệnh khi lệnh trước còn hiệu lực). Mất mạng/cloud: không có đường mở nào (đã bỏ master PIN), timer khóa lại chạy độc lập vòng `loop`.

## Xem trước camera (chỉ hiển thị) — đã triển khai 27/10/2026

- Admin mở khung "Camera cửa trực tiếp" → `GET /api/admin/door/live` (phiên admin); trang kiosk dùng `GET /api/kiosk/live` **công khai** theo D11. Core gửi xuống WebSocket `{"v":1,"kind":"preview","ms":15000}`, gia hạn tối đa mỗi 5 s khi admin còn xem.
- Firmware: `kind:"preview"` chỉ đặt cửa sổ xem trước (tối đa 20 s), không chấp hành gì. Trong cửa sổ đó gửi khung JPEG dạng **binary frame** ~3 hình/s, ≤ 100 KB; bỏ qua khi camera đang chụp xác thực.
- Core chỉ nhận JPEG (magic `FFD8`) từ phiên thiết bị đã xác thực, giữ khung mới nhất trong RAM, xóa khi thiết bị ngắt kết nối. Khung xem trước **không** gửi tới AI, không tạo command, không thay ảnh xác thực qua `POST /api/device/face`.
- Kiosk (`GET /api/door-status`, công khai): `serverTime`, `motionAt` (sự kiện `PIR_MOTION` gần nhất), `lastAttempt` {`method` FACE/PIN, `reasonCode`, `at`, `person` = tên đã che (D12), `commandStatus` của lệnh tương ứng}. Kiosk chỉ báo "đã mở khóa" khi `commandStatus = EXECUTED`.
- Ảnh lần quét PIR gần nhất và kết quả: `GET /api/admin/door/last-capture` (+`/image`), chỉ admin, chỉ trong RAM. `GET /api/door-status` (công khai) chỉ có `lastScan` dạng văn bản, không có ảnh.

## Nguồn ảnh và quyền tạo command — bắt buộc

- Chỉ ảnh upload từ ESP32 đã xác thực thiết bị mới được xét tạo lệnh mở bằng khuôn mặt.
- Core lấy danh tính từ credential đã kiểm chứng, không tin `deviceId` tự khai trong body. Credential chỉ có phạm vi một thiết bị; không chia sẻ cho browser.
- Ràng buộc upload với request/nonce ngắn hạn, dùng một lần; kiểm tra replay và giới hạn kích thước/tần suất trước AI. Chốt cơ chế trong schema trước triển khai.
- Liveness đánh giá nội dung ảnh; không xác nhận ảnh được chụp trực tiếp ở cửa. Device auth cũng không bảo đảm camera còn tin cậy nếu firmware/credential bị chiếm.
- Webcam kiosk chỉ phục vụ enrollment hoặc nghiệp vụ admin đã đăng nhập. Ảnh webcam không được tạo command mở khóa, kể cả khi nhận diện/liveness thành công.
- Kiểm tra phía server; endpoint enrollment không có side effect mở cửa. Có test gọi trực tiếp endpoint với ảnh/browser request.
- PIN phải có contract riêng cho keypad/kiosk, ghi tại D04. Không tự đưa khóa thiết bị vào JavaScript để giữ luồng cũ.
- Core kiểm tra hồ sơ enabled và quyền hiện tại. AI không có quyền tạo hoặc gửi command.

## Trường bản tin cần chốt

| Bản tin | Trường tối thiểu và ý nghĩa |
| --- | --- |
| Xác thực từ thiết bị | `version`, `requestId`, danh tính thiết bị đã xác thực, loại xác thực, nonce/hạn và dữ liệu; PIN/ảnh không ghi log. |
| Command | `version`, `deviceId`, `requestId`, `commandId`, loại lệnh, `issuedAt`, `expiresAt`, thời gian mở bị giới hạn. |
| ACK | `version`, `deviceId`, `commandId`, `eventId`, loại ACK nhận/thực thi/từ chối, lý do và thời gian. |
| Telemetry/event | `version`, `deviceId`, `eventId`, trạng thái/loại sự kiện, thời gian; `commandId` nếu liên quan. |

Trường định danh không thay thế xác thực. Giới hạn độ dài, payload, thời gian mở và TTL bằng cấu hình có kiểm tra. Quy định thời gian đáng tin, đồng bộ đồng hồ và hành vi khi không xác minh được hạn; mặc định từ chối lệnh không chứng minh còn hiệu lực.

## Thực thi, ACK và chống trùng — bắt buộc

1. Một request tạo tối đa một command. Firmware chỉ mở bằng command hợp lệ từ đường điều khiển đã chốt; HTTP xác thực thành công không gọi thêm `unlockDoor()`.
2. Không retain command mở khóa. Lệnh cũ khi reconnect/reboot không được thực thi.
3. Dedup bằng command ID, không dùng cửa sổ 6 giây. Chốt phạm vi bộ nhớ, giới hạn và chống replay qua reboot trước khi nghiệm thu. Không để khởi động lại xóa bảo vệ lệnh cũ.
4. Không gia hạn timer mở khi nhận command trùng. Có thể gửi lại ACK cho command đã xử lý.
5. Độ tin cậy của transport (TCP/WebSocket) không thay thế dedup; socket rớt có thể làm mất frame. Không hứa exactly-once.
6. Trạng thái mục tiêu: `PENDING -> PUBLISHED -> RECEIVED -> EXECUTED`; nhánh lỗi `FAILED/EXPIRED/UNKNOWN`. Chốt transition hợp lệ, event trùng/sai thứ tự và lưu bền vững.
7. Gửi lệnh chỉ là ghi vào kênh tới thiết bị. ACK nhận không phải ACK thực thi. Relay/servo được điều khiển không chứng minh cửa vật lý đã mở nếu không có cảm biến.
8. ACK mất/timeout phải hiện pending/unknown, không tự tạo command mới. Retry nếu có chỉ dùng cùng ID, còn hạn và có dedup.
9. Không giữ transaction/row lock chờ ACK. Khi DB/publish gián đoạn, fail an toàn và ghi trạng thái có thể giải thích. Transactional outbox là cải tiến P1, không bắt buộc cho bản tối thiểu.

## Kết nối, lockdown và an toàn — bắt buộc

- TLS xác minh CA/hostname, credential riêng cho từng thiết bị; danh tính kênh lệnh gắn với credential (không theo nội dung bản tin). Không `setInsecure()` hoặc trust-all.
- Kết nối lần đầu thất bại vẫn có retry có giới hạn/backoff; kết nối lại phải khôi phục kênh lệnh. Dùng heartbeat/ping, sự kiện đóng kết nối và mốc last-seen để phân biệt online/offline/stale.
- Persist trạng thái lockdown mong muốn; phân biệt thiết bị đã xác nhận/chưa xác nhận. Offline không được báo áp dụng thành công. Kiểm tra lockdown ngay trước actuation.
- Mất mạng/cloud/AI/DB không mở khóa hoặc bypass liveness. Bỏ master PIN mặc định; recovery offline mới ngoài phạm vi.
- Timer khóa lại không bị upload HTTP, beep hoặc reconnect blocking vô hiệu hóa. Có timeout phù hợp; xem xét watchdog theo lỗi thực tế.
- Không suy đoán relay mất điện sẽ khóa/nhả. Xác minh loại khóa, wiring và thoát hiểm trước khi đổi GPIO hoặc cực tính.
- Ảnh cảnh báo chỉ đọc theo quyền; ingest cần device auth. Chỉ giữ dữ liệu cần thiết, không log credential/PIN/embedding.

## Cải tiến tùy chọn P1

Outbox và cơ chế đối soát nâng cao, tự động xoay credential, analytics độ trễ command–ACK. Không bỏ các bảo vệ P0 để thay bằng các mục này.

Mọi thay đổi schema phải cập nhật đồng thời tài liệu, Core, firmware, simulator và test liên quan. Bằng chứng và tình trạng triển khai ghi tại [GAP_ANALYSIS](LEVEL3_GAP_ANALYSIS.md) và [ACCEPTANCE](ACCEPTANCE.md).
