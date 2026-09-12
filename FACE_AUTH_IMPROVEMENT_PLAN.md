# Kế hoạch cải thiện xác thực khuôn mặt

- Ngày lập: 11/09/2026.
- Mốc code tham chiếu: `2f6c9664`.
- Trạng thái: **chỉ lập kế hoạch; chưa triển khai hoặc thay ngưỡng xác thực**.
- Phạm vi: frontend chụp/đăng ký ảnh, backend AI, hợp đồng kết quả với Spring Boot và kiểm chứng quyết định cho phép truy cập.

## 1. Mục tiêu và nguyên tắc

Giảm số lần người đã đăng ký bị từ chối nhầm, đồng thời kiểm soát việc nhận nhầm người lạ và chấp nhận ảnh/video giả. Đo riêng chất lượng nhận diện, chống giả mạo và độ trễ; không dùng một tỷ lệ “accuracy” chung để kết luận hệ thống tốt hơn.

Kế hoạch ưu tiên webcam và máy tính hiện có, giữ InsightFace `buffalo_l` làm mốc so sánh ban đầu. Chưa cần mua phần cứng, huấn luyện lại hoặc thay model ngay.

- Đo mức hiện tại trước khi so sánh các cải tiến.
- Không hạ ngưỡng chỉ để một người quét thành công.
- Khi model, database hoặc kết quả AI lỗi, không cho phép mở cửa.
- Các cấu hình 720p, 5–10 ảnh đăng ký và 3–5 khung hình xác thực là phương án thử nghiệm, chưa phải thông số đã tối ưu.
- Mọi thử nghiệm quyết định mở cửa dùng bộ gửi MQTT giả lập; không kích hoạt khóa thật trong bộ kiểm thử.

## 2. Luồng hiện tại

```text
Kiosk chụp một ảnh JPEG
    → Spring Boot chuyển ảnh sang FastAPI
    → InsightFace phát hiện mặt, căn chỉnh và lấy embedding 512 chiều
    → Chọn faces[0]
    → MiniFASNetV2 kiểm tra thật/giả
    → So cosine với toàn bộ khuôn mặt đã đăng ký, ngưỡng 0.5
    → Spring Boot kiểm tra is_real và recognized
    → Gửi lệnh MQTT nếu được chấp nhận
```

Đây là tìm danh tính trong danh sách **1:N**, dù endpoint có tên `verify-face`. Đăng ký hiện nhận một file ảnh tải lên và lưu một embedding/người; giao diện AddUser chưa có chức năng chụp trực tiếp bằng webcam.

## 3. Những vấn đề đã xác nhận

| Ưu tiên | Bằng chứng trong code | Hệ quả / hướng xử lý |
| --- | --- | --- |
| P0 | [anti_spoofing.py](backend-ai/anti_spoofing.py), dòng 39: model chưa tải được thì trả `True, 0.95`. Đường dẫn model phụ thuộc thư mục chạy. | Có thể bỏ qua chống giả mạo. Dùng đường dẫn theo module, báo model chưa sẵn sàng và từ chối xác thực. |
| P0 | [ApiController.java](backend-core/src/main/java/com/example/btl_iot/controller/ApiController.java), dòng 87: có thể đi đến gửi lệnh dù `user_id` thiếu hoặc không tồn tại. | Chỉ cho phép khi kết quả AI hợp lệ và người dùng thực sự tồn tại, được phép truy cập. |
| P1 | [anti_spoofing.py](backend-ai/anti_spoofing.py), dòng 54: crop vuông bằng cạnh lớn nhất rồi cắt cụt ở biên. | Khác crop tham chiếu; có thể làm lệch phân bố đầu vào của model. |
| P1 | [anti_spoofing.py](backend-ai/anti_spoofing.py), dòng 74: chỉ dùng `argmax`, không có ngưỡng điểm lớp real. | Ví dụ `[0.33, 0.34, 0.33]` vẫn qua. `spoof_score` đang là điểm lớp thắng, không phải luôn là xác suất giả mạo. |
| P1 | [Kiosk.jsx](frontend/src/Kiosk.jsx), dòng 21 và 105: một screenshot, `mirrored=true`, không cấu hình ảnh theo kích thước nguồn. | Ảnh quét bị lật ngang và kích thước phụ thuộc vùng hiển thị; không nhất quán với ảnh đăng ký. Mức ảnh hưởng cần đo. |
| P1 | [AddUser.jsx](frontend/src/AddUser.jsx) và [User.java](backend-core/src/main/java/com/example/btl_iot/entity/User.java): một ảnh tải lên, một embedding/người. | Ít mẫu bao phủ góc mặt, ánh sáng và kính; thiếu kiểm soát chất lượng đầu vào. |
| P1 | [main.py](backend-ai/main.py), dòng 101, 122 và 168: chọn `faces[0]`, ngưỡng nhận diện cố định `0.5`. | Không bảo đảm chọn đúng người khi có nhiều mặt; chưa có dữ liệu hiệu chỉnh ngưỡng trong repo. |
| P1 | [main.py](backend-ai/main.py), dòng 27: lỗi database trả danh sách rỗng, embedding lỗi bị bỏ qua; endpoint `/` vẫn báo OK khi model lỗi. | Không phân biệt thiếu đăng ký với lỗi hệ thống; cần readiness và kiểm tra template hợp lệ. |
| P1 | [ApiController.java](backend-core/src/main/java/com/example/btl_iot/controller/ApiController.java), dòng 100: nhiều lỗi đều trả “Verification failed”. | Người dùng và người kiểm tra không biết bước nào thất bại. |

