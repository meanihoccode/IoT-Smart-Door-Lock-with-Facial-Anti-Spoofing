# Phase 3 — An toàn phiên quản trị trên Kiosk dùng chung và nhật ký bảo mật

Ngày triển khai: 24/09/2026. Nhánh: `hnam`, nền `1fe0143`.
Trạng thái: thay đổi local để review, chưa commit/push. Không thay model AI, MQTT/firmware, PIN hoặc dự phòng mất điện.

## 1. Bàn giao máy cho Kiosk

- Thay “Về Kiosk (giữ phiên)” thành **“Đăng xuất và về Kiosk”**.
- Route `/kiosk`, kể cả truy cập trực tiếp hoặc từ `/`, đi qua `KioskGate`: gọi `POST /api/auth/kiosk`, chờ server xác nhận rồi mới hiển thị camera/PIN.
- Endpoint có thể gọi khi chưa đăng nhập để một máy chưa có admin vẫn vào Kiosk được, nhưng luôn yêu cầu CSRF. Endpoint không cấp quyền, không mở cửa; chỉ hủy phiên hiện tại.
- Nếu đang có admin, ghi `KIOSK_HANDOFF`, hủy session và security context. Các tab dùng chung cookie không còn quyền admin.
- Mạng/server lỗi: không hiện Kiosk như thể bàn giao thành công; có nút thử lại. Không tự retry lệnh mở cửa. Khi ghi audit lỗi, endpoint vẫn hủy phiên trong `finally`, nhưng giao diện không báo thành công khi chưa nhận xác nhận.
- Handoff trùng do React StrictMode được gom vào một request đang chạy. Đây chỉ là gộp yêu cầu hủy phiên, không áp dụng cho lệnh mở cửa.
- BroadcastChannel thông báo kết thúc phiên giữa các tab cùng origin, không truyền password/PIN/token. Khi tab Kiosk nhận thông báo đăng nhập admin ở tab khác hoặc được focus trở lại, nó thực hiện lại bàn giao.
- Vì dùng chung cookie, không nên dùng đồng thời tab Kiosk và tab admin trong cùng browser profile. Dùng browser profile/thiết bị riêng nếu cần quản trị song song; tab Kiosk có thể làm phiên admin vừa đăng nhập ở tab khác bị đăng xuất.

## 2. Chính sách phiên do server thực thi

| Chính sách | Mặc định | Biến cấu hình local |
| --- | --- | --- |
| Không có hoạt động quản trị | 15 phút | `SMARTLOCK_ADMIN_IDLE_TIMEOUT` |
| Tuổi thọ tuyệt đối kể từ đăng nhập | 4 giờ | `SMARTLOCK_ADMIN_ABSOLUTE_TIMEOUT` |

- Mốc bắt đầu và hoạt động cuối nằm trong session phía server, được khởi tạo khi đăng nhập thành công.
- Thời gian hoạt động tính theo request quản trị: hồ sơ, overview, audit và đổi mật khẩu; không phải theo di chuột/gõ phím. Đọc trang nhưng không gửi request vẫn có thể hết hạn.
- `/auth/me`, CSRF, quét mặt và PIN không gia hạn idle admin. Poll kiểm tra phiên không giữ admin đăng nhập vô hạn.
- Hoạt động quản trị không kéo dài giới hạn tuyệt đối. Ví dụ thao tác liên tục vẫn phải đăng nhập lại sau 4 giờ.
- Phiên thiếu metadata phase 3 bị coi là hết hạn và yêu cầu đăng nhập lại; không cố giữ phiên cũ sau nâng cấp.
- Khi filter phát hiện hết hạn hoặc tài khoản/credentialVersion thay đổi, session bị hủy trước khi xử lý endpoint. Request quản trị sau đó bị từ chối; một thao tác đã được chấp nhận và đang chạy trước thời điểm hủy phiên không bị rollback chỉ bởi logout.
- Trang admin kiểm tra `/auth/me` mỗi 30 giây khi tab đang hiển thị và khi window được focus; khi nhận 401 sẽ rời giao diện quản trị. Backend vẫn là lớp cưỡng chế quyền, không phụ thuộc timer trình duyệt.
- Timeout session servlet 30 phút vẫn tồn tại như giới hạn nền độc lập. Không có tác vụ nền thu hồi/ghi log mọi phiên đúng thời điểm đồng hồ hết hạn: filter xử lý ở request tiếp theo. Phiên bị container xóa trước đó có thể không có bản ghi hết hạn riêng.

