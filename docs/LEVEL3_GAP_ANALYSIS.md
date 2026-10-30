# Khảo sát và kế hoạch IoT Level 3

Mốc khảo sát mã nguồn: **25/10/2026**. Chưa xác nhận triển khai cloud hay phần cứng thực. Báo cáo này là ảnh chụp tại thời điểm khảo sát; đối chiếu code trước khi dùng. Các phase phần mềm không phải cấp độ IoT.

## Quy tắc cập nhật

Mỗi gap có ID ổn định. Trạng thái: **Chưa xử lý**, **Đang làm**, **Đã sửa, chờ xác minh**, **Đã xác minh**. Khi sửa, cập nhật ngay dòng tương ứng với file và bằng chứng. Không xóa phát hiện cũ để che lịch sử; ghi ngày kiểm tra lại nếu hành vi thay đổi. “Đã xác minh” phải chỉ rõ mức giả lập/staging/phần cứng.

**P0** là bắt buộc cho mục tiêu Level 3 và an toàn dự án. **P1** là cải tiến nên có, không chặn nghiệm thu tối thiểu. Phần P1 của một dòng hỗn hợp không được biến thành điều kiện hoàn thành P0.

## Thành phần và luồng khảo sát

| Thành phần | Điểm đọc mã |
| --- | --- |
| Java Core | `backend-core/build.gradle`, `src/main/resources/application.properties`; controller/service/mqtt/security dưới `src/main/java/com/example/btl_iot`. |
| Python AI | `backend-ai/main.py`, `face_pipeline.py`, `model_runtime.py`, `settings.py`, `configs/baseline.json`. |
| React | `frontend/src/Kiosk.jsx`, `api.js`, `KioskGate.jsx`, `vite.config.js`, `package.json`. |
| ESP32 | `esp32/main.ino` (đã gỡ 26/10/2026), `esp32/main/main.ino`, `scripts/Compile-Esp32.ps1`, `esp32/config.example.h`. |
| Tests | `backend-core/src/test`, `backend-ai/tests`, `frontend/test`. |

Kiosk lấy ảnh webcam hoặc HTTP camera LAN; ESP32 cũng tự chụp theo PIR/chuông. Core nhận ảnh, gọi AI, tái kiểm tra hồ sơ rồi gửi MQTT `OPEN_DOOR`. Firmware điều khiển relay/servo, phát `DOOR_UNLOCKED:<method>`. PIN qua Core hoặc đường master PIN local. Cảnh báo có ảnh được lưu trên filesystem Core.

Tại mốc khảo sát, hai file `main.ino` có cùng SHA-256; script compile lấy bản ở `esp32/main.ino`. Đây là phát hiện lịch sử, không phải cam kết chúng luôn đồng nhất. Ngày 26/10/2026: bản sao ở gốc `esp32/` (`main.ino`, `PubSubClient.*`) đã được gỡ; nguồn chuẩn là sketch `esp32/main/` và script compile lấy từ đó.

## Danh sách khoảng trống

