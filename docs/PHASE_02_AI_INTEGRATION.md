# Bàn giao tích hợp phase 2 với AI mới

Ngày: 20/09/2026. Nhánh làm việc: `hnam`.

## 1. Trạng thái Git và phạm vi

- Đưa `hnam` lên nền `main` tại `62accc8` bằng fast-forward, sau đó áp lại và tích hợp phần phase 2 chưa commit.
- Đã xử lý conflict ở `ApiController.java` và `Kiosk.jsx`, đồng thời sửa lỗi tương thích không được Git báo tại `FaceGateway`.
- Không tạo commit mới và không push. Các thay đổi phase 2/tích hợp để ở trạng thái chưa stage cho bạn review.
- `hnam` hiện ahead `origin/hnam` 9 commit do đã nhận lịch sử main; không phải 9 commit mới do lần tích hợp này tạo ra. Phase 2 vẫn cần được commit trước khi push.
- Giữ stash dự phòng `090af23de73d2b08211c17d949d4d75ac8b35ba6` (`Safety phase2 before AI integration 2026-09-20`), cùng bản sao lưu trước đó. Không apply lại stash vào bản đang làm vì phần code đó đã được tích hợp.
- Code Python AI giữ nguyên theo main; không thay model, ngưỡng hay chính sách chọn khuôn mặt của thành viên phụ trách AI. Không sửa cấu hình riêng máy, chạy migration MySQL thật hoặc phát lệnh đến cửa thật.

## 2. Những phần đã kết hợp

### Core và ranh giới quyền mở cửa

Giữ kiến trúc phase 2: `ApiController` điều phối, `FaceGateway` gọi AI, `DoorAccessService` quyết định quyền và gửi lệnh. Không khôi phục luồng tìm người chỉ bằng PIN hay lưu PIN rõ của controller cũ trên main.

FaceGateway đọc cấu trúc mới: `liveness.isReal`, `recognition.recognized`, `recognition.userId`. Trước khi công nhận kết quả thành công, kiểm tra:

- status `success`, reasonCode `FACE_VERIFIED`, requestId dạng chuỗi không rỗng (tối đa 128 ký tự);
- faceCount là số nguyên dương; chấp nhận nhiều mặt đúng theo chính sách chọn mặt lớn nhất của AI;
- liveness `PASSED`, isReal đúng kiểu boolean và bằng true, liveScore hữu hạn trong [0, 1];
- recognition `MATCHED`, recognized đúng kiểu boolean và bằng true;
- userId là số nguyên dương vừa kiểu long, gallerySize là số nguyên dương;
- similarity/threshold hữu hạn trong [-1, 1], similarity đạt threshold.

Thiếu trường, sai kiểu (ví dụ chuỗi `"true"`), sai trạng thái hoặc phản hồi legacy không được phép mở cửa. Core dùng thông báo cố định theo reasonCode thay vì hiển thị tùy ý nội dung lỗi AI.

Sau kết quả AI hợp lệ, Core vẫn khóa dòng và kiểm tra hồ sơ hiện tại: tồn tại, còn quyền, có dữ liệu khuôn mặt. Giữ các kiểm soát phase 2: BCrypt, mã hồ sơ + PIN, giới hạn thử sai, audit, reset PIN không tự cấp lại quyền, version chống ghi đè và migration opt-in.

Kết quả gửi MQTT tiếp tục được kiểm tra. `SUCCESS` chỉ nghĩa là đã gửi lệnh thành công theo publisher, không phải cảm biến xác nhận chốt cửa mở.

### Đăng ký hồ sơ

- Ảnh vẫn tùy chọn; hồ sơ chỉ dùng PIN không cần AI.
- Nếu có ảnh: yêu cầu envelope `EMBEDDING_EXTRACTED` + requestId hợp lệ, vector đúng 512 số hữu hạn, khác vector zero và norm bình phương hữu hạn/dương.
- Giữ giới hạn ảnh phía Core: JPEG/PNG, tối đa 5 MB và 4096 × 4096 pixel.
- Lỗi trích xuất giữ reasonCode/requestId qua API; không tạo hồ sơ dở dang. Chính sách ảnh đăng ký đúng một mặt được AI thực thi.

### Kiosk và timeout

- Giữ nhập mã hồ sơ + PIN; PIN vẫn là chuỗi để bảo toàn số 0 đầu và được xóa khỏi ô nhập sau khi gửi.
- Ghép thông báo theo reasonCode, lỗi camera và phản hồi `status:error` dù HTTP là 200.
- Dùng một reset timer, hủy timer cũ trước lượt xác thực mới/chuyển chế độ. Có chặn gửi đồng thời khi đang chờ.
- FaceGateway dùng RestTemplate chung: connect 3 giây, read 20 giây. Frontend chờ face 35 giây để có khoảng trống cho thời gian AI và MQTT chờ tối đa 5 giây; PIN chờ 10 giây. Đây không phải bảo đảm tổng thời gian tối đa khi database/hệ thống bị nghẽn.
- Timeout/mất mạng cảnh báo có thể lệnh đã được gửi: kiểm tra cửa trước khi thử lại. Không tự gửi lại yêu cầu mở cửa.

## 3. Mã lỗi quan trọng cho nhóm tích hợp

