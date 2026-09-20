# Bàn giao đợt 2 — Hồ sơ ra vào và mã hồ sơ + PIN

Ngày: 14/09/2026. Nhánh làm việc: `hnam`. Phạm vi: mã nguồn và kiểm thử để nhóm review; chưa commit/push, chưa chạy chuyển đổi trên MySQL thật.

## 1. Phương án đã chốt

- Tài khoản quản trị (`admin_accounts`) và hồ sơ được phép vào (`users`) tiếp tục độc lập.
- Mở cửa bằng PIN phải nhập **mã hồ sơ + PIN của hồ sơ đó**. Mã hồ sơ là trường `username`, ví dụ `nv001`; không phải tài khoản quản trị hoặc ID số nội bộ của database.
- Hai hồ sơ có thể trùng PIN. Hệ thống tìm đúng hồ sơ trước, rồi kiểm tra PIN của hồ sơ đó; không tìm người chỉ từ PIN.
- Mã hồ sơ chỉ là định danh, không phải bí mật. Cách nhập này không phải xác thực hai yếu tố (MFA).
- Chưa làm dự phòng mất điện/offline. Luồng PIN vẫn cần frontend, Core, database và kết nối gửi lệnh tới cửa.

## 2. Chức năng bổ sung

### Quản lý hồ sơ

- Tạo hồ sơ với mã, họ tên, PIN. Ảnh khuôn mặt không bắt buộc: có thể tạo hồ sơ chỉ dùng PIN, không cần gọi AI.
- Nếu gửi ảnh thì ảnh phải hợp lệ và AI phải trả embedding hợp lệ trước khi lưu hồ sơ; lỗi ảnh/AI không tạo hồ sơ dở dang.
- Danh sách có tìm kiếm theo mã/họ tên, hiển thị trạng thái quyền thật và tình trạng cần đặt/chuyển đổi PIN.
- Sửa họ tên; mã hồ sơ giữ nguyên để tránh thay đổi định danh đăng nhập cửa.
- Thu hồi/cấp lại quyền. Không xóa cứng hồ sơ nên lịch sử ra vào vẫn liên kết với đúng người.
- Admin đặt PIN mới nhưng không xem lại PIN đã lưu. PIN cũ hết hiệu lực sau khi cập nhật; đặt PIN không tự cấp lại quyền cho hồ sơ bị thu hồi.
- API sửa hồ sơ/PIN cần `version` hiện tại, chống ghi đè một bản chỉnh sửa mới hơn. Gặp 409 thì tải lại danh sách, mở lại form và review thay đổi trước khi gửi lại.

### Bảo mật PIN và quyết định mở cửa

- PIN mới gồm **6–10 chữ số ASCII**, giữ nguyên số 0 đầu. Không đổi PIN sang kiểu số.
- Dùng BCrypt cost 12 với salt riêng cho mỗi bản băm; cùng PIN vẫn có hash khác nhau. Không dùng SHA thuần hoặc lưu PIN mới dạng plaintext.
- `pin_code` cũ không được dùng để xác thực; chỉ còn phục vụ chuyển đổi dữ liệu cũ. Đăng ký/đặt lại PIN ghi `pin_hash` và để `pin_code = NULL`.
- API danh sách/trả hồ sơ chỉ dùng DTO cho phép rõ từng trường; không trả PIN, hash hoặc embedding. Entity cũng có `JsonIgnore` cho dữ liệu nhạy cảm.
- Mỗi mã hồ sơ tối đa 3 lượt thử trong cửa sổ 5 phút; thành công xóa bộ đếm mã đó. Sau 3 lượt thất bại, lượt tiếp theo nhận 429; có audit `PIN_THROTTLED` ở lượt thứ ba. Key bộ đếm không phân biệt chữ hoa/thường.
- Bổ sung tối đa 20 lượt PIN/IP trong 5 phút, tính cả lượt thành công; độc lập với giới hạn đăng nhập admin. IP lấy từ kết nối tới Core, không tin header do client tự gửi.
- Sai mã, sai PIN, chưa chuyển đổi PIN hoặc hồ sơ bị thu hồi đều nhận thông báo 401 chung, không công khai lý do cụ thể.
- Hồ sơ bị thu hồi cũng bị từ chối ở luồng khuôn mặt. Core kiểm tra lại hồ sơ có tồn tại, còn quyền và có dữ liệu khuôn mặt; không chỉ tin `recognized=true` từ AI.
- Các thao tác thu hồi/đổi PIN/xác thực cùng khóa dòng hồ sơ trong transaction. Một yêu cầu được xử lý sau khi thu hồi/đổi PIN đã commit sẽ dùng trạng thái mới. Lệnh đã được cấp phép/gửi trước khi thu hồi không thể bị “thu hồi ngược”.

