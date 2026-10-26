# Nghiệm thu IoT Level 3

Trạng thái: **chưa nghiệm thu**. Checklist là mục tiêu, không phải kết quả test. Phân biệt tiêu chí phân loại Level 3 trong slide với điều kiện an toàn riêng của dự án.

## A. Tiêu chí Level 3

- [ ] Một ESP32-S3 là nút local duy nhất; laptop không chạy backend/gateway bắt buộc.
- [ ] Liveness, nhận diện và quyết định nghiệp vụ chạy trên cloud.
- [ ] Dữ liệu nghiệp vụ/audit/cảnh báo lưu bền vững trên cloud; restart/recreate dịch vụ vẫn giữ dữ liệu.
- [ ] App/API phục vụ từ cloud qua HTTPS; không cần Vite dev server hoặc fetch camera HTTP LAN.
- [ ] Có bằng chứng xử lý AI trên cloud và kết quả trên dashboard. Thống kê nâng cao không phải điều kiện phân loại Level 3.

## B. An toàn tối thiểu trước demo cửa thật

- [ ] Thiết bị được xác thực; TLS xác minh chứng chỉ; broker có ACL; DB/AI không public.
- [ ] Chỉ ảnh ESP32 xác thực được tạo command mở bằng khuôn mặt; browser/webcam không có đường bypass, kể cả gọi API trực tiếp.
- [ ] Contract PIN đã được chốt riêng; không có credential thiết bị trong bundle React.
- [ ] Liveness, quyền hồ sơ và kiểm soát phiên/CSRF admin được giữ; upload và thử sai có giới hạn cơ bản.
- [ ] Có command ID/TTL/ACK/dedup, một đường actuation; không retain/phát lại lệnh cũ hoặc gia hạn do trùng.
- [ ] UI/log phân biệt publish, ACK nhận, ACK thực thi và trạng thái vật lý chưa đo.
- [ ] Mất Wi-Fi/cloud, ACK mất, broker/Core restart và reboot không gây mở ngoài ý muốn; timer khóa lại còn hoạt động.
- [ ] Lockdown được lưu và đồng bộ; UI thể hiện chưa ACK khi offline.
- [ ] Không master PIN mặc định/log bí mật; ảnh cảnh báo không public vô danh.
- [ ] Có cấu hình mẫu, runbook deploy/rollback và cách backup. Có bản backup trước khi thay dữ liệu/schema cũ.

Hoàn thành code chưa đủ đánh dấu B: cần test tương ứng, có hardware-in-the-loop cho các mục phụ thuộc timer/chấp hành. Chỉ test cửa thật sau khi được người dùng cho phép.

## C. Nên có — không chặn bản tối thiểu

- [ ] Transactional outbox và đối soát command nâng cao.
- [ ] Kiểm tra restore backup trên môi trường thử.
- [ ] Tự động xoay credential và quy trình vòng đời thiết bị.
- [ ] Migration framework có phiên bản thay tự cập nhật schema.
- [ ] Migration timezone cũ sau khi xác minh timezone gốc.
- [ ] Analytics nâng cao: xu hướng thất bại, spoof, p50/p95 command–ACK.

Không triển khai C tự động nếu người dùng chỉ giao một bug hoặc hạng mục P0. Rủi ro còn lại phải được ghi rõ, không mô tả bản tối thiểu là HA hoặc production-ready.

## Kịch bản và mức bằng chứng

| Kịch bản | Kết quả mong đợi | Mức cần ghi |
| --- | --- | --- |
| Face/PIN đúng; hồ sơ thu hồi, spoof, ảnh lỗi, AI/DB lỗi | Chỉ trường hợp hợp lệ mới tạo command; lỗi không mở | Giả lập/integration; face thực cần dataset/model thật |
| Browser upload ảnh trực tiếp, device auth sai, nonce replay | Không tạo command mở bằng khuôn mặt | Integration API |
| HTTP và MQTT cho cùng request, command trùng/hết hạn/sai device | Tối đa một actuation, không gia hạn | Simulator rồi phần cứng |
| Broker chưa sẵn sàng khi boot, broker/Core restart | Kết nối/subscribe phục hồi; không phát lại lệnh cũ | Staging với simulator |
| ACK mất hoặc sai ID; command gián đoạn | UI pending/unknown đúng, không tự mở lại | Integration/staging |
| Upload bị treo, mất Wi-Fi khi đang mở, reboot/lockdown | Timer và chính sách an toàn đúng contract | Phần cứng thật |
| Session, CSRF, quyền ảnh, payload quá lớn, ACL sai | Bị chặn ở đúng lớp, không lộ bí mật | Integration/staging |
| Recreate container và khởi động lại cloud | Dữ liệu cần lưu vẫn tồn tại | Cloud staging |

