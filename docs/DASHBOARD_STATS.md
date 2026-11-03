# Thống kê dashboard admin

Cập nhật 03/11/2026. `GET /api/overview` vẫn yêu cầu phiên admin; bổ sung đối tượng `faceStatsToday` lấy từ bảng `access_logs`, không tạo dữ liệu mẫu hay thay đổi schema.

| Trường | Cách tính trong ngày |
| --- | --- |
| `attempts` | Số bản ghi `accessMethod = FACE`, gồm cả từ chối và lỗi dịch vụ đã được ghi log. |
| `granted` | Số bản ghi FACE có `commandId`: Core đã chấp thuận và tạo lệnh. Không phụ thuộc ACK hoặc kết quả gửi/thực thi lệnh. |
| `spoofDetected` | Số bản ghi FACE có `status = SPOOF_DETECTED`; không cộng cảnh báo PIN, người lạ hay cảnh báo PIR gửi riêng. |
| `successRate` | `100 × granted / attempts`, làm tròn một chữ số thập phân; `null` khi `attempts = 0`. |

Dashboard đổi nhãn “Tỷ lệ nhận diện đúng” thành “Xác thực mặt thành công (Hôm nay)” vì log vận hành không có nhãn đối chứng để đo độ chính xác AI. Đây cũng không phải tỷ lệ cửa mở: lệnh có thể chưa được thiết bị thực thi. Các bản ghi cũ `SUCCESS` không có `commandId` vẫn nằm trong tổng FACE nhưng không được suy đoán là lượt được cấp lệnh.

Phạm vi ngày giữ cùng quy ước `LocalDateTime` của backend hiện có: từ đầu ngày đến trước đầu ngày kế tiếp theo múi giờ JVM. Chưa chuyển timezone dữ liệu cũ. Thống kê truy vấn toàn bộ bản ghi trong ngày bằng aggregate tại DB, không chỉ năm lượt gần nhất. PIN và admin mở từ xa không đi vào thống kê khuôn mặt. Request bị chặn trước khi có access log không nằm trong tổng này.

UI cập nhật mỗi 5 giây cùng overview. Chưa tải được dữ liệu: `—`; không có lượt FACE: tỷ lệ `—`, cảnh báo `0`; có FACE nhưng không lượt được cấp lệnh: `0%`. Nếu tải lại lỗi, giữ dữ liệu lần trước và hiện cảnh báo có thể đã cũ.

Kiểm tra: `BtlIotApplicationTests` bổ sung các ca HTTP + H2 cho ngày không dữ liệu, kết quả FACE/PIN/admin trộn lẫn, command đã gửi/đã thực thi/gửi lỗi, khoảng ngày, tỷ lệ 0 và làm tròn. `frontend/test/overview-stats.test.js` kiểm tra định dạng và phân biệt dữ liệu rỗng với tỷ lệ 0. Đây là kiểm tra giả lập, không đo độ chính xác AI hay nghiệm thu phần cứng/cloud.