Phần không cần sửa theo phỏng đoán:

- Công thức cosine hiện chia cho chuẩn của hai vector, đúng về mặt tính toán.
- InsightFace đã căn chỉnh theo landmark trước khi trích xuất embedding.
- MiniFASNet dùng BGR float trong khoảng `0–255`, phù hợp preprocessing tham chiếu. Không tự thêm `/255` hoặc chuyển RGB.
- Hai checkpoint V2 và V1SE hiện có đã qua kiểm tra tải trọng số đúng kiến trúc và trả đầu ra ba lớp. Điều này không chứng minh độ chính xác trên webcam thực tế.

Lần kiểm tra trước ghi nhận chưa có người dùng đăng ký, đủ để không thể xác thực thành công. Tuy nhiên log cũ không lưu đủ kết quả AI để kết luận riêng từng lần quét thất bại do bước nào; chưa có đánh giá trên ảnh hoặc chuỗi ảnh thực tế của người dùng.

## 4. Kế hoạch triển khai

### Giai đoạn 1 — Quan sát kết quả và xử lý lỗi an toàn (P0)

- [ ] Ghi lại phiên bản thư viện, model và cấu hình hiện tại để tái lập mức cơ sở.
- [ ] Thêm mã kết quả: `NO_ENROLLMENT`, `NO_FACE`, `MULTIPLE_FACES`, `LOW_QUALITY`, `LIVENESS_UNCERTAIN`, `SPOOF_DETECTED`, `NOT_RECOGNIZED`, `MODEL_UNAVAILABLE`, `DB_UNAVAILABLE`, `INVALID_AI_RESPONSE`.
- [ ] Tách trạng thái tiến trình còn chạy với trạng thái model/database sẵn sàng phục vụ.
- [ ] Khi thiếu hoặc hỏng model chống giả mạo, trả lỗi dịch vụ; loại bỏ kết quả giả lập cho qua.
- [ ] Chuẩn hóa phản hồi AI: trạng thái, mã lý do, kết quả liveness/nhận diện và ID hợp lệ khi nhận diện thành công. Spring xử lý đầy đủ null, thiếu trường, sai kiểu và ID không tồn tại.
- [ ] Ghi nội bộ `request_id`, số mặt, điểm chất lượng, `live_score`, điểm nhận diện, phiên bản model/ngưỡng và thời gian từng bước. Không mặc định ghi ảnh gốc, embedding hay PIN vào log.
- [ ] Hiển thị hướng dẫn phù hợp trên Kiosk; thêm timeout và xử lý kết quả bất thường để không treo “Đang xử lý”.

Hoàn tất khi mọi nhánh lỗi có thể phân biệt và kiểm thử chứng minh không có lệnh mở cửa khi AI/database/model lỗi hoặc người dùng không hợp lệ.

### Giai đoạn 2 — Chuẩn hóa chống giả mạo (P1)

