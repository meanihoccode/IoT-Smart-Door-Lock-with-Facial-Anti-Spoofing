# Xác thực khuôn mặt lớn nhất

## Quy tắc xử lý

Trong mỗi lần quét mở khóa, detector tìm vị trí tất cả khuôn mặt. AI chọn box có diện tích nhìn thấy lớn nhất rồi chỉ tính embedding và kiểm tra anti-spoofing cho mặt đó. Nếu mặt được chọn vượt anti-spoofing, embedding được so với danh sách người đã đăng ký.

- Diện tích = chiều rộng × chiều cao của phần box nằm trong ảnh. Tọa độ được giới hạn theo biên ảnh khi tính diện tích.
- Nếu diện tích bằng nhau, chọn mặt gần tâm ảnh hơn. Nếu vẫn bằng nhau, giữ mặt xuất hiện trước trong kết quả detector.
- Box và landmark gốc được giữ để ArcFace căn chỉnh và MiniFASNet crop đúng mặt đã chọn.
- Nếu mặt lớn nhất là giả, không khớp, hoặc model gặp lỗi, lượt xác thực kết thúc. Không thử mặt nhỏ hơn.
- Box không hữu hạn, đảo chiều, không có diện tích hoặc hoàn toàn ngoài ảnh làm lượt xác thực bị từ chối với `INVALID_AI_RESPONSE`.
- Đăng ký qua `/api/extract-embedding` vẫn trả `MULTIPLE_FACES` nếu ảnh có nhiều mặt.

## Đọc code ở đâu?

Các hàm chính trong [face_pipeline.py](../backend-ai/face_pipeline.py) đã có chú thích tiếng Việt:

| Thành phần | Chức năng |
| --- | --- |
| `DetectedFace` | Lưu box, landmark, độ tin cậy detector và embedding của một mặt |
| `detect_faces(image)` | Chạy detector một lần để lấy vị trí mặt; chưa chạy ArcFace |
| `detect_largest(image)` | Chọn mặt lớn nhất và trả kèm tổng số mặt phát hiện |
| `detect_one(image)` | Yêu cầu đúng một mặt khi đăng ký |
| `get_embedding(image, face)` | Chạy ArcFace cho đúng mặt được truyền vào và kiểm tra vector 512 chiều |
| `verify(...)` | Chọn mặt, lấy embedding, kiểm tra thật/giả rồi so gallery |
| `extract(image)` | Lấy embedding cho đăng ký sau khi kiểm tra số mặt |

Trong `detect_largest`, `largest_area` lưu diện tích lớn nhất đã gặp, `selected_face` lưu mặt tương ứng, còn `nearest_center_distance` lưu khoảng cách bình phương tới tâm ảnh để xử lý trường hợp bằng diện tích. Dùng khoảng cách bình phương cho phép so gần/xa mà không cần tính căn bậc hai.

`faceCount` trong JSON và `face_count` trong đánh giá offline luôn là **tổng số mặt detector phát hiện**, không phải số mặt được nhận diện. Ví dụ ảnh có ba mặt vẫn trả `faceCount: 3`, nhưng `liveness` và `recognition` chỉ thuộc mặt lớn nhất. Backend-core chấp nhận kết quả thành công có `faceCount >= 1` và tiếp tục kiểm tra đủ liveness, điểm so khớp và user tồn tại.

Ba chế độ đánh giá offline cũng dùng quy tắc chọn mặt này. `liveness_only` không gọi ArcFace. Phiên bản cấu hình mới là `phase2a-largest-face-v1`, được ghi trong snapshot để phân biệt với kết quả trước đây từ chối ảnh nhiều mặt.

## Kiểm thử

Chạy từ thư mục gốc dự án:

```powershell
.\backend-ai\.venv\Scripts\python.exe -m unittest discover -s backend-ai/tests -v
cd backend-core
.\gradlew.bat test --tests com.example.btl_iot.controller.ApiControllerFaceVerificationTests --console=plain
```

Kết quả ngày 15/09/2026: 41 test Python và 25 test xác thực Java đạt. Test dùng model/dữ liệu giả để kiểm tra chọn đúng box/embedding, số lần gọi model, lỗi, đăng ký và lệnh mở cửa qua MQTT giả lập.

Đã kiểm tra bổ sung với model thật trên ảnh xám tổng hợp: detector trả `NO_FACE`; ArcFace nhận `DetectedFace` và cho embedding giống đối tượng `Face` của InsightFace khi truyền cùng landmark; MiniFASNet suy luận được. Phép kiểm tra này xác nhận tương thích dữ liệu, chưa đánh giá độ chính xác trên người thật.

## Thử qua camera

Khởi động lại `backend-ai` và `backend-core` để nạp code mới. Cho hai người đứng ở khoảng cách khác nhau, quét rồi đổi vị trí để kiểm tra kết quả đi theo người có mặt lớn hơn. Thử thêm trường hợp mặt lớn nhất chưa đăng ký nhưng mặt nhỏ hơn đã đăng ký: kết quả mong đợi là `NOT_RECOGNIZED`.
