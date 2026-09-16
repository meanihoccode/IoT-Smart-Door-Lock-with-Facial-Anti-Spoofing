# Kế hoạch cải thiện độ chính xác AI xác thực khuôn mặt

- Ngày lập: 11/09/2026; rà soát và điều chỉnh phạm vi: 12/09/2026.
- Cập nhật 15/09/2026: đã triển khai [chọn khuôn mặt lớn nhất khi mở khóa](docs/LARGEST_FACE_VERIFICATION.md), đồng bộ backend-core và đánh giá offline; đăng ký vẫn chỉ nhận một mặt.
- Mốc code lịch sử: `2f6c9664`; mức cơ sở mới là code AI sau giai đoạn 1 trong working tree hiện tại, cần chụp lại hash trước khi chạy thí nghiệm.
- Trạng thái: giai đoạn 1 và công cụ giai đoạn 2A đã triển khai; chưa có dữ liệu gán nhãn để đo baseline và chưa có số đo chứng minh tăng độ chính xác.
- Phạm vi thực hiện tiếp theo: `backend-ai/`, công cụ Python thu/đánh giá ảnh, cấu hình thí nghiệm và báo cáo AI.
- Phân công: phần web và Backend Core do thành viên khác trong team phụ trách. Các thay đổi trước đây ở giai đoạn 1 được giữ làm lịch sử.
- Điều kiện hiện tại: chưa lắp mạch. Đánh giá thuật toán bằng ảnh/clip và gallery cục bộ, không phụ thuộc ESP32, relay, MQTT, frontend hay MySQL.

## 1. Mục tiêu và cách xác nhận cải thiện

Mục tiêu là giúp người đã đăng ký được nhận đúng danh tính trong điều kiện sử dụng thực tế, đồng thời kiểm soát nhận nhầm người lạ và chấp nhận ảnh/video giả.

Hai câu hỏi phải được đo riêng:

1. **Nhận diện:** mặt này có thuộc người đã đăng ký không, và trả về đúng người nào?
2. **Chống giả mạo:** đầu vào camera là người thật hay ảnh/video được trình ra camera?

Đây là tìm danh tính trong gallery **1:N**, dù endpoint có tên `verify-face`. Chưa đổi sang 1:1 vì luồng hiện tại không cung cấp danh tính khai báo trước.

