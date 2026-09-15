# Bàn giao đợt 1: Đăng nhập và bảo mật tài khoản quản trị

Ngày: 12/09/2026. Phạm vi: code để nhóm review, chưa commit/push; chưa triển khai lên môi trường thật.

## 1. Mục tiêu và phần đã hoàn thành

Tách tài khoản quản trị khỏi hồ sơ người được phép vào cửa. Đợt này chỉ triển khai đăng nhập quản trị; không chuyển đổi PIN hoặc nghiệp vụ nhận diện/mở cửa.

- Thêm bảng `admin_accounts`, độc lập với bảng `users`. Hồ sơ người ra vào và PIN của họ không dùng để đăng nhập quản trị.
- Mật khẩu quản trị được băm BCrypt, cost 12. Không có tài khoản/mật khẩu mặc định hoặc API đăng ký quản trị công khai.
- Khởi tạo quản trị đầu tiên bằng cấu hình riêng của máy, chỉ khi bảng quản trị đang rỗng.
- Đăng nhập, đọc tài khoản hiện tại, đăng xuất và đổi mật khẩu.
- Phiên đăng nhập lưu phía server; trình duyệt giữ cookie HttpOnly, SameSite=Strict. Không lưu mật khẩu hay token đăng nhập trong localStorage/sessionStorage.
- Đổi session ID và CSRF token khi đăng nhập, nhằm ngăn tái sử dụng phiên/token trước đăng nhập.
- Đổi mật khẩu yêu cầu mật khẩu hiện tại. Phiên hiện tại bị hủy; các phiên khác bị từ chối ở yêu cầu tiếp theo thông qua `credentialVersion`.
- Tài khoản bị vô hiệu hóa hoặc xóa cũng không tiếp tục sử dụng phiên cũ được. Chưa có màn hình/API quản lý việc vô hiệu hóa tài khoản trong đợt này.
- Kiểm soát quyền ở backend, không chỉ ẩn giao diện: API quản trị yêu cầu `ROLE_ADMIN`; các đường dẫn chưa được cho phép bị từ chối.
- Ghi nhật ký `LOGIN_SUCCESS`, `LOGIN_FAILED`, `LOGOUT`, `PASSWORD_CHANGED` vào `security_audits`, không ghi mật khẩu/PIN/token. Chưa có trang xem nhật ký.
- Giao diện `/login`, bảo vệ `/admin/*`, nút đăng xuất và trang `/admin/security` để đổi mật khẩu.

## 2. Luồng sử dụng và API

Frontend gọi API cùng origin qua `/api`. Trong phát triển, Vite proxy sang `127.0.0.1:8080`; đã bỏ CORS wildcard khỏi controller Java.

Trước mỗi POST, client lấy token bằng GET `/api/auth/csrf`, giữ cookie và gửi token trong header do server trả về (`X-CSRF-TOKEN`). Sau đó mới gửi yêu cầu nghiệp vụ. Client không tự gửi lại POST bị lỗi, tránh lặp thao tác đăng ký hoặc mở cửa.

| API | Quyền | Mục đích |
| --- | --- | --- |
| GET `/api/auth/csrf` | Công khai | Lấy CSRF token cho phiên hiện tại |
| POST `/api/auth/login` | Công khai, cần CSRF | JSON `username`, `password`; trả username và role |
| GET `/api/auth/me` | ADMIN | Kiểm tra phiên, trả username và role |
| POST `/api/auth/logout` | ADMIN, cần CSRF | Hủy phiên hiện tại |
| POST `/api/auth/password` | ADMIN, cần CSRF | JSON `currentPassword`, `newPassword`; đổi mật khẩu và đăng xuất |
| GET `/api/users`, GET `/api/overview` | ADMIN | Các API đọc dữ liệu quản trị cũ |
| POST `/api/register` | ADMIN, cần CSRF | Đăng ký hồ sơ ra vào bằng multipart như trước, không tạo admin |
| POST `/api/verify-pin`, POST `/api/verify-face` | Công khai, cần CSRF | Giữ luồng Kiosk; chưa bổ sung xác thực thiết bị |

Mã phản hồi: `401` khi chưa đăng nhập hoặc sai thông tin đăng nhập; `403` khi thiếu/sai CSRF hoặc bị từ chối quyền; `400` khi dữ liệu đăng nhập/đổi mật khẩu không hợp lệ; `429` khi vượt giới hạn thử. POST chưa đăng nhập nhưng thiếu CSRF thường nhận `403` trước bước kiểm tra quyền.

Lưu ý: CSRF không phải xác thực thiết bị. Một client trực tiếp vẫn có thể tự lấy cookie/token rồi gọi API Kiosk. Không coi đợt này là đã bảo vệ toàn bộ hệ thống cửa.

