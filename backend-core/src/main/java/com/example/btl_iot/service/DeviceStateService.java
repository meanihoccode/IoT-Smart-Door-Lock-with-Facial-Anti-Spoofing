package com.example.btl_iot.service;

import com.example.btl_iot.entity.Device;
import com.example.btl_iot.entity.DoorCommand;
import com.example.btl_iot.entity.SecurityAudit;
import com.example.btl_iot.repository.*;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Persistent desired lockdown plus what the device last reported. Offline never counts as applied.
@Service
public class DeviceStateService {
    private final DeviceRepository devices;
    private final DoorCommandRepository commands;
    private final CommandService commandService;
    private final SecurityAuditRepository audits;
    private final Clock clock;
    private final String defaultDeviceId;
    private final Duration staleAfter;

    public DeviceStateService(DeviceRepository devices, DoorCommandRepository commands, CommandService commandService,
                              SecurityAuditRepository audits, Clock clock,
                              @Value("${smartlock.device.id:door-01}") String defaultDeviceId,
                              @Value("${smartlock.device.stale-after:180s}") Duration staleAfter) {
        this.devices = devices; this.commands = commands; this.commandService = commandService;
        this.audits = audits; this.clock = clock; this.defaultDeviceId = defaultDeviceId; this.staleAfter = staleAfter;
    }

    public String defaultDeviceId() { return defaultDeviceId; }

    public boolean isLockdown(String deviceId) {
        return devices.findById(deviceId).map(Device::isDesiredLockdown).orElse(false);
    }

    // Returns the command to dispatch after commit.
    @Transactional
    public DoorCommand requestLockdown(String deviceId, boolean on, String actor) {
        Device device = commandService.lockedDevice(deviceId);
        device.setDesiredLockdown(on);
        device.setReportedLockdown(null);
        devices.save(device);
        audits.save(new SecurityAudit(actor, on ? "EMERGENCY_LOCKDOWN_ENABLED" : "EMERGENCY_LOCKDOWN_DISABLED", deviceId));
        return commandService.createLockdown(device, on, actor);
    }

    // Status topic (retained) and LWT. Returns a lockdown command to dispatch when the device disagrees.
    @Transactional
    public DoorCommand onStatus(String deviceId, boolean online, Boolean reportedLockdown, String firmware) {
        Device device = devices.findLockedById(deviceId).orElse(null);
        if (device == null) return null;
        boolean wasOnline = device.isOnline();
        device.setOnline(online);
        if (online) {
            device.setLastSeenAt(clock.instant());
            if (reportedLockdown != null) device.setReportedLockdown(reportedLockdown);
            if (firmware != null) device.setFirmware(firmware.length() > 32 ? firmware.substring(0, 32) : firmware);
        }
        devices.save(device);
        if (wasOnline != online) audits.save(new SecurityAudit(deviceId, online ? "DEVICE_ONLINE" : "DEVICE_OFFLINE", null));
        if (!online || !device.isEnabled() || reportedLockdown == null || reportedLockdown == device.isDesiredLockdown()) return null;
        // Do not stack resync commands while one is still in flight.
        var last = commands.findFirstByDeviceIdAndTypeOrderByIssuedAtDesc(deviceId,
                device.isDesiredLockdown() ? DoorCommand.Type.LOCKDOWN_ON : DoorCommand.Type.LOCKDOWN_OFF);
        if (last.isPresent() && CommandService.isOpen(last.get().getStatus())
                && last.get().getExpiresAt().isAfter(clock.instant())) return null;
        return commandService.createLockdown(device, device.isDesiredLockdown(), "SYSTEM_RESYNC");
    }

    @Transactional
    public void touch(String deviceId) {
        devices.findById(deviceId).ifPresent(device -> { device.setLastSeenAt(clock.instant()); devices.save(device); });
    }

    public Map<String, Object> status(String deviceId) {
        var body = new LinkedHashMap<String, Object>();
        body.put("deviceId", deviceId);
        Device device = devices.findById(deviceId).orElse(null);
        if (device == null) { body.put("configured", false); body.put("connection", "UNKNOWN"); return body; }
        body.put("configured", true);
        body.put("connection", connection(device));
        body.put("lastSeenAt", device.getLastSeenAt() == null ? null : device.getLastSeenAt().toString());
        body.put("lockdown", device.isDesiredLockdown());
        body.put("lockdownApplied", device.getReportedLockdown() != null
                && device.getReportedLockdown() == device.isDesiredLockdown() && "ONLINE".equals(connection(device)));
        body.put("reportedLockdown", device.getReportedLockdown());
        return body;
    }

    String connection(Device device) {
        if (!device.isOnline()) return "OFFLINE";
        Instant seen = device.getLastSeenAt();
        return seen == null || seen.isBefore(clock.instant().minus(staleAfter)) ? "STALE" : "ONLINE";
    }
}