Mỗi thay đổi phải có kết quả so sánh trên cùng bộ dữ liệu và quy trình. Kiểm thử code đạt không đồng nghĩa độ chính xác ảnh thật tăng; nhận đúng một người cũng chưa đo được khả năng nhận nhầm người khác. Khi chọn ngưỡng, báo riêng tỷ lệ không trả về đúng người, nhận nhầm người lạ và gán sai danh tính. Cách đánh giá có ngưỡng cho 1:N được tham chiếu từ [NIST FRTE 1:N](https://pages.nist.gov/frvt/html/frvt1N.html).

Giữ `buffalo_l` làm mốc ban đầu. Ưu tiên sửa đầu vào, đo dữ liệu và hiệu chỉnh cách quyết định trước khi cân nhắc đổi hoặc huấn luyện lại model.

## 2. Hiện trạng đã đối chiếu với code

Luồng AI hiện tại trong [main.py](backend-ai/main.py):

```text
Giải mã ảnh
  → kiểm tra model sẵn sàng
  → InsightFace phát hiện vị trí các mặt
  → chọn mặt lớn nhất, lấy và kiểm tra embedding 512 chiều của mặt đó
  → MiniFASNetV2: crop hiện tại → softmax → argmax
  → đọc gallery, phân biệt DB lỗi / dữ liệu hỏng / chưa đăng ký
  → so cosine với từng người, chọn điểm cao nhất với ngưỡng 0.5
  → trả kết quả liveness, recognition và reasonCode
```

| Hạng mục | Trạng thái thực tế | Việc còn cần làm để cải thiện AI |
| --- | --- | --- |
| Model thiếu/hỏng | Đã bỏ trả `True, 0.95`; có lỗi model và đường dẫn theo module trong [anti_spoofing.py](backend-ai/anti_spoofing.py) | Giữ hành vi từ chối; bổ sung test checkpoint hỏng và suy luận lỗi |
| Nhiều khuôn mặt | Xác thực chỉ xét mặt lớn nhất; đăng ký vẫn trả `MULTIPLE_FACES` | Đo trên camera với nhiều người; baseline mới ghi chính sách lựa chọn trong snapshot |
| Điểm liveness | Đã trả nhất quán `score[0][1]`, tức điểm softmax lớp real | Quyết định vẫn là `argmax`; chưa có ngưỡng hoặc vùng uncertain được hiệu chỉnh |
| Crop liveness | Đang mở rộng thành hình vuông theo `max(w, h)`, rồi cắt cụt ở biên | Đối chiếu và sửa theo `CropImage`; đo tác động trên ảnh thực tế |
| Nhận diện | `buffalo_l`, detection size `640×640`, cosine ngưỡng `0.5` | Chưa có báo cáo hiệu chỉnh ngưỡng cho camera/gallery của repo |
| Chất lượng ảnh | Có tên mã `LOW_QUALITY`; log `quality_score=None` | Chưa tính chỉ số hoặc chặn ảnh theo chất lượng |
| Gallery | Một embedding/người; kiểm tra 512 chiều, hữu hạn, chuẩn khác 0 | Đánh giá chọn mẫu tốt và tổng hợp nhiều ảnh trước khi đề xuất lưu nhiều template |
| Dữ liệu/đánh giá | Có [8 test Python](backend-ai/tests/test_stage1_face_auth.py) dùng ảnh tổng hợp và kết quả model giả | Chưa có công cụ đánh giá độ chính xác, manifest chia tập hoặc báo cáo trước–sau trong phần AI đã kiểm tra |

Các lỗi fail-open, gộp DB lỗi với gallery rỗng và thiếu mã nguyên nhân là vấn đề lịch sử đã xử lý ở giai đoạn 1. Việc từng ghi nhận DB chưa có người đăng ký cũng là thông tin lần kiểm tra trước, không phải kết luận về DB hiện tại.

## 3. Giai đoạn 1 — Giữ nền tảng đã triển khai

- [x] Ghi môi trường/model/cấu hình trong [PHASE1_IMPLEMENTATION_GUIDE.md](PHASE1_IMPLEMENTATION_GUIDE.md).
- [x] Bỏ liveness giả lập cho qua khi model lỗi; thêm readiness model/database.
- [x] Chuẩn hóa kết quả AI và phân biệt lỗi model, database, template, không có mặt, nhiều mặt, giả mạo và không nhận diện.
- [x] Kiểm tra embedding; ghi request ID, điểm, model, ngưỡng và thời gian.
- [x] Đã triển khai kiểm tra hợp đồng ở Core và hướng dẫn/timeout ở Kiosk trong phạm vi giai đoạn 1 trước đây.

Giới hạn của mốc này: `LOW_QUALITY` và `LIVENESS_UNCERTAIN` mới có trong hợp đồng; `quality_score` chưa được tính. Báo cáo triển khai ghi 8 test AI và 20 test Java đạt, nhưng các test đó kiểm tra logic với dữ liệu giả, không đánh giá độ chính xác camera. Khi rà lại mã test, chưa thấy ca riêng cho mọi tổ hợp checkpoint hỏng, inference lỗi, JSON toàn bộ null, sai kiểu và timeout. Những test hồi quy AI còn thiếu được đưa vào mục 8; phần test Core thuộc team tích hợp.

## 4. Giai đoạn 2 — Đo mức cơ sở và sửa chống giả mạo

### 2A. Tạo bộ đánh giá AI độc lập trước khi đổi thuật toán

- [x] Tách phần suy luận dùng chung thành module Python nhận ảnh và gallery đầu vào. API và công cụ đánh giá gọi cùng `FacePipeline`; endpoint không có chế độ bỏ qua liveness.
- [x] Cho phép gallery thử nghiệm từ manifest/file cục bộ; chạy offline không đọc/ghi database ứng dụng.
- [x] Lưu cấu hình mức cơ sở và snapshot: hash code, checkpoint/ONNX, dependency/provider, thiết bị, detection size, crop, cách tính điểm, ngưỡng và gallery.
- [x] Tạo công cụ đọc manifest ảnh/clip, chạy model thật và xuất kết quả theo mẫu/lượt. Cấu trúc dữ liệu ở mục 7.
- [x] Đo ba nhánh: nhận diện riêng, chống giả mạo riêng và quyết định kết hợp. Nhánh nhận diện riêng chỉ tồn tại trong công cụ offline.
- [x] Giữ lỗi decode/detection/embedding/model và reason code trong thống kê; không loại im lặng các lượt này. `LOW_QUALITY`/uncertain sẽ có số đo thực sau khi thuật toán tương ứng được triển khai.
- [ ] Thu dữ liệu mức cơ sở trước khi sửa crop. Nếu chưa có ảnh được gán nhãn, có thể hoàn thành công cụ và unit test nhưng phần đo phải ghi rõ “chưa đánh giá”.

Đầu ra công cụ và hướng dẫn đã hoàn thành ngày 14/09/2026; chi tiết tại [PHASE2A_EVALUATION_GUIDE.md](PHASE2A_EVALUATION_GUIDE.md). Bộ dữ liệu và báo cáo accuracy vẫn chờ ảnh webcam được gán nhãn.

### 2B. Chuẩn hóa crop và preprocessing MiniFASNet

- [ ] Đổi bounding box InsightFace `xyxy → xywh`; ghi rõ quy ước tọa độ, làm tròn và biên inclusive/exclusive bằng test để tránh lệch một pixel.
- [ ] Dùng [CropImage](backend-ai/src/generate_patches.py) với scale `2.7`, đầu ra `80×80` cho V2. So pixel với crop tham chiếu trên cùng ảnh/bbox hợp lệ ở giữa ảnh, bốn biên, góc và box không vuông.
- [ ] Kiểm tra box rỗng/đảo chiều/NaN/ngoài ảnh; xử lý phần giao ảnh theo chính sách rõ ràng. Đầu vào không sử dụng được phải có lý do cụ thể.
- [ ] Giữ BGR, float32, BCHW và thang đầu vào `0–255` đúng nhánh NumPy của [preprocessing MiniVision](https://github.com/minivision-ai/Silent-Face-Anti-Spoofing/blob/master/src/data_io/functional.py).
- [ ] Kiểm tra đầu ra model đúng ba lớp và hữu hạn; lỗi suy luận không được biến thành một điểm hợp lệ.
- [ ] So baseline với “chỉ đổi crop” trong khi giữ nguyên checkpoint và quy tắc quyết định để đo riêng ảnh hưởng của crop.

[Crop tham chiếu MiniVision](https://github.com/minivision-ai/Silent-Face-Anti-Spoofing/blob/master/src/generate_patches.py) mở rộng chiều rộng/chiều cao theo scale và dịch box khi gặp biên. Sự khác biệt với crop hiện tại là bằng chứng cần sửa/kiểm thử; mức tăng độ chính xác vẫn phải đo. Detector của repo cũng có thể cho box khác demo MiniVision, nên crop khớp không đồng nghĩa toàn pipeline có đầu vào giống demo.

Đầu ra: crop khớp tham chiếu trên đầu vào hợp lệ, test lỗi đầu vào và bảng trước–sau trên tập phát triển.

### 2C. Hiệu chỉnh quyết định liveness

- [x] `live_score = score[real]` đã có từ giai đoạn 1. Đây là điểm softmax, không gọi là xác suất thực tế đã hiệu chỉnh.
- [ ] Đo phân bố điểm cho người thật, ảnh in, ảnh trên màn hình và video replay từ camera mục tiêu.
- [ ] Chọn hai ngưỡng `t_spoof < t_live` trên tập hiệu chỉnh: điểm thấp bị từ chối, vùng giữa trả `LIVENESS_UNCERTAIN`, điểm đủ cao mới được xét pass. Quy định rõ phép so sánh tại biên và cách xử lý khi lớp thắng mâu thuẫn với ngưỡng.
- [ ] Với uncertain: trả `status=error`, `reasonCode=LIVENESS_UNCERTAIN`, `isReal=false`; recognition chưa chạy và không có matched user ID.
- [ ] Báo riêng tỷ lệ người thật bị từ chối, tỷ lệ cần thử lại và tỷ lệ từng loại giả mạo được chấp nhận. Không chọn ngưỡng chỉ vì một ảnh của người dùng qua.
- [ ] Chỉ sau khi có mốc V2 crop chuẩn, thử V1SE checkpoint đang có ở scale `4` và phương án kết hợp điểm. Mỗi phương án phải hiệu chỉnh ngưỡng riêng, rồi so ở cùng giới hạn chấp nhận giả mạo.
- [ ] Nếu kết hợp nhiều model, tính trung bình/trọng số theo đúng số model cấu hình và thống nhất xử lý model lỗi. Không mặc định nhiều model là chính xác hơn.

Demo [MiniVision test.py](https://github.com/minivision-ai/Silent-Face-Anti-Spoofing/blob/master/test.py) cung cấp cơ sở cho thử nghiệm nhiều scale/model; đó là phương án so sánh, chưa phải cấu hình được chọn cho repo này.

Hoàn tất giai đoạn 2 khi có bộ đánh giá dùng được, preprocessing đã kiểm chứng và báo cáo liveness trên dữ liệu camera có nhãn. Nếu thiếu dữ liệu hiệu chỉnh, ghi rõ ngưỡng còn thử nghiệm và chưa nghiệm thu độ chính xác.

## 5. Giai đoạn 3 — Cải thiện nhận diện và mẫu đăng ký trong AI

### 3A. Đo và kiểm soát chất lượng ảnh

- [ ] Dùng chung xử lý hướng ảnh/EXIF và giải mã cho đăng ký/xác thực; để InsightFace thực hiện căn chỉnh landmark như hiện tại.
- [ ] Tính các chỉ số riêng: kích thước mặt, điểm detection, độ mờ, mức sáng, tỷ lệ vùng quá tối/cháy sáng, góc mặt và mức đầy đủ của khuôn mặt.
- [ ] Ghi các chỉ số ở chế độ quan sát trước; kiểm tra tương quan với sai nhận diện/liveness trên tập phát triển rồi chọn ngưỡng trên tập hiệu chỉnh.
- [ ] Trả `LOW_QUALITY` khi áp dụng gate đã chọn; giữ chi tiết chất lượng trong kết quả chẩn đoán AI. Không gộp các chỉ số thành một “điểm chất lượng” tùy ý.
- [ ] Đo cả phần bị gate từ chối và tỷ lệ thành công toàn lượt. Không coi lọc hết ảnh khó là cải thiện độ chính xác.
- [ ] Kiểm thử ảnh gốc và ảnh mô phỏng mờ/tối/resize để kiểm tra độ nhạy; ảnh mô phỏng không thay thế ảnh webcam thật trong nghiệm thu.

Không tự thêm làm đẹp, phục hồi khuôn mặt sinh ảnh, sharpen mạnh, đổi màu hoặc tăng sáng cho toàn pipeline. Chỉ đưa một phép biến đổi vào thí nghiệm khi có giả thuyết cụ thể và đo riêng ảnh hưởng lên nhận diện lẫn chống giả mạo.

### 3B. Tăng chất lượng template và hiệu chỉnh nhận diện 1:N

- [ ] Giữ baseline một ảnh/người; thu thử 5–10 ảnh đăng ký ở góc nhẹ/ánh sáng/kính thường dùng. Đây là số mẫu khởi đầu, chưa phải mức tối ưu.
- [ ] Loại mẫu trùng, ảnh không đạt và embedding không nhất quán với nhóm ảnh của cùng người. Không tự kết luận danh tính chỉ từ việc người tải ảnh khai cùng ID.
- [ ] So sánh ba phương án offline: một ảnh tốt nhất; trung bình các embedding đã chuẩn hóa L2 rồi chuẩn hóa lại; nhiều template có giới hạn số lượng mỗi người.
- [ ] Hiệu chỉnh cosine cho từng phương án trên đúng kích thước gallery dự kiến; ngưỡng `0.5` hiện tại chỉ là mốc so sánh, không mặc định là ngưỡng tối ưu.
- [ ] Thử khoảng cách điểm top-1/top-2 theo hai người khác nhau để xử lý mơ hồ, đo riêng chi phí từ chối thêm. Gallery một người phải có quy tắc riêng vì không có top-2.
- [ ] Đánh giá kết quả nhận diện riêng và cả pipeline kết hợp với liveness/quality đã chọn.
- [ ] Gắn phiên bản model/preprocessing cho template đánh giá; không trộn embedding của model không tương thích.

Công thức cosine hiện tại đã chia cho chuẩn hai vector. Chỉ chuẩn hóa lại embedding trước khi so cùng công thức không tự tạo ra độ chính xác mới; chuẩn hóa có ý nghĩa rõ khi tổng hợp nhiều mẫu hoặc lưu biểu diễn nhất quán.

Thử nhiều mẫu dùng gallery file của công cụ AI. Phương án vector đại diện vẫn có thể xuất một embedding 512 chiều. Nếu chọn nhiều template/người hoặc API đăng ký nhiều ảnh, AI bàn giao cấu trúc và kết quả đo cho team tích hợp; schema MySQL và giao diện không thuộc đợt cải thiện AI này.

Hoàn tất giai đoạn 3 khi phương án mẫu/ngưỡng giúp giảm lỗi người hợp lệ tại giới hạn nhận nhầm đã chốt, có báo cáo theo điều kiện và chỉ rõ phần nào chạy được với API hiện tại.

## 6. Giai đoạn 4–5 — Thử nghiệm có điều kiện và nghiệm thu

### Giai đoạn 4: chỉ mở rộng khi số đo ảnh đơn chỉ ra nhu cầu

- [ ] Nếu lỗi chủ yếu dao động giữa các frame, thử 3–5 frame từ một clip ngắn trong công cụ Python; giữ cùng người xuyên suốt lượt.
- [ ] So kết hợp điểm/biểu quyết đã định nghĩa trước với ảnh đơn. Không chấp nhận chỉ vì có một frame bất kỳ qua.
- [ ] Test đổi người, nhiều người, frame lặp và chất lượng dao động. Đếm theo lượt/clip, không đếm các frame liền kề như các thử nghiệm độc lập.
- [ ] Đo thời gian tổng trên CPU hiện có; chỉ tối ưu provider/module/batching khi phép đo xác nhận cần thiết.
- [ ] Nếu pipeline đầu vào/threshold đã được hiệu chỉnh mà vẫn không đạt, chọn một model thay thế để benchmark theo cùng quy trình. Cân nhắc fine-tune chỉ khi có đủ dữ liệu huấn luyện riêng và một tập đánh giá độc lập.

Nhiều frame và nhiều model là các nhánh thử nghiệm tùy kết quả, không phải điều kiện bắt buộc để hoàn thành bản AI tốt hơn. Xử lý chuỗi ảnh có thể đánh giá offline trước khi team web hỗ trợ gửi chuỗi frame.

### Giai đoạn 5: cố định cấu hình và đánh giá cuối

- [ ] Chốt phương án trên tập phát triển/hiệu chỉnh và khóa model, preprocessing, ngưỡng, cách tổng hợp trước khi mở tập kiểm thử cuối.
- [ ] Chạy baseline và phương án đã chọn trên cùng tập cuối; xuất cả số lỗi/số lượt, tỷ lệ, điều kiện thu và p50/p95.
- [ ] Kiểm tra giới hạn nhận nhầm/giả mạo đã chốt trước đó; báo độ bất định và giới hạn của cỡ mẫu.
- [ ] Nếu dùng kết quả tập cuối để sửa tiếp, đánh dấu tập đó đã trở thành dữ liệu phát triển và cần tập nghiệm thu mới.
- [ ] Bàn giao cấu hình có phiên bản, báo cáo trước–sau, test hồi quy, hướng dẫn dùng công cụ AI và yêu cầu tích hợp.
- [ ] Ghi kết luận đúng mức: “đã sửa preprocessing”, “cải thiện trên pilot” hoặc “đạt tiêu chí trên tập độc lập”; không cam kết phần trăm accuracy khi chưa đo.

## 7. Dữ liệu và chỉ số đánh giá

Có thể bắt đầu chẩn đoán bằng ảnh của người dùng và một số thành viên đồng ý tham gia. Để so sánh khả năng nhận nhầm, mở rộng pilot khoảng 10–20 người, gồm cả người không nằm trong gallery. Số này giúp phát hiện vấn đề ban đầu, không đủ chứng minh tỷ lệ lỗi cực thấp.

| Tập | Cách sử dụng |
| --- | --- |
| Đăng ký | Tạo gallery; không dùng các ảnh này làm probe để báo độ chính xác |
| Phát triển | So crop, quality, phương án template/model; phân tích nguyên nhân |
| Hiệu chỉnh | Chọn ngưỡng/cách quyết định của phương án; cho phép dùng lại có kiểm soát và lưu lịch sử thí nghiệm |
| Kiểm thử cuối | Buổi/clip riêng, giữ kín đến khi chốt cấu hình; có nhóm người lạ độc lập với tập hiệu chỉnh |

Ảnh của cùng người đã đăng ký có thể xuất hiện ở các buổi khác nhau trong nhiều tập vì cần kiểm tra xác thực lại. Toàn bộ frame của một clip, ảnh trùng và bản biến đổi từ cùng ảnh phải nằm trong cùng một tập. Nhóm người lạ dành cho kiểm thử cuối không được dùng để chọn ngưỡng.

Manifest dự kiến lưu `sample_id`, `relative_path`, `subject_id` dùng mã nội bộ, `session_id`, `clip_id`, `split`, `presentation_type` (live/print/screen_photo/replay), `camera_id`, điều kiện sáng/góc/kính và quy ước chiều ảnh. Lưu độ phân giải và hash ảnh để phát hiện trùng. Ảnh/embedding dùng nội bộ, không commit vào Git hoặc log mặc định.

Dữ liệu chống giả mạo phải là camera quay/chụp lại người thật hoặc vật trình ra camera. File chân dung tải từ điện thoại và ảnh chụp màn hình thuần số không thể thay cho phép thử giả mạo được camera thu lại; MiniVision cũng nêu giới hạn theo camera và bối cảnh trong [hướng dẫn sử dụng](https://github.com/minivision-ai/Silent-Face-Anti-Spoofing/blob/master/README_EN.md).

| Nhóm | Chỉ số bắt buộc cho repo |
| --- | --- |
| Nhận diện 1:N | Tỷ lệ trả đúng người ở top-1 trên ngưỡng; không trả đúng người trên ngưỡng; người lạ bị nhận thành người đăng ký; gán sai giữa người đã đăng ký |
| Chống giả mạo | Người thật bị từ chối; người thật cần thử lại; từng loại ảnh in/màn hình/video được chấp nhận |
| Cả pipeline AI | Đúng danh tính qua toàn bộ gate; từ chối/uncertain/quality/lỗi dịch vụ; người lạ và giả mạo qua toàn bộ pipeline |
| Hiệu năng | p50/p95 theo ảnh và theo lượt nếu nhiều frame; thời gian model khởi động báo riêng |

Với uncertain, báo một cột riêng và đồng thời tính là chưa được chấp nhận trong tỷ lệ người hợp lệ không qua toàn lượt. Với lỗi phát hiện/trích xuất, ghi cả tỷ lệ trên mẫu suy luận được và tỷ lệ trên toàn đầu vào; không dùng số liệu có điều kiện để che phần thất bại.

Điều kiện chọn phương án: giảm lỗi người hợp lệ trong khi đáp ứng giới hạn nhận nhầm người lạ, chấp nhận giả mạo và độ trễ đã xác định **trước khi chọn ngưỡng**. Nếu chưa chốt mục tiêu số, xuất đường đánh đổi và các cấu hình ứng viên, ghi rõ chưa chọn cấu hình vận hành. Báo số lỗi cùng mẫu số; tính độ bất định theo người/buổi/clip khi đủ dữ liệu, không giả định các frame liên tiếp độc lập.

## 8. Kiểm thử cần có trong phần AI

Các mục chưa đánh dấu dưới đây là việc cần làm, không phải kết quả test đã đạt.

- [x] Test hồi quy hiện có: checkpoint thiếu, face model không sẵn sàng, không có mặt, nhiều mặt, spoof, gallery rỗng khác DB lỗi, response thành công và không khớp.
- [ ] Checkpoint hỏng, exception suy luận, đầu ra model sai shape/NaN/Inf, readiness thiếu dependency; không sinh kết quả thành công.
- [ ] Crop khớp tham chiếu theo pixel; kiểm tra box lỗi, làm tròn, bốn biên/góc và ảnh kích thước khác nhau.
- [ ] Probe/template sai kích thước, NaN/Inf, vector 0 và norm bất thường; không nhận diện hoặc đăng ký thành công.
- [ ] Đăng ký không có mặt/nhiều mặt/ảnh không đạt; API và offline xử lý giống nhau.
- [ ] Quy tắc tại đúng biên ngưỡng, uncertain, top-2, gallery một người, template trùng và mẫu khác danh tính.
- [x] Bộ tính metric đã được kiểm tra bằng ví dụ có đáp án biết trước; lỗi decode/detection được giữ trong mẫu số và validator chặn rò rỉ hash/source/session/clip giữa các tập. Quality gate vẫn chờ giai đoạn 3A.
- [ ] Benchmark với model thật trên người đã đăng ký, người lạ và giả mạo qua camera, kèm báo cáo so sánh.

Test HTTP null/sai kiểu/timeout và quyết định MQTT thuộc trách nhiệm tích hợp của team web/Core; không dùng các test đó để suy ra độ chính xác AI.

## 9. Đầu ra dự kiến và giao tiếp với team web

| Phần AI | Trạng thái / đầu ra |
| --- | --- |
| Pipeline dùng chung | Đã có `backend-ai/face_pipeline.py` cho API và đánh giá offline |
| Cấu hình thí nghiệm | Đã có `backend-ai/configs/baseline.json`: model, preprocessing, threshold và chính sách baseline |
| Thu dữ liệu độc lập | Đã có `backend-ai/tools/capture_dataset.py`: webcam → ảnh + metadata khi người dùng chủ động chạy |
| Đánh giá | Đã có `backend-ai/tools/evaluate.py`: manifest/gallery → kết quả, metric và snapshot có hash |
| Hiệu chỉnh | `calibrate.py` sẽ được tạo ở giai đoạn 2C sau khi có dữ liệu liveness gán nhãn |
| Test hồi quy | Đã có test pipeline/manifest/metric; crop, quality, threshold calibration tiếp tục ở các giai đoạn tương ứng |
| Báo cáo | `backend-ai/reports/` đã tạo snapshot/smoke report cục bộ và được ignore; chưa có báo cáo accuracy ảnh thật |

Yêu cầu bàn giao để team web/Core phối hợp khi cần:

- Ảnh gửi vào AI có hướng và mức nén nhất quán giữa đăng ký/quét; ưu tiên kích thước nguồn camera, ghi nhận kích thước thực. 720p là cấu hình thử, không phải yêu cầu đã tối ưu.
- Giữ ổn định hai endpoint và trường hợp đồng giai đoạn 1 khi cải tiến ảnh đơn. Thay schema, thêm mã lý do hoặc gửi nhiều ảnh phải có tài liệu/version và phối hợp trước khi tích hợp.
- Team web xử lý việc chụp lại khi nhận `LOW_QUALITY`/`LIVENESS_UNCERTAIN`; AI chịu trách nhiệm tính và trả kết quả.
- Đăng ký nhiều ảnh/lưu nhiều template là hạng mục tích hợp sau khi AI chứng minh phương án đó có ích. Gallery offline cho phép thử trước.

Thứ tự thực hiện gần nhất: **2A bộ đánh giá + baseline → 2B crop chuẩn → 2C hiệu chỉnh liveness → giai đoạn 3 chất lượng/template/ngưỡng nhận diện**. Kết quả baseline quyết định nhánh nào cần dành nhiều công sức hơn; nhiều model, nhiều frame và đổi model được cân nhắc theo số đo.

Đợt 2A đã hoàn thành phần code, kiểm thử, snapshot model và hướng dẫn thu dữ liệu. Bước còn lại trước 2B là thu ảnh webcam có nhãn và chạy baseline thực tế.