## 3. Chính sách hiện tại

- Username quản trị: 1–64 ký tự ASCII, gồm chữ, số, `_`, `-`.
- Mật khẩu mới: ít nhất 12 ký tự, tối đa 72 byte UTF-8, không áp quy tắc bắt buộc ký tự đặc biệt. Nên dùng mật khẩu dài, riêng biệt; không dùng PIN mở cửa.
- Đăng nhập: tối đa 5 lượt cho một username trong cửa sổ 5 phút; đăng nhập thành công xóa bộ đếm username. Key username không phân biệt hoa/thường để tránh né giới hạn.
- Giới hạn bổ sung: tối đa 20 lượt đăng nhập/IP trong 5 phút, tính cả lượt thành công và lượt bị chặn ở bộ đếm username. IP lấy từ kết nối server, không tin header do client tự gửi. Vite/reverse proxy có thể khiến nhiều người cùng chia sẻ một IP và giới hạn này.
- Đổi mật khẩu: tối đa 5 lượt kiểm tra mật khẩu hiện tại/tài khoản trong 5 phút; thành công xóa bộ đếm. Kiểm tra độ mạnh mật khẩu mới diễn ra trước bước này.
- Bộ đếm nằm trong RAM, tối đa 10.000 key; reset khi restart, chưa phù hợp triển khai nhiều instance. `Retry-After: 300` là thời gian chờ bảo thủ, không phải thời gian còn lại chính xác.
- Phiên hết hạn sau 30 phút không có request dùng phiên. Dashboard đang tự tải dữ liệu mỗi 5 giây nên có thể giữ phiên sống dù không thao tác chuột/bàn phím. Đây chưa phải khóa màn hình theo thời gian không tương tác.
- `Secure` cookie mặc định tắt cho HTTP localhost; khi triển khai phải dùng HTTPS và bật `SMARTLOCK_SECURE_COOKIE=true`.
- Backend mặc định chỉ bind `127.0.0.1`. Không tự mở ra Internet/LAN trong đợt này.
- Giới hạn file multipart 5 MB, tổng request 6 MB. Giới hạn kích thước không thay thế kiểm tra nội dung ảnh.

## 4. Cách chạy và tạo quản trị đầu tiên

Yêu cầu môi trường theo README/SETUP_WINDOWS: Java 17, MySQL, Node.js tương thích Vite. Để kiểm tra login không cần chạy AI; tính năng khuôn mặt vẫn cần AI như trước.

1. Sao lưu database trước lần chạy backend mới: Hibernate đang dùng `ddl-auto=update`, sẽ tự thêm hai bảng mới. Đợt này không có migration xóa/chuyển đổi bảng `users`.
2. Giữ nguyên cấu hình MySQL đang dùng trong `backend-core/config/application-local.properties`. Nếu máy mới chưa có file, sao chép từ file `.example`; không ghi đè file local đã có.
3. Thêm `SMARTLOCK_ADMIN_USERNAME` và `SMARTLOCK_ADMIN_PASSWORD` vào file local, tự điền giá trị riêng. File ví dụ để trống, không chứa tài khoản dùng chung. Không đặt mật khẩu thật trong mã nguồn, tài liệu, command line hoặc Git.
4. Chạy backend từ thư mục `backend-core` để đường dẫn cấu hình tương đối được đọc đúng:

   ```powershell
   .\gradlew.bat bootRun
   ```

5. Trong terminal khác, tại `frontend`:

   ```powershell
   npm ci
   npm run dev
   ```

6. Mở địa chỉ Vite in trong terminal, vào `/login`. Dùng tài khoản vừa cấu hình. Không mở frontend bằng file HTML trực tiếp hoặc gọi API localhost:8080 từ một origin khác.
7. Khi đăng nhập thành công lần đầu, xóa dòng chứa mật khẩu bootstrap khỏi file local. Lần restart sau không ghi đè mật khẩu/tài khoản đã tồn tại. Đổi mật khẩu bằng trang Bảo mật tài khoản.

Nếu bảng quản trị rỗng và chưa điền thông tin bootstrap, backend vẫn chạy nhưng không ai đăng nhập quản trị được. Không có chức năng quên mật khẩu/reset qua email trong đợt này; thay cấu hình bootstrap không reset tài khoản đã tồn tại. Không xóa bảng để thử reset trên dữ liệu thật.

Nếu chỉ kiểm tra đăng nhập, có thể đặt `SMARTLOCK_MQTT_ENABLED=false` trong cấu hình local để không kết nối broker. Khi đó không kỳ vọng cửa hoạt động; xóa cấu hình này hoặc đặt `true` khi quay lại kiểm tra IoT.

