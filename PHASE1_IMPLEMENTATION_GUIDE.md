# Giai đoạn 1 — Các bước triển khai và giải thích

- Ngày triển khai: 12/09/2026.
- Cập nhật 15/09/2026: xác thực mở khóa đã chuyển sang [chọn mặt lớn nhất](docs/LARGEST_FACE_VERIFICATION.md); quy tắc từ chối nhiều mặt và `faceCount=1` bên dưới mô tả phiên bản giai đoạn 1. Đăng ký vẫn yêu cầu đúng một mặt.
- Plan nguồn: [FACE_AUTH_IMPROVEMENT_PLAN.md](FACE_AUTH_IMPROVEMENT_PLAN.md).
- Phạm vi: quan sát kết quả và xử lý lỗi an toàn.
- Không nằm trong lần triển khai này: sửa thuật toán crop, hiệu chỉnh ngưỡng liveness/cosine, đánh giá nhiều model, chụp native resolution, đăng ký nhiều ảnh và xác thực nhiều frame.

## 1. Mức cơ sở được ghi lại

Môi trường được dùng để triển khai và kiểm thử:

| Thành phần | Phiên bản / cấu hình |
| --- | --- |
| Python | 3.11.9 |
| FastAPI | 0.141.1 |
| Uvicorn | 0.52.4 |
| InsightFace | 1.0.1 |
| ONNX Runtime | 1.29.0, CPU provider |
| PyTorch | 2.14.0 |
| OpenCV headless | 5.0.0.93 |
| NumPy | 2.4.6 |
| MySQL Connector Python | 26.7.0 |
| Java | 17.0.7 |
| Spring Boot | 4.1.1 |
| Gradle wrapper | 9.5.1 |
| Node.js | 20.20.0 |
| React | 19.2.x |
| Vite thực tế khi build | 8.2.2 |
| Model nhận diện | InsightFace `buffalo_l`, detection size `640×640`, CPU |
| Model liveness | `2.7_80x80_MiniFASNetV2.pth`, 1.849.453 byte |
| SHA-256 model liveness | `A5EB02E1843F19B5386B953CC4C9F011C3F985D0EE2BB9819EEA9A142099BEC0` |
| Ngưỡng cosine | `0.5`, giữ nguyên trong giai đoạn 1 |
| Giới hạn upload AI | 10 MiB |
| Timeout Spring → AI | kết nối 3 giây, đọc 20 giây |
| Timeout Kiosk → Spring | 25 giây cho khuôn mặt, 10 giây cho PIN |

Mục đích của bảng này là giúp so sánh cùng một cấu hình ở các lần đo sau. File dependency hiện chưa được pin toàn bộ; việc pin phiên bản và báo cáo đánh giá cuối thuộc giai đoạn 5.

## 2. Bước 1 — Chuyển liveness sang fail-closed

File thay đổi: [backend-ai/anti_spoofing.py](backend-ai/anti_spoofing.py).

Trước đây, khi checkpoint không tồn tại hoặc không tải được, `predict()` trả `(True, 0.95)`. Điều này biến lỗi hệ thống thành kết quả “người thật”. Các thay đổi đã thực hiện:

1. Tính đường dẫn checkpoint từ vị trí của module bằng `Path(__file__)`, nên chạy AI từ repo root hay `backend-ai` đều tìm cùng một file.
2. Lưu `model_name`, `model_path`, `load_error` và cung cấp thuộc tính `is_ready`.
3. Thêm `LivenessModelUnavailableError`.
4. Nếu model không sẵn sàng hoặc inference phát sinh lỗi, ném exception để API trả `MODEL_UNAVAILABLE`; không sinh điểm giả và không tiếp tục nhận diện.
5. Đổi giá trị điểm trả về thành `score[real]` nhất quán. Trước đây biến `spoof_score` là điểm của lớp thắng, nên ý nghĩa thay đổi theo kết quả. Giai đoạn 2 mới hiệu chỉnh ngưỡng riêng cho điểm này.

Crop và cách chọn lớp bằng `argmax` vẫn được giữ để không trộn thay đổi thuật toán của giai đoạn 2 vào giai đoạn 1.

## 3. Bước 2 — Tách liveness của tiến trình và readiness của dịch vụ

File thay đổi: [backend-ai/main.py](backend-ai/main.py).

Hai endpoint có ý nghĩa khác nhau:

- `GET /`: chỉ chứng minh tiến trình FastAPI đang chạy. Phản hồi chứa đường dẫn `/ready`.
- `GET /ready`: kiểm tra model nhận diện, model liveness và kết nối database. Trả HTTP `200` khi cả ba sẵn sàng, HTTP `503` nếu thiếu một dependency.

