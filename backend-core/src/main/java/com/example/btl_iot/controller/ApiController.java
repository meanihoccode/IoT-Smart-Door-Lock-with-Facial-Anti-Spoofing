package com.example.btl_iot.controller;

import com.example.btl_iot.entity.AccessLog;
import com.example.btl_iot.entity.User;
import com.example.btl_iot.mqtt.MqttPublisher;
import com.example.btl_iot.repository.AccessLogRepository;
import com.example.btl_iot.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Map;
import java.util.HashMap;

@RestController
@RequestMapping("/api")
public class ApiController {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AccessLogRepository accessLogRepository;

    @Autowired
    private MqttPublisher mqttPublisher;

    private final String AI_BACKEND_URL = "http://localhost:8000/api/verify-face";
    private RestTemplate restTemplate = new RestTemplate();

    @PostMapping("/verify-pin")
    public ResponseEntity<?> verifyPin(@RequestBody Map<String, String> payload) {
        String pinCode = payload.get("pinCode");
        Optional<User> userOpt = userRepository.findByPinCode(pinCode);
        
        AccessLog log = new AccessLog();
        log.setAccessMethod("PIN");
        log.setAccessTime(LocalDateTime.now());
        
        if (userOpt.isPresent()) {
            User user = userOpt.get();
            log.setUser(user);
            log.setStatus("SUCCESS");
            accessLogRepository.save(log);
            
            // Mở cửa thông qua MQTT
            mqttPublisher.sendOpenDoorCommand();
            
            return ResponseEntity.ok(Map.of("status", "success", "message", "Welcome " + user.getFullName()));
        } else {
            log.setStatus("FAILED");
            accessLogRepository.save(log);
            return ResponseEntity.status(401).body(Map.of("status", "error", "message", "Invalid PIN"));
        }
    }

    @PostMapping("/verify-face")
    public ResponseEntity<?> verifyFace(@RequestParam("file") MultipartFile file) {
        try {
            // Forward the image to Python AI Backend
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);

            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("file", file.getResource());

            HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

            // Call Python FastAPI
            ResponseEntity<Map> response = restTemplate.postForEntity(AI_BACKEND_URL, requestEntity, Map.class);
            Map<String, Object> aiResult = response.getBody();

            AccessLog log = new AccessLog();
            log.setAccessMethod("FACE");
            log.setAccessTime(LocalDateTime.now());

            if (aiResult != null && Boolean.TRUE.equals(aiResult.get("is_real")) && Boolean.TRUE.equals(aiResult.get("recognized"))) {
                Object userIdObj = aiResult.get("user_id");
                if (userIdObj != null) {
                    Long userId = Long.valueOf(userIdObj.toString());
                    User user = userRepository.findById(userId).orElse(null);
                    log.setUser(user);
                }
                log.setStatus("SUCCESS");
                accessLogRepository.save(log);
                
                mqttPublisher.sendOpenDoorCommand();
                return ResponseEntity.ok(Map.of("status", "success", "message", "Face verified"));
            } else {
                log.setStatus(Boolean.FALSE.equals(aiResult.get("is_real")) ? "SPOOF_DETECTED" : "FAILED");
                accessLogRepository.save(log);
                return ResponseEntity.status(401).body(Map.of("status", "error", "message", "Verification failed"));
            }

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body(Map.of("status", "error", "message", "AI Backend error: " + e.getMessage()));
        }
    }

    @PostMapping("/register")
    public ResponseEntity<?> registerUser(
            @RequestParam("username") String username,
            @RequestParam("fullName") String fullName,
            @RequestParam("pinCode") String pinCode,
            @RequestParam("file") MultipartFile file) {
        
        // 1. Check if username exists
        if (userRepository.findByUsername(username).isPresent()) {
            return ResponseEntity.status(400).body(Map.of("status", "error", "message", "Username already exists"));
        }

        try {
            // 2. Send image to AI Backend to extract embedding
            String extractUrl = "http://localhost:8000/api/extract-embedding";
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);

            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("file", file.getResource());

            HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);
            ResponseEntity<Map> response = restTemplate.postForEntity(extractUrl, requestEntity, Map.class);
            Map<String, Object> aiResult = response.getBody();

            if (aiResult != null && "success".equals(aiResult.get("status"))) {
                // Get embedding list and convert to JSON string (or comma separated string)
                Object embeddingObj = aiResult.get("embedding");
                // Convert list to string for DB storage
                String embeddingStr = embeddingObj.toString(); 
                
                // 3. Save User to DB
                User user = new User();
                user.setUsername(username);
                user.setFullName(fullName);
                user.setPinCode(pinCode);
                user.setFaceEmbedding(embeddingStr); // Lưu chuỗi 512 số
                
                userRepository.save(user);
                
                // (Tùy chọn) Lưu file ảnh vào thư mục uploads/faces/ ở đây
                
                return ResponseEntity.ok(Map.of("status", "success", "message", "User registered successfully"));
            } else {
                return ResponseEntity.status(400).body(Map.of("status", "error", "message", "Không tìm thấy khuôn mặt trong ảnh"));
            }

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body(Map.of("status", "error", "message", "Registration error: " + e.getMessage()));
        }
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

    @GetMapping("/users")
    public ResponseEntity<?> getUsers() {
        java.util.List<User> users = userRepository.findAll();
        java.util.List<Map<String, Object>> usersData = new java.util.ArrayList<>();
        
        for (User user : users) {
            Map<String, Object> userData = new HashMap<>();
            userData.put("id", user.getId());
            userData.put("username", user.getUsername());
            userData.put("name", user.getFullName());
            
            // Determine method
            String method = "N/A";
            boolean hasFace = user.getFaceEmbedding() != null && !user.getFaceEmbedding().isEmpty();
            boolean hasPin = user.getPinCode() != null && !user.getPinCode().isEmpty();
            if (hasFace && hasPin) method = "Face + PIN";
            else if (hasFace) method = "Face Only";
            else if (hasPin) method = "PIN Only";
            
            userData.put("method", method);
            userData.put("status", "Active");
            
            LocalDateTime lastAccess = accessLogRepository.findLastAccessTimeByUserId(user.getId());
            userData.put("lastActive", lastAccess != null ? lastAccess.toString() : "Chưa từng hoạt động");
            
            usersData.add(userData);
        }
        
        return ResponseEntity.ok(usersData);
    }
}
