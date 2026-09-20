package com.example.btl_iot.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.Map;
import java.util.Optional;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import com.example.btl_iot.service.*;
import com.example.btl_iot.repository.SecurityAuditRepository;
import com.example.btl_iot.security.AttemptLimiter;
import org.springframework.security.crypto.password.PasswordEncoder;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
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
    void setUp() throws Exception {
        userRepository = mock(UserRepository.class);
        accessLogRepository = mock(AccessLogRepository.class);
        mqttPublisher = mock(MqttPublisher.class);
        RestTemplate restTemplate = new RestTemplate();
        aiServer = MockRestServiceServer.bindTo(restTemplate).build();
        var access = new DoorAccessService(userRepository, accessLogRepository, mock(SecurityAuditRepository.class),
                mqttPublisher, mock(PasswordEncoder.class), new AttemptLimiter());
        controller = new ApiController(userRepository, accessLogRepository, mock(ProfileService.class),
                access, new FaceGateway(restTemplate));
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", bytes);
        faceImage = new MockMultipartFile(
                "file",
                "face.png",
                MediaType.IMAGE_PNG_VALUE,
                bytes.toByteArray());
        when(accessLogRepository.save(any(AccessLog.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(accessLogRepository.saveAndFlush(any(AccessLog.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 3})
    void spoofResultNeverPublishesOpenCommand(int faceCount) {
        respondWith("""
                {
                  "status":"error",
                  "requestId":"req-spoof",
                  "reasonCode":"SPOOF_DETECTED",
                  "message":"Phát hiện dấu hiệu giả mạo.",
                  "faceCount":%d,
                  "liveness":{"status":"FAILED","isReal":false,"liveScore":0.04},
                  "recognition":{"status":"NOT_RUN","recognized":false,"threshold":0.5,"gallerySize":0}
                }
                """.formatted(faceCount));

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
        assertThat(body(response).get("requestId")).isEqualTo("req-error");
        assertThat(response.getStatusCode().value()).isEqualTo(FaceGateway.statusFor(reasonCode));
        verify(accessLogRepository).saveAndFlush(argThat(log -> reasonCode.equals(log.getStatus())));
        verify(userRepository, never()).findLockedById(any());
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
        when(userRepository.findLockedById(99L)).thenReturn(Optional.empty());

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
        verify(userRepository, never()).findLockedById(any());
        verify(mqttPublisher, never()).sendOpenDoorCommand();
        aiServer.verify();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 5})
    void completeSuccessWithExistingUserPublishesExactlyOnce(int faceCount) {
        respondWith(successResponse("7").replace("\"faceCount\":1", "\"faceCount\":" + faceCount));
        User user = new User();
        user.setId(7L);
        user.setFullName("Test User");
        user.setFaceEmbedding("[1]");
        when(userRepository.findLockedById(7L)).thenReturn(Optional.of(user));
        when(mqttPublisher.sendOpenDoorCommand()).thenReturn(true);

        ResponseEntity<?> response = controller.verifyFace(faceImage);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(body(response).get("reasonCode")).isEqualTo("FACE_VERIFIED");
        verify(mqttPublisher).sendOpenDoorCommand();
        aiServer.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "null"})
    void invalidFaceCountNeverPublishesOpenCommand(String faceCount) {
        respondWith(successResponse("7").replace("\"faceCount\":1", "\"faceCount\":" + faceCount));

        ResponseEntity<?> response = controller.verifyFace(faceImage);

        assertThat(response.getStatusCode().value()).isEqualTo(502);
        assertThat(body(response).get("reasonCode")).isEqualTo("INVALID_AI_RESPONSE");
        verify(userRepository, never()).findLockedById(any());
        verify(mqttPublisher, never()).sendOpenDoorCommand();
        aiServer.verify();
    }

    private void respondWith(String json) {
        aiServer.expect(requestTo("http://localhost:8000/api/verify-face"))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "\"isReal\":true|\"isReal\":\"true\"",
        "\"isReal\":true|\"isReal\":false",
        "\"recognized\":true|\"recognized\":\"true\"",
        "\"recognized\":true|\"recognized\":false",
        "\"status\":\"PASSED\"|\"status\":\"FAILED\"",
        "\"status\":\"MATCHED\"|\"status\":\"NOT_RUN\"",
        "\"userId\":7|\"userId\":\"7\"",
        "\"userId\":7|\"userId\":7.5",
        "\"userId\":7|\"userId\":-1",
        "\"userId\":7|\"userId\":9223372036854775808",
        "\"gallerySize\":1|\"gallerySize\":0",
        "\"faceCount\":1|\"faceCount\":1.5",
        "\"requestId\":\"req-success\"|\"requestId\":null",
        "\"requestId\":\"req-success\"|\"requestId\":123",
        "\"liveScore\":0.93|\"liveScore\":1.1",
        "\"liveScore\":0.93|\"liveScore\":-0.1",
        "\"similarity\":0.71|\"similarity\":1.1",
        "\"threshold\":0.5|\"threshold\":-2",
        "\"threshold\":0.5|\"threshold\":\"0.5\"",
        "\"reasonCode\":\"FACE_VERIFIED\"|\"reasonCode\":\"SPOOF_DETECTED\"",
        "\"status\":\"success\"|\"status\":\"error\""
    })
    void inconsistentOrCoercedSuccessNeverOpensDoor(String from, String to) {
        String valid = successResponse("7");
        assertThat(valid).contains(from);
        respondWith(valid.replace(from, to));
        var response = controller.verifyFace(faceImage);
        assertThat(response.getStatusCode().value()).isEqualTo(502);
        assertThat(body(response).get("reasonCode")).isEqualTo("INVALID_AI_RESPONSE");
        verify(userRepository, never()).findLockedById(any());
        verify(mqttPublisher, never()).sendOpenDoorCommand();
        aiServer.verify();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void recognizedButRevokedOrNotEnrolledProfileNeverOpensDoor(boolean revoked) {
        respondWith(successResponse("7"));
        User user = new User(); user.setId(7L);
        user.setEnabled(!revoked); user.setFaceEmbedding(revoked ? "[1]" : null);
        when(userRepository.findLockedById(7L)).thenReturn(Optional.of(user));
        var response = controller.verifyFace(faceImage);
        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(body(response).get("reasonCode")).isEqualTo("PROFILE_NOT_ALLOWED");
        verify(mqttPublisher, never()).sendOpenDoorCommand();
        verify(accessLogRepository).saveAndFlush(argThat(log -> "PROFILE_NOT_ALLOWED".equals(log.getStatus())));
        aiServer.verify();
    }

    @Test
    void recognizedFaceButBrokerFailureIsNotSuccess() {
        respondWith(successResponse("7"));
        User user = new User(); user.setId(7L); user.setFaceEmbedding("[1]");
        when(userRepository.findLockedById(7L)).thenReturn(Optional.of(user));
        when(mqttPublisher.sendOpenDoorCommand()).thenReturn(false);
        var response = controller.verifyFace(faceImage);
        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(body(response).get("reasonCode")).isEqualTo("COMMAND_FAILED");
        assertThat(body(response).get("requestId")).isEqualTo("req-success");
        verify(mqttPublisher).sendOpenDoorCommand();
        verify(accessLogRepository).save(argThat(log -> "COMMAND_FAILED".equals(log.getStatus())));
        aiServer.verify();
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
