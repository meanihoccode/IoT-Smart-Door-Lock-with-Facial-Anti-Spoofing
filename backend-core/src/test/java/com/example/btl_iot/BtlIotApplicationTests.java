package com.example.btl_iot;

import com.example.btl_iot.entity.User;
import com.example.btl_iot.repository.*;
import com.example.btl_iot.security.AttemptLimiter;
import java.net.*;
import java.net.http.*;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

// Real HTTP filter chain; isolated database; no MQTT connection or door commands.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.config.import=", "spring.datasource.url=jdbc:h2:mem:auth;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa",
    "spring.datasource.password=", "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
    "spring.jpa.hibernate.ddl-auto=create-drop", "smartlock.mqtt.enabled=false",
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
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach void reset() {
        limiter.clearAll();
        var admin = admins.findByUsername("testadmin").orElseThrow();
        admin.setPasswordHash(encoder.encode(PASSWORD));
        admin.setEnabled(true); admin.invalidateSessions(); admins.save(admin);
        audits.deleteAll(); users.deleteAll();
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
            var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api"+path)).header("Content-Type","application/json");
            if (csrf != null) builder.header("X-CSRF-TOKEN", csrf);
            return client.send(builder.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
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

}
