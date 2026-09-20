package com.example.btl_iot.service;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.client.ResourceAccessException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class FaceGatewayTests {
    FaceGateway gateway;
    MockRestServiceServer server;
    MockMultipartFile image;
    @BeforeEach void setup() throws Exception {
        var client = new RestTemplate(); gateway = new FaceGateway(client);
        server = MockRestServiceServer.bindTo(client).build();
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB),"png",bytes);
        image = new MockMultipartFile("file","face.png","image/png",bytes.toByteArray());
    }
    void answer(String endpoint, String json) {
        server.expect(requestTo("http://localhost:8000/api/"+endpoint)).andRespond(withSuccess(json,MediaType.APPLICATION_JSON));
    }
    @Test void acceptsOnlyExplicitPositiveIdentification() {
        answer("verify-face", success());
        assertEquals(new FaceGateway.FaceMatch(1L,false,"FACE_VERIFIED","req-test"),gateway.identify(image)); server.verify();
    }
    @Test void missingOrMalformedAiFieldsNeverAuthorize() {
        for (String json : new String[]{"{}","{\"is_real\":true,\"recognized\":true}",
                "{\"is_real\":true,\"recognized\":true,\"user_id\":1}",
                "{\"is_real\":\"true\",\"recognized\":true,\"user_id\":1}",
                "{\"is_real\":true,\"recognized\":true,\"user_id\":\"1\"}",
                "{\"is_real\":true,\"recognized\":true,\"user_id\":0}"}) {
            server.reset(); answer("verify-face",json);
            assertNull(gateway.identify(image).userId()); server.verify();
        }
    }
    @Test void spoofIsPreservedAsDenial() {
        answer("verify-face","{\"status\":\"error\",\"reasonCode\":\"SPOOF_DETECTED\",\"requestId\":\"req-test\"}");
        assertEquals(new FaceGateway.FaceMatch(null,true,"SPOOF_DETECTED","req-test"),gateway.identify(image));
    }
    @Test void rejectsBadOrOversizedUploadBeforeCallingAi() {
        assertThrows(ResponseStatusException.class,() -> gateway.identify(new MockMultipartFile("file","empty.png","image/png",new byte[0])));
        assertThrows(ResponseStatusException.class,() -> gateway.identify(new MockMultipartFile("file","bad.png","image/png","not-an-image".getBytes())));
        assertThrows(ResponseStatusException.class,() -> gateway.identify(new MockMultipartFile("file","big.png","image/png",new byte[5*1024*1024+1])));
        server.verify();
    }
    @Test void extractionRejectsMissingOrInvalidVectors() {
        for (String json : new String[]{"{}", extraction("[1,2]"), extraction(vector("0")),
                extraction(vector("\"0.1\"")), extraction(vector("1e308")), extraction(vector("null"))}) {
            server.reset(); answer("extract-embedding",json);
            assertThrows(ResponseStatusException.class,() -> gateway.extract(image)); server.verify();
        }
    }
    @Test void validVectorIsStoredAsJsonArray() {
        String vector = "["+String.join(",",java.util.Collections.nCopies(512,"0.1"))+"]";
        answer("extract-embedding","{\"status\":\"success\",\"reasonCode\":\"EMBEDDING_EXTRACTED\",\"requestId\":\"req-test\",\"embedding\":"+vector+"}");
        assertEquals(vector,gateway.extract(image));
    }
    @Test void aiConnectionFailureIsGenericAndFailClosed() {
        server.expect(requestTo("http://localhost:8000/api/verify-face")).andRespond(withServerError());
        var result = gateway.identify(image);
        assertNull(result.userId()); assertEquals("INVALID_AI_RESPONSE",result.reasonCode());
        assertFalse(FaceGateway.messageFor(result.reasonCode()).contains("localhost"));
    }
    private String success() {
        return """
            {"status":"success","reasonCode":"FACE_VERIFIED","requestId":"req-test","faceCount":2,
             "liveness":{"status":"PASSED","isReal":true,"liveScore":0.9},
             "recognition":{"status":"MATCHED","recognized":true,"userId":1,"gallerySize":1,"similarity":0.7,"threshold":0.5}}
            """;
    }
    private String vector(String value) { return "[" + String.join(",", java.util.Collections.nCopies(512,value)) + "]"; }
    private String extraction(String vector) {
        return "{\"status\":\"success\",\"reasonCode\":\"EMBEDDING_EXTRACTED\",\"requestId\":\"req-test\",\"embedding\":"+vector+"}";
    }
    @ParameterizedTest
    @ValueSource(strings = {"NO_FACE", "MULTIPLE_FACES", "MODEL_UNAVAILABLE", "INVALID_IMAGE", "INTERNAL_ERROR"})
    void enrollmentPreservesReasonAndRequestId(String reason) {
        answer("extract-embedding", "{\"status\":\"error\",\"reasonCode\":\""+reason+"\",\"requestId\":\"req-enroll\"}");
        var failure = assertThrows(FaceGateway.AiFailure.class, () -> gateway.extract(image));
        assertEquals(reason, failure.reasonCode()); assertEquals("req-enroll", failure.requestId());
        assertEquals(FaceGateway.statusFor(reason), failure.getStatusCode().value());
        server.verify();
    }
    @ParameterizedTest
    @ValueSource(strings = {"status", "reasonCode", "requestId"})
    void enrollmentRequiresCompleteEnvelope(String field) {
        var json = new tools.jackson.databind.ObjectMapper().readTree(extraction(vector("0.1")));
        ((tools.jackson.databind.node.ObjectNode)json).remove(field);
        answer("extract-embedding", json.toString());
        assertEquals("INVALID_AI_RESPONSE", assertThrows(FaceGateway.AiFailure.class, () -> gateway.extract(image)).reasonCode());
        server.verify();
    }
    @Test void timeoutBecomesServiceUnavailableDenial() {
        server.expect(requestTo("http://localhost:8000/api/verify-face"))
                .andRespond(request -> { throw new ResourceAccessException("private-network-detail"); });
        var result = gateway.identify(image);
        assertNull(result.userId()); assertEquals("MODEL_UNAVAILABLE", result.reasonCode());
        assertEquals(503, FaceGateway.statusFor(result.reasonCode())); server.verify();
    }
    @ParameterizedTest
    @ValueSource(strings = {"not-json", "null", "[]", "{\"status\":\"error\",\"reasonCode\":\"UNTRUSTED\",\"requestId\":\"r\"}"})
    void unreadableOrUnknownResponsesFailClosed(String json) {
        answer("verify-face", json);
        var result = gateway.identify(image);
        assertNull(result.userId()); assertEquals("INVALID_AI_RESPONSE", result.reasonCode()); server.verify();
    }
}