Dùng lệnh đa nền tảng trong [CLAUDE.md](../CLAUDE.md). Chạy test phù hợp phạm vi thay đổi; không phải mọi phiên đều chạy toàn bộ ma trận. Test tự động dùng DB/broker/topic cô lập, không dùng cửa thật. Không trích số pass trong tài liệu cũ làm kết quả hiện tại.

Demo cuối: dừng backend local trên laptop, board gửi ảnh tới cloud, xác thực hợp lệ tạo command có ACK, spoof/hồ sơ bị thu hồi không mở. Dashboard từ máy khác hiển thị kết quả cloud. Gián đoạn và phục hồi kết nối không mở lại do lệnh cũ.

## Sổ bằng chứng

Mỗi lần kiểm tra ghi: ngày, commit/diff hoặc phiên bản artifact, môi trường, lệnh/kịch bản, kết quả, đường dẫn báo cáo đã loại bí mật và giới hạn. Tách ba mức: **giả lập**, **cloud staging với simulator**, **cloud với phần cứng thật**.

| Ngày | Phạm vi | Mức | Bằng chứng | Kết luận |
| --- | --- | --- | --- | --- |
| 25/10/2026 | Tách và chỉnh tài liệu | Chỉ kiểm tra tài liệu | Không chạy test ứng dụng hoặc phần cứng trong tác vụ này | Không chứng minh hệ thống đạt Level 3 |
| 26/10/2026 | Triển khai P0 trong code: device auth, MQTT-only command/ACK/TTL/seq, lockdown bền vững, bỏ PIN/face web và master PIN, deploy Compose (diff chưa commit trên `main` @ `575489b9`) | **Giả lập** | Windows 11, JDK 17.0.7, Python 3.11.9, Node qua nvm. `backend-core`: `gradlew.bat test` → 123 test, 0 lỗi (baseline trước đó **không compile**). `backend-ai`: unittest → 41 OK. `tools/device-simulator`: unittest → 8 OK. `frontend`: `npm test` 10/10, `lint` exit 0 (chỉ cảnh báo), `build` OK (baseline trước đó 1 test đỏ). `docker compose config` hợp lệ. `git diff --check` sạch. | Đã chuẩn bị cho Level 3, **chưa nghiệm thu triển khai thực tế**. Chưa chạy: build image/staging (Docker daemon tắt), compile firmware (không có Arduino CLI), mọi kịch bản phần cứng. Chưa tick mục A/B nào. |
| 26/10/2026 | Stack Compose đầy đủ (db, ai, core, mosquitto, web) trên máy dev; firmware compile | **Staging local với simulator** (chưa phải cloud) | Docker 29.2.1. Overlay `deploy/local/` (CA thử nghiệm, HTTPS `localhost:8443`, MQTT TLS `8883`). AI `/ready`: faceModel, livenessModel, database đều `true`. `deploy/local/smoke_test.py` → **25/25**: admin HTTPS + CSRF; broker từ chối anonymous/sai mật khẩu/CA không tin cậy; ACL chặn thiết bị publish lệnh; trình duyệt không mở được bằng PIN; token sai 401; PIN đúng 202 → command → ACK `RECEIVED`/`EXECUTED` → Core ghi `EXECUTED`, đúng một lần chấp hành; replay 409; PIN sai 401; ảnh không có mặt → AI trả `NO_FACE` (AI chạy trong container); lockdown ACK, chỉ "applied" sau ACK, mở bị 403; restart broker → kết nối lại + resubscribe; restart Core → dữ liệu còn; trạng thái kiosk không có tên; chỉ `web`/`mosquitto` mở cổng. Firmware: Arduino CLI 1.5.1, `esp32:esp32@3.3.12`, FQBN `esp32s3:FlashMode=qio,FlashSize=16M,PSRAM=opi,PartitionScheme=app3M_fat9M_16MB` → exit 0, 1,108,918 B, 0 cảnh báo sketch. Lỗi tìm ra và đã sửa: `opencv-python` GUI trong image AI; CRLF làm lệch hash token trong script dưới Git Bash; ACK REPLAY ghi đè ACK cuối (sửa trước đó). | Mức staging local đạt cho luồng PIN, lockdown, ACL/TLS và khôi phục kết nối. **Chưa** nhận diện khuôn mặt thành công đầu–cuối (chưa đăng ký mặt trong staging), chưa có domain/VM thật, chưa flash/phần cứng, chưa thử backup/restore. Mục A/B vẫn chưa tick. |

Nếu chưa có hạ tầng/phần cứng: ghi **“đã chuẩn bị cho Level 3, chưa nghiệm thu triển khai thực tế”** cùng checklist còn thiếu. Không tuyên bố đạt Level 3 từ việc build/test giả lập.