Cập nhật 26/10/2026 sau đợt triển khai P0 trong code. Quy ước bằng chứng: **giả lập** = test tự động chạy trên máy dev (H2, MQTT giả lập bằng mock publisher + gọi trực tiếp handler inbound, simulator thuần). Cập nhật cùng ngày: đã có bằng chứng **staging local với simulator** (stack Compose chạy trên máy dev bằng overlay `deploy/local/`, CA thử nghiệm). Đây chưa phải **cloud staging** vì chưa có VM/domain (D06). Chưa có bằng chứng **phần cứng thật**: firmware đã compile nhưng chưa flash. Lệnh và số test thực chạy ghi ở [ACCEPTANCE](ACCEPTANCE.md#sổ-bằng-chứng).

| ID | Mức | Phát hiện và tác động | Hướng xử lý | Trạng thái / file thay đổi / bằng chứng |
| --- | --- | --- | --- | --- |
| G01 | P0 | `FaceGateway.java` cố định `http://localhost:8000/api/`; tách container sẽ lỗi. | Cấu hình AI URL, timeout và mạng nội bộ. | **Đã xác minh (giả lập)** — `smartlock.ai.base-url`/timeout trong `FaceGateway`, `HttpClientConfig`, `application.properties`; compose dùng `http://ai:8000/api/` trên mạng internal. Test `FaceGatewayTests.aiBaseUrlIsConfigurableForPrivateContainerNetwork`. |
| G02 | P0 | Firmware ghép API HTTP từ `MQTT_SERVER`, cổng 8080. | Tách API URL/broker; HTTPS xác minh CA/hostname. | **Đã sửa, chờ xác minh** — `API_BASE_URL`, `MQTT_HOST/PORT`, `ROOT_CA_PEM` trong `esp32/config.example.h`; `WiFiClientSecure.setCACert` cho cả HTTPS và MQTT, từ chối kết nối khi thiếu CA, không `setInsecure`. Firmware **chưa compile** (thiếu Arduino CLI). Cập nhật 26/10/2026: firmware compile sạch (xem G15); HTTPS kiểm chứng CA phía server đã chạy ở staging local với simulator. Phần TLS trên ESP32 thật vẫn chờ phần cứng. |
| G03 | P0 + P1 | `MqttConfig` thiếu auth/TLS; firmware dùng `WiFiClient`. | P0: TLS, danh tính thiết bị, ACL. P1: tự động xoay credential. | P0 **đã sửa, chờ xác minh staging** — `MqttConfig` (user/pass, CA tùy chọn, hostname verification), `deploy/mosquitto/config/{mosquitto.conf,acl}` (không anonymous, TLS 8883, ACL theo `%u`), firmware TLS + user/pass. Token HTTP thiết bị: `DeviceAuthFilter`, chỉ lưu SHA-256 (`BtlIotApplicationTests.deviceAuthRequiresMatchingTokenAndIsRateLimited`). P1 tự động xoay: chưa làm; xoay thủ công trong `deploy/README.md`. **Staging local (simulator), 26/10/2026:** broker từ chối anonymous và sai mật khẩu, TLS bị từ chối khi không tin CA, ACL chặn thiết bị tự publish lệnh (`deploy/local/smoke_test.py`). **Cập nhật D05 (REST + WebSocket):** bỏ broker/ACL; danh tính kênh lệnh là token thiết bị kiểm ở bắt tay `wss://<domain>/ws/device` (cùng `DeviceAuthFilter`), Core bỏ qua `cmd` do thiết bị gửi. Bằng chứng giả lập: `DeviceWebSocketTests.handshakeWithoutValidDeviceTokenIsRejected`, `deviceCannotIssueCommandsOverItsSocket`. Bằng chứng staging MQTT ở trên là lịch sử. **Staging local (simulator), 27/10/2026:** bắt tay wss thiếu/sai token 401, Origin trình duyệt 403 dù có token, CA không tin cậy bị từ chối, thiết bị không tự phát lệnh được (smoke 28/28). |
| G04 | P0 | Subscribe tại `@PostConstruct`, clean session; boot khi broker lỗi có thể bỏ subscribe. | Retry kết nối đầu, reconnect/resubscribe và test broker restart. | **Đã xác minh (giả lập, unit)** — `MqttConnectionManager` (SmartLifecycle, backoff 1→30 s, subscribe lại ở mọi `connectComplete`); `MqttConnectionManagerTests` (5 test). **Staging local (simulator), 26/10/2026:** restart Mosquitto → thiết bị và Core kết nối lại, Core resubscribe, luồng lệnh + ACK `EXECUTED` chạy lại; Core khởi động trước khi có ACK vẫn kết nối nền. **Cập nhật D05:** `MqttConnectionManager` được thay bằng `channel/DeviceSocketHandler` + `DeviceChannel`: firmware tự kết nối lại (5 s, ping 15 s), kết nối mới thay socket cũ, đóng socket → OFFLINE. Bằng chứng giả lập: `DeviceWebSocketTests.reconnectReplacesOldSocketAndCloseMeansOffline`. **Staging local (simulator), 27/10/2026:** restart Core → thiết bị tự kết nối lại WebSocket, luồng lệnh + ACK `EXECUTED` chạy lại; đóng socket → OFFLINE. |
| G05 | P0 | `DoorAccessService.open()` ghi SUCCESS/unlocked ngay sau publish. | Command ID/TTL/ACK; trạng thái bền vững và unknown khi chưa xác nhận. | **Đã xác minh (giả lập)** — bảng `door_commands`, `CommandService` (CAS transition, quét hết hạn → `UNKNOWN`, không gửi lại), `access_logs.status = COMMAND_*`; UI nhãn trung thực (`frontend/src/door-status.js`). Test `ackLifecycleIsMonotonicAndBoundToTheDeviceTopic`, `missingAckBecomesUnknownAndIsNeverResent`, `brokerFailureNeverReportsDoorOpened`, `door-status.test.js`. |
| G06 | P0 | Firmware gọi `unlockDoor()` sau HTTP thành công, Core đồng thời publish MQTT. | Một đường thực thi; dedup end-to-end, không gia hạn do lệnh trùng. | **Core đã xác minh (giả lập); firmware đã sửa, chờ xác minh** — D10 MQTT-only; HTTP chỉ trả `decision` + `commandId`; firmware chỉ chấp hành trong `handleCommand`, không gia hạn khi đang mở. Logic chấp nhận được kiểm bằng `tools/device-simulator/test_command_processor.py` (8 test, cùng quy tắc với firmware). **Cập nhật D05:** đường thực thi duy nhất giờ là frame `kind:"cmd"` qua WebSocket (`DeviceWebSocketTests.restDecisionCommandOverWebSocketAndAckLifecycle`, simulator 9 test). |
| G07 | P0 | Subscriber chống trùng bằng 6 giây; trạng thái/lockdown trong RAM. | Tương quan bằng ID, persist, đồng bộ lại sau restart. | **Core đã xác minh (giả lập); firmware chờ xác minh** — tương quan bằng `commandId`, `seq` đơn điệu; lockdown mong muốn/đã báo cáo lưu ở bảng `devices`; resync khi thiết bị báo status khác (`reconnectingDeviceWithStaleLockdownGetsResynced`, `lockdownIsPersistedBlocksUnlockAndIsAppliedOnlyAfterDeviceAck`). Firmware lưu `lastSeq/lastCmd/lastAck/lockdown` vào NVS. Bỏ `MqttSubscriber`/`DoorStateService` cũ. Giới hạn: bộ chống trùng event và rate limit vẫn trong RAM của một instance. |
| G08 | P0 | Master PIN cố định; log mật khẩu Wi-Fi và từng phím PIN. | Bỏ fallback mặc định và log bí mật; recovery mới ngoài phạm vi. | **Đã sửa, chờ xác minh** — xóa `MASTER_PIN` và nhánh offline; không in mật khẩu Wi-Fi, PIN, phím số, token. Mất mạng/cloud → báo lỗi, không mở. Chờ compile + kiểm tra log trên phần cứng. |
| G09 | P0 | Nhánh PIN không username quét hash mọi hồ sơ, khác tài liệu cũ. | Chốt contract keypad/kiosk và giới hạn thử nhất quán; không đổi âm thầm. | **Đã xác minh (giả lập)** — D04 chốt 26/10/2026. `/api/device/pin` không username, giới hạn 5 lần sai/5 phút/thiết bị, từ chối PIN khớp nhiều hồ sơ; bỏ `/api/verify-pin` web. Test `pinFailuresAreGenericAndLimitedPerDevice`, `pinSharedByTwoProfilesIsRefusedAndAudited`, `devicePinWithoutUsernameCreatesExactlyOneCommandAndNeverClaimsDoorOpened`. |
| G10 | P0 | Kiosk fetch HTTP camera LAN; Vite proxy chỉ dùng dev. | ESP32 upload HTTPS, frontend static/cloud; bỏ phụ thuộc LAN. | **Đã sửa, chờ xác minh staging** — Kiosk chỉ đọc `/api/door-status`; gỡ web server cổng 80 `/capture` trên ESP32; frontend build tĩnh phục vụ bởi Caddy cùng origin (`frontend/Dockerfile`, `deploy/Caddyfile`). `npm run build` thành công. **Staging local:** React tĩnh và API phục vụ cùng origin qua Caddy HTTPS (`https://localhost:8443`). Chưa có domain public. |
| G11 | P0 | POST cảnh báo và GET ảnh anonymous trong `SecurityConfig`. | Ingest có device auth; đọc ảnh theo quyền, giữ CSRF admin. | **Đã xác minh (giả lập)** — ingest chuyển sang `/api/device/alerts` (token thiết bị, whitelist loại, giới hạn tần suất); ảnh và `/api/lockdown/status` chỉ admin; tên file ảnh kiểm tra theo UUID. Test `alertsNeedDeviceAuthAndPhotosAreAdminOnly`, `anonymousCannotReadAdminDataOrRegister`. |
| G12 | P0 + P1 | Ảnh ở `uploads/alerts`, DB mặc định local, `ddl-auto=update`, JDBC `useSSL=false`. | P0: cloud storage/volume bền vững, DB private, backup trước thay schema. P1: migration framework và kiểm tra restore. | P0 **đã sửa, chờ xác minh staging** — `SMARTLOCK_ALERTS_DIR` + volume `alerts`, volume `db-data`, DB trong mạng internal, `sslMode` cấu hình được (mặc định `PREFERRED`), `SMARTLOCK_DDL_AUTO`, `deploy/scripts/backup.sh` (dump + ảnh). Thay đổi schema chỉ thêm bảng/cột. **Staging local:** restart container Core giữ nguyên hồ sơ và lịch sử lệnh (volume `db-data`). Chưa thử `backup.sh` và chưa thử restore. P1 (migration framework, kiểm tra restore): chưa làm. |
| G13 | P0 | Chưa thấy bộ deploy cloud trong danh sách file khảo sát. | Container, proxy HTTPS, volume, healthcheck và runbook tái lập. | **Đã sửa, chờ xác minh staging** — `deploy/docker-compose.yml`, Dockerfile cho Core/AI/frontend, Caddy HTTPS, healthcheck DB/AI, `deploy/README.md` (deploy, rollback, backup, xoay credential). `docker compose config` hợp lệ. **Staging local, 26/10/2026:** cả 3 image build được và stack chạy bằng overlay `deploy/local/` (CA thử nghiệm thay Let's Encrypt); AI `/ready` báo cả hai model và DB sẵn sàng; chỉ `web:8443` và `mosquitto:8883` mở ra host; smoke test 25/25. Lỗi phát hiện và đã sửa: `insightface` kéo theo `opencv-python` bản GUI (thiếu `libxcb`) → Dockerfile chỉ giữ bản headless. Chưa triển khai lên VM cloud có domain thật. **Cập nhật D05:** gỡ service Mosquitto, cổng 8883, ACL và script đồng bộ chứng chỉ broker; Caddy chuyển `/ws/*` tới Core; cloud chỉ mở 443. `docker compose config` hợp lệ (4 service). **Staging local, 27/10/2026:** stack 4 service chạy, chỉ `web:8443` mở cổng, smoke 28/28. Build lại image AI lỗi mạng (timeout tải torch) — dùng image AI cũ vì code AI không đổi; cần build lại khi mạng ổn. **Cloud staging, 27/10/2026:** triển khai thật trên AWS EC2 (Sydney) + `smartlock-hung.duckdns.org`, chứng chỉ Let's Encrypt, AI ready, smoke chế độ cloud **29/29** (xem ACCEPTANCE). **Cloud staging, 30/10/2026:** nâng cấp EC2 lên `5840a559`; backup trước nâng cấp, Compose build/up thành công, DB/AI healthy, AI ready đủ model + database, HTTPS 200/TLS hợp lệ và HTTP 308. Sau đó đồng bộ bản firmware/tài liệu `84b3defd`: hai remote và EC2 cùng SHA, Compose build/up dùng cache, DB/AI vẫn healthy, HTTPS 200/TLS hợp lệ và HTTP 308; chỉ kiểm tra health, thiết bị `STALE`, chưa chạy lại simulator/phần cứng (xem ACCEPTANCE). |
| G14 | P0 | Endpoint nhận ảnh trình duyệt có thể dẫn tới command; liveness không chứng minh ảnh đến từ camera cửa. | Chỉ ảnh từ ESP32 xác thực được mở khóa. Webcam chỉ enrollment/admin, không tạo command. | **Đã xác minh (giả lập)** — bỏ `/api/verify-face`; lệnh mở bằng mặt chỉ từ `/api/device/face` với token thiết bị + request ID dùng một lần; webcam chỉ còn ở đăng ký admin. Test `browserCannotUnlockByFaceOrPinEvenWithCsrf`, `replayedOrStaleRequestsNeverReachAiOrCreateCommands`, `DeviceFaceDecisionTests` (49). |
| G15 | P0 | Firmware ghi ESP32-S3 N16R8 nhưng script mặc định ESP32 Dev Module. | Xác minh FQBN, flash/PSRAM, board package và nguồn firmware chuẩn. | **Toolchain đã xác minh; board vật lý chưa xác minh** — nguồn chuẩn `esp32/main/`; script bắt buộc `-Fqbn`, copy cả `PubSubClient.cpp`. Ngày 26/10/2026: Arduino CLI 1.5.1 (checksum khớp, cài trong `.runtime/`), core `esp32:esp32@3.3.12`, Keypad 3.1.1, ESP32Servo 3.2.1. Mặc định của board `esp32s3` là flash 4 MB và **tắt PSRAM**, nên chọn `esp32:esp32:esp32s3:FlashMode=qio,FlashSize=16M,PSRAM=opi,PartitionScheme=app3M_fat9M_16MB` theo đúng thông số N16R8 ghi trong firmware. Compile exit 0: 1,108,918 B (35% vùng app 3 MB), RAM tĩnh 59,444 B; `--warnings all` không còn cảnh báo trong sketch. Cập nhật 30/10/2026 sau sửa chuyển mạng Wi-Fi: compile exit 0, 1.217.266 B (38%) flash và 59.932 B (18%) RAM tĩnh. Chưa xác minh chip thật là N16R8 (D08); chưa flash. |

## Kiến trúc đề xuất, chưa phải hạ tầng đã chốt

```text
LOCAL: ESP32-S3 + camera/PIR/keypad + relay/servo/buzzer
          | REST (HTTPS): ảnh/PIN/cảnh báo có device auth
          | WebSocket (wss): command  ->  ;  ACK/event/status  <-
          v
CLOUD: proxy HTTPS :443 -> Core (REST + WebSocket) -> AI private (liveness/recognition)
          |                  |
       React app (REST)   DB cloud + storage ảnh riêng tư
```

Cập nhật 26/10/2026 (D05): giao thức Local↔Cloud là **REST + WebSocket** theo sơ đồ Level 3 của môn học; không còn broker MQTT. Bản trước của sơ đồ dùng MQTT TLS + broker có ACL.

Một VM cloud + Docker Compose là đề xuất đồ án, chưa được người dùng chọn nhà cung cấp/ngân sách. Frontend và API cùng origin. DB/AI dùng mạng private; chỉ public HTTPS (443). Board kết nối outbound, không port forwarding hoặc gateway laptop.

Upload ảnh theo sự kiện với cooldown và giới hạn dung lượng/tần suất. Không mặc định stream 10 FPS lên cloud. Luồng mở bằng khuôn mặt chỉ dùng camera ESP32 đã xác thực. Trình duyệt không nhận device credential.

## Lộ trình tổng

Chỉ áp dụng khi được giao tiếp tục kế hoạch tổng. Bug cụ thể tuân theo phạm vi phiên trong [CLAUDE.md](../CLAUDE.md).

1. Chốt các quyết định ảnh hưởng phần việc trong [DECISIONS](DECISIONS.md), hoàn thiện contract và tiêu chí test.
2. Tách cấu hình, chuẩn bị deploy dev/cloud và URL nội bộ AI.
3. Đồng bộ Core–firmware: device auth, nguồn ảnh, ID/TTL/ACK/dedup, reconnect, bỏ unlock trùng/master PIN.
4. Lưu bền vững cloud, trạng thái/lockdown và giao diện HTTPS biểu diễn kết quả trung thực.
5. Kiểm tra theo [ACCEPTANCE](ACCEPTANCE.md), bàn giao runbook và demo; ghi riêng P1 chưa làm.

Không tự triển khai P1 trước P0 chỉ vì thuận tiện. Không có tài khoản cloud vẫn có thể chuẩn bị artifact bằng placeholder; không tuyên bố đã deploy.

## Ghi chú kỹ thuật theo phần việc

- Giữ BCrypt, CSRF, admin handoff/session và tái kiểm tra hồ sơ trước command.
- Baseline khảo sát: InsightFace `buffalo_l`, embedding 512, cosine 0.5, MiniFASNetV2; verify chọn mặt lớn nhất, enrollment đúng một mặt. Không đổi model/ngưỡng chỉ để demo dễ qua.
- Khi thay pipeline, dùng `backend-ai/evaluation` và `tools/evaluate.py`; báo dataset, mẫu số, lỗi và độ trễ thực đo. Không bịa accuracy.
- Model cần version/checksum, đường dẫn không phụ thuộc working directory và readiness phù hợp. Kiểm tra nguồn tải/giấy phép khi đóng gói.
- Không tự chuyển `LocalDateTime` cũ sang UTC khi chưa biết timezone gốc; migration timezone là P1 trừ khi trực tiếp gây lỗi.
- Outbox là P1; P0 vẫn phải fail an toàn khi DB/publish/ACK gián đoạn. Không có outbox thì chấp nhận mất cơ hội mở với trạng thái unknown thay vì retry mù.
- Kiểm tra JDK thực tế theo Spring Boot/Gradle thay vì chỉ tin tài liệu JDK 17.
