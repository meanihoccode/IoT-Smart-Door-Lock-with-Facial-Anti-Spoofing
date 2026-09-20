package com.example.btl_iot;

import com.example.btl_iot.entity.User;
import com.example.btl_iot.repository.*;
import com.example.btl_iot.security.AttemptLimiter;
import com.example.btl_iot.service.*;
import com.example.btl_iot.mqtt.MqttPublisher;
import java.net.*;
import java.net.http.*;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.mockito.Mockito.*;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

// Real HTTP filter chain; isolated database; no MQTT connection or door commands.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.config.import=", "spring.datasource.url=jdbc:h2:mem:auth;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa",
    "spring.datasource.password=", "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
    "spring.jpa.hibernate.ddl-auto=create-drop", "smartlock.mqtt.enabled=false",
    "smartlock.pin.migrate-legacy=false",
    "smartlock.admin.username=testadmin", "smartlock.admin.password=Test-only-password-123!",
    "server.address=127.0.0.1", "server.servlet.session.cookie.secure=false"
})
class BtlIotApplicationTests {
    private static final String PASSWORD = "Test-only-password-123!";
    @LocalServerPort int port;
    @Autowired AdminAccountRepository admins;
    @Autowired UserRepository users;
    @Autowired SecurityAuditRepository audits;
    @Autowired PasswordEncoder encoder;
    @Autowired AttemptLimiter limiter;
    @Autowired AccessLogRepository accessLogs;
    @Autowired ProfileService profiles;
    @Autowired LegacyPinMigration migration;
    @MockitoBean MqttPublisher mqtt;
    @MockitoBean FaceGateway faces;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach void reset() {
        limiter.clearAll();
        var admin = admins.findByUsername("testadmin").orElseThrow();
        admin.setPasswordHash(encoder.encode(PASSWORD));
        admin.setEnabled(true); admin.invalidateSessions(); admins.save(admin);
        audits.deleteAll(); accessLogs.deleteAll(); users.deleteAll();
        org.mockito.Mockito.reset(mqtt, faces);
        when(mqtt.sendOpenDoorCommand()).thenReturn(true);
    }
    class Browser {
        final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        final HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).build();
        HttpResponse<String> get(String path) throws Exception {
            return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api"+path)).GET().build(), HttpResponse.BodyHandlers.ofString());
        }
        String csrf() throws Exception { return json.readTree(get("/auth/csrf").body()).get("token").asText(); }
        String sessionId() { return cookies.getCookieStore().getCookies().stream().filter(c -> c.getName().equals("JSESSIONID")).findFirst().orElseThrow().getValue(); }
        HttpResponse<String> post(String path, Object body, String csrf) throws Exception {
            return request("POST", path, body, csrf);
        }
        HttpResponse<String> request(String method, String path, Object body, String csrf) throws Exception {
            var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api"+path)).header("Content-Type","application/json");
            if (csrf != null) builder.header("X-CSRF-TOKEN", csrf);
            return client.send(builder.method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
        }
        HttpResponse<String> multipart(String path, Map<String,String> fields, boolean image) throws Exception {
            String boundary = "test-form-boundary";
            var body = new StringBuilder();
            fields.forEach((name,value) -> body.append("--").append(boundary).append("\r\nContent-Disposition: form-data; name=\"").append(name).append("\"\r\n\r\n").append(value).append("\r\n"));
            if (image) body.append("--").append(boundary).append("\r\nContent-Disposition: form-data; name=\"file\"; filename=\"test.png\"\r\nContent-Type: image/png\r\n\r\nstub-image\r\n");
            body.append("--").append(boundary).append("--\r\n");
            return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api"+path))
                .header("X-CSRF-TOKEN",csrf()).header("Content-Type","multipart/form-data; boundary="+boundary)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.ofString());
        }
        HttpResponse<String> pin(String username, String pin) throws Exception {
            return post("/verify-pin",Map.of("username",username,"pinCode",pin),csrf());
        }
        HttpResponse<String> login(String name, String password) throws Exception {
            return post("/auth/login", Map.of("username",name,"password",password), csrf());
        }
        void login() throws Exception { assertEquals(200,login("testadmin",PASSWORD).statusCode()); }
    }
    @Test void anonymousCannotReadAdminDataOrRegister() throws Exception {
        var browser = new Browser();
        for (String path : new String[]{"/auth/me","/users","/overview"}) assertEquals(401,browser.get(path).statusCode(),path);
        assertEquals(401,browser.post("/register",Map.of(),browser.csrf()).statusCode());
        assertEquals(0,users.count());
    }
    @Test void csrfRequiredEvenForLoginAndKiosk() throws Exception {
        var browser = new Browser();
        for (String path : new String[]{"/auth/login","/verify-pin","/verify-face","/register"})
            assertEquals(403,browser.post(path,Map.of(),null).statusCode(),path);
    }
    @Test void loginRotatesSessionAndCsrfAndLogoutRevokesSession() throws Exception {
        var browser = new Browser();
        String oldToken = browser.csrf(), oldSession = browser.sessionId();
        browser.login();
        assertNotEquals(oldSession,browser.sessionId());
        for (String path : new String[]{"/auth/me","/users","/overview"}) assertEquals(200,browser.get(path).statusCode());
        assertFalse(browser.get("/auth/me").body().contains("password"));
        assertEquals(403,browser.post("/auth/logout",Map.of(),oldToken).statusCode());
        assertEquals(200,browser.post("/auth/logout",Map.of(),browser.csrf()).statusCode());
        assertEquals(401,browser.get("/auth/me").statusCode());
        assertTrue(audits.findAll().stream().anyMatch(a -> a.getAction().equals("LOGOUT")));
    }
    @Test void csrfCookieHasBrowserSecurityFlags() throws Exception {
        var response = new Browser().get("/auth/csrf");
        String cookie = response.headers().firstValue("set-cookie").orElseThrow();
        assertTrue(cookie.toLowerCase().contains("httponly"));
        assertTrue(cookie.toLowerCase().contains("samesite=strict"));
        assertTrue(response.headers().firstValue("cache-control").orElse("").contains("no-store"));
    }
    @Test void profilePinIsNotAnAdminCredential() throws Exception {
        var profile = new User(); profile.setUsername("resident"); profile.setPinCode("123456"); users.save(profile);
        assertEquals(401,new Browser().login("resident","123456").statusCode());
        assertEquals(1,admins.count());
        assertNotEquals(PASSWORD,admins.findByUsername("testadmin").orElseThrow().getPasswordHash());
    }
    @Test void badCredentialsHaveGenericMessageAndRateLimit() throws Exception {
        var browser = new Browser();
        String missing = browser.login("missing","wrong").body();
        for (int i=0;i<5;i++) {
            var response = browser.login("testadmin","wrong");
            assertEquals(401,response.statusCode()); assertEquals(missing,response.body());
        }
        var blocked = browser.login("TESTADMIN",PASSWORD);
        assertEquals(429,blocked.statusCode());
        assertEquals("300",blocked.headers().firstValue("retry-after").orElseThrow());
        String auditJson = json.writeValueAsString(audits.findAll());
        assertFalse(auditJson.contains(PASSWORD)); assertFalse(auditJson.contains("wrong"));
    }
    @Test void passwordChangeChecksCurrentAndStrengthThenInvalidatesOtherSessions() throws Exception {
        var first = new Browser(); var second = new Browser(); first.login(); second.login();
        assertEquals(400,first.post("/auth/password",Map.of("currentPassword",PASSWORD,"newPassword","short"),first.csrf()).statusCode());
        assertEquals(400,first.post("/auth/password",Map.of("currentPassword","wrong","newPassword","New-test-password-456!"),first.csrf()).statusCode());
        assertEquals(400,first.post("/auth/password",Map.of("currentPassword",PASSWORD,"newPassword","á".repeat(37)),first.csrf()).statusCode());
        assertEquals(200,first.post("/auth/password",Map.of("currentPassword",PASSWORD,"newPassword","New-test-password-456!"),first.csrf()).statusCode());
        assertEquals(401,first.get("/auth/me").statusCode());
        assertEquals(401,second.get("/users").statusCode());
        assertEquals(401,first.login("testadmin",PASSWORD).statusCode());
        assertEquals(200,first.login("testadmin","New-test-password-456!").statusCode());
    }
    @Test void disablingAccountInvalidatesSessionAndPreventsLogin() throws Exception {
        var browser = new Browser(); browser.login();
        var admin = admins.findByUsername("testadmin").orElseThrow(); admin.setEnabled(false); admins.save(admin);
        assertEquals(401,browser.get("/auth/me").statusCode());
        assertEquals(401,browser.login("testadmin",PASSWORD).statusCode());
    }
    @Test void unknownEndpointsAreDeniedEvenForAdmin() throws Exception {
        var browser = new Browser(); browser.login();
        assertEquals(403,browser.get("/unexpected").statusCode());
    }

    @Test void adminCreatesPinOnlyProfileWithoutCallingAiOrExposingSecrets() throws Exception {
        var browser = new Browser(); browser.login();
        var response = browser.multipart("/register",Map.of("username","NV001","fullName","Nguyễn Văn A","pinCode","001234"),false);
        assertEquals(201,response.statusCode(),response.body());
        var user = users.findByUsername("nv001").orElseThrow();
        assertTrue(encoder.matches("001234",user.getPinHash())); assertNull(user.getPinCode());
        assertTrue(user.isEnabled()); assertNull(user.getFaceEmbedding()); assertEquals(1,admins.count());
        verifyNoInteractions(faces, mqtt);
        String listing = browser.get("/users").body();
        for (String forbidden : new String[]{"pinHash","pinCode","faceEmbedding","001234"}) assertFalse(listing.contains(forbidden));
        String entityJson = json.writeValueAsString(user);
        assertFalse(entityJson.contains("pinHash")); assertFalse(entityJson.contains("pinCode"));
    }
    @Test void duplicateCodeAndInvalidInputDoNotCreateProfiles() throws Exception {
        var browser = new Browser(); browser.login();
        profiles.create("nv001","First","123456",null,"testadmin");
        assertEquals(409,browser.multipart("/register",Map.of("username","NV001","fullName","Duplicate","pinCode","654321"),false).statusCode());
        for (String pin : new String[]{"12345","12345678901","abc123","12 3456"})
            assertEquals(400,browser.multipart("/register",Map.of("username","nv002","fullName","Second","pinCode",pin),false).statusCode());
        assertEquals(1,users.count()); verifyNoInteractions(faces,mqtt);
    }
    @Test void pairIdentifiesCorrectPersonEvenIfPinsAreTheSame() throws Exception {
        var first = profiles.create("first","First","001234",null,"testadmin");
        var second = profiles.create("second","Second","001234",null,"testadmin");
        assertNotEquals(users.findById(first.id()).orElseThrow().getPinHash(),users.findById(second.id()).orElseThrow().getPinHash());
        var browser = new Browser();
        assertEquals(200,browser.pin("FIRST","001234").statusCode());
        assertEquals(first.id(),accessLogs.findTop5ByOrderByAccessTimeDesc().get(0).getUser().getId());
        assertEquals(200,browser.pin("second","001234").statusCode());
        assertEquals(second.id(),accessLogs.findTop5ByOrderByAccessTimeDesc().get(0).getUser().getId());
        assertEquals(401,browser.pin("second","123456").statusCode());
        assertEquals(400,browser.post("/verify-pin",Map.of("pinCode","001234"),browser.csrf()).statusCode());
        verify(mqtt,times(2)).sendOpenDoorCommand();
    }
    @Test void pinFailureIsGenericAndLocksAfterThreeTriesWithoutBlockingOtherProfile() throws Exception {
        profiles.create("one","One","123456",null,"testadmin");
        profiles.create("two","Two","654321",null,"testadmin");
        var browser = new Browser();
        String missing = browser.pin("missing","123456").body();
        for (int i=0;i<3;i++) {
            var response = browser.pin("one","999999");
            assertEquals(401,response.statusCode()); assertEquals(missing,response.body());
        }
        assertEquals(429,browser.pin("ONE","123456").statusCode());
        assertTrue(audits.findAll().stream().anyMatch(a -> a.getAction().equals("PIN_THROTTLED")));
        assertEquals(200,browser.pin("two","654321").statusCode()); verify(mqtt,times(1)).sendOpenDoorCommand();
        assertFalse(json.writeValueAsString(audits.findAll()).contains("999999"));
    }
    @Test void ipLimitPreventsGuessingAcrossManyCodes() throws Exception {
        var browser = new Browser();
        for (int i=0;i<20;i++) assertEquals(401,browser.pin("missing"+i,"123456").statusCode());
        assertEquals(429,browser.pin("another","123456").statusCode()); verifyNoInteractions(mqtt);
    }
    @Test void profileMutationRequiresAdminAndCsrfAndRejectsStaleVersion() throws Exception {
        var profile = profiles.create("one","One","123456",null,"testadmin");
        var browser = new Browser();
        var body = Map.of("fullName","Renamed","enabled",false,"version",profile.version());
        assertEquals(401,browser.request("PATCH","/users/"+profile.id(),body,browser.csrf()).statusCode());
        assertEquals(401,browser.request("PUT","/users/"+profile.id()+"/pin",Map.of("pinCode","654321","version",profile.version()),browser.csrf()).statusCode());
        browser.login();
        assertEquals(403,browser.request("PATCH","/users/"+profile.id(),body,null).statusCode());
        assertEquals(403,browser.request("PUT","/users/"+profile.id()+"/pin",Map.of("pinCode","654321","version",profile.version()),null).statusCode());
        assertEquals(200,browser.request("PATCH","/users/"+profile.id(),body,browser.csrf()).statusCode());
        assertEquals(409,browser.request("PATCH","/users/"+profile.id(),body,browser.csrf()).statusCode());
        assertEquals(404,browser.request("PATCH","/users/999999",body,browser.csrf()).statusCode());
        assertEquals(400,browser.request("PATCH","/users/"+profile.id(),Map.of("fullName"," ","enabled",true,"version",profile.version()),browser.csrf()).statusCode());
    }
    @Test void revokedProfileCannotUsePinOrFaceAndHistoryRemains() throws Exception {
        var profile = profiles.create("one","One","123456","[1]","testadmin");
        var browser = new Browser(); assertEquals(200,browser.pin("one","123456").statusCode());
        var revoked = profiles.update(profile.id(),"One",false,profile.version(),"testadmin");
        assertEquals(401,browser.pin("one","123456").statusCode());
        when(faces.identify(any())).thenReturn(new FaceGateway.FaceMatch(profile.id(),false));
        assertEquals(401,browser.multipart("/verify-face",Map.of(),true).statusCode());
        assertEquals(1,users.count()); assertEquals(3,accessLogs.count());
        verify(mqtt,times(1)).sendOpenDoorCommand();
        profiles.update(profile.id(),"One",true,revoked.version(),"testadmin");
        assertEquals(200,browser.pin("one","123456").statusCode());
    }
    @Test void pinResetInvalidatesOldPinAndDoesNotReenableRevokedProfile() throws Exception {
        var profile = profiles.create("one","One","123456",null,"testadmin");
        var browser = new Browser(); browser.login();
        var response = browser.request("PUT","/users/"+profile.id()+"/pin",Map.of("pinCode","654321","version",profile.version()),browser.csrf());
        assertEquals(200,response.statusCode());
        assertEquals(401,browser.pin("one","123456").statusCode());
        assertEquals(200,browser.pin("one","654321").statusCode());
        var fresh = profiles.list().get(0);
        var revoked = profiles.update(fresh.id(),"One",false,fresh.version(),"testadmin");
        var reset = profiles.resetPin(revoked.id(),"111222",revoked.version(),"testadmin");
        assertFalse(reset.enabled()); assertEquals(401,browser.pin("one","111222").statusCode());
    }
    @Test void legacyPinNeverAuthenticatesAndMigrationIsIdempotent() throws Exception {
        var valid = new User(); valid.setUsername("old"); valid.setPinCode("001234"); users.saveAndFlush(valid);
        var invalid = new User(); invalid.setUsername("invalid"); invalid.setPinCode("1234"); invalid.setEnabled(false); users.saveAndFlush(invalid);
        var modern = new User(); modern.setUsername("modern"); modern.setPinCode("111111"); modern.setPinHash(encoder.encode("654321")); users.saveAndFlush(modern);
        var browser = new Browser(); assertEquals(401,browser.pin("old","001234").statusCode());
        var result = migration.migrate();
        assertEquals(new LegacyPinMigration.Summary(1,1,1),result);
        assertEquals(new LegacyPinMigration.Summary(0,0,0),migration.migrate());
        assertTrue(users.findLegacyPinIds().isEmpty());
        assertFalse(users.findByUsername("invalid").orElseThrow().isEnabled());
        assertNull(users.findByUsername("invalid").orElseThrow().getPinHash());
        assertEquals(200,browser.pin("old","001234").statusCode());
        assertEquals(200,browser.pin("modern","654321").statusCode());
        assertEquals(401,browser.pin("modern","111111").statusCode());
    }
    @Test void faceMustBelongToExistingEnabledProfileWithEnrollment() throws Exception {
        var profile = profiles.create("one","One","123456",null,"testadmin");
        var browser = new Browser();
        for (var match : new FaceGateway.FaceMatch[]{new FaceGateway.FaceMatch(null,false),new FaceGateway.FaceMatch(999999L,false),new FaceGateway.FaceMatch(profile.id(),false),new FaceGateway.FaceMatch(profile.id(),true)}) {
            when(faces.identify(any())).thenReturn(match);
            assertEquals(Long.valueOf(999999L).equals(match.userId()) ? 502 : 401,
                    browser.multipart("/verify-face",Map.of(),true).statusCode());
        }
        verifyNoInteractions(mqtt);
        var user = users.findById(profile.id()).orElseThrow(); user.setFaceEmbedding("[1]"); users.saveAndFlush(user);
        when(faces.identify(any())).thenReturn(new FaceGateway.FaceMatch(profile.id(),false));
        assertEquals(200,browser.multipart("/verify-face",Map.of(),true).statusCode());
        verify(mqtt).sendOpenDoorCommand();
    }
    @Test void brokerFailureNeverReportsDoorOpened() throws Exception {
        profiles.create("one","One","123456",null,"testadmin");
        when(mqtt.sendOpenDoorCommand()).thenReturn(false);
        var response = new Browser().pin("one","123456");
        assertEquals(503,response.statusCode()); assertTrue(response.body().contains("error"));
        assertEquals("COMMAND_FAILED",accessLogs.findTop5ByOrderByAccessTimeDesc().get(0).getStatus());
    }
    @Test void aiFailureDoesNotCreatePartialProfileOrSendOpenCommand() throws Exception {
        var browser = new Browser(); browser.login();
        when(faces.extract(any())).thenThrow(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_GATEWAY,"AI unavailable"));
        assertEquals(502,browser.multipart("/register",Map.of("username","one","fullName","One","pinCode","123456"),true).statusCode());
        assertEquals(0,users.count()); verifyNoInteractions(mqtt);
    }

    @Test void aiReasonAndCorrelationSurviveHttpAndAreAudited() throws Exception {
        when(faces.identify(any())).thenReturn(FaceGateway.FaceMatch.rejected("MODEL_UNAVAILABLE", "request-http-test"));
        var response = new Browser().multipart("/verify-face",Map.of(),true);
        assertEquals(503,response.statusCode());
        var body = json.readTree(response.body());
        assertEquals("MODEL_UNAVAILABLE",body.path("reasonCode").asText());
        assertEquals("request-http-test",body.path("requestId").asText());
        assertEquals("MODEL_UNAVAILABLE",accessLogs.findTop5ByOrderByAccessTimeDesc().get(0).getStatus());
        verifyNoInteractions(mqtt);
    }

    @Test void enrollmentErrorAdvicePreservesAiReasonWithoutCreatingProfile() throws Exception {
        var browser = new Browser(); browser.login();
        when(faces.extract(any())).thenThrow(new FaceGateway.AiFailure("MULTIPLE_FACES","request-enroll-test"));
        var response = browser.multipart("/register",Map.of("username","one","fullName","One","pinCode","123456"),true);
        assertEquals(422,response.statusCode());
        var body = json.readTree(response.body());
        assertEquals("MULTIPLE_FACES",body.path("reasonCode").asText());
        assertEquals("request-enroll-test",body.path("requestId").asText());
        assertEquals(0,users.count()); verifyNoInteractions(mqtt);
    }

}
