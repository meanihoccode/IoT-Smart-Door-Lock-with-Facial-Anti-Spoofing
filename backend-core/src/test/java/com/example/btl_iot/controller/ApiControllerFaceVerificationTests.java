package com.example.btl_iot.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import com.example.btl_iot.entity.AccessLog;
import com.example.btl_iot.entity.User;
import com.example.btl_iot.mqtt.MqttPublisher;
import com.example.btl_iot.repository.AccessLogRepository;
import com.example.btl_iot.repository.UserRepository;

class ApiControllerFaceVerificationTests {

    private UserRepository userRepository;
    private AccessLogRepository accessLogRepository;
    private MqttPublisher mqttPublisher;
    private ApiController controller;
    private MockRestServiceServer aiServer;
    private MockMultipartFile faceImage;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        accessLogRepository = mock(AccessLogRepository.class);
        mqttPublisher = mock(MqttPublisher.class);
        RestTemplate restTemplate = new RestTemplate();
        aiServer = MockRestServiceServer.bindTo(restTemplate).build();
        controller = new ApiController(
                userRepository,
                accessLogRepository,
                mqttPublisher,
                restTemplate);
        faceImage = new MockMultipartFile(
                "file",
                "face.jpg",
                MediaType.IMAGE_JPEG_VALUE,
                new byte[] {1, 2, 3});
        when(accessLogRepository.save(any(AccessLog.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void spoofResultNeverPublishesOpenCommand() {
        respondWith("""
                {
                  "status":"error",
                  "requestId":"req-spoof",
                  "reasonCode":"SPOOF_DETECTED",
                  "message":"Phát hiện dấu hiệu giả mạo.",
                  "faceCount":1,
                  "liveness":{"status":"FAILED","isReal":false,"liveScore":0.04},
                  "recognition":{"status":"NOT_RUN","recognized":false,"threshold":0.5,"gallerySize":0}
                }
                """);

        ResponseEntity<?> response = controller.verifyFace(faceImage);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(body(response).get("reasonCode")).isEqualTo("SPOOF_DETECTED");
        verify(mqttPublisher, never()).sendOpenDoorCommand();
        aiServer.verify();
    }

    @Test
    void modelUnavailableNeverPublishesOpenCommand() {
        respondWith("""
                {
                  "status":"error",
                  "requestId":"req-model",
                  "reasonCode":"MODEL_UNAVAILABLE",
                  "message":"Dịch vụ AI chưa sẵn sàng.",
                  "faceCount":0,
                  "liveness":{"status":"NOT_RUN","isReal":null,"liveScore":null},
                  "recognition":{"status":"NOT_RUN","recognized":false,"threshold":0.5,"gallerySize":0}
                }
                """);

        ResponseEntity<?> response = controller.verifyFace(faceImage);

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(body(response).get("reasonCode")).isEqualTo("MODEL_UNAVAILABLE");
        verify(mqttPublisher, never()).sendOpenDoorCommand();
        aiServer.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {
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
            "INTERNAL_ERROR"
    })
    void everyStandardErrorReasonNeverPublishesOpenCommand(String reasonCode) {
        respondWith(errorResponse(reasonCode));

        ResponseEntity<?> response = controller.verifyFace(faceImage);

        assertThat(response.getStatusCode().isError()).isTrue();
        assertThat(body(response).get("reasonCode")).isEqualTo(reasonCode);
        verify(userRepository, never()).findById(any());
        verify(mqttPublisher, never()).sendOpenDoorCommand();
        aiServer.verify();
    }

    @Test
    void malformedSuccessWithoutUserIdNeverPublishesOpenCommand() {
        respondWith(successResponse("null"));

        ResponseEntity<?> response = controller.verifyFace(faceImage);

        assertThat(response.getStatusCode().value()).isEqualTo(502);
        assertThat(body(response).get("reasonCode")).isEqualTo("INVALID_AI_RESPONSE");
        verify(mqttPublisher, never()).sendOpenDoorCommand();
        aiServer.verify();
    }

    @Test
    void unknownUserIdNeverPublishesOpenCommand() {
        respondWith(successResponse("99"));
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.verifyFace(faceImage);

        assertThat(response.getStatusCode().value()).isEqualTo(502);
        assertThat(body(response).get("reasonCode")).isEqualTo("INVALID_AI_RESPONSE");
        verify(mqttPublisher, never()).sendOpenDoorCommand();
        aiServer.verify();
    }

    @Test
    void successClaimBelowItsThresholdNeverPublishesOpenCommand() {
        respondWith(successResponse("7").replace(
                "\"similarity\":0.71",
                "\"similarity\":0.49"));

        ResponseEntity<?> response = controller.verifyFace(faceImage);

        assertThat(response.getStatusCode().value()).isEqualTo(502);
        assertThat(body(response).get("reasonCode")).isEqualTo("INVALID_AI_RESPONSE");
        verify(userRepository, never()).findById(any());
        verify(mqttPublisher, never()).sendOpenDoorCommand();
        aiServer.verify();
    }

    @Test
    void completeSuccessWithExistingUserPublishesExactlyOnce() {
        respondWith(successResponse("7"));
        User user = new User();
        user.setId(7L);
        user.setFullName("Test User");
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));

        ResponseEntity<?> response = controller.verifyFace(faceImage);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(body(response).get("reasonCode")).isEqualTo("FACE_VERIFIED");
        verify(mqttPublisher).sendOpenDoorCommand();
        aiServer.verify();
    }

    private void respondWith(String json) {
        aiServer.expect(requestTo("http://localhost:8000/api/verify-face"))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
    }

    private String successResponse(String userId) {
        return """
                {
                  "status":"success",
                  "requestId":"req-success",
                  "reasonCode":"FACE_VERIFIED",
                  "message":"Xác thực khuôn mặt thành công.",
                  "faceCount":1,
                  "liveness":{"status":"PASSED","isReal":true,"liveScore":0.93},
                  "recognition":{"status":"MATCHED","recognized":true,"userId":%s,"username":"test","similarity":0.71,"threshold":0.5,"gallerySize":1}
                }
                """.formatted(userId);
    }

    private String errorResponse(String reasonCode) {
        return """
                {
                  "status":"error",
                  "requestId":"req-error",
                  "reasonCode":"%s",
                  "message":"Verification rejected",
                  "faceCount":0,
                  "liveness":{"status":"NOT_RUN","isReal":null,"liveScore":null},
                  "recognition":{"status":"NOT_RUN","recognized":false,"threshold":0.5,"gallerySize":0}
                }
                """.formatted(reasonCode);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(ResponseEntity<?> response) {
        return (Map<String, Object>) response.getBody();
    }
}
