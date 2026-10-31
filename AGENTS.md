# Hướng dẫn làm việc — Smart Door Lock / IoT Level 3

## Mục tiêu

Phát triển hệ thống hiện có bằng thay đổi có thể review và kiểm thử. Giao tiếp bằng tiếng Việt. Đọc code, xác minh giả định và báo cáo bằng chứng thực tế.

IoT Level 3 theo slide đồ án: **một nút local ESP32-S3**, **lưu trữ và phân tích trên cloud**, **ứng dụng trên cloud**. Camera, PIR, keypad và chấp hành cùng board thuộc một nút. ESP32 thu dữ liệu và điều khiển; cloud chạy liveness, nhận diện và xử lý nghiệp vụ. Laptop không được là backend/gateway bắt buộc. Chỉ đưa DB/broker lên cloud trong khi AI còn trên laptop chưa đạt Level 3. Các phase phần mềm không phải cấp độ IoT.

Mức ưu tiên:

- **Bắt buộc cho Level 3 và an toàn của dự án:** AI/DB/app trên cloud; lưu trữ bền vững; HTTPS, xác thực thiết bị, TLS/ACL broker; command ID/TTL/ACK và chống thực thi trùng; bỏ master PIN; DB/AI không public; giữ kiểm tra quyền, liveness và phiên admin.
- **Nên có, không chặn nghiệm thu tối thiểu:** transactional outbox, kiểm tra restore, tự động xoay credential, migration framework, chuyển timezone dữ liệu cũ và analytics nâng cao. Chỉ làm khi được giao hoặc là phụ thuộc trực tiếp đã giải thích.
- **Ngoài phạm vi mặc định:** nhiều nút/gateway, HA/Kubernetes, huấn luyện model mới, đổi framework và recovery offline mới.

Đây là mức ưu tiên dự án; không phải mọi yêu cầu bảo mật đều là tiêu chí phân loại IoT trong slide.

## Quy tắc tuyệt đối

- Chỉ ảnh từ **ESP32 đã xác thực thiết bị** mới được tạo lệnh mở khóa bằng khuôn mặt. Webcam kiosk chỉ dùng đăng ký hoặc nghiệp vụ admin đã đăng nhập, không dùng làm nguồn ảnh mở khóa. Core phải cưỡng chế quy tắc này; không chỉ ẩn nút frontend. Liveness không xác minh nguồn ảnh trong HTTP request.
- Không retain lệnh mở, phát lại lệnh hết hạn hoặc thực thi hai lần qua HTTP và MQTT.
- Không gọi publish thành công là cửa đã mở. Phân biệt ACK nhận lệnh, ACK thực thi và cảm biến vật lý.
- Không bỏ liveness/kiểm tra quyền khi AI, DB hoặc mạng lỗi. Timer khóa lại vẫn phải hoạt động.
- Không dùng `setInsecure()`, trust-all, master PIN mặc định, log bí mật hoặc nhúng device credential trong React.
- Không commit bí mật/dataset thật; không tự xóa dữ liệu. Backup trước migration có nguy cơ mất dữ liệu.
- Không tự đổi GPIO, cực tính relay hoặc cơ chế thoát hiểm khi chưa xác minh wiring.
- Không flash/phát lệnh tới cửa thật, trả phí cloud hoặc commit/push khi chưa được người dùng cho phép.

## Cách làm việc mỗi phiên

1. Khi người dùng giao việc cụ thể, chỉ làm việc đó và phụ thuộc trực tiếp. Lộ trình tổng chỉ áp dụng khi được yêu cầu tiếp tục kế hoạch Level 3; không tự khởi động lại khảo sát/ADR cho bug nhỏ.
2. Xem `git status`/diff và đọc mã liên quan trước khi sửa; giữ thay đổi có sẵn. Đọc tài liệu theo phần việc. Code và kiểm thử hiện tại quyết định hành vi; báo cáo có ngày chỉ là ảnh chụp khảo sát.
3. Đọc [quyết định](docs/DECISIONS.md) trước khi hỏi lại cloud/domain/protocol/phần cứng. Ghi quyết định mới, nguồn và trạng thái; không biến đề xuất thành lựa chọn đã được người dùng duyệt.
4. Cập nhật từng dòng [gap analysis](docs/LEVEL3_GAP_ANALYSIS.md) khi xử lý: trạng thái, file thay đổi và bằng chứng. Đổi contract thì cập nhật [DEVICE_CONTRACT](docs/DEVICE_CONTRACT.md) cùng code.
5. Chạy kiểm tra phù hợp thay đổi. Tài liệu thuần túy chỉ cần kiểm tra nội dung/liên kết/diff; không gọi phần cứng.
6. Báo cáo kết quả, file chính, kiểm thử thực chạy và giới hạn còn lại. Phân biệt **giả lập**, **cloud staging với simulator**, **cloud với phần cứng thật**. Không lấy số test cũ làm kết quả mới hoặc bịa độ chính xác AI.

## Build và test

Chạy trong thư mục ghi ở cột đầu, dùng interpreter đã cài dependency. Xác minh phiên bản từ build file và môi trường; không giả định tài liệu cũ đúng.

| Thư mục | Windows PowerShell | Linux/macOS |
| --- | --- | --- |
| `backend-core` | `.\gradlew.bat test --no-daemon` | `./gradlew test --no-daemon` |
| `backend-ai` | `.\.venv\Scripts\python.exe -B -m unittest discover -s tests -v` | `.venv/bin/python -B -m unittest discover -s tests -v` |
| `frontend` | `npm.cmd test` | `npm test` |
| `frontend` | `npm.cmd run lint` và `npm.cmd run build` | `npm run lint` và `npm run build` |
| Root | `git diff --check` | `git diff --check` |

Firmware: Windows dùng `scripts/Compile-Esp32.ps1 -Fqbn '<FQBN đã xác minh>'`. Linux có thể chạy script bằng `pwsh -File` nếu có PowerShell; nếu dùng Arduino CLI trực tiếp, chuẩn bị sketch theo script. Xác minh ESP32-S3, flash/PSRAM và board package trước; chỉ compile, không tự upload. Thiếu tool/dependency thì ghi rõ kiểm tra chưa chạy.

## Tài liệu theo nhu cầu

- [LEVEL3_GAP_ANALYSIS](docs/LEVEL3_GAP_ANALYSIS.md): khảo sát có ngày, trạng thái từng khoảng trống, kiến trúc đề xuất và lộ trình tổng.
- [DEVICE_CONTRACT](docs/DEVICE_CONTRACT.md): nguồn ảnh tin cậy, command/event/ACK, chống trùng và hành vi khi lỗi.
- [DECISIONS](docs/DECISIONS.md): lựa chọn đã chốt, đề xuất và câu hỏi còn mở, gồm yêu cầu REST/WebSocket của giảng viên.
- [ACCEPTANCE](docs/ACCEPTANCE.md): tiêu chí tối thiểu, hạng mục nâng cao và bằng chứng nghiệm thu.
- [README](README.md), [SETUP_WINDOWS](SETUP_WINDOWS.md), `docs/PHASE_*.md`: hướng dẫn và lịch sử tính năng; đối chiếu code trước khi dùng.
