# Hệ Thống Cửa Thông Minh (Smart Lock System) tích hợp AI và IoT

Đây là một dự án Bài Tập Lớn (BTL) môn IoT / Đồ án tốt nghiệp kết hợp giữa hệ thống phần cứng IoT, Backend (Java + Python), AI (Nhận diện khuôn mặt & Chống giả mạo) và một trang Dashboard quản trị bằng React.

Hệ thống cho phép mở khóa bằng mã PIN hoặc quét khuôn mặt (qua một thiết bị có camera), nhận diện người dùng thật/giả (chống dùng ảnh/video), sau đó điều khiển cửa thông qua ESP32. Đồng thời, mọi hoạt động mở cửa và quản lý người dùng đều được thống kê trên Web Dashboard.

## 🏗 Cấu Trúc Dự Án

Dự án được chia thành 4 module độc lập:

1. **`backend-core` (Java Spring Boot)**: Là máy chủ trung tâm. Nhiệm vụ:
   - Cung cấp các API RESTful cho Frontend (Dashboard, Quản lý Nhân Viên).
   - Lưu trữ dữ liệu lịch sử và thông tin người dùng vào **MySQL**.
   - Giao tiếp với `backend-ai` để phân tích khuôn mặt.
   - Giao tiếp với thiết bị `esp32` thông qua giao thức **MQTT**.
2. **`backend-ai` (Python FastAPI)**: Là máy chủ chuyên xử lý AI. Nhiệm vụ:
   - Sử dụng **InsightFace (RetinaFace + ArcFace)** để trích xuất đặc trưng khuôn mặt (Face Embedding 512 chiều) và so sánh Cosine Similarity.
   - Sử dụng **MiniFASNet (Silent-Face-Anti-Spoofing)** để kiểm tra Liveness Detection (Chống giả mạo bằng cách giơ ảnh từ điện thoại hoặc mặt nạ giấy).
   - Truy vấn MySQL để lấy dữ liệu khuôn mặt và trả kết quả chính xác về cho Core.
3. **`frontend` (React + Vite + TailwindCSS)**: Trang Web quản trị. Nhiệm vụ:
   - Theo dõi tổng quan: Số nhân viên, số lượt ra vào, lịch sử mở cửa.
   - Quản lý danh sách nhân viên: Xem phương thức mở khóa, ngày hoạt động cuối cùng.
   - Thêm nhân viên: Gọi API để trích xuất đặc trưng mặt và cấp quyền.
4. **`esp32` (C/C++ Arduino)**: Code nạp cho vi điều khiển. Nhiệm vụ:
   - Lắng nghe lệnh mở cửa `OPEN_DOOR` từ broker MQTT (topic `iot/lock/commands`) để bật Relay/Servo mở chốt cửa.
   - Theo dõi cảm biến chuyển động PIR và gửi dữ liệu về topic `iot/lock/events` nếu phát hiện có người đến gần cửa.

## 🚀 Hướng Dẫn Cài Đặt

Phần quản trị hiện yêu cầu đăng nhập. Xem [bàn giao đợt 1 — đăng nhập quản trị](docs/PHASE_01_ADMIN_AUTH.md) để tạo tài khoản đầu tiên, chạy kiểm thử và review các giới hạn bảo mật còn lại.

### Yêu Cầu Hệ Thống
- JDK 17 (theo Gradle toolchain của repo)
- Python 3.11 (đã kiểm tra)
- Node.js 20.19+ thuộc nhánh 20, hoặc từ 22.12 trở lên
- MySQL Server (Port 3306)
- MQTT Broker (VD: Eclipse Mosquitto - Port 1883)

### 1. Database MySQL
- Tạo một database trong MySQL với tên `btl_iot`.
- Copy `backend-ai/.env.example` thành `backend-ai/.env` và `backend-core/config/application-local.properties.example` thành `backend-core/config/application-local.properties`.
- Điền thông tin MySQL của máy bạn trong hai file local; Java và Python phải dùng cùng database. Không sửa mật khẩu trực tiếp trong mã nguồn hoặc commit file local.
- Hướng dẫn Windows và quy tắc cấu hình dùng chung: [SETUP_WINDOWS.md](SETUP_WINDOWS.md).

### 2. MQTT Broker
- Cài đặt và khởi chạy Mosquitto MQTT Broker trên máy tính ở cổng `1883`.

### 3. Backend Core (Java)
Di chuyển vào thư mục `backend-core` và chạy lệnh sau để build và khởi động:
```bash
cd backend-core
./gradlew bootRun
```
*Server sẽ chạy ở cổng `8080`.*

### 4. Backend AI (Python)
Di chuyển vào thư mục `backend-ai` và cài đặt thư viện:
```bash
cd backend-ai
pip install -r requirements.txt
```
Chạy server AI:
```bash
python main.py
```
*Server sẽ chạy ở cổng `8000`. Lần đầu chạy có thể sẽ tốn thời gian để tải mô hình AI về máy.*

### 5. Frontend (React)
Di chuyển vào thư mục `frontend`:
```bash
cd frontend
npm install
npm run dev
```
*Trang web sẽ chạy tại địa chỉ được sinh ra (thường là `http://localhost:5173`).*

### 6. Phần cứng (ESP32)
- Mở file `esp32/main.ino` bằng Arduino IDE.
- Copy `esp32/config.example.h` thành `esp32/config.h`, điền Wi-Fi và IP MQTT của máy bạn trong file local này.
- Có thể dùng `scripts/Compile-Esp32.ps1` để biên dịch bằng Arduino CLI; xem hướng dẫn Windows ở trên.
- Cài các thư viện `PubSubClient` và nạp code vào board ESP32.

---
## ✨ Tính năng nổi bật
- **Anti-Spoofing (Liveness Detection)**: Hệ thống sử dụng mô hình học sâu để kiểm tra xem khuôn mặt là thực hay là hình ảnh giả mạo chụp từ màn hình/giấy.
- **Microservices Architecture**: Tách rời Backend quản trị logic (Spring Boot) và Backend AI (FastAPI) để tối ưu hiệu năng.
- **MQTT Messaging**: Điều khiển IoT realtime, không phụ thuộc vào IP nội bộ (nếu broker đưa lên Cloud).
- **Dashboard thời gian thực**: Sử dụng giao diện hiện đại (TailwindCSS) hiển thị thông kê người dùng trực quan.