### Nhật ký và thông báo gửi lệnh

- Audit mới: `PROFILE_CREATED`, `PROFILE_UPDATED`, `PROFILE_ENABLED`, `PROFILE_DISABLED`, `PROFILE_PIN_RESET`, `PIN_THROTTLED`, `LEGACY_PIN_CLEARED`. Không ghi PIN/hash/token vào audit. Chưa có UI xem security audit.
- Access log vẫn có phương thức `PIN`/`FACE`, người liên quan và trạng thái. `SUCCESS` trong code mới nghĩa là đã xác thực và hàm gửi MQTT trả thành công, không phải cảm biến xác nhận chốt mở.
- `COMMAND_FAILED`/HTTP 503 khi không xác nhận được gửi lệnh. Lỗi mạng có thể khiến việc giao lệnh chưa rõ kết quả; phải kiểm tra cửa, không tự gửi lại liên tục.
- MQTT giữ nguyên topic, payload `OPEN_DOOR`, QoS 1. Chỉ bổ sung kết quả gửi và giới hạn chờ 5 giây để không giữ khóa hồ sơ vô thời hạn; chưa bổ sung TLS/ACL/xác thực thiết bị/chống phát lại.
- Dashboard đổi nhãn thành lượt xác thực/đã gửi lệnh, bỏ thông báo tĩnh “Hệ thống an toàn”/“MQTT kết nối bình thường” vốn chưa được đo thực tế. Log lịch sử cũ có thể mang ngữ nghĩa `SUCCESS` của phiên bản trước.

## 3. API để thành viên khác tích hợp

Các API ghi vẫn yêu cầu cookie phiên và CSRF như đợt 1. API Kiosk công khai chỉ để xác thực mở cửa, không cho phép quản lý hồ sơ. Không dùng CSRF token như khóa xác thực thiết bị.

| API | Quyền | Dữ liệu và kết quả |
| --- | --- | --- |
| POST `/api/register` | ADMIN + CSRF | Multipart `username`, `fullName`, `pinCode`, `file` tùy chọn; 201 với `status` và `profile` |
| GET `/api/users` | ADMIN | Danh sách DTO, gồm `id`, `username`, `name`, `enabled`, `version`, `hasPin`, `pinResetRequired`, `hasFace`, `method`, `status`, `lastActive` |
| PATCH `/api/users/{id}` | ADMIN + CSRF | JSON `enabled`, `version`, `fullName` tùy chọn; 200 với DTO mới |
| PUT `/api/users/{id}/pin` | ADMIN + CSRF | JSON `pinCode`, `version`; 200 với DTO mới, không trả PIN |
| POST `/api/verify-pin` | Công khai + CSRF | JSON `username`, `pinCode`; 200 khi xác thực và gửi lệnh thành công |
| POST `/api/verify-face` | Công khai + CSRF | Multipart `file`; Core kiểm tra quyền hiện tại sau kết quả AI |

Ví dụ cấu trúc yêu cầu PIN (chỉ là dữ liệu minh họa, không phải tài khoản có sẵn):

```json
{"username":"nv001","pinCode":"001234"}
```

Mã kết quả cần xử lý: 400 dữ liệu không hợp lệ; 401 chưa đăng nhập/sai cặp mã-PIN/không được phép vào; 403 CSRF/quyền không hợp lệ; 404 không có hồ sơ cần sửa; 409 trùng mã hoặc version cũ; 429 quá nhiều lượt; 502 lỗi AI; 503 chưa xác nhận được gửi lệnh.

Thay đổi có thể ảnh hưởng client cũ:

- Gửi chỉ `pinCode` không còn hợp lệ. Phải cập nhật frontend và Core cùng đợt.
- Tạo hồ sơ thành công trả 201 thay vì 200, vẫn có `status: success`.
- `lastActive` là datetime hoặc `null`, không còn chuỗi “Chưa từng hoạt động” từ server.
- Mã mới dùng 1–64 ký tự chữ/số/`_`/`-`, chuẩn hóa chữ thường khi tạo, tra cứu không phân biệt hoa/thường. Không tự đổi mã cũ. Mã cũ ngoài quy tắc này cần nhóm chuẩn hóa bằng quy trình dữ liệu riêng trước khi dùng PIN.
- Bàn phím vật lý chưa được triển khai lại. Nếu bàn phím chỉ có số, nhóm cần chọn mã hồ sơ dạng số hoặc thiết kế bước chọn hồ sơ ở đợt firmware; không coi giao diện Web hiện tại là đã tích hợp keypad ESP32.

