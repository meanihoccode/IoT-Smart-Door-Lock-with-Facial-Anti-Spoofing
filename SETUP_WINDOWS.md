# Thiết lập môi trường Windows cho cả nhóm

Mở repository trong VS Code. Các lệnh bắt đầu từ thư mục gốc repo,
không phụ thuộc tên ổ đĩa hoặc đường dẫn clone.

## Công cụ

- JDK 17; đặt JAVA_HOME tới JDK trên máy bạn. Repo có Gradle Wrapper.
- Python 3.11 (đã kiểm tra).
- Node.js 20 từ 20.19, hoặc từ 22.12 trở lên, kèm npm.
- MySQL và MQTT broker, mặc định localhost:3306 và localhost:1883.
- Arduino CLI nếu cần biên dịch ESP32.

Extension Java/Python/Spring Boot là tùy chọn. Cấu hình .vscode là local;
các thành viên có thể chạy bằng terminal mà không cần các task của máy khác.

## Cấu hình riêng

Tạo database btl_iot nếu chưa có. Tạo bản local từ các file mẫu, chỉ khi chưa có:

```powershell
if (!(Test-Path backend-ai/.env)) {
    Copy-Item backend-ai/.env.example backend-ai/.env
}
if (!(Test-Path backend-core/config/application-local.properties)) {
    Copy-Item backend-core/config/application-local.properties.example backend-core/config/application-local.properties
}
```

Điền SMARTLOCK_DB_HOST, SMARTLOCK_DB_PORT, SMARTLOCK_DB_NAME, SMARTLOCK_DB_USER, SMARTLOCK_DB_PASSWORD vào cả hai file để Java
và Python cùng dùng một database. Mật khẩu mẫu để trống; dùng tài khoản của
máy bạn, không sửa mật khẩu trong mã nguồn.

- Python tự đọc backend-ai/.env; có thể đặt giá trị trong dấu nháy đơn.
- Java đọc config/application-local.properties khi chạy từ backend-core.
  File này dùng định dạng Java properties, không đặt dấu nháy quanh giá trị.
- Biến môi trường SMARTLOCK_DB_* có thể thay thế giá trị file local.
- Đổi broker Java bằng SMARTLOCK_MQTT_BROKER_URL trong cấu hình local hoặc môi trường.

Các file local bị Git bỏ qua; chỉ commit file mẫu.

## Cài dependency

Python, từ thư mục gốc:

```powershell
py -3.11 -m venv backend-ai/.venv
./backend-ai/.venv/Scripts/python.exe -m pip install -r backend-ai/requirements.txt
```

Frontend:

```powershell
cd frontend
npm.cmd ci
```

## Chạy ba terminal

Khởi động MySQL và MQTT broker trước. Mỗi terminal bắt đầu từ thư mục gốc repo.

Backend AI:

```powershell
cd backend-ai
./.venv/Scripts/python.exe main.py
```

Chạy từ backend-ai để đường dẫn trọng số MiniFASNet hoạt động đúng.
InsightFace tải model vào thư mục người dùng ở lần chạy đầu.

Backend Java:

```powershell
cd backend-core
./gradlew.bat bootRun
```

Frontend:

```powershell
cd frontend
npm.cmd run dev
```

Web thường ở http://localhost:5173/kiosk, quản trị ở /admin.
AI API: http://localhost:8000/docs. Java: http://localhost:8080/api/overview.
Dùng Ctrl+C để dừng. Đóng/mở lại VS Code sau khi thay PATH.

## ESP32

Cài Arduino CLI vào PATH. Cài board/library (phiên bản đã kiểm tra):

```powershell
arduino-cli core update-index --additional-urls https://espressif.github.io/arduino-esp32/package_esp32_index.json
arduino-cli core install esp32:esp32@3.3.11 --additional-urls https://espressif.github.io/arduino-esp32/package_esp32_index.json
arduino-cli lib install PubSubClient@2.8.0
```

Copy esp32/config.example.h thành esp32/config.h và điền Wi-Fi/IP MQTT của bạn.
config.h bị Git bỏ qua. Nếu chưa có, firmware dùng giá trị mẫu: biên dịch được
nhưng chưa thể kết nối mạng thực tế.

Biên dịch từ thư mục gốc:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File ./scripts/Compile-Esp32.ps1
```

Script mặc định dùng ESP32 Dev Module; truyền -Fqbn cho board khác.
Arduino CLI được tìm trên PATH hoặc vị trí cài Windows thông thường.
Có thể truyền -CliPath hoặc đặt ARDUINO_CLI khi cài ở vị trí khác.

Nếu có .runtime/arduino-cli.yaml, script dùng cấu hình local này; nếu không,
script dùng cấu hình mặc định của Arduino CLI. Có thể truyền -ConfigPath.
Dùng cùng cấu hình khi cài board/library và biên dịch (thêm --config-file
vào các lệnh cài ở trên nếu bạn dùng file riêng).
File build ở .runtime/esp32-build, không commit.

Script chỉ biên dịch. Trước khi nạp/chạy ESP32 thật cần cấu hình listener MQTT,
firewall và phần cứng phù hợp với mạng của từng người.

## Quy tắc commit

- Chia sẻ source, dependency, script và cấu hình mẫu.
- Không dùng git add -f với .env, config local, .runtime hoặc .vscode.
- Không commit cache Python, node_modules, môi trường ảo hay ghi chú đường dẫn riêng.
- Kiểm tra git status và git diff --cached trước khi commit.
