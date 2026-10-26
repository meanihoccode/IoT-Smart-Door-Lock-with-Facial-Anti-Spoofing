# Runbook triển khai cloud — IoT Level 3

Trạng thái: **artifact đã chuẩn bị, chưa triển khai lên cloud thật** (D06 chưa chốt nhà cung cấp/domain). Mọi tên miền, email, mật khẩu dưới đây là placeholder.

## Kiến trúc

```text
ESP32-S3 ──HTTPS 443 (Bearer token thiết bị)──► Caddy (web) ──► Core :8080 ──► AI :8000
   │                                              │ React tĩnh       │  └──► MySQL
   └──MQTT TLS 8883 (user/pass + ACL)──► Mosquitto ◄──── tcp 1883 nội bộ (user core)
```

- Chỉ public **80/443** (Caddy, HTTPS tự động Let's Encrypt) và **8883** (MQTT TLS). DB, AI, Core nằm trong mạng `backend` `internal: true`: không cổng public, không ra Internet.
- Frontend và API cùng origin (`https://<domain>/` và `/api`). Cookie admin `Secure` + `SameSite=Strict`.
- Laptop không cần chạy gì. Thiết bị kết nối outbound, không port forwarding.

## Yêu cầu

- Một VM Linux có Docker Engine + Compose plugin, tối thiểu 2 vCPU / 4 GB RAM (AI chạy CPU), 20 GB đĩa.
- Một bản ghi DNS `A` trỏ domain về IP VM; firewall mở 80, 443, 8883.
- Thư mục model InsightFace `buffalo_l` (sao chép từ máy dev `~/.insightface/models/buffalo_l`). Model không được tải tự động. Kiểm tra giấy phép InsightFace trước khi dùng ngoài mục đích học tập.

## Triển khai lần đầu

Chạy trong thư mục `deploy/` trên VM.

1. **Cấu hình:** `cp .env.example .env`, điền domain, email, mật khẩu MySQL dài ngẫu nhiên, `INSIGHTFACE_MODELS_DIR` (thư mục chứa `models/buffalo_l`), `ADMIN_USERNAME/ADMIN_PASSWORD` cho lần chạy đầu. `chmod 600 .env`.
2. **Credential thiết bị:** `sh scripts/new-device-credentials.sh door-01`. Đưa `DEVICE_TOKEN_SHA256` vào `.env`; giữ token và mật khẩu MQTT cho `esp32/config.h`. Server chỉ lưu hash.
3. **Tài khoản broker:**
   ```sh
   touch mosquitto/config/passwd
   docker compose run --rm mosquitto mosquitto_passwd -b /mosquitto/config/passwd core '<MQTT_CORE_PASSWORD>'
   docker compose run --rm mosquitto mosquitto_passwd -b /mosquitto/config/passwd door-01 '<mật khẩu MQTT thiết bị>'
   # Mosquitto reads the password file as root before dropping privileges; 2.0.x warns otherwise.
   sudo chown root:root mosquitto/config/passwd && sudo chmod 600 mosquitto/config/passwd
   ```
4. **Chứng chỉ:** `docker compose up -d --build web core db ai` rồi chờ Caddy lấy chứng chỉ (`docker compose logs -f web`). Sau đó `sudo sh scripts/sync-mqtt-cert.sh` và `docker compose up -d mosquitto`. Đặt cron hằng ngày cho `sync-mqtt-cert.sh` để broker nhận chứng chỉ gia hạn.
5. **Kiểm tra:** `docker compose ps`; `curl -s https://<domain>/api/door-status`; đăng nhập `/login`, đổi mật khẩu, rồi xóa `ADMIN_PASSWORD` khỏi `.env` và `docker compose up -d core`.
6. **Firmware:** điền `esp32/config.h` (copy từ `config.example.h`): Wi-Fi, `API_BASE_URL=https://<domain>`, `MQTT_HOST=<domain>`, token, mật khẩu MQTT, và **ISRG Root X1 + X2** từ <https://letsencrypt.org/certificates/> (đối chiếu fingerprint). Compile bằng `scripts/Compile-Esp32.ps1 -Fqbn '<FQBN đã xác minh>'`. Chỉ flash/thử cửa thật khi đã được phép và đã xác minh wiring/thoát hiểm.

## Chạy thử toàn bộ stack trên máy dev (không cần domain)

Overlay `local/` dùng cùng image, mạng và ACL như production, chỉ thay Let's Encrypt bằng một CA thử nghiệm tự tạo (hết hạn sau 30 ngày; không nạp CA này vào thiết bị thật). Chạy trong `deploy/`, Docker Desktop đang bật:

```sh
sh local/setup-local.sh "C:/Users/<bạn>/.insightface"     # tạo CA, bí mật tạm, passwd broker, local/.env.local
docker compose -p smartlock-local -f docker-compose.yml -f local/docker-compose.local.yml --env-file local/.env.local up -d --build
python local/smoke_test.py                                  # cần paho-mqtt==2.1.0; tạo một hồ sơ PIN thử
```

App ở `https://localhost:8443` (trình duyệt sẽ cảnh báo vì CA thử nghiệm), admin `localadmin`, mật khẩu trong `local/secrets/admin.txt`. Dừng bằng lệnh `... down`; thêm `-v` sẽ **xóa** dữ liệu staging.

Firmware compile (đã xác minh với board package `esp32:esp32@3.3.12`; chip thật vẫn cần đối chiếu là N16R8):

```powershell
.\scripts\Compile-Esp32.ps1 -Fqbn 'esp32:esp32:esp32s3:FlashMode=qio,FlashSize=16M,PSRAM=opi,PartitionScheme=app3M_fat9M_16MB'
```

## Kiểm thử staging bằng simulator (chưa cần phần cứng)

```sh
pip install paho-mqtt==2.1.0
python tools/device-simulator/device_simulator.py --api https://<domain> --mqtt-host <domain> \
  --device-id door-01 --token-file token.txt --mqtt-password-file mqtt.txt pin <PIN hồ sơ thử>
```

Kết quả mong đợi: HTTP `202` với `decision=GRANTED`, simulator nhận command, gửi `RECEIVED` rồi `EXECUTED`; dashboard admin hiện "Thiết bị đã điều khiển khóa". Ghi bằng chứng mức **cloud staging với simulator** vào [ACCEPTANCE](../docs/ACCEPTANCE.md). Xóa file token/mật khẩu tạm sau khi thử.

## Nâng cấp và rollback

1. `sh scripts/backup.sh` (bắt buộc trước khi đổi schema/dữ liệu) và chép bản backup ra khỏi VM.
2. `git pull` phiên bản đã review, chạy test theo `CLAUDE.md` trên máy dev.
3. `docker compose up -d --build`.
4. Rollback: checkout commit trước, `docker compose up -d --build`. Nếu schema đã đổi không tương thích, khôi phục DB:
   `gunzip -c backups/db-<stamp>.sql.gz | docker compose exec -T db sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE"'`.

Schema hiện do Hibernate `ddl-auto=update` tự thêm bảng/cột (thay đổi Level 3 chỉ thêm: `devices`, `door_commands`, `device_requests`, cột mới ở `access_logs`). Migration framework và kiểm tra restore định kỳ là hạng mục P1.

## Xoay hoặc thu hồi credential thiết bị

- Token HTTP: tạo token mới, cập nhật `DEVICE_TOKEN_SHA256` trong `.env`, `docker compose up -d core`, rồi nạp firmware mới. Token cũ hết hiệu lực ngay khi Core khởi động với hash mới.
- MQTT: `mosquitto_passwd -b ... door-01 '<mới>'` rồi `docker compose restart mosquitto`; hoặc `mosquitto_passwd -D` để thu hồi.

## Giới hạn đã biết

- Một instance Core (giới hạn thử và chống trùng event nằm trong bộ nhớ; mất khi restart). Không HA.
- PubSubClient chỉ publish QoS 0: ACK có thể mất, khi đó lệnh hiện **Không rõ kết quả**, không tự gửi lại.
- Listener 1883 nội bộ không TLS (chỉ trong mạng `backend` internal, có mật khẩu).
- Không có cảm biến cửa: "đã điều khiển khóa" không chứng minh cửa đã mở vật lý.
