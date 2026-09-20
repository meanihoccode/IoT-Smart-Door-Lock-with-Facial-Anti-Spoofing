package com.example.btl_iot.controller;

import com.example.btl_iot.entity.AccessLog;
import com.example.btl_iot.repository.*;
import com.example.btl_iot.security.*;
import com.example.btl_iot.service.*;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.HashMap;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
public class ApiController {
    public record PinAttempt(String username, String pinCode) {}
    private final UserRepository userRepository;
    private final AccessLogRepository accessLogRepository;
    private final ProfileService profiles;
    private final DoorAccessService access;
    private final FaceGateway faces;
    public ApiController(UserRepository users, AccessLogRepository logs, ProfileService profiles,
                         DoorAccessService access, FaceGateway faces) {
        this.userRepository = users; this.accessLogRepository = logs;
        this.profiles = profiles; this.access = access; this.faces = faces;
    }
    @PostMapping("/verify-pin")
    public ResponseEntity<?> verifyPin(@RequestBody PinAttempt body, HttpServletRequest request) {
        return response(access.pin(body.username(), body.pinCode(), request.getRemoteAddr()));
    }
    @PostMapping("/verify-face")
    public ResponseEntity<?> verifyFace(@RequestParam("file") MultipartFile file) {
        // AI has no authority to grant access: Core checks the current profile under a row lock.
        return response(access.face(faces.identify(file)));
    }
    private ResponseEntity<?> response(DoorAccessService.Result result) {
        var body = new HashMap<String, Object>();
        body.put("status", result.status()); body.put("message", result.message());
        if (result.reasonCode() != null) body.put("reasonCode", result.reasonCode());
        if (result.requestId() != null) body.put("requestId", result.requestId());
        return ResponseEntity.status(result.code()).body(body);
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
                logData.put("userName", "Unknown");
            }
            recentLogsData.add(logData);
        }
        
        return ResponseEntity.ok(Map.of(
            "totalEmployees", totalEmployees,
            "accessesToday", accessesToday,
            "recentLogs", recentLogsData
        ));
    }


}