## 4. Database và PIN cũ — đọc trước khi chạy

Các cột mới trong `users`: `pin_hash` nullable (100 ký tự), `enabled` mặc định true và `version` mặc định 0. Giữ `id`, `username`, `full_name`, `face_embedding`, `created_at`, `pin_code` và liên kết lịch sử. `ddl-auto=update` hiện vẫn do Hibernate quản lý; đây chưa phải bộ migration có version dành cho production.

**Không khởi động code mới trên database quan trọng khi chưa có bản sao lưu.** Trong lần làm này chỉ dùng H2 tạm để test, không khởi động ứng dụng mới với MySQL của bạn.

### Trình tự review/chuyển đổi

1. Dừng bản Core cũ và các thao tác ghi từ ứng dụng cũ. Sao lưu database bằng công cụ MySQL bạn đang dùng; giữ bản sao lưu ngoài repo vì còn chứa PIN và dữ liệu cá nhân. Tốt nhất chạy thử nâng cấp trên bản sao database trước.
2. Giữ nguyên cấu hình DB/admin riêng trong `backend-core/config/application-local.properties`; không chép file `.example` đè lên file này.
3. Có hai cách xử lý PIN cũ:
   - **Đặt lại thủ công:** để migration tắt, khởi động code mới, đăng nhập admin → Hồ sơ ra vào → Sửa/Đặt PIN. Mỗi lần đặt PIN sẽ thay hash và xóa plaintext của chính hồ sơ đó. PIN cũ chưa chuyển đổi sẽ bị từ chối; khuôn mặt vẫn theo trạng thái quyền hiện tại.
   - **Chuyển đổi hàng loạt có chủ động:** sau backup, thêm `SMARTLOCK_MIGRATE_LEGACY_PINS=true` vào file local rồi khởi động Core mới. Runner thực hiện transaction chuyển đổi các dòng có `pin_code`.
4. Với chuyển đổi hàng loạt:
   - PIN cũ có 6–10 chữ số → băm, giữ nguyên giá trị sử dụng, kể cả số 0 đầu và PIN trùng giữa các hồ sơ.
   - PIN cũ ngắn hơn, rỗng, chứa ký tự không hợp lệ → xóa plaintext, không tạo hash; admin phải đặt PIN mới. Đây là thay đổi dữ liệu chủ động, không thể xem lại PIN từ hash.
   - Nếu đã có hash mới → giữ nguyên hash, chỉ xóa plaintext còn sót. Không dùng PIN cũ để ghi đè PIN đã reset.
   - Giữ nguyên trạng thái thu hồi quyền, dữ liệu khuôn mặt và ID hồ sơ. Chạy lại không băm lại hash/đổi PIN đã chuyển.
5. Xem log tổng `converted`, `resetRequired`, `preserved`; kiểm tra danh sách hồ sơ cần đặt PIN. Sau khi xong, bỏ dòng migration hoặc đặt lại `false`.
6. Có thể kiểm tra bằng truy vấn chỉ đếm, không in PIN/hash:

   ```sql
   SELECT COUNT(*) AS plaintext_remaining FROM users WHERE pin_code IS NOT NULL;
   SELECT COUNT(*) AS missing_pin_hash FROM users WHERE pin_hash IS NULL;
   ```

7. Chạy checklist bên dưới. Khi rollback, dùng kế hoạch code + backup dữ liệu thống nhất; **không chạy lại Core cũ sau khi đã xóa PIN plaintext** rồi kỳ vọng luồng PIN cũ hoạt động. Core cũ cũng không thực thi cờ thu hồi quyền mới.

File local/mật khẩu/backup không được commit. Băm PIN không xóa PIN trong backup, binlog, log cũ hoặc bản sao database; phải bảo vệ những bản đó riêng.

## 5. Chạy và kiểm thử

Vẫn dùng môi trường đợt 1, không thêm dependency npm/Gradle cho đợt 2.

```powershell
# Từ thư mục backend-core
.\gradlew.bat test --no-daemon

# Từ thư mục frontend
npm test
npm run build
npm run lint
```

Để chạy ứng dụng sau khi đã backup và chọn cách chuyển PIN: khởi động Core từ `backend-core` bằng `.\gradlew.bat bootRun`, frontend bằng `npm run dev`. AI chỉ cần cho tạo ảnh/nhận diện; MQTT phải kết nối nếu muốn thử gửi lệnh mở cửa thật. `SMARTLOCK_MQTT_ENABLED=false` sẽ không cho kết quả gửi lệnh thành công.

