package com.example.btl_iot;

import com.example.btl_iot.entity.DoorCommand;
import com.example.btl_iot.entity.User;
import com.example.btl_iot.repository.*;
import com.example.btl_iot.security.AttemptLimiter;
import com.example.btl_iot.service.*;
import com.example.btl_iot.channel.DeviceMessageHandler;
import com.example.btl_iot.channel.DeviceChannel;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.*;
import java.net.http.*;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.mockito.Mockito.*;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

// Real HTTP filter chain; isolated database; device channel mocked; inbound device messages fed to the handler.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.config.import=", "spring.datasource.url=jdbc:h2:mem:auth;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa",
    "spring.datasource.password=", "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "smartlock.pin.migrate-legacy=false",
    "smartlock.admin.username=testadmin", "smartlock.admin.password=Test-only-password-123!",
    "smartlock.device.id=door-01",
    "smartlock.device.token-sha256=986769ebdbc5786f11763d3e491b8b36d2106027d196fc854fea14f2ebdddbe3",
    "smartlock.storage.alerts-dir=build/test-alerts",
    "server.address=127.0.0.1", "server.servlet.session.cookie.secure=false"
})
class BtlIotApplicationTests {
    private static final String PASSWORD = "Test-only-password-123!";
    private static final String DEVICE_TOKEN = "test-device-token-0123456789abcdef";
    @LocalServerPort int port;
    @Autowired AdminAccountRepository admins;
    @Autowired UserRepository users;
    @Autowired SecurityAuditRepository audits;
    @Autowired PasswordEncoder encoder;
    @Autowired AttemptLimiter limiter;
    @Autowired AccessLogRepository accessLogs;
    @Autowired ProfileService profiles;
    @Autowired LegacyPinMigration migration;
    @Autowired DeviceRepository devices;
    @Autowired DoorCommandRepository commands;
    @Autowired DeviceRequestRepository deviceRequests;
    @Autowired CommandService commandService;
    @Autowired DeviceMessageHandler inbound;
    @Autowired DeviceStateService deviceState;
    @Autowired SecurityAlertRepository alerts;
    @MockitoBean DeviceChannel channel;
    @MockitoBean FaceGateway faces;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach void reset() {
        limiter.clearAll();
        var admin = admins.findByUsername("testadmin").orElseThrow();
        admin.setPasswordHash(encoder.encode(PASSWORD));
        admin.setEnabled(true); admin.invalidateSessions(); admins.save(admin);
        audits.deleteAll(); accessLogs.deleteAll(); users.deleteAll(); commands.deleteAll(); deviceRequests.deleteAll();
        alerts.deleteAll();
        var device = devices.findById("door-01").orElseThrow();
        device.setDesiredLockdown(false); device.setReportedLockdown(null); device.setOnline(false);
        device.setEnabled(true); devices.save(device);
        org.mockito.Mockito.reset(channel, faces);
        when(channel.send(anyString(), anyString())).thenReturn(true);
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
        HttpResponse<String> login(String name, String password) throws Exception {
            return post("/auth/login", Map.of("username",name,"password",password), csrf());
        }
        void login() throws Exception { assertEquals(200,login("testadmin",PASSWORD).statusCode()); }
    }
    // Simulated ESP32: bearer token + single-use request ID + request time, no cookies.
    class Door {
        final HttpClient client = HttpClient.newHttpClient();
        String token = DEVICE_TOKEN;
        HttpRequest.Builder base(String path, String requestId, long time) {
            return HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/device"+path))
                .header("Authorization","Bearer "+token).header("X-Device-Id","door-01")
                .header("X-Request-Id",requestId).header("X-Request-Time",String.valueOf(time));
        }
        HttpResponse<String> pin(String pin) throws Exception { return pin(pin, UUID.randomUUID().toString(), Instant.now().getEpochSecond()); }
        HttpResponse<String> pin(String pin, String requestId, long time) throws Exception {
            return client.send(base("/pin",requestId,time).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("pinCode",pin)))).build(), HttpResponse.BodyHandlers.ofString());
        }
        HttpResponse<String> face() throws Exception { return face(UUID.randomUUID().toString()); }
        HttpResponse<String> face(String requestId) throws Exception {
            return upload("/face", requestId, Map.of(), "face.jpg", "stub-image".getBytes());
        }
        HttpResponse<String> upload(String path, String requestId, Map<String,String> fields, String filename, byte[] file) throws Exception {
            String boundary = "door-boundary";
            var out = new ByteArrayOutputStream();
            for (var field : fields.entrySet())
                out.writeBytes(("--"+boundary+"\r\nContent-Disposition: form-data; name=\""+field.getKey()+"\"\r\n\r\n"+field.getValue()+"\r\n").getBytes());
            out.writeBytes(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\""+filename+"\"\r\nContent-Type: image/png\r\n\r\n").getBytes());
            out.writeBytes(file);
            out.writeBytes(("\r\n--"+boundary+"--\r\n").getBytes());
            return client.send(base(path,requestId,Instant.now().getEpochSecond()).header("Content-Type","multipart/form-data; boundary="+boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())).build(), HttpResponse.BodyHandlers.ofString());
        }
    }
    String commandId(HttpResponse<String> response) throws Exception { return json.readTree(response.body()).path("commandId").asText(); }
    void ack(String commandId, String ack) {
        inbound.handle("door-01", "{\"v\":1,\"kind\":\"ack\",\"deviceId\":\"door-01\",\"commandId\":\""+commandId+"\",\"ack\":\""+ack+"\"}");
    }
    DoorCommand.Status status(String commandId) { return commands.findById(commandId).orElseThrow().getStatus(); }
    static byte[] png() throws Exception {
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(4,4,BufferedImage.TYPE_INT_RGB),"png",bytes);
        return bytes.toByteArray();
    }

    @Test void anonymousCannotReadAdminDataOrRegister() throws Exception {
        var browser = new Browser();
        for (String path : new String[]{"/auth/me","/users","/overview","/admin/door","/lockdown/status","/security-alerts"})
            assertEquals(401,browser.get(path).statusCode(),path);
        assertEquals(401,browser.post("/register",Map.of(),browser.csrf()).statusCode());
        assertEquals(0,users.count());
    }
    @Test void csrfRequiredForBrowserWrites() throws Exception {
        var browser = new Browser();
        for (String path : new String[]{"/auth/login","/register","/admin/door/open","/lockdown/toggle"})
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
        verifyNoInteractions(faces, channel);
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
        assertEquals(1,users.count()); verifyNoInteractions(faces,channel);
    }

    // D02/D04: the browser kiosk has no unlock route, even when it calls the API directly with a valid CSRF token.
    @Test void browserCannotUnlockByFaceOrPinEvenWithCsrf() throws Exception {
        profiles.create("one","One","123456","[1]","testadmin");
        when(faces.identify(any())).thenReturn(new FaceGateway.FaceMatch(users.findAll().get(0).getId(),false));
        var browser = new Browser();
        assertFalse(browser.multipart("/verify-face",Map.of(),true).statusCode() < 300);
        assertFalse(browser.post("/verify-pin",Map.of("pinCode","123456"),browser.csrf()).statusCode() < 300);
        assertEquals(401,browser.multipart("/device/face",Map.of(),true).statusCode());
        browser.login();
        assertFalse(browser.multipart("/device/face",Map.of(),true).statusCode() < 300);
        verifyNoInteractions(faces, channel);
        assertEquals(0,commands.count());
    }
    @Test void deviceAuthRequiresMatchingTokenAndIsRateLimited() throws Exception {
        var door = new Door(); door.token = "wrong-token";
        assertEquals(401,door.pin("123456").statusCode());
        assertTrue(audits.findAll().stream().anyMatch(a -> a.getAction().equals("DEVICE_AUTH_FAILED")));
        var device = devices.findById("door-01").orElseThrow(); device.setEnabled(false); devices.save(device);
        door.token = DEVICE_TOKEN;
        assertEquals(401,door.pin("123456").statusCode());
        device.setEnabled(true); devices.save(device);
        door.token = "wrong-token";
        for (int i=0;i<18;i++) door.pin("123456");
        door.token = DEVICE_TOKEN;
        assertEquals(429,door.pin("123456").statusCode());
        verifyNoInteractions(channel);
    }
    @Test void devicePinWithoutUsernameCreatesExactlyOneCommandAndNeverClaimsDoorOpened() throws Exception {
        var profile = profiles.create("first","First","001234",null,"testadmin");
        var response = new Door().pin("001234");
        assertEquals(202,response.statusCode(),response.body());
        var body = json.readTree(response.body());
        assertEquals("GRANTED",body.path("decision").asText()); assertEquals("PUBLISHED",body.path("commandStatus").asText());
        assertFalse(response.body().toLowerCase().contains("đã mở cửa"));
        var command = commands.findById(body.path("commandId").asText()).orElseThrow();
        assertEquals(DoorCommand.Type.UNLOCK,command.getType()); assertEquals(profile.id(),command.getUserId());
        var payload = ArgumentCaptor.forClass(String.class);
        verify(channel,times(1)).send(eq("door-01"),payload.capture());
        var published = json.readTree(payload.getValue());
        assertEquals(command.getId(),published.path("commandId").asText()); assertEquals(command.getSeq(),published.path("seq").asLong());
        assertEquals(1,published.path("v").asInt()); assertTrue(published.path("expiresAt").asLong() > published.path("issuedAt").asLong());
        assertFalse(payload.getValue().contains("001234"));
        var log = accessLogs.findTop5ByOrderByAccessTimeDesc().get(0);
        assertEquals("COMMAND_PUBLISHED",log.getStatus()); assertEquals(command.getId(),log.getCommandId());
        assertTrue(new Door().pin("001234").statusCode() == 202);
        assertTrue(commands.findAll().stream().mapToLong(DoorCommand::getSeq).distinct().count() == 2);
    }
    @Test void pinSharedByTwoProfilesIsRefusedAndAudited() throws Exception {
        profiles.create("first","First","001234",null,"testadmin");
        profiles.create("second","Second","001234",null,"testadmin");
        assertEquals(401,new Door().pin("001234").statusCode());
        assertTrue(audits.findAll().stream().anyMatch(a -> a.getAction().equals("PIN_AMBIGUOUS")));
        verifyNoInteractions(channel);
    }
    @Test void pinFailuresAreGenericAndLimitedPerDevice() throws Exception {
        profiles.create("one","One","123456",null,"testadmin");
        var door = new Door();
        String first = door.pin("999999").body();
        for (int i=0;i<4;i++) {
            var response = door.pin(i == 0 ? "12" : "99999"+i);
            assertEquals(401,response.statusCode()); assertEquals(json.readTree(first).path("message"),json.readTree(response.body()).path("message"));
        }
        assertEquals(429,door.pin("123456").statusCode());
        assertTrue(audits.findAll().stream().anyMatch(a -> a.getAction().equals("PIN_THROTTLED")));
        assertFalse(json.writeValueAsString(audits.findAll()).contains("999999"));
        verifyNoInteractions(channel);
    }
    @Test void replayedOrStaleRequestsNeverReachAiOrCreateCommands() throws Exception {
        profiles.create("one","One","123456",null,"testadmin");
        var door = new Door();
        String requestId = UUID.randomUUID().toString();
        long now = Instant.now().getEpochSecond();
        assertEquals(202,door.pin("123456",requestId,now).statusCode());
        assertEquals(409,door.pin("123456",requestId,now).statusCode());
        assertEquals(400,door.pin("123456",UUID.randomUUID().toString(),now - 600).statusCode());
        assertEquals(400,door.pin("123456","short",now).statusCode());
        assertEquals(1,commands.count());
        when(faces.identify(any())).thenReturn(FaceGateway.FaceMatch.rejected("NOT_RECOGNIZED","ai-1"));
        String faceId = UUID.randomUUID().toString();
        door.face(faceId);
        assertEquals(409,door.face(faceId).statusCode());
        verify(faces,times(1)).identify(any());
    }
    @Test void revokedProfileCannotUsePinOrFaceAndHistoryRemains() throws Exception {
        var profile = profiles.create("one","One","123456","[1]","testadmin");
        var door = new Door(); assertEquals(202,door.pin("123456").statusCode());
        var revoked = profiles.update(profile.id(),"One",false,profile.version(),"testadmin");
        assertEquals(401,door.pin("123456").statusCode());
        when(faces.identify(any())).thenReturn(new FaceGateway.FaceMatch(profile.id(),false));
        assertEquals(401,door.face().statusCode());
        assertEquals(1,users.count()); assertEquals(3,accessLogs.count());
        verify(channel,times(1)).send(anyString(),anyString());
        profiles.update(profile.id(),"One",true,revoked.version(),"testadmin");
        assertEquals(202,door.pin("123456").statusCode());
    }
    @Test void pinResetInvalidatesOldPinAndDoesNotReenableRevokedProfile() throws Exception {
        var profile = profiles.create("one","One","123456",null,"testadmin");
        var browser = new Browser(); browser.login();
        var response = browser.request("PUT","/users/"+profile.id()+"/pin",Map.of("pinCode","654321","version",profile.version()),browser.csrf());
        assertEquals(200,response.statusCode());
        var door = new Door();
        assertEquals(401,door.pin("123456").statusCode());
        assertEquals(202,door.pin("654321").statusCode());
        var fresh = profiles.list().get(0);
        var revoked = profiles.update(fresh.id(),"One",false,fresh.version(),"testadmin");
        var reset = profiles.resetPin(revoked.id(),"111222",revoked.version(),"testadmin");
        assertFalse(reset.enabled()); assertEquals(401,door.pin("111222").statusCode());
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
    @Test void legacyPinNeverAuthenticatesAndMigrationIsIdempotent() throws Exception {
        var valid = new User(); valid.setUsername("old"); valid.setPinCode("001234"); users.saveAndFlush(valid);
        var invalid = new User(); invalid.setUsername("invalid"); invalid.setPinCode("1234"); invalid.setEnabled(false); users.saveAndFlush(invalid);
        var modern = new User(); modern.setUsername("modern"); modern.setPinCode("111111"); modern.setPinHash(encoder.encode("654321")); users.saveAndFlush(modern);
        var door = new Door(); assertEquals(401,door.pin("001234").statusCode());
        var result = migration.migrate();
        assertEquals(new LegacyPinMigration.Summary(1,1,1),result);
        assertEquals(new LegacyPinMigration.Summary(0,0,0),migration.migrate());
        assertTrue(users.findLegacyPinIds().isEmpty());
        assertFalse(users.findByUsername("invalid").orElseThrow().isEnabled());
        assertNull(users.findByUsername("invalid").orElseThrow().getPinHash());
        assertEquals(202,door.pin("001234").statusCode());
        assertEquals(202,door.pin("654321").statusCode());
        assertEquals(401,door.pin("111111").statusCode());
    }
    @Test void faceMustBelongToExistingEnabledProfileWithEnrollment() throws Exception {
        var profile = profiles.create("one","One","123456",null,"testadmin");
        var door = new Door();
        for (var match : new FaceGateway.FaceMatch[]{new FaceGateway.FaceMatch(null,false),new FaceGateway.FaceMatch(999999L,false),new FaceGateway.FaceMatch(profile.id(),false),new FaceGateway.FaceMatch(profile.id(),true)}) {
            when(faces.identify(any())).thenReturn(match);
            assertEquals(Long.valueOf(999999L).equals(match.userId()) ? 502 : 401, door.face().statusCode());
        }
        verifyNoInteractions(channel);
        var user = users.findById(profile.id()).orElseThrow(); user.setFaceEmbedding("[1]"); users.saveAndFlush(user);
        when(faces.identify(any())).thenReturn(new FaceGateway.FaceMatch(profile.id(),false));
        var granted = door.face();
        assertEquals(202,granted.statusCode());
        assertEquals("FACE_VERIFIED",json.readTree(granted.body()).path("reasonCode").asText());
        verify(channel,times(1)).send(eq("door-01"),anyString());
    }
    @Test void commandChannelFailureNeverReportsDoorOpened() throws Exception {
        profiles.create("one","One","123456",null,"testadmin");
        when(channel.send(anyString(),anyString())).thenReturn(false);
        var response = new Door().pin("123456");
        assertEquals(503,response.statusCode()); assertTrue(response.body().contains("COMMAND_FAILED"));
        assertEquals("COMMAND_FAILED",accessLogs.findTop5ByOrderByAccessTimeDesc().get(0).getStatus());
        assertEquals(DoorCommand.Status.FAILED,status(commandId(response)));
    }

    // Contract: PUBLISHED -> RECEIVED -> EXECUTED, never backwards; ACK identity is the topic's device.
    @Test void ackLifecycleIsMonotonicAndBoundToTheDeviceTopic() throws Exception {
        profiles.create("one","One","123456",null,"testadmin");
        String id = commandId(new Door().pin("123456"));
        inbound.handle("door-02", "{\"v\":1,\"kind\":\"ack\",\"commandId\":\""+id+"\",\"ack\":\"EXECUTED\"}");
        assertEquals(DoorCommand.Status.PUBLISHED,status(id));
        assertTrue(audits.findAll().stream().anyMatch(a -> a.getAction().equals("ACK_UNKNOWN_COMMAND")));
        inbound.handle("door-01", "{\"v\":1,\"kind\":\"ack\",\"deviceId\":\"door-02\",\"commandId\":\""+id+"\",\"ack\":\"EXECUTED\"}");
        assertEquals(DoorCommand.Status.PUBLISHED,status(id));
        ack(id,"RECEIVED"); assertEquals(DoorCommand.Status.RECEIVED,status(id));
        ack(id,"EXECUTED"); assertEquals(DoorCommand.Status.EXECUTED,status(id));
        ack(id,"RECEIVED"); ack(id,"REJECTED"); assertEquals(DoorCommand.Status.EXECUTED,status(id));
        assertEquals("COMMAND_EXECUTED",accessLogs.findTop5ByOrderByAccessTimeDesc().get(0).getStatus());
        verify(channel,times(1)).send(anyString(),anyString());
    }
    @Test void missingAckBecomesUnknownAndIsNeverResent() throws Exception {
        var past = Instant.now().minusSeconds(60);
        var stale = new DoorCommand(UUID.randomUUID().toString(),"door-01",1,DoorCommand.Type.UNLOCK,past,past.plusSeconds(15));
        commands.saveAndFlush(stale);
        commands.transition(stale.getId(),java.util.EnumSet.of(DoorCommand.Status.PENDING),DoorCommand.Status.PUBLISHED,null,Instant.now());
        var unpublished = new DoorCommand(UUID.randomUUID().toString(),"door-01",2,DoorCommand.Type.UNLOCK,past,past.plusSeconds(15));
        commands.saveAndFlush(unpublished);
        commandService.expireStale();
        assertEquals(DoorCommand.Status.UNKNOWN,status(stale.getId()));
        assertEquals(DoorCommand.Status.FAILED,status(unpublished.getId()));
        assertEquals(DoorCommand.Status.FAILED,commandService.dispatch(unpublished.getId()).getStatus());
        verifyNoInteractions(channel);
        ack(stale.getId(),"EXECUTED");
        assertEquals(DoorCommand.Status.EXECUTED,status(stale.getId()));
    }
    @Test void lockdownIsPersistedBlocksUnlockAndIsAppliedOnlyAfterDeviceAck() throws Exception {
        profiles.create("one","One","123456",null,"testadmin");
        var browser = new Browser(); browser.login();
        var toggled = json.readTree(browser.post("/lockdown/toggle",Map.of("enabled",true),browser.csrf()).body());
        assertTrue(toggled.path("lockdown").asBoolean()); assertFalse(toggled.path("lockdownApplied").asBoolean());
        assertTrue(devices.findById("door-01").orElseThrow().isDesiredLockdown());
        assertEquals(403,new Door().pin("123456").statusCode());
        when(faces.identify(any())).thenReturn(new FaceGateway.FaceMatch(users.findAll().get(0).getId(),false));
        assertEquals(403,new Door().face().statusCode());
        verify(faces,never()).identify(any());
        String lockId = toggled.path("commandId").asText();
        assertEquals(DoorCommand.Type.LOCKDOWN_ON,commands.findById(lockId).orElseThrow().getType());
        inbound.handle("door-01", "{\"v\":1,\"kind\":\"status\",\"online\":true,\"lockdown\":false,\"fw\":\"test\"}");
        // Device came online disagreeing: a fresh lockdown command is NOT stacked while the first is in flight.
        verify(channel,times(1)).send(eq("door-01"),anyString());
        ack(lockId,"EXECUTED");
        var status = json.readTree(browser.get("/lockdown/status").body());
        assertTrue(status.path("lockdownApplied").asBoolean()); assertEquals("ONLINE",status.path("connection").asText());
        deviceState.onStatus("door-01", false, null, null); // what DeviceSocketHandler does when the socket closes
        assertFalse(json.readTree(browser.get("/lockdown/status").body()).path("lockdownApplied").asBoolean());
        assertEquals("OFFLINE",json.readTree(new Browser().get("/door-status").body()).path("connection").asText());
    }
    @Test void reconnectingDeviceWithStaleLockdownGetsResynced() throws Exception {
        var device = devices.findById("door-01").orElseThrow(); device.setDesiredLockdown(true); devices.save(device);
        inbound.handle("door-01", "{\"v\":1,\"kind\":\"status\",\"online\":true,\"lockdown\":false}");
        var payload = ArgumentCaptor.forClass(String.class);
        verify(channel).send(eq("door-01"),payload.capture());
        assertEquals("LOCKDOWN_ON",json.readTree(payload.getValue()).path("type").asText());
        inbound.handle("door-01", "{\"v\":1,\"kind\":\"status\",\"online\":true,\"lockdown\":true}");
        verify(channel,times(1)).send(anyString(),anyString());
    }
    @Test void remoteOpenRequiresAdminSessionAndIsAudited() throws Exception {
        var browser = new Browser();
        assertEquals(401,browser.post("/admin/door/open",Map.of(),browser.csrf()).statusCode());
        browser.login();
        var response = browser.post("/admin/door/open",Map.of(),browser.csrf());
        assertEquals(202,response.statusCode(),response.body());
        var command = commands.findById(commandId(response)).orElseThrow();
        assertEquals("REMOTE_ADMIN",command.getMethod()); assertEquals("testadmin",command.getActor());
        assertTrue(audits.findAll().stream().anyMatch(a -> a.getAction().equals("REMOTE_OPEN_REQUESTED")));
        var door = json.readTree(browser.get("/admin/door").body());
        assertEquals(command.getId(),door.path("commands").get(0).path("commandId").asText());
    }
    @Test void alertsNeedDeviceAuthAndPhotosAreAdminOnly() throws Exception {
        var browser = new Browser();
        assertEquals(401,browser.multipart("/device/alerts",Map.of("alertType","SECURITY_ALERT"),true).statusCode());
        var created = new Door().upload("/alerts",UUID.randomUUID().toString(),Map.of("alertType","WRONG_PIN_3_TIMES","description","test"),"x.png",png());
        assertEquals(201,created.statusCode(),created.body());
        String url = alerts.findAll().get(0).getImageUrl();
        assertTrue(url.startsWith("/api/security-alerts/images/"));
        String path = url.substring("/api".length());
        assertEquals(401,browser.get(path).statusCode());
        assertEquals(401,browser.get("/security-alerts").statusCode());
        browser.login();
        assertEquals(200,browser.get(path).statusCode());
        assertNotEquals(200,browser.get("/security-alerts/images/..%2F..%2Fapplication.properties").statusCode());
        assertEquals(400,new Door().upload("/alerts",UUID.randomUUID().toString(),Map.of("alertType","<script>"),"x.png",png()).statusCode());
    }
    @Test void anonymousDoorStatusHasNoPersonalData() throws Exception {
        profiles.create("one","Nguyễn Văn A","123456",null,"testadmin");
        new Door().pin("123456");
        var body = new Browser().get("/door-status").body();
        assertFalse(body.contains("Nguyễn")); assertFalse(body.contains("one")); assertTrue(body.contains("UNLOCK"));
    }
    @Test void aiFailureDoesNotCreatePartialProfileOrSendOpenCommand() throws Exception {
        var browser = new Browser(); browser.login();
        when(faces.extract(any())).thenThrow(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_GATEWAY,"AI unavailable"));
        assertEquals(502,browser.multipart("/register",Map.of("username","one","fullName","One","pinCode","123456"),true).statusCode());
        assertEquals(0,users.count()); verifyNoInteractions(channel);
    }
    @Test void kioskHandoffRevokesAdminAndRequiresCsrf() throws Exception {
        var browser = new Browser(); browser.login();
        assertEquals(403,browser.post("/auth/kiosk",Map.of(),null).statusCode());
        assertEquals(200,browser.get("/auth/me").statusCode());
        assertEquals(200,browser.post("/auth/kiosk",Map.of(),browser.csrf()).statusCode());
        assertEquals(401,browser.get("/auth/me").statusCode());
        assertEquals(401,browser.get("/admin/security-audits").statusCode());
        assertTrue(audits.findAll().stream().anyMatch(a -> a.getAction().equals("KIOSK_HANDOFF")));
        assertEquals(200,browser.post("/auth/kiosk",Map.of(),browser.csrf()).statusCode());
    }
    @Test void auditIsAdminOnlyReadOnlyAndDoesNotExposeSecrets() throws Exception {
        var browser = new Browser(); assertEquals(401,browser.get("/admin/security-audits").statusCode());
        browser.login();
        var response = browser.get("/admin/security-audits"); assertEquals(200,response.statusCode());
        var body = json.readTree(response.body()); assertTrue(body.path("totalElements").asLong() > 0);
        for (String secret : new String[]{PASSWORD,"passwordHash","pinHash","JSESSIONID","faceEmbedding"}) assertFalse(response.body().contains(secret));
        assertEquals(405,browser.post("/admin/security-audits",Map.of(),browser.csrf()).statusCode());
    }
    @Test void auditSupportsExactFiltersStablePagesAndInclusiveDates() throws Exception {
        var browser = new Browser(); browser.login();
        for (int i=0;i<3;i++) audits.save(new com.example.btl_iot.entity.SecurityAudit("profile-admin","PROFILE_UPDATED",String.valueOf(i)));
        String today = java.time.LocalDate.now().toString();
        String filter = "/admin/security-audits?actor=profile-admin&action=PROFILE_UPDATED&from="+today+"&to="+today+"&size=2";
        var first = json.readTree(browser.get(filter).body()); var second = json.readTree(browser.get(filter+"&page=1").body());
        assertEquals(3,first.path("totalElements").asInt()); assertEquals(2,first.path("items").size());
        assertEquals(1,second.path("items").size());
        assertNotEquals(first.path("items").get(0).path("id"),second.path("items").get(0).path("id"));
        assertEquals(0,json.readTree(browser.get("/admin/security-audits?actor=missing").body()).path("totalElements").asInt());
    }
    @Test void invalidAuditFiltersAreRejected() throws Exception {
        var browser = new Browser(); browser.login();
        for (String query : new String[]{"page=-1","size=101","size=0","from=bad","from=2026-02-02&to=2026-02-01","action=invalid"})
            assertEquals(400,browser.get("/admin/security-audits?"+query).statusCode(),query);
    }

    @Test void aiReasonAndCorrelationSurviveHttpAndAreAudited() throws Exception {
        when(faces.identify(any())).thenReturn(FaceGateway.FaceMatch.rejected("MODEL_UNAVAILABLE", "request-http-test"));
        var response = new Door().face();
        assertEquals(503,response.statusCode());
        var body = json.readTree(response.body());
        assertEquals("MODEL_UNAVAILABLE",body.path("reasonCode").asText());
        assertEquals("request-http-test",body.path("aiRequestId").asText());
        assertEquals("MODEL_UNAVAILABLE",accessLogs.findTop5ByOrderByAccessTimeDesc().get(0).getStatus());
        verifyNoInteractions(channel);
    }

    @Test void enrollmentErrorAdvicePreservesAiReasonWithoutCreatingProfile() throws Exception {
        var browser = new Browser(); browser.login();
        when(faces.extract(any())).thenThrow(new FaceGateway.AiFailure("MULTIPLE_FACES","request-enroll-test"));
        var response = browser.multipart("/register",Map.of("username","one","fullName","One","pinCode","123456"),true);
        assertEquals(422,response.statusCode());
        var body = json.readTree(response.body());
        assertEquals("MULTIPLE_FACES",body.path("reasonCode").asText());
        assertEquals("request-enroll-test",body.path("requestId").asText());
        assertEquals(0,users.count()); verifyNoInteractions(channel);
    }

}