Ví dụ readiness thành công:

```json
{
  "status": "ready",
  "dependencies": {
    "faceModel": true,
    "livenessModel": true,
    "database": true
  },
  "models": {
    "faceRecognition": "buffalo_l",
    "antiSpoofing": "2.7_80x80_MiniFASNetV2.pth"
  }
}
```

Không đưa chi tiết exception hoặc thông tin xác thực database vào response readiness.

## 4. Bước 3 — Chuẩn hóa hợp đồng xác thực của AI

File thay đổi: [backend-ai/main.py](backend-ai/main.py).

Mọi kết quả của `POST /api/verify-face` có cùng cấu trúc. FastAPI dùng HTTP `200` cho kết quả quyết định để Spring luôn đọc được hợp đồng và tự ánh xạ sang HTTP dành cho Kiosk. Endpoint `/ready` vẫn dùng HTTP `503` đúng nghĩa khi dependency chưa sẵn sàng.

Ví dụ thành công:

```json
{
  "status": "success",
  "requestId": "6bc3f9cd-...",
  "reasonCode": "FACE_VERIFIED",
  "message": "Xác thực khuôn mặt thành công.",
  "faceCount": 1,
  "liveness": {
    "status": "PASSED",
    "isReal": true,
    "liveScore": 0.91
  },
  "recognition": {
    "status": "MATCHED",
    "recognized": true,
    "userId": 7,
    "username": "nv007",
    "similarity": 0.68,
    "threshold": 0.5,
    "gallerySize": 10
  },
  "models": {
    "faceRecognition": "buffalo_l",
    "antiSpoofing": "2.7_80x80_MiniFASNetV2.pth"
  },
  "timingsMs": {
    "decode": 2.1,
    "detectionAndEmbedding": 184.2,
    "liveness": 14.8,
    "database": 3.5,
    "matching": 0.3,
    "total": 205.7
  }
}
```

Các bước quyết định trong AI:

1. Sinh UUID cho mỗi request, hoặc giữ `X-Request-ID` hợp lệ dài tối đa 64 ký tự.
2. Kiểm tra đuôi file, kích thước và khả năng giải mã ảnh.
3. Kiểm tra hai model sẵn sàng.
4. Phát hiện khuôn mặt; từ chối khi không có mặt hoặc có nhiều hơn một mặt.
5. Kiểm tra embedding probe có đúng 512 số hữu hạn và chuẩn khác 0.
6. Chạy liveness; lỗi inference trở thành `MODEL_UNAVAILABLE`.
7. Đọc gallery; lỗi kết nối là `DB_UNAVAILABLE`, không bị biến thành gallery rỗng.
8. Kiểm tra từng template database. Có template sai kích thước, NaN/Infinity, zero-vector hoặc JSON lỗi thì trả `INVALID_TEMPLATE`.
9. Gallery hợp lệ nhưng rỗng trả `NO_ENROLLMENT`.
10. Tính cosine, kẹp sai số số thực về `[-1, 1]`, chọn kết quả tốt nhất và so với ngưỡng `0.5` hiện tại.

Endpoint `POST /api/extract-embedding` cũng trả `requestId`, `reasonCode`, `faceCount`, tên model và thời gian. Đăng ký chỉ chấp nhận đúng một mặt và embedding hợp lệ.

## 5. Bước 4 — Định nghĩa mã lý do

| Mã | Ý nghĩa | HTTP Spring trả cho Kiosk |
| --- | --- | --- |
| `FACE_VERIFIED` | Liveness qua, nhận diện khớp và user tồn tại | 200 |
| `NO_ENROLLMENT` | Database hoạt động nhưng không có template hợp lệ | 409 |
| `NO_FACE` | Không phát hiện khuôn mặt | 422 |
| `MULTIPLE_FACES` | Có nhiều hơn một khuôn mặt | 422 |
| `LOW_QUALITY` | Ảnh không đạt kiểm tra chất lượng | 422 |
| `LIVENESS_UNCERTAIN` | Điểm liveness nằm vùng chưa chắc chắn | 422 |
| `SPOOF_DETECTED` | Liveness phân loại giả mạo | 401 |
| `NOT_RECOGNIZED` | Có mặt thật nhưng không vượt ngưỡng nhận diện | 401 |
| `MODEL_UNAVAILABLE` | Model thiếu, lỗi hoặc AI timeout | 503 |
| `DB_UNAVAILABLE` | Không thể đọc gallery | 503 |
| `INVALID_TEMPLATE` | Có dữ liệu embedding đăng ký bị hỏng | 503 |
| `INVALID_AI_RESPONSE` | Hợp đồng AI thiếu/sai/mâu thuẫn | 502 |
| `INVALID_IMAGE` | File sai loại, rỗng, quá lớn hoặc giải mã lỗi | 422 |
| `INTERNAL_ERROR` | Lỗi chưa phân loại | 500 |

