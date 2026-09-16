# Giai đoạn 2A — Bộ đánh giá độ chính xác AI độc lập

- Ngày triển khai công cụ: 14/09/2026.
- Phạm vi: `backend-ai`, ảnh/clip cục bộ và báo cáo offline.
- Không sử dụng frontend, Spring Boot, MySQL, MQTT hoặc phần cứng khóa.
- Trạng thái số liệu: **chưa đánh giá độ chính xác vì chưa có bộ ảnh webcam được gán nhãn**.

## 1. Mục đích

Bộ công cụ chạy đúng model và pipeline đang dùng trong API, nhưng nhận gallery và probe từ manifest cục bộ. Mỗi ảnh được đo theo ba nhánh:

1. `recognition_only`: phát hiện mặt, lấy embedding và so gallery; bỏ qua liveness chỉ trong công cụ chẩn đoán.
2. `liveness_only`: phát hiện mặt và chạy MiniFASNet; không dùng kết quả nhận diện.
3. `combined`: gọi đúng `FacePipeline.verify()` như API; liveness phải qua trước khi nhận diện.

Nhờ vậy, một lượt `SPOOF_DETECTED` vẫn có thể được kiểm tra xem recognition có nhận đúng hay không, nhưng endpoint mở khóa không có tham số bỏ qua liveness.

Cập nhật 15/09/2026: cả ba nhánh chọn [khuôn mặt lớn nhất](docs/LARGEST_FACE_VERIFICATION.md) giống API; `face_count` ghi tổng số mặt phát hiện. `liveness_only` chỉ chạy detector và MiniFASNet. Ảnh enrollment vẫn yêu cầu đúng một mặt. Snapshot dùng phiên bản `phase2a-largest-face-v1` để phân biệt với baseline cũ từ chối nhiều mặt.

## 2. Những file đã tạo/thay đổi

| File | Vai trò |
| --- | --- |
| [baseline.json](backend-ai/configs/baseline.json) | Cấu hình có phiên bản cho baseline hiện tại |
| [face_pipeline.py](backend-ai/face_pipeline.py) | Decode, chọn mặt lớn nhất khi xác thực, kiểm tra embedding, liveness, cosine và quyết định dùng chung |
| [model_runtime.py](backend-ai/model_runtime.py) | Chỉ tải model khi API khởi động hoặc CLI thực sự cần model |
| [main.py](backend-ai/main.py) | Adapter HTTP/DB gọi pipeline dùng chung; `faceCount` là tổng số mặt phát hiện |
| [evaluate.py](backend-ai/tools/evaluate.py) | CLI kiểm tra manifest, snapshot và đánh giá |
| [capture_dataset.py](backend-ai/tools/capture_dataset.py) | Thu từng loạt ảnh từ webcam bằng OpenCV headless |
| [manifest.py](backend-ai/evaluation/manifest.py) | Đọc manifest, hash ảnh và phát hiện rò rỉ giữa các tập |
| [runner.py](backend-ai/evaluation/runner.py) | Chạy gallery/probe và ba nhánh chẩn đoán |
| [metrics.py](backend-ai/evaluation/metrics.py) | Tính số lỗi, mẫu số, tỷ lệ và p50/p95 |
| [snapshot.py](backend-ai/evaluation/snapshot.py) | Hash code/config/model, dependency/provider và metadata run |
| [manifest.csv](backend-ai/evaluation/examples/manifest.csv) | Ví dụ schema, không phải dữ liệu thật để chạy |

`backend-ai/datasets/` và `backend-ai/reports/` đã được ignore để tránh commit ảnh, embedding và kết quả có metadata.

## 3. Cấu hình baseline được cố định

Baseline giữ đúng hành vi trước giai đoạn 2B:

- InsightFace `buffalo_l`, CPU `ctx_id=-1`, detection `640×640`;
- embedding 512 chiều, cosine và ngưỡng `0.5`;
- một ảnh đăng ký cho mỗi người;
- MiniFASNetV2 checkpoint `2.7_80x80_MiniFASNetV2.pth`;
- crop vuông hiện tại `square_max_side_clipped_v1`, scale `2.7`, resize `80×80`;
- BGR, float32, BCHW, thang `0–255`;
- quyết định liveness bằng argmax lớp 1;
- chưa có quality gate và chưa có vùng uncertain.

