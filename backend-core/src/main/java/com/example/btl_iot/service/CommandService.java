package com.example.btl_iot.service;

import com.example.btl_iot.entity.Device;
import com.example.btl_iot.entity.DoorCommand;
import com.example.btl_iot.entity.DoorCommand.Status;
import com.example.btl_iot.entity.DoorCommand.Type;
import com.example.btl_iot.entity.SecurityAudit;
import com.example.btl_iot.channel.DeviceChannel;
import com.example.btl_iot.repository.*;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

// Contract: docs/DEVICE_CONTRACT.md. One command per authorized request, never retained, never retried blindly.
@Service
public class CommandService {
    public static final class LockdownActive extends ResponseStatusException {
        public LockdownActive() { super(HttpStatus.FORBIDDEN, "Cửa đang bị vô hiệu hóa khẩn cấp bởi Quản trị viên."); }
    }
    private static final EnumSet<Status> OPEN = EnumSet.of(Status.PENDING, Status.PUBLISHED, Status.RECEIVED);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final DoorCommandRepository commands;
    private final DeviceRepository devices;
    private final AccessLogRepository logs;
    private final SecurityAuditRepository audits;
    private final DeviceChannel channel;
    private final Clock clock;
    private final Duration unlockTtl, lockdownTtl, ackGrace;
    private final int unlockMs;

    public CommandService(DoorCommandRepository commands, DeviceRepository devices, AccessLogRepository logs,
                          SecurityAuditRepository audits, DeviceChannel channel, Clock clock,
                          @Value("${smartlock.command.unlock-ttl:15s}") Duration unlockTtl,
                          @Value("${smartlock.command.lockdown-ttl:60s}") Duration lockdownTtl,
                          @Value("${smartlock.command.ack-grace:5s}") Duration ackGrace,
                          @Value("${smartlock.command.unlock-ms:7000}") int unlockMs) {
        if (unlockMs < 1000 || unlockMs > 15000) throw new IllegalArgumentException("smartlock.command.unlock-ms must be 1000..15000");
        if (unlockTtl.isNegative() || unlockTtl.isZero() || unlockTtl.compareTo(Duration.ofMinutes(1)) > 0)
            throw new IllegalArgumentException("smartlock.command.unlock-ttl must be within (0, 60s]");
        this.commands = commands; this.devices = devices; this.logs = logs; this.audits = audits;
        this.channel = channel; this.clock = clock; this.unlockTtl = unlockTtl; this.lockdownTtl = lockdownTtl;
        this.ackGrace = ackGrace; this.unlockMs = unlockMs;
    }

    // Runs inside the caller's transaction; the device row lock orders seq allocation and the lockdown check.
    @Transactional
    public DoorCommand createUnlock(String deviceId, String method, Long userId, String requestId, String actor) {
        Device device = lockedDevice(deviceId);
        if (device.isDesiredLockdown()) throw new LockdownActive();
        var command = newCommand(device, Type.UNLOCK, unlockTtl);
        command.setMethod(method); command.setUserId(userId); command.setRequestId(requestId);
        command.setActor(actor); command.setUnlockMs(unlockMs);
        return commands.saveAndFlush(command);
    }

    @Transactional
    public DoorCommand createLockdown(Device lockedDevice, boolean on, String actor) {
        var command = newCommand(lockedDevice, on ? Type.LOCKDOWN_ON : Type.LOCKDOWN_OFF, lockdownTtl);
        command.setMethod("ADMIN"); command.setActor(actor);
        return commands.saveAndFlush(command);
    }

    Device lockedDevice(String deviceId) {
        Device device = devices.findLockedById(deviceId).orElse(null);
        if (device == null || !device.isEnabled())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Thiết bị cửa chưa được cấu hình hoặc đã bị vô hiệu hóa.");
        return device;
    }

    private DoorCommand newCommand(Device device, Type type, Duration ttl) {
        Instant now = clock.instant();
        // Monotonic even after a DB restore: never below wall-clock millis.
        long seq = Math.max(device.getLastSeq() + 1, now.toEpochMilli());
        device.setLastSeq(seq);
        devices.save(device);
        return new DoorCommand(UUID.randomUUID().toString(), device.getId(), seq, type, now, now.plus(ttl));
    }

    // Call only after the creating transaction has committed, so an early ACK always finds the row.
    public DoorCommand dispatch(String commandId) {
        DoorCommand command = commands.findById(commandId).orElseThrow();
        if (command.getStatus() != Status.PENDING) return command;
        Instant now = clock.instant();
        if (!now.isBefore(command.getExpiresAt())) {
            move(command, EnumSet.of(Status.PENDING), Status.FAILED, "EXPIRED_BEFORE_SEND");
        } else {
            // PUBLISHED = handed to the device WebSocket; only the device ACK says more.
            boolean sent = channel.send(command.getDeviceId(), payload(command));
            move(command, EnumSet.of(Status.PENDING), sent ? Status.PUBLISHED : Status.FAILED, sent ? null : "SEND_FAILED");
        }
        return commands.findById(commandId).orElseThrow();
    }