Trong môi trường triển khai, cần reverse proxy phục vụ frontend và `/api` cùng origin, cấu hình HTTPS và SPA fallback. `npm run build` chỉ tạo static files, không tự cung cấp API proxy cho hosting bất kỳ. Không mở rộng CORS thành `*` để xử lý sai cấu hình triển khai.

## 5. Danh sách file của đợt 1

Các đường dẫn dưới đây tương đối từ gốc repo để dùng chung trong nhóm.

| Nhóm file | Cập nhật |
| --- | --- |
| `backend-core/build.gradle` | Spring Security; H2 chỉ dùng khi kiểm thử |
| `backend-core/src/main/java/com/example/btl_iot/entity/AdminAccount.java`, `SecurityAudit.java` | Tài khoản quản trị và nhật ký riêng |
| `backend-core/src/main/java/com/example/btl_iot/repository/AdminAccountRepository.java`, `SecurityAuditRepository.java` | Truy cập bảng mới |
| `backend-core/src/main/java/com/example/btl_iot/security/` | Principal, kiểm tra phiên, bootstrap, quy tắc đầu vào, giới hạn thử |
| `backend-core/src/main/java/com/example/btl_iot/config/SecurityConfig.java` | Bộ lọc xác thực, quyền, CSRF |
| `backend-core/src/main/java/com/example/btl_iot/controller/AuthController.java`, `ApiErrors.java` | API đăng nhập/phiên/mật khẩu và xử lý lỗi |
| `backend-core/src/main/java/com/example/btl_iot/controller/ApiController.java` | Bỏ CORS wildcard, giữ nghiệp vụ cũ |
| `backend-core/src/main/java/com/example/btl_iot/config/MqttConfig.java` | Cho phép tắt kết nối để kiểm thử an toàn |
| `backend-core/src/main/resources/application.properties` | Cookie/session, bind, upload, bootstrap; tắt in SQL |
| `backend-core/config/application-local.properties.example` | Biến mẫu cho admin/secure cookie, không chứa bí mật |
| `backend-core/src/test/java/com/example/btl_iot/BtlIotApplicationTests.java` | Kiểm thử HTTP tích hợp với H2 |
| `frontend/src/api.js`, `Auth.jsx`, `auth-context.js` | Client cùng origin, CSRF, trạng thái xác thực và route guard |
| `frontend/src/Login.jsx`, `SecuritySettings.jsx` | Đăng nhập và đổi mật khẩu |
| `frontend/src/App.jsx`, `AdminLayout.jsx` | Routes, hiển thị tài khoản, đăng xuất |
| `frontend/src/Overview.jsx`, `EmployeeList.jsx`, `AddUser.jsx`, `Kiosk.jsx` | Dùng client mới; Kiosk phân biệt sai PIN với lỗi phiên/kết nối |
| `frontend/vite.config.js` | Proxy phát triển `/api` |
| `frontend/package.json`, `frontend/test/api.test.js` | Lệnh test và kiểm thử client, không thêm dependency frontend |
| `.gitignore`, `README.md`, tài liệu này | Bỏ qua thư mục persistence MQTT local, chỉ dẫn bàn giao |

`User.java` và `UserRepository.java` không có thay đổi nội dung so với HEAD; Git có thể báo touched do chuẩn hóa xuống dòng. Không thêm `pinHash`, cờ quyền ra vào hoặc migration PIN trong đợt này.

Working tree còn các thay đổi cấu hình dùng chung từ công việc trước: backend-ai/settings và `.env.example`, requirements, firmware/config mẫu, script biên dịch, SETUP_WINDOWS, README/.gitignore. Đây không phải tính năng bảo mật AI/PIN mới của đợt 1. Những thay đổi trước đó đã được giữ nguyên.

## 6. Kiểm thử và kết quả

Đã chạy ngày 12/09/2026:

| Kiểm tra | Kết quả |
| --- | --- |
| Backend `gradlew.bat test --no-daemon` | 9 test pass, 0 lỗi, 0 bỏ qua |
| Frontend `npm test` | 4 test pass |
| Frontend `npm run build` | Thành công |
| Frontend `npm run lint` | Không lỗi; còn cảnh báo biến/import chưa dùng từ code cũ |
| `git diff --check`, `git diff --cached --check` | Không lỗi whitespace; Git có cảnh báo LF/CRLF của môi trường Windows |
| `git check-ignore` đối với `.env`, cấu hình Java local, `.runtime`, `.vscode`, `esp32/config.h` | Các đường dẫn riêng máy vẫn bị bỏ qua |

Backend test dùng H2 trong RAM, cổng HTTP ngẫu nhiên, tắt MQTT, không gọi AI hoặc gửi lệnh mở cửa. Mật khẩu `Test-only-...` trong test là fixture cho database tạm, không được dùng cho tài khoản thật. Test không dùng MySQL hiện tại.

