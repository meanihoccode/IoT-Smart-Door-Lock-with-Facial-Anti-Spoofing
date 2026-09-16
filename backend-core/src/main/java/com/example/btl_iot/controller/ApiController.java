package com.example.btl_iot.controller;

import com.example.btl_iot.entity.AccessLog;
import com.example.btl_iot.entity.User;
import com.example.btl_iot.dto.AiVerificationResponse;
import com.example.btl_iot.mqtt.MqttPublisher;
import com.example.btl_iot.repository.AccessLogRepository;
import com.example.btl_iot.repository.UserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestController
@RequestMapping("/api")
public class ApiController {

    private static final Logger logger = LoggerFactory.getLogger(ApiController.class);
    private static final String AI_BACKEND_URL = "http://localhost:8000/api/verify-face";
    private static final String AI_EXTRACT_URL = "http://localhost:8000/api/extract-embedding";
    private static final Set<String> AI_REASON_CODES = Set.of(
            "FACE_VERIFIED",
            "NO_ENROLLMENT",
            "NO_FACE",
            "MULTIPLE_FACES",
            "LOW_QUALITY",
            "LIVENESS_UNCERTAIN",
            "SPOOF_DETECTED",
            "NOT_RECOGNIZED",
            "MODEL_UNAVAILABLE",
            "DB_UNAVAILABLE",
            "INVALID_AI_RESPONSE",
            "INVALID_TEMPLATE",
            "INVALID_IMAGE",
            "INTERNAL_ERROR");

    private final UserRepository userRepository;
    private final AccessLogRepository accessLogRepository;
    private final MqttPublisher mqttPublisher;
    private final RestTemplate restTemplate;

