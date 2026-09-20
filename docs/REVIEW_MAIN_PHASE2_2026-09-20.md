# Đối chiếu main mới với phase 2 của hnam

Ngày kiểm tra: 20/09/2026.

**Ghi chú lịch sử:** Báo cáo này mô tả trạng thái trước tích hợp. Việc xử lý conflict và kiểm thử sau đó được ghi tại [bàn giao tích hợp](PHASE_02_AI_INTEGRATION.md).

## 1. Trạng thái và phạm vi

- Đã fetch GitHub, chuyển sang `main` và pull fast-forward thành công tới `62accc8` (merge PR #3).
- `hnam` vẫn ở `f0f1eab`, cùng commit với `origin/hnam`; phase 2 đang là thay đổi chưa commit.
- Đã quay về `hnam` và khôi phục toàn bộ thay đổi phase 2, kể cả file mới chưa được Git theo dõi. Cây nội dung sau khôi phục khớp cây nội dung trước cập nhật.
- Giữ stash dự phòng `419c31ecfaa28d19868ed53cc4c6a2c8791b1f9a`, mô tả `Safety backup phase2 before main update 2026-09-20`. Không apply lại khi code đã được khôi phục.
- Dùng snapshot và phép gộp thử bằng `git merge-tree`, không merge vào nhánh thật. Snapshot chẩn đoán không nằm trên lịch sử nhánh `hnam` hoặc `main`.
- Không push, không commit phase 2, không sửa code để giải quyết conflict. Báo cáo này là file mới được thêm sau khi xác minh khôi phục.

## 2. Những cập nhật đáng chú ý trên main

So với commit `f0f1eab` của hnam, main thay đổi 33 file. Các cập nhật gồm bộ đánh giá AI (`d43bf0c`), các thay đổi xử lý lỗi/xác thực được gộp từ nhánh AI và cải tiến chọn khuôn mặt/tốc độ (`7550a10`).

### Pipeline và an toàn AI

- Tách xử lý nhận diện thành `FacePipeline`, dùng chung cho API và công cụ đánh giá offline.
- Khi xác thực, chọn khuôn mặt có diện tích hiển thị lớn nhất; nếu bằng nhau ưu tiên gần tâm ảnh. Không thử một khuôn mặt khác nếu mặt đã chọn thất bại. Khi đăng ký hồ sơ vẫn yêu cầu đúng một khuôn mặt.
- Chỉ chạy lấy embedding cho khuôn mặt đã chọn, giảm công việc nhận diện không cần thiết ở ảnh nhiều người. Chưa đo mức cải thiện tốc độ trên camera thực tế trong lần kiểm tra này.
- Model được tải trong vòng đời khởi động, có kiểm tra tài nguyên và endpoint `/ready` để kiểm tra model/database.
- Thiếu model chống giả mạo hoặc lỗi suy luận sẽ từ chối xác thực, không mặc định cho qua.
- Kiểm tra ảnh, embedding, kết quả liveness và lỗi database rõ ràng hơn; chỉ so khớp gallery sau khi qua chống giả mạo.

### Giao tiếp AI – Core – Kiosk

- AI trả cấu trúc mới với `requestId`, `reasonCode`, `faceCount`, `liveness`, `recognition`, thông tin model và thời gian xử lý.
- Core bổ sung DTO và kiểm tra tính nhất quán của phản hồi AI trước khi phát lệnh MQTT; phân biệt các nhóm lỗi xác thực, model, database và phản hồi sai định dạng.
- Có HTTP client cấu hình timeout kết nối 3 giây, đọc 20 giây.
- Kiosk bổ sung thông báo theo mã lỗi, camera chưa sẵn sàng, timeout và quản lý thời gian đặt lại giao diện.

### Bộ đánh giá offline và cấu hình

- Thêm công cụ thu ảnh, manifest, kiểm tra rò rỉ dữ liệu giữa các tập, snapshot cấu hình/model và tính chỉ số đánh giá.
- Ba nhánh đánh giá: nhận diện riêng, chống giả mạo riêng và kết hợp. Nhánh chẩn đoán không phải cơ chế bỏ qua chống giả mạo của API mở cửa.
- Tài liệu `PHASE2A_EVALUATION_GUIDE.md` xác nhận chưa có số liệu độ chính xác vì chưa có bộ webcam được gán nhãn. Test qua không chứng minh độ chính xác thực tế đã tăng.
- Core hỗ trợ thêm cấu hình `.env`; vẫn giữ cấu hình local. Không cần commit thông tin đăng nhập hoặc cấu hình riêng của máy.

## 3. Conflict trực tiếp do Git phát hiện

| File | Nguyên nhân | Hướng kết hợp |
| --- | --- | --- |
| `backend-core/src/main/java/com/example/btl_iot/controller/ApiController.java` | Main đổi xử lý AI và kiểm tra kết quả; phase 2 tách service, thay luồng đăng ký và PIN. | Giữ kiến trúc service, mã hồ sơ + PIN, kiểm tra quyền hiện tại của phase 2; chuyển kiểm tra phản hồi AI mới vào FaceGateway và truyền kết quả/lỗi phù hợp về Core. |
| `frontend/src/Kiosk.jsx` | Main đổi thông báo lỗi AI, timeout và timer; phase 2 thêm mã hồ sơ, xử lý PIN và timer riêng. | Giữ mã hồ sơ + PIN, kết hợp reasonCode/timeout/camera từ main, thống nhất một cơ chế reset timer. |

Không chọn toàn bộ một phía bằng “Accept Current” hoặc “Accept Incoming”: có nguy cơ mất bảo mật phase 2 hoặc mất cải tiến AI.

README và application.properties được Git gộp tự động trong phép thử; cấu hình import mới và cờ migration PIN đều còn. Thay đổi package-lock.json ở phase 2 trùng với nội dung main nên không có khác biệt giữa hai bản này.

## 4. Lỗi tương thích không được Git báo conflict

### 4.1. FaceGateway đọc sai cấu trúc AI mới — cần xử lý trước khi kiểm thử tích hợp

`FaceGateway.identify()` của phase 2 đang đọc các trường cấp gốc:

```text
is_real
recognized
user_id
```

AI mới trả:

```text
liveness.isReal
recognition.recognized
recognition.userId
```

Giữ nguyên FaceGateway sẽ làm phản hồi xác thực thành công của AI mới bị coi là không nhận diện được. File này là file mới ở hnam nên Git không báo xung đột văn bản.

Không chỉ đổi tên ba trường: cần giữ các kiểm tra chặt từ main đối với status/reasonCode, requestId, trạng thái liveness/recognition, số mặt, ID, điểm số và ngưỡng; bảo toàn reasonCode để Kiosk hiển thị đúng. Kiểm tra lại cả endpoint trích xuất embedding khi đăng ký.

### 4.2. Test Core mới chưa phù hợp kiến trúc phase 2

`ApiControllerFaceVerificationTests` của main khởi tạo controller với UserRepository, AccessLogRepository, MqttPublisher và RestTemplate. Controller phase 2 nhận ProfileService, DoorAccessService và FaceGateway trong kiến trúc mới. Nếu giữ controller phase 2 mà không cập nhật test, các test này sẽ không biên dịch.

Cần chuyển các tình huống kiểm tra phản hồi AI sang lớp phù hợp, không xóa test chỉ để build thành công.

### 4.3. Phải bảo toàn ranh giới quyền mở cửa của phase 2

- AI chỉ xác định danh tính; Core vẫn kiểm tra hồ sơ còn hiệu lực trước khi phát lệnh mở cửa.
- Giữ PIN băm BCrypt, mã hồ sơ + PIN, giới hạn thử sai, audit và migration PIN có kiểm soát.
- Giữ xử lý kết quả publish MQTT của phase 2; không báo thành công khi gửi lệnh thất bại.
- Không mang lại truy vấn PIN dạng rõ hoặc luồng chỉ nhập PIN từ controller main.
- Rà soát đồng bộ timeout: FaceGateway phase 2 hiện tự tạo client với thời gian đọc 15 giây, còn bean mới của main là 20 giây; không mặc định cho rằng bean mới tự áp dụng cho FaceGateway.

## 5. Kết quả kiểm thử trong lần kiểm tra này

Các kết quả dưới đây chạy trên **main tại 62accc8**, trước khi khôi phục hnam:

| Phạm vi | Kết quả |
| --- | --- |
| AI: unittest discover | 41 test qua |
| Core: Gradle test | 34 test qua: 9 test ứng dụng và 25 test xác thực khuôn mặt |
| Frontend: npm test | 4 test qua |
| Frontend: npm run build | Thành công |

Test ứng dụng Core dùng H2 và tắt MQTT; test AI dùng giả lập ở các tình huống kiểm thử. Không thực hiện đo độ chính xác bằng dữ liệu thực, kiểm thử camera/khóa vật lý hay migration database thật. Chưa có bản gộp hoàn chỉnh để chạy test tích hợp main + phase 2.

## 6. Bước tiếp theo đề xuất

1. Sau khi được duyệt, tích hợp main vào phần phase 2 trên hnam, xử lý hai conflict trực tiếp.
2. Cập nhật FaceGateway theo hợp đồng AI mới và giữ toàn bộ kiểm soát quyền ra vào của phase 2.
3. Điều chỉnh/bổ sung test: khuôn mặt hợp lệ, spoof, thiếu model, phản hồi AI không hợp lệ, nhiều mặt, hồ sơ bị thu hồi, MQTT lỗi và mã hồ sơ + PIN.
4. Chạy lại cả ba bộ kiểm thử, build frontend, sau đó kiểm thử thủ công trên môi trường thật.
5. Review danh sách file trước khi commit/push hnam; tạo PR vào main sau khi bản tích hợp ổn định.

Conflict với main không ngăn việc push lên nhánh hnam riêng; nó ảnh hưởng việc gộp PR vào main. Tuy vậy, nên xử lý tương thích và kiểm thử bản kết hợp trước khi yêu cầu merge phase 2.