Kết quả xác minh cuối ngày 14/09/2026:

| Kiểm tra | Kết quả |
| --- | --- |
| Backend `gradlew.bat test --no-daemon` | 33 test đạt, 0 lỗi, 0 bỏ qua: 21 HTTP tích hợp, 1 nâng cấp schema H2, 2 MQTT giả lập, 2 bộ đếm, 7 adapter AI |
| Frontend `npm test` | 6 test đạt (4 đợt 1 + 2 cặp mã/PIN) |
| Frontend `npm run build` | Thành công |
| Frontend `npm run lint` | Không lỗi/cảnh báo |
| `git diff --check` | Không lỗi whitespace; có cảnh báo chuẩn hóa LF/CRLF của Git trên Windows |
| Cấu hình riêng máy | `.env`, Java local properties, `esp32/config.h`, `.runtime`, `.vscode`, `frontend/dist` vẫn được Git bỏ qua |

Các test dùng H2, AI/MQTT giả lập hoặc HTTP giả lập; không chạm MySQL, camera hoặc thiết bị thật. Có cảnh báo JVM/deprecated API từ môi trường/thư viện Java; build/test không thất bại vì các cảnh báo này.

Những nội dung được kiểm tra tự động:

- Toàn bộ 9 tình huống đăng nhập quản trị của đợt 1.
- Tạo hồ sơ PIN-only, băm/ẩn dữ liệu nhạy cảm, trùng mã và dữ liệu không hợp lệ.
- Cặp mã-PIN và hai người trùng PIN; từ chối request chỉ có PIN.
- Giới hạn 3 lượt theo mã và 20 lượt/IP, thông báo lỗi chung, audit không chứa PIN.
- Quyền ADMIN/CSRF cho sửa/reset; version cũ và ID không tồn tại.
- Thu hồi/cấp lại quyền, chặn cả PIN/face, giữ lịch sử; đổi PIN không tự cấp lại quyền.
- Không xác thực bằng plaintext cũ; migration hợp lệ/không hợp lệ/đã có hash/idempotent.
- Từ chối AI trả người không tồn tại/đã thu hồi/chưa có ảnh; AI lỗi không tạo hồ sơ hoặc gửi lệnh.
- Schema H2 cũ được bổ sung cột mà giữ dữ liệu; migration chỉ chạy khi chủ động gọi.
- Giới hạn thử đồng thời và hết cửa sổ bằng đồng hồ giả; lỗi broker và payload MQTT giữ nguyên.
- Adapter AI thực tế với HTTP giả lập: kiểm tra ảnh, vector và kiểu dữ liệu phản hồi.
- Frontend gửi đúng mã-PIN, giữ số 0, không tự lặp yêu cầu ghi, tiếp tục dùng cookie/CSRF.

Chưa kiểm chứng end-to-end UI trong trình duyệt, upgrade trên MySQL thật/bản sao MySQL, camera/liveness thật, keypad/chốt cửa thật, HTTPS và triển khai nhiều instance. H2 test không thay thế kiểm thử MySQL của nhóm.

## 6. Checklist review thủ công

- [ ] Kiểm tra đang ở `hnam`, backup và đọc kỹ phần PIN cũ trước khi khởi động.
- [ ] Đăng nhập admin, tạo `nv001` và `nv002` cùng PIN thử nghiệm, không gửi ảnh. Xem DB chỉ có hash mới, `pin_code` null (không chia sẻ/screenshot hash hay PIN thật).
- [ ] Tại Kiosk, nhập mã + PIN từng người; khi broker hoạt động, nhật ký phải gắn đúng hồ sơ. Dùng thiết bị thử nghiệm an toàn, không tự thử trên cửa đang phục vụ người dùng.
- [ ] Nhập PIN sai 3 lần cho một mã; lần tiếp theo 429. Mã khác vẫn theo bộ đếm riêng, nhưng còn giới hạn IP chung. Chờ 5 phút trước khi thử lại.
- [ ] Thu hồi một hồ sơ, thử PIN đúng và khuôn mặt: đều bị từ chối. Cấp lại quyền và kiểm tra lại.
- [ ] Đặt PIN mới: PIN cũ bị từ chối; trạng thái thu hồi không đổi. Đóng form rồi mở lại không thấy PIN.
- [ ] Sửa cùng hồ sơ ở hai tab: bản cũ nhận 409. Tải lại danh sách rồi chọn lại hồ sơ.
- [ ] Tạo hồ sơ có ảnh hợp lệ, kiểm tra AI thật; ảnh sai/AI không chạy không tạo hồ sơ dở dang.
- [ ] Tắt broker trong môi trường thử: không báo “đã gửi lệnh” thành công. Nếu lỗi mạng không rõ kết quả, kiểm tra cửa trước khi thử lại.
- [ ] Chuyển đổi PIN cũ trên bản sao database: PIN hợp lệ giữ giá trị, PIN không hợp lệ cần đặt mới, hồ sơ bị khóa vẫn bị khóa, số lượng hồ sơ/lịch sử không mất.
- [ ] Review `git diff`, `git diff --cached`, test/build và file mẫu trước khi stage. Không commit database dump, cấu hình local hoặc `.runtime`.

