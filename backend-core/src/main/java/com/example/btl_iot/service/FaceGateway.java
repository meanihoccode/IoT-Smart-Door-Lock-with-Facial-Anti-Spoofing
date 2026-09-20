package com.example.btl_iot.service;

import java.io.IOException;
import java.util.Set;
import javax.imageio.ImageIO;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

// Core-side adapter only. Does not change the AI model or service-to-service authentication.
@Service
public class FaceGateway {
    public record FaceMatch(Long userId, boolean spoof, String reasonCode, String requestId) {
        public FaceMatch(Long userId, boolean spoof) {
            this(userId, spoof, spoof ? "SPOOF_DETECTED" : userId == null ? "NOT_RECOGNIZED" : "FACE_VERIFIED", null);
        }
        public static FaceMatch rejected(String reason, String requestId) {
            return new FaceMatch(null, "SPOOF_DETECTED".equals(reason), reason, requestId);
        }
    }
    private static final Set<String> ERROR_REASONS = Set.of("NO_ENROLLMENT", "NO_FACE", "MULTIPLE_FACES",
            "LOW_QUALITY", "LIVENESS_UNCERTAIN", "SPOOF_DETECTED", "NOT_RECOGNIZED", "MODEL_UNAVAILABLE",
            "DB_UNAVAILABLE", "INVALID_AI_RESPONSE", "INVALID_TEMPLATE", "INVALID_IMAGE", "INTERNAL_ERROR");
    public static final class AiFailure extends ResponseStatusException {
        private final String reasonCode;
        private final String requestId;
        public AiFailure(String reasonCode, String requestId) {
            super(HttpStatus.valueOf(statusFor(reasonCode)), messageFor(reasonCode));
            this.reasonCode = reasonCode; this.requestId = requestId;
        }
        public String reasonCode() { return reasonCode; }
        public String requestId() { return requestId; }
    }
    private final RestTemplate client;
    // Use the shared timeout policy from HttpClientConfig (connect 3s, read 20s).
    public FaceGateway(RestTemplate client) { this.client = client; }
    private JsonNode call(String path, MultipartFile file) {
        var parts = new LinkedMultiValueMap<String, Object>();
        parts.add("file", file.getResource());
        var headers = new HttpHeaders(); headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        try {
            var result = client.postForObject("http://localhost:8000/api/" + path, new HttpEntity<>(parts, headers), JsonNode.class);
            if (result == null) throw new AiFailure("INVALID_AI_RESPONSE", null);
            return result;
        } catch (ResourceAccessException ex) {
            throw new AiFailure("MODEL_UNAVAILABLE", null);
        } catch (RestClientException ex) {
            throw new AiFailure("INVALID_AI_RESPONSE", null);
        }
    }
    public String extract(MultipartFile file) {
        validateImage(file);
        var result = call("extract-embedding", file);
        validateEnvelope(result, "EMBEDDING_EXTRACTED");
        var embedding = result.get("embedding");
        if (embedding == null || !embedding.isArray() || embedding.size() != 512)
            throw invalid(result);
        boolean nonZero = false;
        double squaredNorm = 0;
        for (var element : embedding) {
            if (!element.isNumber() || !Double.isFinite(element.asDouble()))
                throw invalid(result);
            nonZero |= element.asDouble() != 0;
            squaredNorm += element.asDouble() * element.asDouble();
        }
        if (!nonZero || !Double.isFinite(squaredNorm) || squaredNorm == 0) throw invalid(result);
        return embedding.toString();
    }
    public FaceMatch identify(MultipartFile file) {
        validateImage(file);
        try {
            var result = call("verify-face", file);
            validateEnvelope(result, "FACE_VERIFIED");
            var live = result.path("liveness");
            var recognition = result.path("recognition");
            var id = recognition.path("userId");
            if (!positiveInteger(result.path("faceCount"))
                    || !"PASSED".equals(live.path("status").asText()) || !isTrue(live.path("isReal"))
                    || !inRange(live.path("liveScore"), 0, 1)
                    || !"MATCHED".equals(recognition.path("status").asText())
                    || !isTrue(recognition.path("recognized")) || !positiveInteger(id)
                    || !positiveInteger(recognition.path("gallerySize"))
                    || !inRange(recognition.path("similarity"), -1, 1)
                    || !inRange(recognition.path("threshold"), -1, 1)
                    || recognition.path("similarity").asDouble() < recognition.path("threshold").asDouble())
                throw invalid(result);
            return new FaceMatch(id.asLong(), false, "FACE_VERIFIED", requestId(result));
        } catch (AiFailure failure) {
            // Return a denial so DoorAccessService records the failed attempt, never publishes MQTT.
            return FaceMatch.rejected(failure.reasonCode(), failure.requestId());
        }
    }
    private void validateEnvelope(JsonNode result, String successReason) {
        if (!result.isObject() || requestId(result) == null || !result.path("reasonCode").isString()
                || !result.path("status").isString()) throw invalid(result);
        String status = result.path("status").asText(), reason = result.path("reasonCode").asText();
        if ("error".equals(status) && ERROR_REASONS.contains(reason)) throw new AiFailure(reason, requestId(result));
        if (!"success".equals(status) || !successReason.equals(reason)) throw invalid(result);
    }
    private static String requestId(JsonNode result) {
        var id = result.path("requestId");
        return id.isString() && !id.asText().isBlank() && id.asText().length() <= 128 ? id.asText() : null;
    }
    private static AiFailure invalid(JsonNode result) { return new AiFailure("INVALID_AI_RESPONSE", requestId(result)); }
    private static boolean positiveInteger(JsonNode value) {
        return value.isIntegralNumber() && value.canConvertToLong() && value.asLong() > 0;
    }
    private static boolean isTrue(JsonNode value) { return value.isBoolean() && value.asBoolean(); }
    private static boolean inRange(JsonNode value, double min, double max) {
        return value.isNumber() && Double.isFinite(value.asDouble()) && value.asDouble() >= min && value.asDouble() <= max;
    }
    public static int statusFor(String reason) {
        return switch (reason) {
            case "NO_FACE", "MULTIPLE_FACES", "LOW_QUALITY", "LIVENESS_UNCERTAIN", "INVALID_IMAGE" -> 422;
            case "NO_ENROLLMENT" -> 409;
            case "MODEL_UNAVAILABLE", "DB_UNAVAILABLE", "INVALID_TEMPLATE" -> 503;
            case "INVALID_AI_RESPONSE" -> 502;
            case "INTERNAL_ERROR" -> 500;
            default -> 401;
        };
    }
    public static String messageFor(String reason) {
        return switch (reason) {
            case "NO_ENROLLMENT" -> "Hệ thống chưa có khuôn mặt đã đăng ký.";
            case "NO_FACE" -> "Không tìm thấy khuôn mặt. Hãy nhìn thẳng vào camera và thử lại.";
            case "MULTIPLE_FACES" -> "Ảnh đăng ký chỉ được có một khuôn mặt.";
            case "LOW_QUALITY" -> "Ảnh chưa đủ rõ. Hãy giữ yên và bảo đảm khuôn mặt đủ sáng.";
            case "LIVENESS_UNCERTAIN" -> "Chưa xác định được khuôn mặt thật. Hãy thử lại.";
            case "SPOOF_DETECTED" -> "Phát hiện dấu hiệu giả mạo. Từ chối truy cập.";
            case "NOT_RECOGNIZED" -> "Khuôn mặt chưa được nhận diện hoặc chưa đăng ký.";
            case "MODEL_UNAVAILABLE" -> "Dịch vụ AI chưa sẵn sàng hoặc đã quá thời gian xử lý.";
            case "DB_UNAVAILABLE" -> "Không thể đọc dữ liệu khuôn mặt đã đăng ký.";
            case "INVALID_TEMPLATE" -> "Dữ liệu khuôn mặt đăng ký không hợp lệ. Hãy liên hệ quản trị viên.";
            case "INVALID_IMAGE" -> "Ảnh chụp không hợp lệ. Hãy thử lại.";
            case "INVALID_AI_RESPONSE" -> "Kết quả từ dịch vụ AI không hợp lệ. Cửa không được mở.";
            case "INTERNAL_ERROR" -> "Dịch vụ AI gặp lỗi khi xử lý. Vui lòng thử lại sau.";
            default -> "Không thể xác thực hoặc hồ sơ không được phép vào.";
        };
    }
    private void validateImage(MultipartFile file) {
        if (file == null || file.isEmpty() || file.getSize() > 5L * 1024 * 1024)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cần ảnh JPEG/PNG, tối đa 5 MB.");
        try (var source = file.getInputStream(); var stream = ImageIO.createImageInputStream(source)) {
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw new IOException();
            var reader = readers.next();
            try {
                reader.setInput(stream);
                String format = reader.getFormatName();
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (!(format.equalsIgnoreCase("jpeg") || format.equalsIgnoreCase("png"))
                        || width < 1 || height < 1 || width > 4096 || height > 4096) throw new IOException();
            } finally { reader.dispose(); }
        } catch (IOException | IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ảnh phải là JPEG/PNG hợp lệ, tối đa 4096 × 4096 pixel.");
        }
    }
}