Việc ghi rõ crop hiện tại là cần thiết: sau giai đoạn 2B có thể chạy lại cùng dữ liệu để so với crop chuẩn tham chiếu.

## 4. Chuẩn bị bộ ảnh

Dùng mã nội bộ như `p01`, không ghi họ tên trong tên file/manifest. Người xuất hiện trong dữ liệu phải đồng ý tham gia.

Với mỗi người đăng ký, tối thiểu nên có:

- 1 ảnh `enrollment` để tạo gallery baseline;
- ảnh `development` ở một buổi khác: chính diện đủ sáng, thiếu sáng, kính nếu thường dùng và góc nhẹ;
- ảnh giả được chính webcam mục tiêu chụp lại: ảnh in, ảnh hiển thị trên màn hình và video replay;
- một số người lạ không có ảnh `enrollment` để đo nhận nhầm.

Không dùng ảnh enrollment làm probe. Mỗi buổi dùng `session-id` khác nhau. Ảnh từ cùng buổi, cùng clip hoặc cùng nguồn biến đổi không được chia sang các tập khác nhau.

Ví dụ thu năm ảnh đăng ký:

```powershell
cd backend-ai
.\.venv\Scripts\python.exe -B tools\capture_dataset.py `
  --dataset-root datasets\pilot01 `
  --manifest datasets\pilot01\manifest.csv `
  --subject-id p01 --session-id p01_enroll_01 `
  --split enrollment --presentation-type live `
  --camera-id laptop01 --condition normal --count 1
```

Baseline chỉ cho một ảnh enrollment/người. Thu probe ở buổi khác:

```powershell
.\.venv\Scripts\python.exe -B tools\capture_dataset.py `
  --dataset-root datasets\pilot01 `
  --manifest datasets\pilot01\manifest.csv `
  --subject-id p01 --session-id p01_dev_01 `
  --split development --presentation-type live `
  --camera-id laptop01 --condition normal --count 5
```

Thu ảnh in hoặc màn hình bằng cách đặt vật trình diễn trước webcam rồi đổi `presentation-type` thành `print` hoặc `screen_photo`. Tool chụp số ảnh đã khai báo sau thời gian warmup; dùng `Ctrl+C` để hủy. Replay từ file video hiện được thêm thủ công theo schema ví dụ, với `clip_id`, `frame_index` cụ thể và một frame cho mỗi `attempt_id`.

## 5. Manifest và quy tắc chống rò rỉ

Các cột bắt buộc:

- `sample_id`, `relative_path`, `subject_id`, `session_id`, `clip_id`;
- `split`: `enrollment`, `development`, `calibration` hoặc `test`;
- `presentation_type`: `live`, `print`, `screen_photo` hoặc `replay`;
- `camera_id`, `condition`, `mirrored`, `capture_source`, `frame_index`;
- `source_id`: dùng chung cho ảnh gốc và mọi bản biến đổi từ ảnh đó;
- `attempt_id`: một ảnh/frame đại diện một lượt baseline.

Validator kiểm tra đường dẫn không thoát khỏi dataset root, hash trùng, source/session/clip không băng qua split, một enrollment/người và người lạ ở final test không xuất hiện trong development/calibration. File thiếu vẫn được giữ với `MEDIA_UNREADABLE` để không biến lỗi đầu vào thành dữ liệu bị bỏ qua.

Kiểm tra manifest mà không tải model:

```powershell
.\.venv\Scripts\python.exe -B tools\evaluate.py `
  --manifest datasets\pilot01\manifest.csv `
  --dataset-root datasets\pilot01 --validate-only
```

## 6. Chạy baseline

Chụp trạng thái môi trường và model khi chưa có dữ liệu:

```powershell
.\.venv\Scripts\python.exe -B tools\evaluate.py `
  --snapshot-only --output reports\phase2a-no-data-20260914
```

Chạy split development sau khi có dữ liệu:

```powershell
.\.venv\Scripts\python.exe -B tools\evaluate.py `
  --manifest datasets\pilot01\manifest.csv `
  --dataset-root datasets\pilot01 `
  --split development `
  --output reports\baseline-development-run01
