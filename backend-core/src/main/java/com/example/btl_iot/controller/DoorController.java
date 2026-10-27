package com.example.btl_iot.controller;

import com.example.btl_iot.service.*;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class DoorController {
    private final DoorAccessService access;
    private final DeviceStateService devices;
    private final CommandService commands;
    private final LastCaptureStore captures;
    private final LivePreview preview;
    public DoorController(DoorAccessService access, DeviceStateService devices, CommandService commands,
                          LastCaptureStore captures, LivePreview preview) {
        this.access = access; this.devices = devices; this.commands = commands; this.captures = captures;
        this.preview = preview;
    }

    // Anonymous kiosk view: no names, no images, no credentials.
    @GetMapping("/door-status")
    public Map<String, Object> doorStatus() {
        var status = devices.status(devices.defaultDeviceId());
        var body = new LinkedHashMap<String, Object>();
        body.put("connection", status.get("connection"));
        body.put("lockdown", status.getOrDefault("lockdown", false));
        body.put("lockdownApplied", status.getOrDefault("lockdownApplied", false));
        var recent = commands.recent(devices.defaultDeviceId(), 1);
        if (!recent.isEmpty()) {
            var last = recent.get(0);
            body.put("lastCommand", Map.of("type", last.get("type"), "status", last.get("status"),
                    "method", String.valueOf(last.get("method")), "issuedAt", last.get("issuedAt")));
        }
        var scan = captures.latest(devices.defaultDeviceId());
        if (scan != null) body.put("lastScan", Map.of("reasonCode", String.valueOf(scan.reasonCode()),
                "message", String.valueOf(scan.message()), "capturedAt", scan.capturedAt().toString()));
        return body;
    }

    @GetMapping("/admin/door")
    public Map<String, Object> adminDoor() {
        var body = new LinkedHashMap<String, Object>(devices.status(devices.defaultDeviceId()));
        body.put("commands", commands.recent(devices.defaultDeviceId(), 10));
        return body;
    }

    // Latest door-camera photo and decision (admin only, in memory). The image URL carries seq to bust caches.
    @GetMapping("/admin/door/last-capture")
    public ResponseEntity<?> lastCapture() {
        var scan = captures.latest(devices.defaultDeviceId());
        if (scan == null) return ResponseEntity.noContent().build();
        var body = new LinkedHashMap<String, Object>();
        body.put("seq", scan.seq()); body.put("capturedAt", scan.capturedAt().toString());
        body.put("reasonCode", scan.reasonCode()); body.put("message", scan.message());
        body.put("commandId", scan.commandId());
        body.put("imageUrl", "/api/admin/door/last-capture/image?seq=" + scan.seq());
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(body);
    }

    @GetMapping("/admin/door/last-capture/image")
    public ResponseEntity<byte[]> lastCaptureImage() {
        var scan = captures.latest(devices.defaultDeviceId());
        if (scan == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .contentType(MediaType.parseMediaType(scan.contentType())).body(scan.image());
    }

    // Live door-camera view. 204 while the device has not sent a fresh frame yet.
    // Also public on the kiosk by the user's choice (D11): anyone with the kiosk URL sees the door camera.
    @GetMapping({"/admin/door/live", "/kiosk/live"})
    public ResponseEntity<byte[]> live() {
        var frame = preview.latest(devices.defaultDeviceId());
        if (frame == null) return ResponseEntity.noContent().header("Cache-Control", "no-store").build();
        return ResponseEntity.ok().header("Cache-Control", "no-store").contentType(MediaType.IMAGE_JPEG).body(frame.jpeg());
    }

    @PostMapping("/admin/door/open")
    public ResponseEntity<?> remoteOpen(Authentication auth) {
        return AccessResponses.of(access.dispatch(access.remoteOpen(auth.getName())), null);
    }

    @GetMapping("/lockdown/status")
    public Map<String, Object> lockdownStatus() { return devices.status(devices.defaultDeviceId()); }

    // Desired state is persisted first; "applied" only becomes true after the device's EXECUTED ACK.
    @PostMapping("/lockdown/toggle")
    public ResponseEntity<?> toggleLockdown(@RequestBody(required = false) Map<String, Object> body, Authentication auth) {
        String deviceId = devices.defaultDeviceId();
        boolean on = body != null && body.containsKey("enabled") ? Boolean.TRUE.equals(body.get("enabled")) : !devices.isLockdown(deviceId);
        var command = commands.dispatch(devices.requestLockdown(deviceId, on, auth.getName()).getId());
        var response = new LinkedHashMap<String, Object>();
        response.put("status", "success"); response.put("lockdown", on); response.put("lockdownApplied", false);
        response.put("commandId", command.getId()); response.put("commandStatus", command.getStatus().name());
        response.put("message", (on ? "Đã lưu chế độ khóa khẩn cấp." : "Đã lưu lệnh tắt khóa khẩn cấp.")
                + (command.getStatus() == com.example.btl_iot.entity.DoorCommand.Status.FAILED
                    ? " Chưa gửi được tới thiết bị; sẽ đồng bộ lại khi thiết bị kết nối."
                    : " Đang chờ thiết bị xác nhận."));
        return ResponseEntity.ok(response);
    }
}
