# Sơ đồ kiến trúc và luồng hoạt động

Ngày lập: 28/10/2026 · Áp dụng cho nhánh `level3-cloud` (commit `c228d5cd`). Sơ đồ viết bằng Mermaid; GitHub hiển thị trực tiếp, VS Code cần extension xem trước Mermaid. Chi tiết giao thức xem [DEVICE_CONTRACT](DEVICE_CONTRACT.md); lịch sử thay đổi xem [CHANGES_SINCE_575489b9](CHANGES_SINCE_575489b9.md).

Mục lục:

1. [Kiến trúc tổng thể IoT Level 3](#1-kiến-trúc-tổng-thể-iot-level-3)
2. [Trước và sau nâng cấp](#2-trước-và-sau-nâng-cấp)
3. [Triển khai trên AWS](#3-triển-khai-trên-aws)
4. [Thành phần bên trong Core](#4-thành-phần-bên-trong-core)
5. [Luồng mở cửa bằng khuôn mặt](#5-luồng-mở-cửa-bằng-khuôn-mặt)
6. [Luồng mở cửa bằng PIN keypad](#6-luồng-mở-cửa-bằng-pin-keypad)
7. [Luồng admin mở cửa từ xa](#7-luồng-admin-mở-cửa-từ-xa)
8. [Vòng đời lệnh (command)](#8-vòng-đời-lệnh-command)
9. [Firmware xử lý lệnh và chống phát lại](#9-firmware-xử-lý-lệnh-và-chống-phát-lại)
10. [Luồng lockdown khẩn cấp và đồng bộ lại](#10-luồng-lockdown-khẩn-cấp-và-đồng-bộ-lại)
11. [Luồng camera trực tiếp](#11-luồng-camera-trực-tiếp)
12. [Vòng lặp firmware: PIR, cảnh báo kẻ lạ](#12-vòng-lặp-firmware-pir-cảnh-báo-kẻ-lạ)
13. [Kết nối thiết bị: Wi-Fi, NTP, WebSocket](#13-kết-nối-thiết-bị-wi-fi-ntp-websocket)
14. [Đăng ký khuôn mặt](#14-đăng-ký-khuôn-mặt)

---

## 1. Kiến trúc tổng thể IoT Level 3

Một nút local (ESP32-S3) thu dữ liệu và chấp hành; lưu trữ, phân tích và ứng dụng nằm trên cloud. Ánh xạ thành phần theo tài liệu môn học ở cột chú thích.

```mermaid
flowchart LR
    subgraph NODE["Nút local – ESP32-S3 (Device)"]
        CAM["Camera OV2640"]
        PIR["Cảm biến PIR"]
        KEY["Keypad 4x4"]
        ACT["Relay / Servo / Buzzer / LED"]
        FW["Firmware<br/>(Controller Service)"]
        CAM --> FW
        PIR --> FW
        KEY --> FW
        FW --> ACT
    end

    subgraph CLOUD["Cloud – AWS EC2 (Docker Compose)"]
        WEB["Caddy<br/>TLS + web tĩnh<br/>(Web Service)"]
        CORE["Core Spring Boot<br/>quyết định, command, ACK<br/>(Web Service + Management)"]
        AI["AI FastAPI<br/>liveness + nhận diện<br/>(Analysis Component)"]
        DB[("MySQL<br/>(Database)")]
        WEB --> CORE
        CORE --> AI
        CORE --> DB
        AI --> DB
    end

    subgraph APP["Ứng dụng (Application)"]
        ADMIN["Trang admin<br/>(đăng nhập)"]
        KIOSK["Trang kiosk<br/>(công khai, chỉ xem)"]
    end

    FW -- "REST HTTPS<br/>ảnh, PIN, cảnh báo" --> WEB
    FW <-- "WebSocket wss<br/>lệnh ↓ · ACK/sự kiện/trạng thái/khung hình ↑" --> WEB
    ADMIN -- "HTTPS REST" --> WEB
    KIOSK -- "HTTPS REST" --> WEB
```

Điểm chính:

- Thiết bị chỉ nói chuyện với **một tên miền** (`smartlock-hung.duckdns.org`, cổng 443), xác thực bằng token Bearer.
- DB và AI **không** mở ra internet; chỉ Core gọi được.
- Trình duyệt không có đường mở cửa bằng ảnh/PIN; chỉ admin đã đăng nhập mở từ xa.

## 2. Trước và sau nâng cấp

```mermaid
flowchart TB
    subgraph BEFORE["Trước – commit 575489b9 (mọi thứ trong LAN)"]
        direction LR
        E1["ESP32<br/>web server :80<br/>master PIN 123456"]
        L1["Laptop<br/>Core :8080 · AI :8000<br/>MySQL · Mosquitto :1883"]
        B1["Trình duyệt kiosk<br/>webcam/PIN mở cửa"]
        E1 -- "HTTP thường<br/>/api/verify-face, /verify-pin" --> L1
        L1 -- "MQTT tcp 1883<br/>chuỗi OPEN_DOOR" --> E1
        B1 -- "HTTP<br/>/api/verify-face" --> L1
        B1 -- "http://IP-ESP32/capture" --> E1
    end

    subgraph AFTER["Sau – nhánh level3-cloud"]
        direction LR
        E2["ESP32<br/>không web server<br/>không master PIN"]
        C2["Cloud AWS<br/>Caddy → Core → AI, MySQL"]
        B2["Trình duyệt<br/>admin / kiosk chỉ xem"]
        E2 -- "HTTPS + token<br/>/api/device/*" --> C2
        C2 <-- "wss + token<br/>command có ID/TTL/ACK" --> E2
        B2 -- "HTTPS<br/>phiên admin + CSRF" --> C2
    end

    BEFORE ~~~ AFTER
```

## 3. Triển khai trên AWS

```mermaid
flowchart LR
    NET(("Internet"))
    DNS["DuckDNS<br/>smartlock-hung.duckdns.org<br/>→ Elastic IP 54.206.197.197"]

    subgraph VM["EC2 c7i-flex.large · Ubuntu 24.04 · Sydney"]
        subgraph PUB["Mạng 'edge' (mở 80/443)"]
            WEB["web: Caddy 2.8<br/>Let's Encrypt (không email)<br/>/srv = React build"]
        end
        subgraph PRIV["Mạng 'backend' (internal)"]
            CORE["core: Spring Boot :8080"]
            AI["ai: FastAPI :8000<br/>InsightFace + MiniFASNet"]
            DB[("db: mysql 8.4")]
        end
        V1[("volume db-data")]
        V2[("volume alerts")]
        V3[("/opt/smartlock/insightface<br/>model, chỉ đọc")]
    end

    NET --> DNS --> WEB
    WEB -- "/api/*, /ws/*" --> CORE
    CORE -- "http://ai:8000/api/" --> AI
    CORE --> DB
    AI --> DB
    DB --- V1
    CORE --- V2
    AI --- V3
```

Bí mật (`.env`: mật khẩu DB, hash token thiết bị, admin ban đầu) chỉ nằm trên VM với quyền `600`, không có trong Git.

## 4. Thành phần bên trong Core

```mermaid
flowchart TB
    subgraph SEC["Bảo mật (SecurityConfig – 2 chuỗi)"]
        DAF["DeviceAuthFilter<br/>Bearer token → SHA-256"]
        ADM["Phiên admin<br/>+ CSRF + timeout"]
    end

    subgraph IN["Đầu vào"]
        DC["DeviceController<br/>/api/device/face · pin · alerts · state"]
        WS["DeviceSocketHandler<br/>/ws/device"]
        DOOR["DoorController<br/>/api/admin/door* · /api/lockdown/* · /api/door-status · /api/kiosk/live"]
        API["ApiController / AuthController<br/>đăng ký, hồ sơ, đăng nhập"]
    end

    subgraph SVC["Dịch vụ"]
        GUARD["DeviceRequestGuard<br/>X-Request-Id 1 lần · ±120 s"]
        ACC["DoorAccessService<br/>quyết định mở"]
        FG["FaceGateway<br/>gọi AI, kiểm tra phản hồi"]
        CMD["CommandService<br/>tạo/gửi lệnh, ACK, quét hết hạn"]
        DS["DeviceStateService<br/>online, lockdown mong muốn/đã áp dụng"]
        CH["DeviceChannel<br/>phiên WebSocket mỗi thiết bị"]
        MH["DeviceMessageHandler<br/>ack · event · status"]
        LP["LivePreview<br/>khung hình xem trước (RAM)"]
        LC["LastCaptureStore<br/>ảnh quét gần nhất (RAM)"]
    end

    DAF --> DC
    DAF --> WS
    ADM --> DOOR
    ADM --> API
    DC --> GUARD
    DC --> ACC
    DC --> FG
    DC --> LC
    ACC --> CMD
    DOOR --> ACC
    DOOR --> CMD
    DOOR --> DS
    DOOR --> LP
    DOOR --> LC
    WS --> CH
    WS --> MH
    WS --> LP
    MH --> CMD
    MH --> DS
    CMD --> CH
    LP --> CH
```

## 5. Luồng mở cửa bằng khuôn mặt

Chỉ ảnh từ ESP32 đã xác thực mới tạo được lệnh mở (D02). AI không có quyền mở cửa: Core luôn kiểm tra lại hồ sơ.

```mermaid
sequenceDiagram
    autonumber
    actor U as Người trước cửa
    participant E as ESP32
    participant C as Core (cloud)
    participant A as AI (cloud)
    participant D as MySQL

    U->>E: Đứng trước cửa (PIR = HIGH)
    E->>E: Bật LED, chụp ảnh (xả khung cũ)
    E-)C: WS event PIR_MOTION
    E->>C: POST /api/device/face<br/>Bearer token, X-Request-Id, X-Request-Time, ảnh JPEG
    C->>C: DeviceAuthFilter (token) · RequestGuard (ID 1 lần, ±120 s)<br/>giới hạn 30 lần/5 phút · kiểm tra lockdown
    C->>A: POST /api/verify-face (mạng nội bộ)
    A->>A: Phát hiện mặt → liveness (MiniFASNet) → embedding → so khớp
    A-->>C: FACE_VERIFIED + userId · hoặc NO_FACE / SPOOF_DETECTED / NOT_RECOGNIZED…

    alt Bị từ chối
        C->>D: access_logs (lý do)
        C-->>E: 401/422 + reasonCode
        E->>E: Tắt LED, bíp 2 lần, đếm lần thất bại
    else Được chấp thuận
        C->>D: Khóa hàng user: còn bật? có khuôn mặt?
        C->>D: door_commands PENDING (commandId, seq, hết hạn sau 15 s)
        C-)E: WS cmd UNLOCK (sau khi giao dịch commit) → PUBLISHED
        C-->>E: 202 + commandId (không phải "cửa đã mở")
        E-)C: ACK RECEIVED
        E->>E: Relay/servo mở, hẹn giờ esp_timer khóa lại sau 7 s
        E-)C: ACK EXECUTED
        C->>D: Command EXECUTED, cập nhật access_logs
        Note over E: Hết 7 s → khóa lại → event DOOR_LOCKED
    end
    C->>C: Lưu ảnh + kết quả vào LastCaptureStore (admin xem)
```

## 6. Luồng mở cửa bằng PIN keypad

PIN chỉ nhập trên keypad, không kèm mã hồ sơ (D04). Không còn master PIN.

```mermaid
sequenceDiagram
    autonumber
    actor U as Người dùng
    participant E as ESP32
    participant C as Core
    participant D as MySQL

    U->>E: Nhập 6–10 chữ số rồi bấm phím thăng
    E->>C: POST /api/device/pin {pinCode} + header xác thực
    C->>C: Kiểm tra lockdown · giới hạn 5 lần sai/5 phút theo thiết bị
    C->>D: Quét hồ sơ đang bật, so khớp BCrypt
    alt Khớp đúng 1 hồ sơ
        C->>D: Khóa hàng, kiểm tra lại PIN chưa đổi/thu hồi
        C->>D: Tạo command UNLOCK
        C-)E: WS cmd UNLOCK
        C-->>E: 202 + commandId
        E-)C: ACK RECEIVED → mở khóa → ACK EXECUTED
    else Sai / không hồ sơ nào / khớp nhiều hồ sơ
        C->>D: access_logs FAILED hoặc PIN_AMBIGUOUS (+ audit)
        C-->>E: 401 (hoặc 429 khi vượt giới hạn)
        E->>E: Đếm sai, đủ 3 lần → cảnh báo WRONG_PIN_3_TIMES kèm ảnh
    end
```

## 7. Luồng admin mở cửa từ xa

```mermaid
sequenceDiagram
    autonumber
    actor A as Admin
    participant B as Trình duyệt
    participant C as Core
    participant E as ESP32

    A->>B: Bấm "Mở cửa từ xa" → xác nhận
    B->>C: GET /api/auth/csrf
    B->>C: POST /api/admin/door/open (cookie phiên + CSRF)
    C->>C: Audit REMOTE_OPEN_REQUESTED · tạo đúng 1 command
    C-)E: WS cmd UNLOCK
    alt Thiết bị đang kết nối
        C-->>B: 202 "Đã gửi lệnh, chờ thiết bị xác nhận"
        E-)C: ACK RECEIVED → ACK EXECUTED
        B->>C: GET /api/admin/door (làm mới định kỳ)
        C-->>B: Lệnh: "Thiết bị đã điều khiển khóa"
    else Thiết bị offline
        C-->>B: 503 COMMAND_FAILED "Cửa không mở, kiểm tra kết nối"
    end
    Note over B: Không tự gửi lại: phản hồi bị mất vẫn có thể đã mở cửa
```

## 8. Vòng đời lệnh (command)

```mermaid
stateDiagram-v2
    [*] --> PENDING: Quyết định được lưu (cùng giao dịch)
    PENDING --> PUBLISHED: Đã ghi vào WebSocket thiết bị
    PENDING --> FAILED: Thiết bị offline (SEND_FAILED)<br/>hoặc hết hạn trước khi gửi
    PUBLISHED --> RECEIVED: ACK RECEIVED
    PUBLISHED --> EXECUTED: ACK EXECUTED
    RECEIVED --> EXECUTED: ACK EXECUTED
    PUBLISHED --> REJECTED: ACK REJECTED<br/>(EXPIRED, REPLAY, CLOCK_SKEW…)
    RECEIVED --> REJECTED: ACK REJECTED
    PUBLISHED --> UNKNOWN: Quá hạn + thời gian chờ<br/>không có ACK thực thi
    RECEIVED --> UNKNOWN: Quá hạn không có ACK thực thi
    UNKNOWN --> EXECUTED: ACK EXECUTED đến muộn
    UNKNOWN --> REJECTED: ACK REJECTED đến muộn
    EXECUTED --> [*]
    REJECTED --> [*]
    FAILED --> [*]
```

- Mọi chuyển trạng thái là compare-and-set; lệnh hết hạn **không bao giờ gửi lại**.
- `EXECUTED` = thiết bị đã điều khiển relay/servo, **không** phải cảm biến xác nhận cửa mở.

## 9. Firmware xử lý lệnh và chống phát lại

```mermaid
flowchart TD
    R["Nhận WS text frame (≤ 700 byte)"] --> K{"kind?"}
    K -- "preview" --> P["Đặt cửa sổ xem trước ≤ 20 s<br/>(không chấp hành gì)"]
    K -- "khác cmd" --> X["Bỏ qua"]
    K -- "cmd" --> V{"v=1, deviceId đúng,<br/>commandId, seq, issuedAt, expiresAt?"}
    V -- "thiếu/sai" --> RM["ACK REJECTED MALFORMED"]
    V -- "hợp lệ" --> DUP{"commandId == lastCmdId?"}
    DUP -- "có" --> RA["Gửi lại ACK cũ<br/>không chấp hành lại"]
    DUP -- "không" --> SEQ{"seq > lastSeq?"}
    SEQ -- "không" --> RR["ACK REJECTED REPLAY"]
    SEQ -- "có" --> NVS["Ghi lastSeq, lastCmd vào NVS<br/>TRƯỚC khi chấp hành"]
    NVS --> RCV["ACK RECEIVED"]
    RCV --> T{"Giờ NTP đã đồng bộ?<br/>chưa hết hạn? issuedAt hợp lý?"}
    T -- "không" --> RT["ACK REJECTED<br/>TIME_UNSYNCED / EXPIRED / CLOCK_SKEW"]
    T -- "có" --> TY{"type"}
    TY -- "UNLOCK" --> UL["Mở relay/servo<br/>esp_timer khóa lại sau unlockMs (1–15 s)"]
    UL --> EX["ACK EXECUTED"]
    TY -- "LOCKDOWN_ON/OFF" --> LD["Lưu lockdown vào NVS<br/>ON: khóa ngay"]
    LD --> EX2["ACK EXECUTED + gửi status"]
    TY -- "khác" --> RU["ACK REJECTED UNKNOWN_TYPE"]
```

## 10. Luồng lockdown khẩn cấp và đồng bộ lại

```mermaid
sequenceDiagram
    autonumber
    actor A as Admin
    participant C as Core
    participant D as MySQL
    participant E as ESP32

    A->>C: POST /api/lockdown/toggle {enabled:true}
    C->>D: desiredLockdown = true (lưu trước)
    C-)E: WS cmd LOCKDOWN_ON (hết hạn 60 s)
    C-->>A: "Đã lưu, chờ thiết bị xác nhận" (lockdownApplied = false)
    E->>E: Lưu NVS, khóa ngay, bíp 5 lần
    E-)C: ACK EXECUTED + status {lockdown:true}
    C->>D: reportedLockdown = true → lockdownApplied = true

    Note over E,C: Mất kết nối rồi kết nối lại
    E-)C: Bắt tay wss (token) → online
    C->>D: reportedLockdown = chưa biết
    E-)C: status {lockdown: …}
    alt Thiết bị lệch với desiredLockdown
        C-)E: WS cmd LOCKDOWN_ON/OFF mới (seq mới)
    end
    Note over C: Mọi yêu cầu mở bằng mặt/PIN/từ xa bị từ chối khi desiredLockdown = true
```

## 11. Luồng camera trực tiếp

Khung hình xem trước **chỉ để hiển thị**: không gửi tới AI, không tạo lệnh. Kiosk xem công khai theo D11; trang admin cần đăng nhập.

```mermaid
sequenceDiagram
    autonumber
    participant B as Trình duyệt (kiosk / admin)
    participant C as Core – LivePreview
    participant E as ESP32

    loop Mỗi 350 ms khi tab đang hiển thị
        B->>C: GET /api/kiosk/live (hoặc /api/admin/door/live)
        opt Lần yêu cầu trước đã quá 5 s
            C-)E: WS {"kind":"preview","ms":15000}
        end
        alt Có khung hình mới trong 3 s
            C-->>B: 200 image/jpeg
        else Chưa có
            C-->>B: 204 "Đang chờ khung hình…"
        end
    end

    loop Trong cửa sổ xem trước (≤ 20 s, ~3 khung/s)
        E->>E: Lấy khung JPEG (bỏ qua nếu camera đang chụp xác thực)
        E-)C: WS binary frame (≤ 100 KB)
        C->>C: Chỉ nhận JPEG (FFD8) từ phiên đã xác thực, giữ khung mới nhất trong RAM
    end
    Note over B,E: Đóng tab → không còn gia hạn → ESP32 tự dừng sau tối đa 20 s
```

## 12. Vòng lặp firmware: PIR, cảnh báo kẻ lạ

```mermaid
flowchart TD
    L(["loop()"]) --> RL{"relockPending?"}
    RL -- "có" --> DL["Bíp, event DOOR_LOCKED, status<br/>nghỉ PIR sau khi mở"]
    RL -- "không" --> NW
    DL --> NW{"Wi-Fi kết nối?"}
    NW -- "không" --> WR["Mỗi 8 s đổi sang mạng kế tiếp"]
    NW -- "có" --> WS["NTP nếu cần · bắt đầu wss<br/>ws.loop() · heartbeat status<br/>gửi khung xem trước nếu đang mở cửa sổ"]
    WR --> AW
    WS --> AW{"Đang chờ command quá hạn?"}
    AW -- "có" --> AWX["Giữ khóa, bíp 3 lần"]
    AW -- "không" --> KP
    AWX --> KP["Đọc keypad → xử lý PIN / phím D"]
    KP --> PIRC{"PIR HIGH, đã nhả trước đó,<br/>hết thời gian nghỉ, cửa đang khóa?"}
    PIRC -- "không" --> L
    PIRC -- "có" --> LDK{"Lockdown?"}
    LDK -- "có" --> LDB["Bỏ qua nhận diện, bíp, nghỉ 10 s"] --> L
    LDK -- "không" --> FACE["Chụp + POST /api/device/face"]
    FACE --> DEC{"Kết quả"}
    DEC -- "Granted" --> WAIT["Chờ WS cmd UNLOCK"] --> L
    DEC -- "Unavailable / Lockdown / Throttled" --> CD["Tắt LED, nghỉ PIR"] --> L
    DEC -- "Denied" --> CNT["Đếm thất bại liên tiếp"]
    CNT --> F5{"≥ 5 lần?"}
    F5 -- "không" --> CD
    F5 -- "có" --> ALR["POST /api/device/alerts PIR_INTRUDER_5_TIMES + ảnh<br/>event FACE_FAIL_STREAK · nghỉ 30 s"] --> L
```

## 13. Kết nối thiết bị: Wi-Fi, NTP, WebSocket

```mermaid
stateDiagram-v2
    [*] --> WifiConnecting: Khởi động (setup)
    WifiConnecting --> WifiConnecting: Thử mạng chính 10 s → mạng dự phòng 10 s
    WifiConnecting --> NtpSync: WIFI OK
    NtpSync --> NtpSync: Thử lại mỗi 30 s
    NtpSync --> WsConnecting: Giờ hợp lệ + có CA + có token
    WsConnecting --> Online: Bắt tay wss thành công<br/>(TLS kiểm chứng ISRG Root X1/X2,<br/>Bearer token, không Origin)
    WsConnecting --> WsConnecting: Thất bại → thử lại sau 5 s
    Online --> WsConnecting: Mất ping 2 lần / server đóng
    Online --> WifiConnecting: Mất Wi-Fi
    WifiConnecting --> Online: Có lại Wi-Fi, thư viện tự kết nối lại
    note right of Online
        Gửi status + BOOT khi kết nối
        Heartbeat status định kỳ
        Core: online = true
    end note
```

## 14. Đăng ký khuôn mặt

Webcam laptop chỉ dùng ở bước này (admin đã đăng nhập), không bao giờ là nguồn ảnh mở cửa.

```mermaid
sequenceDiagram
    autonumber
    actor A as Admin
    participant B as Trình duyệt (webcam)
    participant C as Core
    participant AI as AI
    participant D as MySQL

    A->>B: Nhập mã hồ sơ, họ tên, PIN rồi chụp ảnh webcam
    B->>C: POST /api/register (phiên admin + CSRF, multipart)
    C->>C: Kiểm tra định dạng, PIN 6–10 số, ảnh JPEG/PNG ≤ 5 MB
    C->>AI: POST /api/extract-embedding
    AI-->>C: embedding 512 chiều (hoặc NO_FACE / MULTIPLE_FACES / LOW_QUALITY)
    C->>C: Kiểm tra embedding hợp lệ (đủ 512 số, khác 0)
    C->>D: Lưu user (PIN băm BCrypt, embedding)
    C-->>B: 201 Đăng ký thành công
```