| Tình huống | HTTP | reasonCode |
| --- | --- | --- |
| AI xác thực và MQTT gửi thành công | 200 | FACE_VERIFIED |
| Giả mạo / không nhận ra | 401 | SPOOF_DETECTED / NOT_RECOGNIZED |
| Hồ sơ bị thu hồi hoặc chưa có ảnh | 401 | PROFILE_NOT_ALLOWED |
| Không có mặt, nhiều mặt khi đăng ký, chất lượng/ảnh không hợp lệ | 422 | NO_FACE / MULTIPLE_FACES / LOW_QUALITY / LIVENESS_UNCERTAIN / INVALID_IMAGE |
| Chưa có gallery | 409 | NO_ENROLLMENT |
| AI thiếu model/không kết nối được/quá thời gian | 503 | MODEL_UNAVAILABLE |
| AI lỗi database/template | 503 | DB_UNAVAILABLE / INVALID_TEMPLATE |
| Phản hồi AI sai hoặc trả ID không tồn tại ở Core | 502 | INVALID_AI_RESPONSE |
| AI báo lỗi xử lý nội bộ | 500 | INTERNAL_ERROR |
| Không xác nhận gửi MQTT thành công | 503 | COMMAND_FAILED |

Kiểm tra ảnh đầu vào của Core vẫn có thể trả 400 trước khi gọi AI; lỗi multipart quá lớn/CSRF/đăng nhập tiếp tục theo cơ chế hiện có. requestId chỉ xuất hiện khi có ID hợp lệ từ AI. Lỗi kết nối hoặc không đọc được phản hồi có thể không có requestId.

## 4. Kiểm thử

Kết quả cuối ngày 20/09/2026 trên bản tích hợp `hnam`:

| Kiểm tra | Kết quả |
| --- | --- |
| Core: `gradlew.bat test --no-daemon` | 97 test qua; 0 lỗi, 0 bỏ qua |
| AI: `.venv/Scripts/python.exe -m unittest discover -s tests -q` | 41 test qua |
| Frontend: `npm test` | 10 test qua |
| Frontend: `npm run build` | Thành công |
| Frontend: `npm run lint` | Thành công |
| Git | Không còn unmerged paths; index thật trống; `git diff --check` không lỗi |

97 test Core gồm 23 test HTTP/ứng dụng, 49 test controller AI với gateway/service thật và hạ tầng giả lập, 20 test FaceGateway, 1 test nâng cấp schema, 2 test MQTT và 2 test giới hạn thử. Có cảnh báo deprecated API/JVM và LF/CRLF; không làm test/build thất bại. Cảnh báo thiếu model trong test AI là tình huống test cố ý dùng thư mục tạm rỗng để kiểm tra từ chối an toàn.

Phạm vi test gồm:

- Giữ các tình huống đăng nhập, PIN, quản lý hồ sơ, migration và MQTT của phase 2.
- Giữ 25 ca test controller AI của main, chuyển cách khởi tạo sang controller → gateway → access service; dùng ảnh PNG hợp lệ và HTTP AI giả lập.
- Bổ sung phản hồi sai kiểu/điểm số/trạng thái; nhiều mặt; hồ sơ đã thu hồi/chưa có ảnh; MQTT thất bại; reasonCode/requestId; lỗi model/kết nối; dữ liệu enrollment sai và phản hồi legacy.
- HTTP với H2 kiểm tra mã lỗi tới client, audit và không tạo hồ sơ khi AI từ chối ảnh.
- Frontend kiểm tra thông báo, timeout, mã hồ sơ + PIN và cookie/CSRF; build và lint.

Các test tự động không thay thế kiểm thử camera/model thật, MySQL thật, broker/ESP32 hoặc toàn bộ UI trong trình duyệt. Không có kết luận mới về độ chính xác AI từ các test giả lập.

## 5. Checklist trước khi commit/push

1. Review diff so với main: phần AI đã có trong lịch sử main; tập trung thay đổi phase 2 và lớp tích hợp.
2. Dùng môi trường thử nghiệm: đăng nhập, tạo hồ sơ PIN-only, mã + PIN đúng/sai, reset PIN và thu hồi quyền.
3. Tạo hồ sơ có ảnh; thử khuôn mặt hợp lệ/không đăng ký/giả mạo. Thu hồi hồ sơ rồi kiểm tra cả face và PIN đều bị từ chối.
4. Thử lỗi AI và broker; không coi thông báo gửi MQTT là xác nhận chốt đã mở. Kiểm tra cửa trước khi thử lại sau timeout.
5. Nếu nâng cấp database cũ, làm theo hướng dẫn backup/chuyển PIN trong tài liệu phase 2. Không tự bật migration nếu chưa cần.
6. Stage rõ file cần thiết, gồm các file service/test mới chưa theo dõi; review `git diff --cached --name-status` và `git diff --cached`. Không stage `.env`, local properties, `.venv`, `.runtime`, `.vscode`, model/dataset hoặc database dump.
7. Commit trên `hnam`, push `hnam` và mở PR vào main sau khi bạn duyệt. Nếu main tiếp tục thay đổi, đối chiếu lại trước khi merge PR.

Tài liệu nền: [phase 2 hồ sơ/PIN](PHASE_02_PROFILES_AND_PIN.md), [đối chiếu trước tích hợp](REVIEW_MAIN_PHASE2_2026-09-20.md), [chính sách mặt lớn nhất](LARGEST_FACE_VERIFICATION.md).
