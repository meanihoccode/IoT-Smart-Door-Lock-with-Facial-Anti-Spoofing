# Sổ quyết định IoT Level 3

Ngày tạo: 25/10/2026. Ghi nguồn và trạng thái để phiên sau không hỏi lại điều đã chốt. Phân biệt **Đã chốt**, **Đề xuất**, **Chưa chốt**, **Đã thay thế**. Không tự ghi đề xuất kỹ thuật là quyết định được người dùng duyệt.

## Quyết định và câu hỏi hiện tại

| ID | Chủ đề | Trạng thái | Nội dung / nguồn |
| --- | --- | --- | --- |
| D01 | Phân loại IoT | Đã chốt | Một nút local, lưu trữ/phân tích/app cloud. Nguồn: yêu cầu và slide người dùng. |
| D02 | Nguồn ảnh mở khóa | Đã chốt cho thiết kế mục tiêu | Chỉ ảnh từ ESP32 xác thực được tạo command mở bằng khuôn mặt. Webcam chỉ enrollment/nghiệp vụ admin, không mở bằng ảnh. Nguồn: góp ý người dùng trong tác vụ chỉnh tài liệu. Chưa triển khai trong code. |
| D03 | Phạm vi và ưu tiên | Đã chốt | P0 tối thiểu/an toàn; P1 không chặn nghiệm thu; công việc từng phiên chỉ gồm yêu cầu và phụ thuộc trực tiếp. Nguồn: góp ý người dùng. |
| D04 | PIN keypad/kiosk | Đã chốt 26/10/2026 | PIN chỉ nhập trên keypad ESP32 đã xác thực, **không kèm username**; Core quét hồ sơ enabled, giới hạn thử theo thiết bị, từ chối khi PIN khớp nhiều hồ sơ. **Bỏ PIN trên kiosk web**; kiosk chỉ hiển thị trạng thái. Admin đã đăng nhập được mở từ xa, có audit. Nguồn: người dùng trả lời câu hỏi trong phiên 26/10/2026. Lịch sử: trước đó “Chưa chốt” (code PIN không username, tài liệu cũ mã hồ sơ + PIN). |
| D05 | Giao thức theo yêu cầu chấm | Chưa chốt với giảng viên | Slide có REST/WebSocket; chưa có kết luận giảng viên về MQTT. Giữ đề xuất HTTPS + MQTT TLS để chuẩn bị; không tuyên bố đã đáp ứng rubric giao thức. |
| D06 | Cloud/domain/ngân sách | Chưa chốt | Chưa có nhà cung cấp, region, domain, tài khoản hoặc mức chi phí được duyệt. Dùng placeholder cho artifact. |
| D07 | Cách triển khai | Đề xuất; broker đã chốt | Một VM cloud + Docker Compose, proxy cùng origin, DB/AI private và persistent volumes — vẫn là đề xuất, chưa chọn nhà cung cấp. **Broker: Mosquitto tự vận hành trong Compose** (TLS, mật khẩu, ACL theo topic) — người dùng chốt 26/10/2026. |
| D08 | Board/wiring | Chưa xác minh thực tế | Firmware ghi ESP32-S3 N16R8/OV2640; cần FQBN, flash/PSRAM, loại khóa và cực tính relay thực tế. Không đổi GPIO theo suy đoán. |
| D09 | Recovery offline | Ngoài phạm vi mặc định | Bỏ master PIN mặc định. Cơ chế recovery mới cần yêu cầu riêng và xác minh thoát hiểm/wiring. |
| D10 | Schema command | Hướng đã chốt 26/10/2026; chi tiết là đề xuất | **Một đường thực thi: MQTT-only.** Firmware chỉ mở khi nhận command JSON hợp lệ qua MQTT và gửi ACK `RECEIVED`/`EXECUTED`/`REJECTED`; phản hồi HTTP chỉ chứa quyết định + `commandId`. Nguồn: người dùng. Trường, TTL, `seq` chống phát lại qua reboot và transition là đề xuất kỹ thuật đã triển khai theo [DEVICE_CONTRACT](DEVICE_CONTRACT.md) v1; có thể chỉnh khi nghiệm thu phần cứng. |

## Cách xử lý D05

Không để agent suy đoán ý giảng viên hoặc tự viết adapter WSS. Khi người dùng có kết luận rubric, ghi nguyên ý, ngày và nguồn ở D05.

Các phương án cần đối chiếu nếu có yêu cầu WebSocket: HTTPS cho upload và WebSocket bảo mật cho điều khiển; hoặc MQTT qua WebSocket bảo mật nếu cả broker lẫn thư viện thiết bị được chọn hỗ trợ và rubric chấp nhận. Không giả định PubSubClient hiện tại hỗ trợ WSS; kiểm tra trước khi chọn hoặc thay thư viện. Đây là các phương án đánh giá, chưa là quyết định triển khai.

Không chặn công việc độc lập trong lúc chờ: tách URL/config, định nghĩa command ID/TTL/ACK và test logic không phụ thuộc transport.

## Khi cập nhật

- Sửa đúng ID, ghi ngày, người/nguồn xác nhận, lý do và ảnh hưởng.
- Nếu thay quyết định, lưu nội dung cũ trong lịch sử ngắn và trỏ quyết định thay thế.
- Khi chỉ có giả định triển khai, gắn “Đề xuất”; nêu giả định trong báo cáo.
- Chỉ hỏi lại nếu có thông tin mới xung đột hoặc thiếu dữ kiện ảnh hưởng trực tiếp công việc đang làm.

## Lịch sử

- 25/10/2026: tách sổ quyết định từ CLAUDE.md; ghi yêu cầu người dùng và các vấn đề còn mở. Không có quyết định mới về nhà cung cấp cloud, wiring hoặc chấp nhận giao thức từ giảng viên.
- 26/10/2026: người dùng chốt D04 (PIN keypad không username, giới hạn theo thiết bị; bỏ PIN web), D10 (MQTT-only + ACK) và broker Mosquitto trong Compose (một phần D07). D05, D06, D08 vẫn mở.
