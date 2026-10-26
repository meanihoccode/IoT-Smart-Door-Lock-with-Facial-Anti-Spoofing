package com.example.btl_iot.controller;

import com.example.btl_iot.entity.SecurityAlert;
import com.example.btl_iot.entity.SecurityAudit;
import com.example.btl_iot.repository.SecurityAlertRepository;
import com.example.btl_iot.repository.SecurityAuditRepository;
import com.example.btl_iot.service.AlertImageStore;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

// Admin-only (SecurityConfig). Devices submit alerts through /api/device/alerts.
@RestController
@RequestMapping("/api")
public class SecurityAlertController {

    private final SecurityAlertRepository alertRepository;
    private final SecurityAuditRepository auditRepository;
    private final AlertImageStore images;

    public SecurityAlertController(SecurityAlertRepository alertRepository,
                                   SecurityAuditRepository auditRepository,
                                   AlertImageStore images) {
        this.alertRepository = alertRepository;
        this.auditRepository = auditRepository;
        this.images = images;
    }

    @GetMapping("/security-alerts/images/{filename:.+}")
    public ResponseEntity<Resource> getAlertImage(@PathVariable String filename) {
        Resource resource = images.load(filename);
        if (resource == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .body(resource);
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
            auditRepository.save(new SecurityAudit(auth.getName(), "RESOLVE_SECURITY_ALERT", "Alert #" + id));
            return ResponseEntity.ok(Map.of("status", "success", "message", "Đã đánh dấu xử lý cảnh báo."));
        }).orElse(ResponseEntity.notFound().build());
    }
}