`LOW_QUALITY` và `LIVENESS_UNCERTAIN` đã được định nghĩa xuyên suốt AI → Spring → Kiosk để hợp đồng ổn định. Logic tính chất lượng và ngưỡng uncertain sẽ được thêm ở giai đoạn 2–3 sau khi có dữ liệu hiệu chỉnh.

## 6. Bước 5 — Ghi log đủ để chẩn đoán, không ghi dữ liệu nhạy cảm

File thay đổi: [backend-ai/main.py](backend-ai/main.py) và [ApiController.java](backend-core/src/main/java/com/example/btl_iot/controller/ApiController.java).

FastAPI ghi theo mỗi lượt:

- `request_id`, `reason`, trạng thái và số mặt;
- `quality_score`, `live_score`, similarity và gallery size;
- tên hai model và ngưỡng nhận diện đang dùng;
- thời gian decode, detection/embedding, liveness, database, matching và tổng thời gian;
- sự kiện model/database/template lỗi.

Trong giai đoạn 1, `quality_score` được ghi là `None` để biểu diễn rõ bước quality gate chưa chạy; không tạo một điểm giả. Điểm này chỉ có giá trị sau khi quality gate được định nghĩa và hiệu chỉnh ở giai đoạn 3.

Spring ghi `requestId`, quyết định accept/reject và nguyên nhân. Log không mặc định chứa ảnh gốc, embedding, PIN hoặc mật khẩu database. `username` vẫn có trong response nội bộ AI → Spring để tương thích và chẩn đoán; Spring không trả username hoặc các điểm AI về Kiosk.

## 7. Bước 6 — Spring kiểm tra chặt trước khi mở cửa

Các file thay đổi/thêm:

- [AiVerificationResponse.java](backend-core/src/main/java/com/example/btl_iot/dto/AiVerificationResponse.java): DTO kiểu rõ ràng cho hợp đồng AI.
- [HttpClientConfig.java](backend-core/src/main/java/com/example/btl_iot/config/HttpClientConfig.java): timeout kết nối/đọc.
- [ApiController.java](backend-core/src/main/java/com/example/btl_iot/controller/ApiController.java): xác minh hợp đồng và quyết định.

Spring chỉ gọi `sendOpenDoorCommand()` khi đồng thời thỏa mãn:

1. `status=success`, `reasonCode=FACE_VERIFIED`, `faceCount=1`.
2. Liveness có `status=PASSED`, `isReal=true`, `liveScore` hữu hạn trong `[0,1]`.
3. Recognition có `status=MATCHED`, `recognized=true`.
4. `userId > 0`, gallery có ít nhất một người.
5. Similarity/ngưỡng hữu hạn trong `[-1,1]` và `similarity >= threshold`.
6. `userId` tồn tại trong database Core.

Thiếu trường, mã lạ, `success` tự mâu thuẫn, ID không tồn tại hoặc response không đọc được đều bị từ chối. Chỉ sau khi tìm được `User`, Core mới lưu `SUCCESS` và gửi MQTT. Các lượt từ chối được lưu bằng `reasonCode` cụ thể thay vì `FAILED` chung.

Spring cũng kiểm tra response đăng ký phải có `status=success`, `reasonCode=EMBEDDING_EXTRACTED`, request ID và đúng 512 số hữu hạn trước khi lưu user.

## 8. Bước 7 — Kiosk hiển thị hướng dẫn và có timeout

File thay đổi: [frontend/src/Kiosk.jsx](frontend/src/Kiosk.jsx).

Các thay đổi:

1. Ánh xạ từng `reasonCode` sang hướng dẫn tiếng Việt.
2. Nếu `getScreenshot()` chưa tạo được ảnh, báo camera chưa sẵn sàng thay vì không làm gì.
3. Nếu HTTP 200 nhưng payload không phải success, thoát trạng thái SCANNING và hiển thị lỗi.
4. Timeout khuôn mặt 25 giây; timeout PIN 10 giây.
5. Thông báo lỗi mặt giữ 8 giây, thành công 3 giây; timer cũ được hủy khi tạo timer mới hoặc component unmount.
6. Báo rõ khi trình duyệt không có quyền truy cập camera.

