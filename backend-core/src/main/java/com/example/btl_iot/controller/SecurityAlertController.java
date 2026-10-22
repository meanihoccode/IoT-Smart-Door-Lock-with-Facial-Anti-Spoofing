package com.example.btl_iot.controller;

import com.example.btl_iot.entity.SecurityAlert;
import com.example.btl_iot.entity.SecurityAudit;
import com.example.btl_iot.repository.SecurityAlertRepository;
import com.example.btl_iot.repository.SecurityAuditRepository;
import com.example.btl_iot.service.DoorStateService;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class SecurityAlertController {

    private final SecurityAlertRepository alertRepository;
    private final SecurityAuditRepository auditRepository;
    private final DoorStateService doorStateService;
    private final Path uploadDir = Paths.get("uploads", "alerts").toAbsolutePath().normalize();

    public SecurityAlertController(SecurityAlertRepository alertRepository,
                                   SecurityAuditRepository auditRepository,
                                   DoorStateService doorStateService) {
        this.alertRepository = alertRepository;
        this.auditRepository = auditRepository;
        this.doorStateService = doorStateService;
        try {
            Files.createDirectories(uploadDir);
            System.out.println("[ALERT] Alert photos directory: " + uploadDir);
        } catch (IOException e) {
            System.err.println("Could not create alerts upload directory: " + e.getMessage());
        }
    }

    @PostMapping("/security-alerts")
    public ResponseEntity<?> createAlert(
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "alertType", defaultValue = "SECURITY_ALERT") String alertType,
            @RequestParam(value = "description", defaultValue = "Cảnh báo an ninh từ thiết bị") String description) {

        String imageUrl = null;
        if (file != null && !file.isEmpty()) {
            try {
                Files.createDirectories(uploadDir);
                String filename = UUID.randomUUID().toString() + ".jpg";
                Path targetPath = uploadDir.resolve(filename);
                Files.copy(file.getInputStream(), targetPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                imageUrl = "/api/security-alerts/images/" + filename;
                System.out.println("[ALERT OK] Đã lưu ảnh cảnh báo kẻ lạ (" + file.getSize() + " bytes): " + targetPath);
            } catch (IOException e) {
                System.err.println("Failed to save alert image: " + e.getMessage());
                e.printStackTrace();
            }
        } else {
            System.out.println("[ALERT WARN] Nhận cảnh báo không kèm file ảnh: " + alertType);
        }

        SecurityAlert alert = new SecurityAlert(alertType, description, imageUrl);
        SecurityAlert saved = alertRepository.save(alert);

        auditRepository.save(new SecurityAudit("SYSTEM", alertType, description));

        return ResponseEntity.status(201).body(Map.of(
                "status", "success",
                "message", "Cảnh báo an ninh đã được ghi nhận.",
                "alert", saved
        ));
    }

    @GetMapping("/security-alerts/images/{filename:.+}")
    public ResponseEntity<Resource> getAlertImage(@PathVariable String filename) {
        try {
            Path filePath = uploadDir.resolve(filename).normalize();
            Resource resource = new UrlResource(filePath.toUri());
            if (resource.exists() && resource.isReadable()) {
                return ResponseEntity.ok()
                        .contentType(MediaType.IMAGE_JPEG)
                        .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + resource.getFilename() + "\"")
                        .body(resource);
            }
        } catch (Exception e) {
            System.err.println("Error reading alert image: " + e.getMessage());
        }
        return ResponseEntity.notFound().build();
    }

    @GetMapping("/security-alerts")
    public ResponseEntity<List<SecurityAlert>> getAllAlerts() {
        return ResponseEntity.ok(alertRepository.findAllByOrderByCreatedAtDesc());
    }

    @PostMapping("/security-alerts/{id}/resolve")
    public ResponseEntity<?> resolveAlert(@PathVariable Long id, Authentication auth) {
        return alertRepository.findById(id).map(alert -> {
            alert.setResolved(true);
            alertRepository.save(alert);
            String actor = auth != null ? auth.getName() : "ADMIN";
            auditRepository.save(new SecurityAudit(actor, "RESOLVE_SECURITY_ALERT", "Alert #" + id));
            return ResponseEntity.ok(Map.of("status", "success", "message", "Đã đánh dấu xử lý cảnh báo."));
        }).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/lockdown/status")
    public ResponseEntity<?> getLockdownStatus() {
        return ResponseEntity.ok(Map.of("lockdown", doorStateService.isEmergencyLockdown()));
    }

    @PostMapping("/lockdown/toggle")
    public ResponseEntity<?> toggleLockdown(@RequestBody(required = false) Map<String, Object> body,
                                            Authentication auth) {
        boolean newState;
        if (body != null && body.containsKey("enabled")) {
            newState = Boolean.TRUE.equals(body.get("enabled"));
        } else {
            newState = !doorStateService.isEmergencyLockdown();
        }

        doorStateService.setEmergencyLockdown(newState);
        String actor = auth != null ? auth.getName() : "ADMIN";
        auditRepository.save(new SecurityAudit(actor,
                newState ? "EMERGENCY_LOCKDOWN_ENABLED" : "EMERGENCY_LOCKDOWN_DISABLED",
                "Khóa cửa toàn diện"));

        return ResponseEntity.ok(Map.of(
                "status", "success",
                "lockdown", newState,
                "message", newState ? "Đã kích hoạt chế độ vô hiệu hóa mở cửa (Khóa khẩn cấp)!"
                                    : "Đã tắt chế độ vô hiệu hóa mở cửa. Hệ thống hoạt động bình thường."
        ));
    }

    @GetMapping("/door-status")
    public ResponseEntity<?> getDoorStatus() {
        return ResponseEntity.ok(doorStateService.getDoorStatus());
    }
}
