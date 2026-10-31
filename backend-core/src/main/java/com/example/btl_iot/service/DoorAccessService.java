package com.example.btl_iot.service;

import com.example.btl_iot.entity.*;
import com.example.btl_iot.repository.*;
import com.example.btl_iot.security.*;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Decides access and creates at most one command per request. Callers dispatch the command after commit.
@Service
public class DoorAccessService {
    public record Result(int code, String status, String message, String reasonCode, String aiRequestId,
                         String commandId, String commandStatus, String userName) {
        public Result(int code, String status, String message) { this(code, status, message, null, null, null, null, null); }
        public Result(int code, String status, String message, String reasonCode, String aiRequestId,
                      String commandId, String commandStatus) {
            this(code, status, message, reasonCode, aiRequestId, commandId, commandStatus, null);
        }
    }
    static final int PIN_DEVICE_LIMIT = 5;
    static final Duration PIN_WINDOW = Duration.ofMinutes(5);
    private static final String GENERIC_DENIAL = "Không thể xác thực hoặc hồ sơ không được phép vào.";
    private final UserRepository users;
    private final AccessLogRepository logs;
    private final SecurityAuditRepository audits;
    private final CommandService commands;
    private final PasswordEncoder encoder;
    private final AttemptLimiter limiter;
    private final DeviceStateService devices;
    public DoorAccessService(UserRepository users, AccessLogRepository logs, SecurityAuditRepository audits,
                             CommandService commands, PasswordEncoder encoder, AttemptLimiter limiter,
                             DeviceStateService devices) {
        this.users = users; this.logs = logs; this.audits = audits; this.commands = commands;
        this.encoder = encoder; this.limiter = limiter; this.devices = devices;
    }

    // Cheap early exit before calling AI. CommandService re-checks under the device row lock.
    public void ensureNotLocked(String deviceId) {
        if (devices.isLockdown(deviceId)) throw new CommandService.LockdownActive();
    }

    // Only reachable from an authenticated device (see DeviceController); browsers have no face-unlock path.
    @Transactional
    public Result face(String deviceId, FaceGateway.FaceMatch match, String requestId) {
        if (!"FACE_VERIFIED".equals(match.reasonCode()) || match.spoof()) {
            String reason = match.spoof() ? "SPOOF_DETECTED" : match.reasonCode();
            record(null, "FACE", reason, deviceId, requestId, null);
            return new Result(FaceGateway.statusFor(reason), "error", FaceGateway.messageFor(reason), reason,
                    match.requestId(), null, null);
        }
        User user = match.userId() == null ? null : users.findLockedById(match.userId()).orElse(null);
        if (user == null) {
            record(null, "FACE", "INVALID_AI_RESPONSE", deviceId, requestId, null);
            return new Result(502, "error", FaceGateway.messageFor("INVALID_AI_RESPONSE"), "INVALID_AI_RESPONSE",
                    match.requestId(), null, null);
        }
        if (!user.isEnabled() || user.getFaceEmbedding() == null || user.getFaceEmbedding().isBlank()) {
            record(user, "FACE", "PROFILE_NOT_ALLOWED", deviceId, requestId, null);
            return new Result(401, "error", GENERIC_DENIAL, "PROFILE_NOT_ALLOWED", match.requestId(), null, null);
        }
        return grant(user, "FACE", "FACE_VERIFIED", deviceId, requestId, null, match.requestId());
    }

    // D04: keypad PIN without username. Failures are limited per device; a PIN shared by two profiles is refused.
    @Transactional
    public Result pin(String deviceId, String pin, String requestId) {
        String key = "pin:device:" + deviceId;
        limiter.check(key, PIN_DEVICE_LIMIT);
        boolean wellFormed = pin != null && pin.matches("[0-9]{6,10}");
        List<User> matches = !wellFormed ? List.of() : users.findAll().stream()
                .filter(User::isEnabled).filter(u -> u.getPinHash() != null)
                .filter(u -> encoder.matches(pin, u.getPinHash())).toList();
        User user = matches.size() == 1 ? users.findLockedById(matches.get(0).getId()).orElse(null) : null;
        // Re-check under the row lock: revocation or PIN reset may have committed after the scan.
        if (user == null || !user.isEnabled() || !Objects.equals(user.getPinHash(), matches.get(0).getPinHash())) {
            int attempt = limiter.consume(key, PIN_DEVICE_LIMIT, PIN_WINDOW);
            if (matches.size() > 1) audits.save(new SecurityAudit(deviceId, "PIN_AMBIGUOUS", null));
            if (attempt == PIN_DEVICE_LIMIT) audits.save(new SecurityAudit(deviceId, "PIN_THROTTLED", null));
            record(null, "PIN", matches.size() > 1 ? "PIN_AMBIGUOUS" : "FAILED", deviceId, requestId, null);
            return new Result(401, "error", GENERIC_DENIAL, "PIN_REJECTED", null, null, null);
        }
        limiter.clear(key);
        return grant(user, "PIN", "PIN_VERIFIED", deviceId, requestId, null, null);
    }

    @Transactional
    public Result remoteOpen(String admin) {
        audits.save(new SecurityAudit(admin, "REMOTE_OPEN_REQUESTED", devices.defaultDeviceId()));
        return grant(null, "REMOTE_ADMIN", "REMOTE_OPEN", devices.defaultDeviceId(), null, admin, null);
    }

    private Result grant(User user, String method, String reason, String deviceId, String requestId,
                         String actor, String aiRequestId) {
        DoorCommand command = commands.createUnlock(deviceId, method, user == null ? null : user.getId(), requestId, actor);
        record(user, method, "COMMAND_PENDING", deviceId, requestId, command.getId());
        return new Result(202, "success", "Đã xác thực; đang gửi lệnh mở tới thiết bị.", reason, aiRequestId,
                command.getId(), command.getStatus().name(), user == null ? null : user.getFullName());
    }

    // Publish only after the decision transaction committed. Never reports the door as opened.
    public Result dispatch(Result decision) {
        if (decision.commandId() == null) return decision;
        DoorCommand command = commands.dispatch(decision.commandId());
        if (command.getStatus() == DoorCommand.Status.FAILED)
            return new Result(503, "error", "Đã xác thực nhưng chưa gửi được lệnh tới thiết bị. Cửa không mở; kiểm tra kết nối.",
                    "COMMAND_FAILED", decision.aiRequestId(), command.getId(), command.getStatus().name(), decision.userName());
        return new Result(202, "success", "Đã xác thực và gửi lệnh mở tới thiết bị. Chờ thiết bị xác nhận.",
                decision.reasonCode(), decision.aiRequestId(), command.getId(), command.getStatus().name(), decision.userName());
    }

    private AccessLog record(User user, String method, String status, String deviceId, String requestId, String commandId) {
        var log = new AccessLog(); log.setUser(user); log.setAccessMethod(method); log.setStatus(status);
        log.setDeviceId(deviceId); log.setRequestId(requestId); log.setCommandId(commandId);
        return logs.saveAndFlush(log);
    }
}