- [ ] Dùng [CropImage](backend-ai/src/generate_patches.py), chuyển rõ `xyxy → xywh`, giữ cách scale và xử lý biên theo tham chiếu.
- [ ] Kiểm tra bounding box rỗng, ngoài ảnh, không hợp lệ và crop sát biên.
- [ ] Đặt tên điểm nhất quán: `live_score = p(real)`. Điểm softmax chưa được xem là xác suất đã hiệu chỉnh.
- [ ] Thêm ngưỡng chấp nhận và trạng thái chưa đủ chắc chắn; chọn ngưỡng trên tập hiệu chỉnh sau khi sửa preprocessing.
- [ ] Lấy V2 với crop chuẩn làm mốc; thử thêm V1SE ở scale tương ứng để so sánh kết hợp nhiều model.
- [ ] Giữ phương án kết hợp chỉ khi cải thiện kết quả trên tập độc lập và đáp ứng độ trễ.

Hoàn tất khi crop đúng tham chiếu, lỗi model không bị coi là người thật và kết quả đo phân biệt rõ từ chối nhầm người thật với chấp nhận nhầm giả mạo.

### Giai đoạn 3 — Chuẩn hóa chụp ảnh và đăng ký nhiều mẫu (P1)

- [ ] Yêu cầu độ phân giải camera phù hợp, thử ưu tiên 720p và có phương án khi camera không hỗ trợ; lấy screenshot theo nguồn camera.
- [ ] Thống nhất chiều ảnh gửi lên giữa đăng ký và xác thực. Nếu cần preview kiểu gương, xử lý phần hiển thị riêng.
- [ ] Chỉ chụp khi camera sẵn sàng; hướng dẫn người dùng điều chỉnh ánh sáng, khoảng cách và góc mặt.
- [ ] Kiểm tra chất lượng ở server: đúng một mặt, đủ kích thước, không quá mờ/tối/cháy sáng và góc mặt phù hợp. Ngưỡng chất lượng phải được thử trên camera thực tế.
- [ ] Thêm đăng ký trực tiếp bằng webcam, thử 5–10 ảnh đủ chất lượng với các góc nhẹ và điều kiện sử dụng điển hình.
- [ ] Kiểm tra liveness cho đăng ký trực tiếp; tách rõ quy trình ảnh tải lên do quản trị viên duyệt.
- [ ] Loại ảnh trùng và mẫu không nhất quán danh tính; kiểm tra embedding đúng 512 chiều, hữu hạn và có chuẩn khác 0.
- [ ] Thiết kế lưu nhiều template/người, có phiên bản model/preprocessing, chất lượng và thời gian tạo. Có phương án chuyển dữ liệu cũ và đăng ký lại khi không tương thích.
- [ ] So sánh vector đại diện được chuẩn hóa L2 với phương án nhiều template có giới hạn số lượng. Không mặc định lấy điểm lớn nhất trên càng nhiều mẫu càng tốt, vì phải đo lại khả năng nhận nhầm.

Hoàn tất khi ảnh đăng ký/quét tuân cùng chính sách, ảnh không đạt được yêu cầu chụp lại với lý do cụ thể, dữ liệu cũ có đường chuyển đổi và mẫu mới được kiểm tra hợp lệ.

### Giai đoạn 4 — Xác thực bằng chuỗi ảnh ngắn (P2)

- [ ] Thử 3–5 frame trong một lượt, kiểm soát tổng thời gian xử lý và số lần thử lại.
- [ ] Theo dõi cùng một người xuyên suốt các frame; ảnh nhiều người hoặc đổi danh tính không được gộp thành kết quả thành công.
- [ ] Kết hợp chất lượng, liveness và điểm nhận diện bằng quy tắc được hiệu chỉnh. Không chấp nhận chỉ vì một frame bất kỳ vượt ngưỡng.
- [ ] Thử điều kiện về khoảng cách điểm giữa hai danh tính đứng đầu để xử lý trường hợp mơ hồ; so sánh top-2 theo người, không theo hai template của cùng người.
- [ ] Tránh tải model lại nhiều lần; cân nhắc chỉ chạy module cần thiết và giới hạn tác vụ suy luận đồng thời khi đo thấy nghẽn CPU.
- [ ] Nếu thử cache embedding, phải cập nhật khi đăng ký/xóa/thay mẫu; cache cũ không được giữ quyền của người đã bị loại bỏ.