    String payload(DoorCommand command) {
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("v", 1); body.put("kind", "cmd"); body.put("type", command.getType().name()); body.put("deviceId", command.getDeviceId());
        body.put("commandId", command.getId()); body.put("seq", command.getSeq());
        body.put("issuedAt", command.getIssuedAt().getEpochSecond());
        body.put("expiresAt", command.getExpiresAt().getEpochSecond());
        if (command.getUnlockMs() != null) body.put("unlockMs", command.getUnlockMs());
        return JSON.writeValueAsString(body);
    }

    public enum Ack { RECEIVED, EXECUTED, REJECTED }

    // deviceId comes from the authenticated WebSocket session (device token), never from the message body.
    @Transactional
    public Status applyAck(String deviceId, String commandId, Ack ack, String reason) {
        DoorCommand command = commandId == null ? null : commands.findById(commandId).orElse(null);
        if (command == null || !command.getDeviceId().equals(deviceId)) {
            audits.save(new SecurityAudit(deviceId, "ACK_UNKNOWN_COMMAND", truncate(commandId, 100)));
            return null;
        }
        Instant now = clock.instant();
        switch (ack) {
            case RECEIVED -> move(command, EnumSet.of(Status.PENDING, Status.PUBLISHED), Status.RECEIVED, null);
            // UNKNOWN is accepted so a late ACK can still record what the device reports it did.
            case EXECUTED -> {
                if (move(command, EnumSet.of(Status.PENDING, Status.PUBLISHED, Status.RECEIVED, Status.UNKNOWN), Status.EXECUTED, null)
                        && command.getType() != Type.UNLOCK) {
                    devices.findLockedById(deviceId).ifPresent(device -> {
                        device.setReportedLockdown(command.getType() == Type.LOCKDOWN_ON);
                        device.setLastSeenAt(now);
                        devices.save(device);
                    });
                }
            }
            case REJECTED -> move(command, EnumSet.of(Status.PENDING, Status.PUBLISHED, Status.RECEIVED, Status.UNKNOWN),
                    Status.REJECTED, truncate(reason == null ? "UNSPECIFIED" : reason, 40));
        }
        return commands.findById(commandId).map(DoorCommand::getStatus).orElse(null);
    }

    private boolean move(DoorCommand command, EnumSet<Status> from, Status to, String reason) {
        boolean moved = commands.transition(command.getId(), from, to, reason, clock.instant()) == 1;
        if (moved) logs.updateStatusByCommandId(command.getId(), "COMMAND_" + to.name());
        return moved;
    }

    // An open command past its TTL is never re-sent. Without an EXECUTED ACK its outcome is unknown.
    @Scheduled(fixedDelayString = "${smartlock.command.sweep-interval:5s}")
    public void expireStale() {
        Instant now = clock.instant(), cutoff = now.minus(ackGrace);
        if (commands.expire(EnumSet.of(Status.PENDING), Status.FAILED, "NOT_PUBLISHED", cutoff, now) > 0)
            logs.syncCommandStatus(List.of("COMMAND_PENDING"), "COMMAND_FAILED", Status.FAILED);
        if (commands.expire(EnumSet.of(Status.PUBLISHED, Status.RECEIVED), Status.UNKNOWN, "NO_EXECUTION_ACK", cutoff, now) > 0)
            logs.syncCommandStatus(List.of("COMMAND_PUBLISHED", "COMMAND_RECEIVED"), "COMMAND_UNKNOWN", Status.UNKNOWN);
    }

    public List<Map<String, Object>> recent(String deviceId, int limit) {
        return commands.findByDeviceIdOrderByIssuedAtDesc(deviceId, org.springframework.data.domain.PageRequest.of(0, limit))
                .stream().map(CommandService::view).toList();
    }

    public static Map<String, Object> view(DoorCommand c) {
        var item = new java.util.LinkedHashMap<String, Object>();
        item.put("commandId", c.getId()); item.put("type", c.getType().name()); item.put("status", c.getStatus().name());
        item.put("statusReason", c.getStatusReason()); item.put("method", c.getMethod());
        item.put("issuedAt", c.getIssuedAt().toString()); item.put("expiresAt", c.getExpiresAt().toString());
        item.put("updatedAt", c.getUpdatedAt() == null ? null : c.getUpdatedAt().toString());
        return item;
    }

    public static boolean isOpen(Status status) { return OPEN.contains(status); }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        String clean = value.replaceAll("[^A-Za-z0-9_\\-]", "");
        return clean.length() <= max ? clean : clean.substring(0, max);
    }
}