    public ApiController(
            UserRepository userRepository,
            AccessLogRepository accessLogRepository,
            MqttPublisher mqttPublisher,
            RestTemplate restTemplate) {
        this.userRepository = userRepository;
        this.accessLogRepository = accessLogRepository;
        this.mqttPublisher = mqttPublisher;
        this.restTemplate = restTemplate;
    }

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
        AccessLog log = new AccessLog();
        log.setAccessMethod("FACE");
        log.setAccessTime(LocalDateTime.now());

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);

            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("file", file.getResource());

            HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

            ResponseEntity<AiVerificationResponse> response = restTemplate.postForEntity(
                    AI_BACKEND_URL,
                    requestEntity,
                    AiVerificationResponse.class);
            AiVerificationResponse aiResult = response.getBody();

            String contractError = validateAiContract(aiResult);
            if (contractError != null) {
                logger.warn("Face verification rejected: reason=INVALID_AI_RESPONSE detail={} requestId={}",
                        contractError, requestIdOf(aiResult));
                saveFaceFailure(log, "INVALID_AI_RESPONSE");
                return errorResponse(502, "INVALID_AI_RESPONSE",
                        "Kết quả từ dịch vụ AI không hợp lệ.", requestIdOf(aiResult));
            }

            if (!isSuccessfulMatch(aiResult)) {
                String reasonCode = aiResult.reasonCode();
                logger.info("Face verification rejected: reason={} requestId={}",
                        reasonCode, aiResult.requestId());
                saveFaceFailure(log, reasonCode);
                return errorResponse(httpStatusFor(reasonCode), reasonCode,
                        messageFor(reasonCode, aiResult.message()), aiResult.requestId());
            }

            Long userId = aiResult.recognition().userId();
            Optional<User> userOpt = userRepository.findById(userId);
            if (userOpt.isEmpty()) {
                logger.warn("Face verification rejected: reason=INVALID_AI_RESPONSE "
                        + "detail=unknown_user requestId={}", aiResult.requestId());
                saveFaceFailure(log, "INVALID_AI_RESPONSE");
                return errorResponse(502, "INVALID_AI_RESPONSE",
                        "Kết quả AI tham chiếu người dùng không tồn tại.", aiResult.requestId());
            }

            log.setUser(userOpt.get());
            log.setStatus("SUCCESS");
            accessLogRepository.save(log);

            mqttPublisher.sendOpenDoorCommand();
            logger.info("Face verification accepted: reason=FACE_VERIFIED requestId={}",
                    aiResult.requestId());
            Map<String, Object> successBody = new HashMap<>();
            successBody.put("status", "success");
            successBody.put("reasonCode", "FACE_VERIFIED");
            successBody.put("message", "Xác thực khuôn mặt thành công.");
            successBody.put("requestId", aiResult.requestId());
            return ResponseEntity.ok(successBody);
        } catch (ResourceAccessException e) {
            logger.warn("Face verification rejected: reason=MODEL_UNAVAILABLE detail=ai_timeout");
            saveFaceFailure(log, "MODEL_UNAVAILABLE");
            return errorResponse(503, "MODEL_UNAVAILABLE",
                    "Dịch vụ AI không phản hồi hoặc đã quá thời gian xử lý.", null);
        } catch (RestClientException e) {
            logger.warn("Face verification rejected: reason=INVALID_AI_RESPONSE detail=unreadable_response");
            saveFaceFailure(log, "INVALID_AI_RESPONSE");
            return errorResponse(502, "INVALID_AI_RESPONSE",
                    "Không thể đọc kết quả từ dịch vụ AI.", null);
        } catch (Exception e) {
            logger.error("Face verification failed: reason=INTERNAL_ERROR", e);
            saveFaceFailure(log, "INTERNAL_ERROR");
            return errorResponse(500, "INTERNAL_ERROR",
                    "Máy chủ gặp lỗi khi xác thực khuôn mặt.", null);
        }
    }

    private boolean isSuccessfulMatch(AiVerificationResponse result) {
        // faceCount là tổng số mặt trong ảnh. AI chỉ xác thực mặt lớn nhất,
        // nên ảnh có nhiều mặt vẫn hợp lệ nếu mặt được chọn vượt đủ các kiểm tra.
        return "success".equals(result.status())
                && "FACE_VERIFIED".equals(result.reasonCode())
                && result.faceCount() != null
                && result.faceCount() >= 1
                && result.liveness() != null
                && "PASSED".equals(result.liveness().status())
                && Boolean.TRUE.equals(result.liveness().isReal())
                && isProbability(result.liveness().liveScore())
                && result.recognition() != null
                && "MATCHED".equals(result.recognition().status())
                && Boolean.TRUE.equals(result.recognition().recognized())
                && result.recognition().userId() != null
                && result.recognition().userId() > 0
                && result.recognition().gallerySize() != null
                && result.recognition().gallerySize() > 0
                && isSimilarity(result.recognition().similarity())
                && isSimilarity(result.recognition().threshold())
                && result.recognition().similarity() >= result.recognition().threshold();
    }

    private String validateAiContract(AiVerificationResponse result) {
        if (result == null || isBlank(result.status()) || isBlank(result.reasonCode())
                || isBlank(result.requestId())) {
            return "missing required result fields";
        }
        if (!"success".equals(result.status()) && !"error".equals(result.status())) {
            return "unknown status";
        }
        if (!AI_REASON_CODES.contains(result.reasonCode())) {
            return "unknown reason code";
        }
        if ("success".equals(result.status()) && !isSuccessfulMatch(result)) {
            return "inconsistent successful result";
        }
        if ("error".equals(result.status()) && "FACE_VERIFIED".equals(result.reasonCode())) {
            return "inconsistent error result";
        }
        return null;
    }

    private boolean isProbability(Double value) {
        return value != null && Double.isFinite(value) && value >= 0.0 && value <= 1.0;
    }

    private boolean isSimilarity(Double value) {
        return value != null && Double.isFinite(value) && value >= -1.0 && value <= 1.0;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String requestIdOf(AiVerificationResponse result) {
        return result == null ? null : result.requestId();
    }

    private void saveFaceFailure(AccessLog log, String reasonCode) {
        log.setStatus(reasonCode);
        accessLogRepository.save(log);
    }

    private ResponseEntity<Map<String, Object>> errorResponse(
            int statusCode, String reasonCode, String message, String requestId) {
        Map<String, Object> body = new HashMap<>();
        body.put("status", "error");
        body.put("reasonCode", reasonCode);
        body.put("message", message);
        if (requestId != null) {
            body.put("requestId", requestId);
        }
        return ResponseEntity.status(statusCode).body(body);
    }

    private int httpStatusFor(String reasonCode) {
        return switch (reasonCode) {
            case "NO_FACE", "MULTIPLE_FACES", "LOW_QUALITY", "LIVENESS_UNCERTAIN", "INVALID_IMAGE" -> 422;
            case "NO_ENROLLMENT" -> 409;
            case "MODEL_UNAVAILABLE", "DB_UNAVAILABLE", "INVALID_TEMPLATE" -> 503;
            case "INVALID_AI_RESPONSE" -> 502;
            case "INTERNAL_ERROR" -> 500;
            default -> 401;
        };
    }

    private String messageFor(String reasonCode, String aiMessage) {
        if (aiMessage != null && !aiMessage.isBlank()) {
            return aiMessage;
        }
        return switch (reasonCode) {
            case "NO_ENROLLMENT" -> "Hệ thống chưa có khuôn mặt đã đăng ký.";
            case "NO_FACE" -> "Không tìm thấy khuôn mặt. Hãy nhìn thẳng vào camera và thử lại.";
            case "MULTIPLE_FACES" -> "Chỉ một người được đứng trước camera.";
            case "LOW_QUALITY" -> "Ảnh chưa đủ rõ. Hãy giữ yên và bảo đảm khuôn mặt đủ sáng.";
            case "LIVENESS_UNCERTAIN" -> "Chưa xác định được khuôn mặt thật. Hãy thử lại.";
            case "SPOOF_DETECTED" -> "Phát hiện dấu hiệu giả mạo. Từ chối truy cập.";
            case "NOT_RECOGNIZED" -> "Khuôn mặt chưa được nhận diện hoặc chưa đăng ký.";
            case "MODEL_UNAVAILABLE" -> "Dịch vụ AI chưa sẵn sàng. Vui lòng thử lại sau.";
            case "DB_UNAVAILABLE" -> "Không thể đọc dữ liệu khuôn mặt đã đăng ký.";
            default -> "Không thể xác thực khuôn mặt.";
        };
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
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);

            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("file", file.getResource());

            HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);
            ResponseEntity<Map> response = restTemplate.postForEntity(AI_EXTRACT_URL, requestEntity, Map.class);
            Map<String, Object> aiResult = response.getBody();

            if (isValidEmbeddingResponse(aiResult)) {
                List<?> embedding = (List<?>) aiResult.get("embedding");
                String embeddingStr = embedding.toString();
                
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
                boolean malformedSuccess = aiResult != null && "success".equals(aiResult.get("status"));
                String reasonCode = aiResult == null || malformedSuccess
                        ? "INVALID_AI_RESPONSE"
                        : String.valueOf(aiResult.getOrDefault("reasonCode", "INVALID_AI_RESPONSE"));
                if (!AI_REASON_CODES.contains(reasonCode)) {
                    reasonCode = "INVALID_AI_RESPONSE";
                }
                String message = aiResult == null || malformedSuccess
                        ? "Kết quả từ dịch vụ AI không hợp lệ."
                        : String.valueOf(aiResult.getOrDefault("message", "Không thể đăng ký khuôn mặt."));
                Object requestId = aiResult == null ? null : aiResult.get("requestId");
                return errorResponse(httpStatusFor(reasonCode), reasonCode, message,
                        requestId == null ? null : requestId.toString());
            }

        } catch (ResourceAccessException e) {
            logger.warn("Face enrollment rejected: reason=MODEL_UNAVAILABLE detail=ai_timeout");
            return errorResponse(503, "MODEL_UNAVAILABLE",
                    "Dịch vụ AI không phản hồi hoặc đã quá thời gian xử lý.", null);
        } catch (RestClientException e) {
            logger.warn("Face enrollment rejected: reason=INVALID_AI_RESPONSE detail=unreadable_response");
            return errorResponse(502, "INVALID_AI_RESPONSE",
                    "Không thể đọc kết quả từ dịch vụ AI.", null);
        } catch (Exception e) {
            logger.error("Face enrollment failed: reason=INTERNAL_ERROR", e);
            return errorResponse(500, "INTERNAL_ERROR",
                    "Máy chủ gặp lỗi khi đăng ký khuôn mặt.", null);
        }
    }

    private boolean isValidEmbeddingResponse(Map<?, ?> aiResult) {
        if (aiResult == null
                || !"success".equals(aiResult.get("status"))
                || !"EMBEDDING_EXTRACTED".equals(aiResult.get("reasonCode"))
                || !(aiResult.get("requestId") instanceof String requestId)
                || requestId.isBlank()
                || !(aiResult.get("embedding") instanceof List<?> embedding)
                || embedding.size() != 512) {
            return false;
        }
        for (Object value : embedding) {
            if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) {
                return false;
            }
        }
        return true;
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