Các mặc định là lựa chọn cho đồ án, không phải khẳng định đạt chứng nhận bảo mật. Có thể đổi trong file local; không sửa/commit bí mật. Ví dụ test nhanh dùng `1m` và `3m`, khởi động lại Core rồi đăng nhập mới; sau test khôi phục mặc định.

## 3. Trang nhật ký bảo mật

Vào **Admin → Nhật ký bảo mật**, URL `/admin/audit`.

- Chỉ admin được đọc qua backend, không chỉ ẩn menu.
- Hiển thị thời gian, tài khoản, mã hành động và đối tượng (thường là ID hồ sơ).
- Lọc tài khoản khớp chính xác, mã hành động, ngày bắt đầu/kết thúc; ngày kết thúc bao gồm cả ngày.
- Thời gian là LocalDateTime theo đồng hồ server, không tự đổi múi giờ trên trình duyệt. Các server cần dùng cấu hình thời gian nhất quán.
- Phân trang mặc định 20 dòng; API cho phép tối đa 100 dòng/lần, trang 0–100000. Sắp xếp thời gian giảm dần rồi ID giảm dần để ổn định thứ tự khi timestamp trùng. Khi có log mới, phân trang offset không phải một snapshot cố định.
- Có trạng thái đang tải, không có dữ liệu và lỗi; phản hồi cũ của request trước không ghi đè bộ lọc mới.
- Chỉ đọc: không thêm endpoint sửa/xóa/export log. Không trả password, hash, PIN, embedding hoặc mã session.
- Dùng bảng `security_audits` hiện có, không thêm cột/bảng và không chuyển đổi dữ liệu MySQL trong lần làm này.

Nhật ký này khác `access_logs`: lịch sử quét mặt/PIN vẫn ở luồng ra vào, không được đổi thành security audit.

## 4. API và sự kiện

| API | Quyền | Kết quả |
| --- | --- | --- |
| POST `/api/auth/kiosk` | Không cần đăng nhập, bắt buộc CSRF | 200 `{"status":"success"}` khi hoàn tất hủy phiên; lỗi không báo bàn giao thành công |
| GET `/api/admin/security-audits` | ADMIN | `items`, `page`, `size`, `totalElements`, `totalPages` |

Query audit: `page`, `size`, `actor`, `action`, `from`, `to`; ngày theo `YYYY-MM-DD`.
Ví dụ: `/api/admin/security-audits?action=LOGIN_FAILED&from=2026-09-24&to=2026-09-24&page=0&size=20`.

Sự kiện bổ sung:

- `KIOSK_HANDOFF`: kết thúc phiên admin để dùng Kiosk.
- `SESSION_IDLE_EXPIRED`: hết thời gian không hoạt động quản trị.
- `SESSION_ABSOLUTE_EXPIRED`: hết tuổi thọ tối đa.
- `SESSION_EXPIRED`: thiếu metadata phiên hợp lệ.
- `SESSION_REVOKED`: tài khoản mất hiệu lực hoặc phiên dùng credentialVersion cũ.

Giữ các sự kiện có sẵn như `LOGIN_SUCCESS`, `LOGIN_FAILED`, `LOGOUT`, `PASSWORD_CHANGED`, `PROFILE_CREATED`, `PROFILE_UPDATED`, `PROFILE_ENABLED`, `PROFILE_DISABLED`, `PROFILE_PIN_RESET`, `PIN_THROTTLED` và `LEGACY_PIN_CLEARED`.

## 5. Kiểm thử tự động

Kết quả ngày 24/09/2026:

| Kiểm tra | Kết quả |
| --- | --- |
| Backend `gradlew.bat test --no-daemon` | 107 test đạt, 0 lỗi, 0 bỏ qua (97 test trước + 10 test phase 3) |
| Frontend `npm test` | 12 test đạt (10 test trước + 2 test handoff) |
| Frontend `npm run build` | Thành công |
| Frontend `npm run lint` | Thành công, không còn cảnh báo lint |
| Git | `git diff --check` không lỗi; không stage/commit/push |

Backend gồm 27 test HTTP/ứng dụng, 49 test controller AI, 20 test FaceGateway, 6 test chính sách phiên, 2 test giới hạn thử, 2 test MQTT và 1 test schema. Test chạy bằng H2 và AI/MQTT giả lập; không kết nối MySQL hoặc phát lệnh đến cửa thật. Không chạy lại bộ Python AI ở phase này vì không sửa Python/model. Có cảnh báo deprecated API/JVM và LF/CRLF của môi trường, không làm test/build thất bại.

