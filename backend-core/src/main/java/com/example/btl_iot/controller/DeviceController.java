package com.example.btl_iot.controller;

import com.example.btl_iot.entity.SecurityAlert;
import com.example.btl_iot.entity.SecurityAudit;
import com.example.btl_iot.repository.SecurityAlertRepository;
import com.example.btl_iot.repository.SecurityAuditRepository;
import com.example.btl_iot.security.AttemptLimiter;
import com.example.btl_iot.security.DevicePrincipal;
import com.example.btl_iot.security.DeviceRequestGuard;
import com.example.btl_iot.service.*;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

// The only routes that can create an unlock from a face or PIN. Device identity comes from DeviceAuthFilter.
@RestController
@RequestMapping("/api/device")
public class DeviceController {
    public record PinAttempt(String pinCode) {}
    private static final Set<String> ALERT_TYPES = Set.of("WRONG_PIN_3_TIMES", "PIR_INTRUDER_5_TIMES", "SECURITY_ALERT");
    private final DoorAccessService access;
    private final FaceGateway faces;
    private final DeviceRequestGuard guard;
    private final AttemptLimiter limiter;
    private final DeviceStateService devices;
    private final AlertImageStore images;
    private final LastCaptureStore captures;
    private final KioskFeed kiosk;
    private final SecurityAlertRepository alerts;
    private final SecurityAuditRepository audits;
    private final Clock clock;
    public DeviceController(DoorAccessService access, FaceGateway faces, DeviceRequestGuard guard, AttemptLimiter limiter,
                            DeviceStateService devices, AlertImageStore images, LastCaptureStore captures, KioskFeed kiosk,
                            SecurityAlertRepository alerts,
                            SecurityAuditRepository audits, Clock clock) {
        this.access = access; this.faces = faces; this.guard = guard; this.limiter = limiter; this.devices = devices;
        this.images = images; this.captures = captures; this.kiosk = kiosk; this.alerts = alerts; this.audits = audits; this.clock = clock;
    }

    @PostMapping("/face")
    public ResponseEntity<?> face(@AuthenticationPrincipal DevicePrincipal device, @RequestParam("file") MultipartFile file,
                                  @RequestHeader(value = "X-Request-Id", required = false) String requestId,
                                  @RequestHeader(value = "X-Request-Time", required = false) String requestTime) {
        guard.claim(device.deviceId(), requestId, requestTime);
        limiter.consume("face:device:" + device.deviceId(), 30, Duration.ofMinutes(5));
        access.ensureNotLocked(device.deviceId());
        // AI has no authority to grant access: Core checks the current profile under a row lock.
        var decision = access.dispatch(access.face(device.deviceId(), faces.identify(file), requestId));
        captures.save(device.deviceId(), file, decision.reasonCode(), decision.message(), decision.commandId(),
                decision.userName());
        kiosk.attempt(device.deviceId(), "FACE", decision.reasonCode(), decision.userName(), decision.commandId());
        return AccessResponses.of(decision, requestId);
    }

    @PostMapping("/pin")
    public ResponseEntity<?> pin(@AuthenticationPrincipal DevicePrincipal device, @RequestBody PinAttempt body,
                                 @RequestHeader(value = "X-Request-Id", required = false) String requestId,
                                 @RequestHeader(value = "X-Request-Time", required = false) String requestTime) {
        guard.claim(device.deviceId(), requestId, requestTime);
        access.ensureNotLocked(device.deviceId());
        var decision = access.dispatch(access.pin(device.deviceId(), body == null ? null : body.pinCode(), requestId));
        kiosk.attempt(device.deviceId(), "PIN", decision.reasonCode(), decision.userName(), decision.commandId());
        return AccessResponses.of(decision, requestId);
    }

    @PostMapping("/alerts")
    public ResponseEntity<?> alert(@AuthenticationPrincipal DevicePrincipal device,
                                   @RequestParam(value = "file", required = false) MultipartFile file,
                                   @RequestParam(value = "alertType", defaultValue = "SECURITY_ALERT") String alertType,
                                   @RequestParam(value = "description", defaultValue = "") String description,
                                   @RequestHeader(value = "X-Request-Id", required = false) String requestId,
                                   @RequestHeader(value = "X-Request-Time", required = false) String requestTime) throws IOException {
        guard.claim(device.deviceId(), requestId, requestTime);
        limiter.consume("alert:device:" + device.deviceId(), 10, Duration.ofMinutes(5));
        if (!ALERT_TYPES.contains(alertType))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Loại cảnh báo không hợp lệ.");
        String text = description.replaceAll("\\p{Cntrl}", " ").strip();
        if (text.length() > 200) text = text.substring(0, 200);
        String imageUrl = file == null || file.isEmpty() ? null : images.save(file);
        var saved = alerts.save(new SecurityAlert(alertType, text.isEmpty() ? "Cảnh báo an ninh từ thiết bị" : text, imageUrl));
        audits.save(new SecurityAudit(device.deviceId(), alertType, "Alert #" + saved.getId()));
        return ResponseEntity.status(201).body(Map.of("status", "success", "alertId", saved.getId()));
    }

    // Boot-time sync: desired lockdown and server time for a clock sanity check.
    @GetMapping("/state")
    public Map<String, Object> state(@AuthenticationPrincipal DevicePrincipal device) {
        return Map.of("deviceId", device.deviceId(), "lockdown", devices.isLockdown(device.deviceId()),
                "serverTime", clock.instant().getEpochSecond());
    }
}
