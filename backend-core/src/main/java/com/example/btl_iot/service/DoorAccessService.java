package com.example.btl_iot.service;

import com.example.btl_iot.entity.*;
import com.example.btl_iot.repository.*;
import com.example.btl_iot.mqtt.MqttPublisher;
import com.example.btl_iot.security.*;
import java.time.Duration;
import java.util.Locale;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DoorAccessService {
    public record Result(int code, String status, String message, String reasonCode, String requestId) {
        public Result(int code, String status, String message) { this(code, status, message, null, null); }
    }
    private final UserRepository users;
    private final AccessLogRepository logs;
    private final SecurityAuditRepository audits;
    private final MqttPublisher mqtt;
    private final PasswordEncoder encoder;
    private final AttemptLimiter limiter;
    private final String dummyHash;
    public DoorAccessService(UserRepository users, AccessLogRepository logs, SecurityAuditRepository audits,
                             MqttPublisher mqtt, PasswordEncoder encoder, AttemptLimiter limiter) {
        this.users = users; this.logs = logs; this.audits = audits; this.mqtt = mqtt;
        this.encoder = encoder; this.limiter = limiter;
        dummyHash = encoder.encode("not-a-valid-numeric-pin");
    }
    @Transactional
    public Result pin(String username, String pin, String ip) {
        InputRules.username(username); ProfileRules.pin(pin);
        limiter.consume("pin:ip:" + ip, 20, Duration.ofMinutes(5));
        String key = "pin:user:" + username.toLowerCase(Locale.ROOT);
        // Acquire the row before checking the secret. Revocation/reset uses the same row lock.
        User user = users.findLockedByUsername(username).orElse(null);
        int attempt = limiter.consume(key, 3, Duration.ofMinutes(5));
        String hash = user == null || user.getPinHash() == null ? dummyHash : user.getPinHash();
        boolean matches = encoder.matches(pin, hash);
        if (!matches || user == null || !user.isEnabled()) {
            if (attempt == 3) audits.save(new SecurityAudit(username, "PIN_THROTTLED", user == null ? null : user.getId().toString()));
            return denied(user, "PIN", "FAILED");
        }
        limiter.clear(key);
        return open(user, "PIN");
    }
    @Transactional
    public Result face(FaceGateway.FaceMatch match) {
        if (!"FACE_VERIFIED".equals(match.reasonCode()) || match.spoof()) {
            String reason = match.spoof() ? "SPOOF_DETECTED" : match.reasonCode();
            record(null, "FACE", reason);
            return new Result(FaceGateway.statusFor(reason), "error", FaceGateway.messageFor(reason), reason, match.requestId());
        }
        User user = match.userId() == null ? null : users.findLockedById(match.userId()).orElse(null);
        if (user == null) {
            record(null, "FACE", "INVALID_AI_RESPONSE");
            return new Result(502, "error", FaceGateway.messageFor("INVALID_AI_RESPONSE"), "INVALID_AI_RESPONSE", match.requestId());
        }
        if (!user.isEnabled() || user.getFaceEmbedding() == null || user.getFaceEmbedding().isBlank()) {
            Result denied = denied(user, "FACE", "PROFILE_NOT_ALLOWED");
            return new Result(denied.code(), denied.status(), denied.message(), "PROFILE_NOT_ALLOWED", match.requestId());
        }
        Result result = open(user, "FACE");
        return new Result(result.code(), result.status(), result.message(),
                result.code() == 200 ? "FACE_VERIFIED" : "COMMAND_FAILED", match.requestId());
    }
    private Result denied(User user, String method, String status) {
        record(user, method, status);
        return new Result(401, "error", "Không thể xác thực hoặc hồ sơ không được phép vào.");
    }
    private Result open(User user, String method) {
        // Fail before publishing if the audit row cannot be written.
        AccessLog log = record(user, method, "COMMAND_PENDING");
        boolean sent = mqtt.sendOpenDoorCommand();
        log.setStatus(sent ? "SUCCESS" : "COMMAND_FAILED");
        logs.save(log);
        return sent ? new Result(200, "success", "Đã xác thực và gửi lệnh mở cửa.")
                : new Result(503, "error", "Đã xác thực nhưng chưa xác nhận được việc gửi lệnh. Kiểm tra trạng thái cửa và kết nối thiết bị.");
    }
    private AccessLog record(User user, String method, String status) {
        var log = new AccessLog(); log.setUser(user); log.setAccessMethod(method); log.setStatus(status);
        return logs.saveAndFlush(log);
    }
}