Test mới kiểm tra: handoff cần CSRF, hủy quyền admin, gọi lại khi anonymous; audit chỉ admin/read-only, lọc/phân trang/ngày, đầu vào sai và không lộ trường bí mật; idle/absolute timeout bằng đồng hồ giả, request Kiosk/CSRF/me không gia hạn admin, filter hủy phiên và ghi audit; frontend chờ xác nhận handoff, gộp yêu cầu đồng thời, lỗi không báo thành công và cho phép retry chủ động.

## 6. Checklist bạn review thủ công

1. Khởi động Core/frontend trong môi trường thử. Không cần bật migration PIN hoặc sửa DB/admin hiện có.
2. Đăng nhập, mở trang nhật ký: thấy `LOGIN_SUCCESS`; lọc một action/tài khoản, thử ngày, trang trống và phân trang.
3. Bấm “Đăng xuất và về Kiosk”; chỉ sau xác nhận mới thấy camera/PIN. Quay lại `/admin` hoặc tab admin cũ: không đọc được dữ liệu bằng phiên cũ.
4. Đăng nhập lại, mở trực tiếp `/kiosk`: vẫn phải hủy phiên. Kiểm tra log `KIOSK_HANDOFF`.
5. Thử nhiều tab cùng origin và browser profile. Khi bàn giao, tab admin khác mất quyền. Không dùng Kiosk và admin đồng thời cùng profile nếu không muốn đăng xuất lẫn nhau.
6. Tắt Core rồi chuyển sang Kiosk: phải hiện lỗi/chưa thể bàn giao, không hiện camera/PIN. Bật lại và thử lại. Không giao máy cho người khác khi chưa xác nhận xong.
7. Với timeout local ngắn, đăng nhập rồi không gửi request quản trị: `/auth/me` polling không giữ phiên. Đăng nhập lại và liên tục đọc dữ liệu: vẫn hết khi đạt absolute timeout. Kiểm tra log tương ứng.
8. Giữ regression phase 2: mã hồ sơ + PIN, reset/thu hồi quyền, quét mặt và lỗi MQTT trên môi trường thử an toàn.
9. Review file/diff, test và tài liệu trước khi quyết định commit/push. Cấu hình local và dữ liệu thực vẫn không được commit.

## 7. Giới hạn và việc chưa làm

- Chưa có yêu cầu nhập lại mật khẩu cho từng thao tác nhạy cảm, MFA, khôi phục mật khẩu, quản lý nhiều admin hoặc danh sách phiên để thu hồi chọn lọc.
- Audit chưa có IP/device/requestId, chính sách tự xóa/lưu trữ, index phục vụ dữ liệu lớn hoặc cơ chế chống sửa bởi người có quyền DB. Không tự xóa log cũ.
- BroadcastChannel/focus là đồng bộ giao diện, không thay thế kiểm tra quyền server; không phải bảo đảm xóa ngay nội dung đã render trên mọi tab bị treo/offline. Đóng các tab quản trị khi bàn giao máy.
- Đăng xuất không thể thu hồi dữ liệu người dùng đã xem/chụp hoặc hủy một lệnh cửa đã được gửi.
- Không thay xác thực Core–AI, MQTT/TLS/ACL, giới hạn face, HTTPS hoặc cơ chế dự phòng mất điện. Không coi phase 3 là chứng nhận sẵn sàng vận hành cửa thật.
- Chưa kiểm thử end-to-end bằng trình duyệt/camera, MySQL thật hay phần cứng. Checklist phía trên dành cho lần review thủ công của nhóm.

## 8. File cần review

- Backend: `AdminSessionPolicy`, `AdminSessionFilter`, `AuthController`, `SecurityConfig`, `SecurityAuditController`, `SecurityAuditRepository`, `ApiErrors` và cấu hình mẫu/session.
- Frontend: `KioskGate`, `kiosk-handoff`, `session-events`, `SecurityAudit`, `Auth`, `AdminLayout`, `App`.
- Test: `AdminSessionPolicyTests`, phần bổ sung `BtlIotApplicationTests`, `kiosk-handoff.test.js`.
- Tài liệu: file này và liên kết README. Không thêm dependency mới.

Sau bàn giao dừng để bạn review; không tự commit/push hoặc bắt đầu phase tiếp theo.
