package com.example.btl_iot.controller;

import com.example.btl_iot.entity.AccessLog;
import com.example.btl_iot.repository.*;
import com.example.btl_iot.security.*;
import com.example.btl_iot.service.*;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.HashMap;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
// Browser routes never unlock: face/PIN unlock exists only under /api/device (DEVICE_CONTRACT, D02/D04).
public class ApiController {
    private final UserRepository userRepository;
    private final AccessLogRepository accessLogRepository;
    private final ProfileService profiles;
    private final FaceGateway faces;
    public ApiController(UserRepository users, AccessLogRepository logs, ProfileService profiles, FaceGateway faces) {
        this.userRepository = users; this.accessLogRepository = logs;
        this.profiles = profiles; this.faces = faces;
    }
    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestParam String username, @RequestParam String fullName,
                                      @RequestParam String pinCode, @RequestParam(required = false) MultipartFile file,
                                      Authentication auth) {
        InputRules.username(username); ProfileRules.name(fullName); ProfileRules.pin(pinCode);
        // Extract before opening the DB transaction. No partial profile if AI rejects the photo.
        String embedding = file == null ? null : faces.extract(file);
        var profile = profiles.create(username, fullName, pinCode, embedding, auth.getName());
        return ResponseEntity.status(201).body(Map.of("status", "success", "profile", profile));
    }
    @GetMapping("/overview")
    public ResponseEntity<?> getOverview() {
        long totalEmployees = userRepository.count();
        
        LocalDateTime startOfDay = LocalDateTime.now().withHour(0).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime endOfDay = LocalDateTime.now().withHour(23).withMinute(59).withSecond(59).withNano(999999999);
        long accessesToday = accessLogRepository.countByAccessTimeBetween(startOfDay, endOfDay);
        
        java.util.List<AccessLog> recentLogs = accessLogRepository.findTop5ByOrderByAccessTimeDesc();
        
        java.util.List<Map<String, Object>> recentLogsData = new java.util.ArrayList<>();
        for (AccessLog log : recentLogs) {
            Map<String, Object> logData = new HashMap<>();
            logData.put("id", log.getId());
            logData.put("method", log.getAccessMethod());
            logData.put("status", log.getStatus());
            logData.put("time", log.getAccessTime().toString());
            if (log.getUser() != null) {
                logData.put("userName", log.getUser().getFullName());
            } else {
                logData.put("userName", "Khách / Ngoại tuyến");
            }
            recentLogsData.add(logData);
        }
        
        return ResponseEntity.ok(Map.of(
            "totalEmployees", totalEmployees,
            "accessesToday", accessesToday,
            "recentLogs", recentLogsData
        ));
    }

    @GetMapping("/admin/access-logs")
    public ResponseEntity<?> getAccessLogs(@RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "20") int size) {
        Page<AccessLog> logPage = accessLogRepository.findAllByOrderByAccessTimeDesc(PageRequest.of(page, size));
        java.util.List<Map<String, Object>> items = new java.util.ArrayList<>();
        for (AccessLog log : logPage.getContent()) {
            Map<String, Object> item = new HashMap<>();
            item.put("id", log.getId());
            item.put("time", log.getAccessTime().toString());
            item.put("method", log.getAccessMethod());
            item.put("status", log.getStatus());
            if (log.getUser() != null) {
                item.put("userName", log.getUser().getFullName());
                item.put("username", log.getUser().getUsername());
            } else if (log.getAccessMethod() != null && log.getAccessMethod().startsWith("OFFLINE_")) {
                // Historical rows only: firmware no longer has an offline/master-PIN unlock path.
                item.put("userName", "Mở khóa ngoại tuyến (bản ghi cũ)");
                item.put("username", "OFFLINE");
            } else if ("REMOTE_ADMIN".equals(log.getAccessMethod())) {
                item.put("userName", "Quản trị viên mở từ xa");
                item.put("username", "ADMIN");
            } else {
                item.put("userName", "Khách / Không xác định");
                item.put("username", "—");
            }
            item.put("imageUrl", log.getImageUrl());
            item.put("commandId", log.getCommandId());
            items.add(item);
        }

        return ResponseEntity.ok(Map.of(
            "items", items,
            "page", logPage.getNumber(),
            "totalPages", logPage.getTotalPages(),
            "totalElements", logPage.getTotalElements()
        ));
    }
}
