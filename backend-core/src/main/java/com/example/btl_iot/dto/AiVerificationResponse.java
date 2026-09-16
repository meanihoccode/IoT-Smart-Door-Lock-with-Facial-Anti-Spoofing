package com.example.btl_iot.dto;

public record AiVerificationResponse(
        String status,
        String requestId,
        String reasonCode,
        String message,
        Integer faceCount, // Tổng số mặt phát hiện; liveness/recognition chỉ thuộc mặt lớn nhất.
        LivenessResult liveness,
        RecognitionResult recognition) {

    public record LivenessResult(
            String status,
            Boolean isReal,
            Double liveScore) {
    }

    public record RecognitionResult(
            String status,
            Boolean recognized,
            Long userId,
            String username,
            Double similarity,
            Double threshold,
            Integer gallerySize) {
    }
}
