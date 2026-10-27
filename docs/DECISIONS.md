# Sổ quyết định IoT Level 3

Ngày tạo: 25/10/2026. Ghi nguồn và trạng thái để phiên sau không hỏi lại điều đã chốt. Phân biệt **Đã chốt**, **Đề xuất**, **Chưa chốt**, **Đã thay thế**. Không tự ghi đề xuất kỹ thuật là quyết định được người dùng duyệt.

## Quyết định và câu hỏi hiện tại

| ID | Chủ đề | Trạng thái | Nội dung / nguồn |
| --- | --- | --- | --- |
| D01 | Phân loại IoT | Đã chốt | Một nút local, lưu trữ/phân tích/app cloud. Nguồn: yêu cầu và slide người dùng. |
| D02 | Nguồn ảnh mở khóa | Đã chốt cho thiết kế mục tiêu | Chỉ ảnh từ ESP32 xác thực được tạo command mở bằng khuôn mặt. Webcam chỉ enrollment/nghiệp vụ admin, không mở bằng ảnh. Nguồn: góp ý người dùng trong tác vụ chỉnh tài liệu. Chưa triển khai trong code. |
| D03 | Phạm vi và ưu tiên | Đã chốt | P0 tối thiểu/an toàn; P1 không chặn nghiệm thu; công việc từng phiên chỉ gồm yêu cầu và phụ thuộc trực tiếp. Nguồn: góp ý người dùng. |
| D04 | PIN keypad/kiosk | Đã chốt 26/10/2026 | PIN chỉ nhập trên keypad ESP32 đã xác thực, **không kèm username**; Core quét hồ sơ enabled, giới hạn thử theo thiết bị, từ chối khi PIN khớp nhiều hồ sơ. **Bỏ PIN trên kiosk web**; kiosk chỉ hiển thị trạng thái. Admin đã đăng nhập được mở từ xa, có audit. Nguồn: người dùng trả lời câu hỏi trong phiên 26/10/2026. Lịch sử: trước đó “Chưa chốt” (code PIN không username, tài liệu cũ mã hồ sơ + PIN). |
| D05 | Giao thức Local↔Cloud | Đã chốt bởi người dùng 26/10/2026; giảng viên chưa xác nhận | **REST + WebSocket** đúng sơ đồ IoT Level 3 trong tài liệu môn học (trang 47; web service “REST hoặc WebSocket” trang 43): thiết bị gửi ảnh/PIN/cảnh báo bằng REST qua HTTPS; lệnh xuống và ACK/sự kiện/trạng thái lên qua một WebSocket bảo mật `wss://<domain>/ws/device` giữ liên tục (mô hình Exclusive Pair, trang 36/40). Bỏ MQTT/Mosquitto. Nguồn: người dùng yêu cầu “chuyển sang rest + websocket” sau phân tích theo tài liệu môn học. Lịch sử: trước đó “Chưa chốt với giảng viên”, tạm dùng HTTPS + MQTT TLS. |
| D06 | Cloud/domain/ngân sách | Chưa chốt | Chưa có nhà cung cấp, region, domain, tài khoản hoặc mức chi phí được duyệt. Dùng placeholder cho artifact. |
| D07 | Cách triển khai | Đề xuất; phần broker **đã thay thế** | Một VM cloud + Docker Compose, proxy cùng origin, DB/AI private và persistent volumes — vẫn là đề xuất, chưa chọn nhà cung cấp. Chỉ mở 443 (HTTPS + wss). Lịch sử: 26/10/2026 người dùng chốt broker Mosquitto trong Compose; cùng ngày bị thay bởi D05 (REST + WebSocket, không còn broker). |
| D08 | Board/wiring | Chưa xác minh thực tế | Firmware ghi ESP32-S3 N16R8/OV2640; cần FQBN, flash/PSRAM, loại khóa và cực tính relay thực tế. Không đổi GPIO theo suy đoán. |
| D09 | Recovery offline | Ngoài phạm vi mặc định | Bỏ master PIN mặc định. Cơ chế recovery mới cần yêu cầu riêng và xác minh thoát hiểm/wiring. |
| D10 | Schema command | Hướng đã chốt 26/10/2026; chi tiết là đề xuất | **Một đường thực thi: lệnh chỉ đi qua WebSocket thiết bị.** Firmware chỉ mở khi nhận command JSON hợp lệ (`kind:"cmd"`) qua WebSocket và gửi ACK `RECEIVED`/`EXECUTED`/`REJECTED`; phản hồi REST chỉ chứa quyết định + `commandId`. Nguồn: người dùng. Trường, TTL, `seq` chống phát lại qua reboot và transition không đổi so với v1; transport theo [DEVICE_CONTRACT](DEVICE_CONTRACT.md) v2. Lịch sử: chốt lần đầu là “MQTT-only”, đổi transport theo D05 cùng ngày. |

## Ghi chú D05

Người dùng đã chọn REST + WebSocket theo sơ đồ môn học; giảng viên chưa xác nhận trực tiếp. Nếu giảng viên có kết luận khác, ghi nguyên ý, ngày và nguồn ở D05 trước khi đổi code. Cloud ↔ App hiện là REST (dashboard tự làm mới); thêm WebSocket cho dashboard là tùy chọn, chưa được giao.

Lựa chọn kỹ thuật đi kèm (đề xuất đã triển khai, có thể chỉnh): thư viện firmware “WebSockets” (Markus Sattler) 2.7.2 — đã kiểm tra mã nguồn: có CA thì dùng `setCACert`, chỉ `setInsecure()` khi không có CA nên firmware từ chối kết nối khi thiếu CA; Spring WebSocket ở Core, TLS kết thúc tại Caddy.

## Khi cập nhật

- Sửa đúng ID, ghi ngày, người/nguồn xác nhận, lý do và ảnh hưởng.
- Nếu thay quyết định, lưu nội dung cũ trong lịch sử ngắn và trỏ quyết định thay thế.
- Khi chỉ có giả định triển khai, gắn “Đề xuất”; nêu giả định trong báo cáo.
- Chỉ hỏi lại nếu có thông tin mới xung đột hoặc thiếu dữ kiện ảnh hưởng trực tiếp công việc đang làm.

## Lịch sử

- 25/10/2026: tách sổ quyết định từ CLAUDE.md; ghi yêu cầu người dùng và các vấn đề còn mở. Không có quyết định mới về nhà cung cấp cloud, wiring hoặc chấp nhận giao thức từ giảng viên.
- 26/10/2026: người dùng chốt D04 (PIN keypad không username, giới hạn theo thiết bị; bỏ PIN web), D10 (MQTT-only + ACK) và broker Mosquitto trong Compose (một phần D07). D05, D06, D08 vẫn mở.
- 26/10/2026 (sau): người dùng chốt D05 = REST + WebSocket theo tài liệu môn học. D07 phần broker và transport MQTT của D10 được thay thế. D06, D08 vẫn mở.
