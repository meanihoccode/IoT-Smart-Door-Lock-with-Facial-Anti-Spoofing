# Runbook triển khai cloud — IoT Level 3 (REST + WebSocket)

Trạng thái: **artifact đã chuẩn bị, chưa triển khai lên cloud thật** (D06 chưa chốt nhà cung cấp/domain). Mọi tên miền, email, mật khẩu dưới đây là placeholder.

## Kiến trúc

Theo sơ đồ IoT Level 3 của môn học: Local ↔ Cloud và Cloud ↔ App đều là **REST/WebSocket** (D05).

```text
LOCAL (1 nút)                                   CLOUD (1 VM, chỉ mở 443)
ESP32-S3 ──REST  https://<domain>/api/device/*──► Caddy ──► Core :8080 ──► AI :8000 (liveness + nhận diện)
         ◄─WebSocket wss://<domain>/ws/device──►   │          │
           (lệnh xuống, ACK/sự kiện/trạng thái lên) │          └──► MySQL + volume ảnh cảnh báo
                                     React tĩnh ◄───┘ (App: https://<domain>/)
```

- Chỉ public **80/443** (Caddy, HTTPS tự động Let's Encrypt). REST, WebSocket và app dùng chung chứng chỉ và tên miền. DB, AI, Core nằm trong mạng `backend` `internal: true`: không cổng public, không ra Internet.
- Một token thiết bị (Core chỉ lưu SHA-256) xác thực cả REST lẫn bắt tay WebSocket; mỗi thiết bị chỉ có một socket.
- Frontend và API cùng origin. Cookie admin `Secure` + `SameSite=Strict`.
- Laptop không cần chạy gì. Thiết bị chỉ kết nối outbound tới 443, không port forwarding.

## Yêu cầu

- Một VM Linux có Docker Engine + Compose plugin, tối thiểu 2 vCPU / 4 GB RAM (AI chạy CPU), 20 GB đĩa.
- Một bản ghi DNS `A` trỏ domain về IP VM; firewall chỉ mở 22, 80, 443.
- Thư mục model InsightFace `buffalo_l` (sao chép từ máy dev `~/.insightface/models/buffalo_l`, ví dụ `scp -r` vào `/opt/smartlock/insightface/models/`). Model không được tải tự động. Sau khi chép, chạy `chmod -R a+rX,go-w /opt/smartlock/insightface`: `scp` từ Windows tạo thư mục quyền `700`, container AI (uid 10001) sẽ không đọc được và báo `faceModel:false`. Kiểm tra giấy phép InsightFace trước khi dùng ngoài mục đích học tập.

## Triển khai lần đầu

Chạy trong thư mục `deploy/` trên VM.

1. **Cấu hình:** `cp .env.example .env`, điền domain, mật khẩu MySQL dài ngẫu nhiên (không cần email: Let's Encrypt cấp chứng chỉ không kèm email liên hệ), `INSIGHTFACE_MODELS_DIR` (thư mục chứa `models/buffalo_l`), `ADMIN_USERNAME/ADMIN_PASSWORD` cho lần chạy đầu. `chmod 600 .env`.
2. **Credential thiết bị:** `sh scripts/new-device-credentials.sh door-01`. Đưa `DEVICE_TOKEN_SHA256` vào `.env`; giữ token cho `esp32/config.h`.
3. **Khởi động:** `docker compose up -d --build`. Caddy tự lấy chứng chỉ (`docker compose logs -f web`).
4. **Kiểm tra:** `docker compose ps`; `curl -s https://<domain>/api/door-status`; đăng nhập `/login`, đổi mật khẩu, rồi xóa `ADMIN_PASSWORD` khỏi `.env` và `docker compose up -d core`.
5. **Firmware:** điền `esp32/config.h` (copy từ `config.example.h`): Wi-Fi, `CLOUD_HOST=<domain>`, `CLOUD_PORT=443`, token và **ISRG Root X1 + X2** từ <https://letsencrypt.org/certificates/> (đối chiếu fingerprint). Thư viện: Keypad, ESP32Servo, WebSockets (Markus Sattler) 2.7.2. Compile bằng `scripts/Compile-Esp32.ps1 -Fqbn '<FQBN>'`. Chỉ flash/thử cửa thật khi đã được phép và đã xác minh wiring/thoát hiểm.

## Chạy thử toàn bộ stack trên máy dev (không cần domain)

Overlay `local/` dùng cùng image và mạng như production, chỉ thay Let's Encrypt bằng một CA thử nghiệm tự tạo (hết hạn sau 30 ngày; không nạp CA này vào thiết bị thật). Chạy trong `deploy/`, Docker Desktop đang bật:

```sh
sh local/setup-local.sh "C:/Users/<bạn>/.insightface"     # tạo CA, token tạm, local/.env.local
docker compose -p smartlock-local -f docker-compose.yml -f local/docker-compose.local.yml --env-file local/.env.local up -d --build
python local/smoke_test.py                                  # cần websocket-client==1.8.0; tạo một hồ sơ PIN thử
```

App ở `https://localhost:8443` (trình duyệt sẽ cảnh báo vì CA thử nghiệm), admin `localadmin`, mật khẩu trong `local/secrets/admin.txt`. Dừng bằng lệnh `... down`; thêm `-v` sẽ **xóa** dữ liệu staging.

Firmware compile (đã xác minh với board package `esp32:esp32@3.3.12`; chip thật vẫn cần đối chiếu là N16R8):

```powershell
.\scripts\Compile-Esp32.ps1 -Fqbn 'esp32:esp32:esp32s3:FlashMode=qio,FlashSize=16M,PSRAM=opi,PartitionScheme=app3M_fat9M_16MB'
```

## Kiểm thử cloud staging bằng simulator (chưa cần phần cứng)

```sh
pip install websocket-client==1.8.0
python tools/device-simulator/device_simulator.py --cloud https://<domain> --device-id door-01 \
  --token-file token.txt pin <PIN hồ sơ thử>
```

Kết quả mong đợi: HTTP `202` với `decision=GRANTED`, simulator nhận command qua WebSocket, gửi `RECEIVED` rồi `EXECUTED`; dashboard admin hiện "Thiết bị đã điều khiển khóa". Ghi bằng chứng mức **cloud staging với simulator** vào [ACCEPTANCE](../docs/ACCEPTANCE.md). Xóa file token tạm sau khi thử.

## Nâng cấp và rollback

1. `sh scripts/backup.sh` (bắt buộc trước khi đổi schema/dữ liệu) và chép bản backup ra khỏi VM.
2. `git pull` phiên bản đã review, chạy test theo `AGENTS.md` trên máy dev.
3. `docker compose up -d --build`. Thiết bị tự kết nối lại WebSocket sau khi Core khởi động lại.
4. Rollback: checkout commit trước, `docker compose up -d --build`. Nếu schema đã đổi không tương thích, khôi phục DB:
   `gunzip -c backups/db-<stamp>.sql.gz | docker compose exec -T db sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE"'`.

Schema hiện do Hibernate `ddl-auto=update` tự thêm bảng/cột (thay đổi Level 3 chỉ thêm: `devices`, `door_commands`, `device_requests`, cột mới ở `access_logs`). Migration framework và kiểm tra restore định kỳ là hạng mục P1.

## Xoay hoặc thu hồi credential thiết bị

Tạo token mới, cập nhật `DEVICE_TOKEN_SHA256` trong `.env`, `docker compose up -d core`, rồi nạp firmware mới. Token cũ hết hiệu lực cho cả REST và WebSocket ngay khi Core khởi động với hash mới (socket đang mở sẽ bị đóng khi Core restart và không bắt tay lại được).

## Giới hạn đã biết

- Một instance Core: socket thiết bị, giới hạn thử và chống trùng event nằm trong bộ nhớ của instance đó. Không HA.
- `PUBLISHED` nghĩa là lệnh đã được ghi vào WebSocket của thiết bị; ACK có thể mất khi socket rớt, khi đó lệnh hiện **Không rõ kết quả**, không tự gửi lại.
- Không có cảm biến cửa: "đã điều khiển khóa" không chứng minh cửa đã mở vật lý.