9 test backend kiểm tra: chặn đọc API/đăng ký hồ sơ khi chưa đăng nhập; yêu cầu CSRF; đổi ID phiên/token và logout; cookie/cache headers; hồ sơ/PIN không phải admin; thông báo sai đăng nhập chung và giới hạn username; đổi mật khẩu/độ mạnh/thu hồi phiên khác; vô hiệu hóa tài khoản; chặn endpoint ngoài danh sách.

4 test frontend kiểm tra: cookie và CSRF mới cho mỗi POST; GET không cần CSRF; 401 của admin làm mất trạng thái phiên nhưng sai PIN/login không gây chuyển trang quản trị; không tự gửi lại POST lỗi.

Giới hạn kiểm chứng: chưa chạy end-to-end giao diện trên trình duyệt, chưa thử migration trên bản sao MySQL, chưa kiểm tra HTTPS/Secure cookie thực tế, tải đồng thời, giới hạn IP/hết cửa sổ bằng đồng hồ giả hoặc cơ chế hết phiên sau 30 phút. Build và test client không thay thế kiểm thử giao diện/phần cứng.

## 7. Checklist review trước khi push

- [ ] Sao lưu DB, khởi động backend mới, tạo admin riêng; xác nhận có bảng `admin_accounts`, `security_audits` và dữ liệu `users` giữ nguyên.
- [ ] Mở `/admin` bằng cửa sổ riêng tư: chuyển về `/login`; gọi GET `/api/users` không cookie nhận 401.
- [ ] Đăng nhập đúng → vào quản trị, đọc danh sách và tổng quan; refresh vẫn giữ phiên.
- [ ] Sai mật khẩu 5 lần rồi thử tiếp → 429; chờ tối đa 5 phút và thử lại.
- [ ] Đăng xuất rồi quay lại bằng nút Back hoặc URL `/admin`: không đọc được API quản trị.
- [ ] Mở hai trình duyệt/profile độc lập, đăng nhập cả hai; đổi mật khẩu ở một bên; bên kia gọi API tiếp theo bị từ chối. Mật khẩu cũ không còn đăng nhập được.
- [ ] Kiosk không yêu cầu tài khoản admin; chỉ test mở cửa trên thiết bị thử nghiệm an toàn khi nhóm chủ động cho phép.
- [ ] Hiểu rằng “Về Kiosk (giữ phiên)” không phải đăng xuất. Trên thiết bị dùng chung, bấm “Đăng xuất” trước khi bàn giao.
- [ ] Chạy lại các lệnh test/build; kiểm tra `git diff` và `git diff --cached` trước khi stage.
- [ ] Không stage file bí mật/local. Có các staged deletion cũ của `anote.txt` và ba file `.pyc`: mục đích bỏ theo dõi, không xóa bản local; review riêng cùng đợt dọn cấu hình.
- [ ] Không dùng `git add .` một cách máy móc nếu muốn tách commit cấu hình cũ và commit đăng nhập. Chọn file/hunk theo danh sách trên.

Rollback code về phiên bản chưa có Security sẽ mở lại API quản trị như trước, không phải một phương án an toàn để vận hành. Hai bảng mới không cần xóa để rollback; mọi thao tác dữ liệu phải có backup và quyết định riêng của nhóm.

## 8. Chưa thực hiện — chỉ bắt đầu sau khi bạn duyệt đợt này

- PIN vẫn là dữ liệu plaintext trong `users`; chưa có cơ chế băm PIN, chống đoán PIN hoặc xử lý PIN trùng theo mô hình chốt của nhóm.
- Chưa có quản lý thu hồi quyền ra vào, trạng thái thực của hồ sơ hoặc sửa/xóa hồ sơ đầy đủ.
- Chưa xác thực Core–AI, thiết bị Kiosk, MQTT; chưa bổ sung TLS/ACL broker, chống phát lại lệnh hoặc sửa hành vi fail-open khi thiếu mô hình chống giả mạo.
- Chưa có MFA, quản lý nhiều vai trò/admin, quên mật khẩu, UI xem audit, chính sách lưu trữ nhật ký, phiên/bộ đếm phân tán hoặc khóa theo không tương tác giao diện.
- Chưa triển khai mã hóa dữ liệu khuôn mặt. Dòng mô tả “mã hóa” và trạng thái “Hệ thống an toàn” trong giao diện cũ không phải bằng chứng những cơ chế đó đã được thực hiện.
- Chưa làm dự phòng mất điện theo yêu cầu để sau.

Đề xuất đợt kế tiếp để bạn quyết định sau review/push: chuẩn hóa hồ sơ người được phép vào và bảo mật PIN riêng từng người. Không tự tiếp tục triển khai đợt kế tiếp trong lần bàn giao này.