Cấu hình độ phân giải nguồn và tách mirror preview khỏi ảnh gửi lên thuộc giai đoạn 3, nên chưa thay đổi ở đây.

## 9. Bước 8 — Kiểm thử được thêm

### Python

File: [test_stage1_face_auth.py](backend-ai/tests/test_stage1_face_auth.py).

Các trường hợp gồm model liveness thiếu phải fail-closed, model nhận diện thiếu, không có mặt, nhiều mặt, spoof, database lỗi khác gallery rỗng, không nhận diện và hợp đồng thành công đầy đủ. Model/camera thật không được dùng để mở cửa trong test.

Chạy:

```powershell
cd backend-ai
.\.venv\Scripts\python.exe -B -m unittest discover -s tests -v
```

### Spring Boot

File: [ApiControllerFaceVerificationTests.java](backend-core/src/test/java/com/example/btl_iot/controller/ApiControllerFaceVerificationTests.java).

Test dùng AI HTTP giả và MQTT publisher giả. Mọi mã lỗi chuẩn, thiếu `userId`, ID không tồn tại và similarity thấp hơn ngưỡng đều phải có **0 lần** gọi lệnh mở cửa. Chỉ hợp đồng đầy đủ với user tồn tại được gọi đúng một lần.

Chạy:

```powershell
cd backend-core
.\gradlew.bat test
```

### Frontend

Chạy lint riêng file đã sửa và build production:

```powershell
cd frontend
npx oxlint src/Kiosk.jsx
npm run build
```

### Kết quả kiểm chứng ngày 12/09/2026

| Thành phần | Kết quả |
|---|---|
| AI Python | 8/8 ca kiểm thử đạt, 0 lỗi |
| Spring Boot | 20/20 ca kiểm thử đạt, gồm 19 ca cho luồng xác thực khuôn mặt và 1 ca khởi tạo ứng dụng |
| Kiosk | `oxlint src/Kiosk.jsx` đạt, 0 lỗi |
| Frontend | Vite production build thành công, 1.870 module được xử lý |

Các bài test Spring dùng HTTP server và MQTT publisher giả, vì vậy quá trình kiểm chứng không phát lệnh mở cửa tới thiết bị thật. `git diff --check` cũng đạt; các cảnh báo duy nhất là quy ước xuống dòng LF/CRLF trên Windows, không phải lỗi mã nguồn.

## 10. Cách kiểm tra thủ công sau khi chạy lại dịch vụ

1. Khởi động MySQL, MQTT, AI, Spring và frontend theo README.
2. Mở `http://localhost:8000/`: phải báo tiến trình đang chạy.
3. Mở `http://localhost:8000/ready`: phải trả `status=ready` và ba dependency đều `true`.
4. Khi chưa đăng ký ai, quét một khuôn mặt thật: Kiosk phải báo `NO_ENROLLMENT`, không báo chung “Verification failed”.
5. Đăng ký một ảnh chỉ có một khuôn mặt, sau đó quét lại.
6. Thử ảnh không có mặt và ảnh có hai người; UI phải đưa hướng dẫn khác nhau.
7. Quan sát log hai backend bằng cùng `requestId` để tìm bước gây lỗi.
8. Trong kiểm thử tự động, dùng publisher giả. Chỉ thử relay/khóa thật sau khi đã xác nhận cấu hình thiết bị và phạm vi vận hành an toàn.

## 11. Điều còn lại sau giai đoạn 1

Giai đoạn này làm cho quyết định dễ quan sát và an toàn khi dịch vụ lỗi; nó chưa chứng minh độ chính xác nhận diện tăng. Các việc tiếp theo vẫn là:

- sửa crop liveness theo code tham chiếu và hiệu chỉnh `liveScore`;
- xây dựng quality gate để thực sự phát sinh `LOW_QUALITY`;
- định nghĩa vùng `LIVENESS_UNCERTAIN` bằng dữ liệu;
- chuẩn hóa ảnh webcam và đăng ký nhiều mẫu;
- xây tập kiểm thử người thật/người lạ/ảnh in/video replay rồi chọn ngưỡng.

Không nên đánh giá giai đoạn 1 bằng việc một khuôn mặt cụ thể đã quét qua hay chưa. Tiêu chí của giai đoạn là nhận đúng nguyên nhân thất bại và bảo đảm không có đường mở cửa khi dependency hoặc hợp đồng AI không hợp lệ.