```

CLI từ chối ghi đè thư mục report cũ. Split `test` yêu cầu `--allow-final-test` để nhắc rằng chỉ nên mở sau khi đã khóa cấu hình.

Mỗi run gồm:

- `baseline_snapshot.json`: hash code/config/model, phiên bản runtime, provider và metadata manifest;
- `enrollment_results.json`: trạng thái tạo gallery cho từng người;
- `results.jsonl`: kết quả từng lượt ở ba nhánh;
- `summary.json`: chỉ số tổng, theo điều kiện và theo camera;
- `README.md`: trạng thái của run.

Nếu một nhóm không có mẫu, tỷ lệ là `null`, không phải 0%. Lỗi decode/detection/model được tính trong tỷ lệ trên toàn đầu vào. Báo cáo cũng có số liệu `scored_only` để chẩn đoán model, nhưng không dùng số này thay cho kết quả toàn lượt.

## 7. Diễn giải kết quả

Các chỉ số chính:

- `known_live_all_inputs.correct_identity`: người đã đăng ký được trả đúng danh tính;
- `known_live_all_inputs.not_correct_identity`: người đã đăng ký không được trả đúng, gồm lỗi ảnh/detection/gate;
- `unknown_live_false_accept_all_inputs`: người lạ bị nhận thành người trong gallery;
- `wrong_identity`: người đã đăng ký bị gán thành một người đăng ký khác;
- `liveness_only.*.accepted_all_inputs`: tỷ lệ từng loại trình diện được coi là thật;
- `combined.attack_false_accept_by_type`: giả mạo qua toàn pipeline;
- `reason_counts`: nguyên nhân các lượt không được xử lý/không qua;
- `latency`: p50/p95 theo từng nhánh.

Baseline chưa chứng minh cải thiện vì chưa có candidate để so. Sau khi có dữ liệu, kết quả sẽ chỉ ra lỗi của người dùng chủ yếu đến từ detection, liveness hay ngưỡng nhận diện; lúc đó mới triển khai 2B và đo lại trên cùng split development.

## 8. Giới hạn hiện tại

- Chưa có ảnh webcam gán nhãn nên chưa có tỷ lệ accuracy thật.
- Baseline là một ảnh/frame cho một attempt; chưa tổng hợp nhiều frame.
- Công cụ chạy ba nhánh tuần tự trên cùng ảnh nên tổng thời gian chạy batch không phải latency vận hành. Latency từng nhánh gồm media decode; startup model được báo riêng.
- Chưa tính khoảng tin cậy theo người/buổi/clip; pilot nhỏ chỉ dùng chẩn đoán.
- Công cụ không tự chọn ngưỡng và không thay đổi ngưỡng API.

## 9. Kết quả kiểm chứng ngày 14/09/2026

| Kiểm tra | Kết quả |
| --- | --- |
| Unit/integration test Python | 30/30 đạt |
| CLI `evaluate.py --help` | Đạt trên Windows với UTF-8 |
| CLI `capture_dataset.py --help` | Đạt; không truy cập camera trong lần kiểm chứng này |
| Snapshot model thật | Face model và liveness đều ready; ghi hash 5 ONNX + 1 checkpoint |
| Synthetic smoke test | Model thật trả `NO_FACE` với ảnh xám; run được ghi `smoke_test_only` |
| FastAPI lifecycle | Khởi động thành công; `/ready` trả HTTP 200 với model và database ready |
| API ảnh sai | Giữ đúng hợp đồng giai đoạn 1 và trả `INVALID_IMAGE` |
| Accuracy trên ảnh webcam | Chưa đánh giá — chưa có dữ liệu gán nhãn |

Snapshot cục bộ đã tạo tại `backend-ai/reports/phase2a-no-data-20260914-run01`; smoke report tại `backend-ai/reports/phase2a-synthetic-smoke-20260914-run01`. Hai thư mục nằm trong vùng ignore và không được commit. Snapshot ghi `accuracy_improvement_established=false`; kết quả này chỉ chứng minh công cụ/model hoạt động, không phải số đo độ chính xác.