## 7. File thay đổi để review

Các đường dẫn dưới đây tương đối từ gốc repo; code Java chính ở `backend-core/src/main/java/com/example/btl_iot/`.

| Nhóm | File |
| --- | --- |
| Model/repository | `entity/User.java`, `repository/UserRepository.java` |
| Hồ sơ và PIN | `service/ProfileService.java`, `service/DoorAccessService.java`, `security/ProfileRules.java`, `controller/ProfileController.java` |
| Chuyển đổi cũ | `service/LegacyPinMigration.java`, `security/LegacyPinMigrationRunner.java` |
| API/tích hợp | `controller/ApiController.java`, `controller/ApiErrors.java`, `service/FaceGateway.java` |
| Giới hạn thử/kết quả gửi | `security/AttemptLimiter.java`, `mqtt/MqttPublisher.java`, `config/MqttConfig.java` |
| Cấu hình dùng chung | `backend-core/src/main/resources/application.properties`, `backend-core/config/application-local.properties.example` |
| Frontend | `frontend/src/AddUser.jsx`, `EmployeeList.jsx`, `Kiosk.jsx`, `AdminLayout.jsx`, `Overview.jsx`, `pin-credentials.js` |
| Test | `backend-core/src/test/` và `frontend/test/pin-credentials.test.js` |
| Tài liệu | `README.md`, tài liệu này |

Không sửa code Python AI, firmware ESP32, mật khẩu/config riêng máy. Các fixture PIN trong test chỉ thuộc database tạm, không phải tài khoản mặc định.

## 8. Giới hạn và phần để sau

- PIN vẫn có không gian đoán nhỏ dù đã băm. BCrypt làm chậm dò offline, không biến PIN ngắn thành bí mật mạnh; phải bảo vệ DB/backup và dùng PIN riêng, khó đoán. Chưa thêm pepper/kho khóa bí mật.
- Bộ đếm thử nằm trong RAM, reset khi Core restart, không chia sẻ giữa nhiều instance. Vite/reverse proxy có thể khiến nhiều người dùng chung một IP; không tự tin header IP từ Internet.
- Có thể cố tình khóa tạm một mã bằng thử sai; cần cân bằng khả dụng/bảo mật ở đợt vận hành. Chưa thêm còi phần cứng/báo động sau 3 lần sai; hiện ghi audit và chặn tạm.
- Chưa bảo vệ danh tính Kiosk hoặc đường Core–AI. Cập nhật tích hợp 20/09/2026: AI từ main đã sửa liveness fail-open khi thiếu mô hình; Core đã được ghép với hợp đồng AI mới. Xem [bàn giao tích hợp](PHASE_02_AI_INTEGRATION.md). Không coi hệ thống đã sẵn sàng vận hành Internet/cửa thật chỉ vì test đạt.
- Chưa có TLS/ACL MQTT, chống phát lại, xác nhận cảm biến trạng thái cửa hoặc bảo đảm giao lệnh exactly-once. Transaction DB không thể rollback một lệnh MQTT đã gửi; lỗi commit/timeout có thể cần đối chiếu thực tế.
- Chưa có thay ảnh/sửa mã hồ sơ/xóa vĩnh viễn, giao diện audit, phân quyền nhiều cấp, firmware nhập mã + PIN, offline hoặc dự phòng mất điện.

Tham khảo thiết kế lưu bí mật và giới hạn thử: [OWASP Password Storage](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html), [OWASP Authentication](https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html). Các chính sách cụ thể (6–10 số, 3 lượt/5 phút) là lựa chọn của phiên bản này, không phải tuyên bố dự án đã đạt chứng nhận/chuẩn bảo mật.

Sau bàn giao, dừng để bạn review và tự quyết định commit/push. Chưa tự bắt đầu đợt 3.