Hoàn tất khi phương án chuỗi ảnh được đánh giá ở mức cả lượt xác thực, cải thiện so với một ảnh tại cùng giới hạn nhận nhầm và đạt độ trễ chấp nhận được. Nhiều frame không tự chứng minh chống được video phát lại.

### Giai đoạn 5 — Hiệu chỉnh và nghiệm thu (P2)

- [ ] Lập bộ dữ liệu pilot, có thể bắt đầu với 10–20 người qua nhiều buổi chụp; có cả người không được đăng ký.
- [ ] Thu các điều kiện: đủ sáng, thiếu sáng, ngược sáng, góc nhẹ, kính, khoảng cách khác nhau, mặt gần biên và có nhiều người.
- [ ] Kiểm tra giả mạo bằng ảnh in và ảnh/video hiển thị qua màn hình, được camera thực tế thu lại.
- [ ] Tách dữ liệu đăng ký, hiệu chỉnh và kiểm thử cuối theo buổi/đoạn quay. Không đưa các frame liền kề của một video sang các tập khác nhau; giữ một nhóm người lạ độc lập cho kiểm thử cuối.
- [ ] Đo mức cơ sở và từng cải tiến riêng để biết thay đổi nào có tác dụng; báo cả số lượng mẫu/lượt, điều kiện thử và sai số thống kê khi đủ dữ liệu.
- [ ] Chọn ngưỡng nhận diện, liveness, chất lượng và cách kết hợp trên tập hiệu chỉnh; cố định cấu hình trước khi chạy tập kiểm thử cuối.
- [ ] Ghim phiên bản dependency/model và lưu cấu hình được chọn. Chỉ đánh giá model khác nếu các bước trên chưa đạt yêu cầu.

## 5. Chỉ số và điều kiện nghiệm thu

| Nhóm | Chỉ số cần báo cáo | Điều kiện chọn phương án |
| --- | --- | --- |
| Nhận diện 1:N | Người đúng không được trả về đúng danh tính trên ngưỡng; người chưa đăng ký bị gán vào danh sách (FNIR/FPIR); số lượt gán nhầm giữa người đã đăng ký | Giảm lỗi người đúng tại giới hạn nhận nhầm đã chốt; đo trên kích thước danh sách dự kiến. |
| Chống giả mạo | Người thật bị từ chối và ảnh/video giả được chấp nhận; phân theo loại tấn công | Không đánh đổi bằng việc cho qua khi model lỗi hoặc giảm bảo vệ dưới giới hạn đã chốt. |
| Toàn luồng | Từ chối người hợp lệ, chấp nhận sai, phải chụp lại, lỗi dịch vụ | Tính trên toàn bộ lượt, gồm cả ảnh không đạt chất lượng; không loại lượt khó để làm đẹp kết quả. |
| Hiệu năng | Thời gian p50/p95 mỗi lượt, mức dùng CPU/RAM | Đạt ngân sách độ trễ trên máy hiện có; báo riêng thời gian khởi động và xử lý ổn định. |

Giới hạn nhận nhầm, giả mạo được chấp nhận và độ trễ sẽ được chốt trước khi chọn cấu hình thắng. Chưa đặt con số cam kết khi chưa có dữ liệu. Pilot nhỏ có thể phát hiện vấn đề nhưng chưa đủ chứng minh tỷ lệ lỗi rất thấp; không gặp lỗi trong một số lượt thử không đồng nghĩa lỗi bằng 0.

## 6. Các kiểm thử bắt buộc

- [ ] Model thiếu/hỏng, database lỗi, phản hồi AI null/sai kiểu hoặc người dùng không tồn tại → không gửi lệnh mở cửa.
- [ ] Crop ảnh tổng hợp ở giữa, sát bốn biên, khung không vuông và bounding box không hợp lệ → khớp tham chiếu hoặc trả lỗi rõ ràng.
- [ ] Không có mặt, nhiều mặt, ảnh mờ/tối và embedding sai kích thước/NaN → không đăng ký hoặc xác thực nhầm.
- [ ] Screenshot không bị thay đổi chính sách chiều ảnh/kích thước chỉ vì đổi kích thước cửa sổ trình duyệt.
- [ ] Database không có template khác với database lỗi; người chưa đăng ký khác với phát hiện giả mạo.
- [ ] Chuỗi frame đổi người, frame trùng hoặc chất lượng dao động → không được ghép thành kết quả cho phép sai.
- [ ] Bộ kiểm thử độc lập có người thật, người lạ, ảnh in và video replay; xuất đủ các chỉ số ở mục 5.

## 7. Phạm vi file dự kiến khi triển khai

| Thành phần | File / khu vực |
| --- | --- |
| API AI, kết quả, kiểm tra template, nhận diện | [backend-ai/main.py](backend-ai/main.py) và các module tách thêm nếu cần |
| Preprocessing, nạp model, điểm liveness | [backend-ai/anti_spoofing.py](backend-ai/anti_spoofing.py), [src/generate_patches.py](backend-ai/src/generate_patches.py) |
| Chụp ảnh và phản hồi cho người dùng | [frontend/src/Kiosk.jsx](frontend/src/Kiosk.jsx), [frontend/src/AddUser.jsx](frontend/src/AddUser.jsx) |
| Hợp đồng AI, kiểm tra quyền trước khi mở cửa | [ApiController.java](backend-core/src/main/java/com/example/btl_iot/controller/ApiController.java) |
| Lưu nhiều mẫu và chuyển dữ liệu | Entity/repository trong `backend-core`, migration có kiểm soát |
| Tái lập và đánh giá | Cấu hình ngưỡng/model, dependency đã ghim, script đánh giá và báo cáo thử nghiệm |

Tách kết quả “khuôn mặt hợp lệ”, “đã gửi lệnh” và “thiết bị xác nhận mở cửa” khi làm phần tích hợp; lỗi MQTT không phải lỗi độ chính xác AI. Bảo vệ quyền quản trị/đăng ký là hạng mục kiểm soát truy cập riêng cần xử lý trước khi dùng khóa thật, không được coi là đã giải quyết chỉ nhờ nâng độ chính xác model.

## 8. Thứ tự ưu tiên và đầu ra

1. Triển khai giai đoạn 1 để phân biệt nguyên nhân và chặn lỗi nguy hiểm.
2. Làm giai đoạn 2–3, thu dữ liệu và so sánh với mức cơ sở.
3. Thử giai đoạn 4 khi ảnh đơn đã được chuẩn hóa, đồng thời đo chi phí thời gian.
4. Hoàn tất giai đoạn 5, chốt cấu hình dựa trên kết quả độc lập.

Đầu ra cần có: code đã kiểm thử, cấu hình tái lập, dữ liệu mẫu/metadata được quản lý phù hợp, báo cáo trước–sau theo từng điều kiện, hướng dẫn đăng ký lại và giới hạn đã biết. Không triển khai hoặc thay đổi dịch vụ chỉ vì file kế hoạch này được tạo.

## 9. Nguồn tham chiếu

- [MiniVision — crop tham chiếu](https://github.com/minivision-ai/Silent-Face-Anti-Spoofing/blob/master/src/generate_patches.py): cách scale bounding box và xử lý biên.
- [MiniVision — preprocessing](https://github.com/minivision-ai/Silent-Face-Anti-Spoofing/blob/master/src/data_io/functional.py): cách chuyển ảnh sang tensor.
- [MiniVision — demo kết hợp nhiều model](https://github.com/minivision-ai/Silent-Face-Anti-Spoofing/blob/master/test.py): phương án nhiều scale để đưa vào thử nghiệm.
- [MiniVision — hướng dẫn sử dụng](https://github.com/minivision-ai/Silent-Face-Anti-Spoofing/blob/master/README_EN.md): độ nhạy với camera, bối cảnh và tư thế khuôn mặt.
- [NIST — đánh giá nhận diện 1:N](https://pages.nist.gov/frvt/html/frvt1N.html): định nghĩa FNIR/FPIR và đánh giá với ngưỡng.
- [NIST — đánh giá xác thực 1:1](https://pages.nist.gov/frvt/html/frvt11.html): mối quan hệ giữa nhận nhầm, từ chối nhầm và chất lượng ảnh.

Các đề xuất về số ảnh, nhiều template, nhiều frame và cấu hình camera là giả thuyết thử nghiệm cho repo này; không phải mức cải thiện đã được các nguồn trên bảo đảm.
