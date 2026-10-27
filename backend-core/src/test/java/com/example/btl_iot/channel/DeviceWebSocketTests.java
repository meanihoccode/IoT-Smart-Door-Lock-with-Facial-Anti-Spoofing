package com.example.btl_iot.channel;

import com.example.btl_iot.entity.DoorCommand;
import com.example.btl_iot.repository.*;
import com.example.btl_iot.security.AttemptLimiter;
import com.example.btl_iot.service.ProfileService;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

// Real server, real WebSocket: the device command channel end to end (REST in, WebSocket command out, ACK back).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.config.import=", "spring.datasource.url=jdbc:h2:mem:ws;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa",
    "spring.datasource.password=", "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
    "spring.jpa.hibernate.ddl-auto=create-drop", "smartlock.pin.migrate-legacy=false",
    "smartlock.admin.username=", "smartlock.admin.password=",
    "smartlock.device.id=door-01",
    "smartlock.device.token-sha256=986769ebdbc5786f11763d3e491b8b36d2106027d196fc854fea14f2ebdddbe3",
    "smartlock.storage.alerts-dir=build/test-alerts", "server.address=127.0.0.1"
})
class DeviceWebSocketTests {
    private static final String TOKEN = "test-device-token-0123456789abcdef";
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();
    @LocalServerPort int port;
    @Autowired DeviceRepository devices;
    @Autowired DoorCommandRepository commands;
    @Autowired UserRepository users;
    @Autowired AccessLogRepository logs;
    @Autowired SecurityAuditRepository audits;
    @Autowired ProfileService profiles;
    @Autowired AttemptLimiter limiter;
    @Autowired DeviceChannel channel;

    // Collects text frames like the firmware's WStype_TEXT handler.
    static final class Door implements WebSocket.Listener {
        final BlockingQueue<String> frames = new LinkedBlockingQueue<>();
        final CompletableFuture<Integer> closed = new CompletableFuture<>();
        private final StringBuilder partial = new StringBuilder();
        WebSocket socket;
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            partial.append(data);
            if (last) { frames.add(partial.toString()); partial.setLength(0); }
            ws.request(1);
            return null;
        }
        public CompletionStage<?> onClose(WebSocket ws, int code, String reason) { closed.complete(code); return null; }
        void send(String text) { socket.sendText(text, true).join(); }
        JsonNode next(ObjectMapper json) throws Exception {
            String frame = frames.poll(10, TimeUnit.SECONDS);
            assertNotNull(frame, "no frame from Core");
            return json.readTree(frame);
        }
    }

    Door connect(String token) throws Exception {
        var door = new Door();
        door.socket = http.newWebSocketBuilder().header("Authorization", "Bearer " + token).header("X-Device-Id", "door-01")
                .connectTimeout(Duration.ofSeconds(5))
                .buildAsync(URI.create("ws://127.0.0.1:" + port + "/ws/device"), door).get(10, TimeUnit.SECONDS);
        return door;
    }

    HttpResponse<String> pin(String pin) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/device/pin"))
                .header("Authorization", "Bearer " + TOKEN).header("X-Device-Id", "door-01")
                .header("X-Request-Id", UUID.randomUUID().toString()).header("X-Request-Time", String.valueOf(Instant.now().getEpochSecond()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("pinCode", pin)))).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    boolean eventually(java.util.function.BooleanSupplier condition) throws InterruptedException {
        for (int i = 0; i < 100; i++) { if (condition.getAsBoolean()) return true; Thread.sleep(50); }
        return false;
    }

    @BeforeEach void reset() {
        limiter.clearAll(); logs.deleteAll(); commands.deleteAll(); users.deleteAll(); audits.deleteAll();
        var device = devices.findById("door-01").orElseThrow();
        device.setDesiredLockdown(false); device.setReportedLockdown(null); device.setOnline(false); devices.save(device);
    }

    @Test void handshakeWithoutValidDeviceTokenIsRejected() {
        var failure = assertThrows(ExecutionException.class, () -> connect("wrong-token"));
        assertInstanceOf(WebSocketHandshakeException.class, failure.getCause());
        assertEquals(401, ((WebSocketHandshakeException) failure.getCause()).getResponse().statusCode());
        assertThrows(ExecutionException.class, () -> http.newWebSocketBuilder()
                .buildAsync(URI.create("ws://127.0.0.1:" + port + "/ws/device"), new Door()).get(10, TimeUnit.SECONDS));
        assertFalse(channel.isConnected("door-01"));
    }

    // Firmware sends no Origin. A web page always does, so even a leaked token cannot be used from a browser tab.
    @Test void browserOriginIsRefusedEvenWithValidToken() {
        var failure = assertThrows(ExecutionException.class, () -> http.newWebSocketBuilder()
                .header("Authorization", "Bearer " + TOKEN).header("X-Device-Id", "door-01").header("Origin", "https://evil.example")
                .buildAsync(URI.create("ws://127.0.0.1:" + port + "/ws/device"), new Door()).get(10, TimeUnit.SECONDS));
        assertInstanceOf(WebSocketHandshakeException.class, failure.getCause());
        assertEquals(403, ((WebSocketHandshakeException) failure.getCause()).getResponse().statusCode());
        assertFalse(channel.isConnected("door-01"));
    }

    @Test void restDecisionCommandOverWebSocketAndAckLifecycle() throws Exception {
        profiles.create("one", "One", "123456", null, "test");
        var door = connect(TOKEN);
        assertTrue(eventually(() -> channel.isConnected("door-01") && devices.findById("door-01").orElseThrow().isOnline()));

        var response = pin("123456");
        assertEquals(202, response.statusCode(), response.body());
        var body = json.readTree(response.body());
        assertEquals("PUBLISHED", body.path("commandStatus").asText());
        var command = door.next(json);
        assertEquals("cmd", command.path("kind").asText());
        assertEquals("UNLOCK", command.path("type").asText());
        assertEquals(body.path("commandId").asText(), command.path("commandId").asText());

        String id = command.path("commandId").asText();
        door.send("{\"v\":1,\"kind\":\"ack\",\"deviceId\":\"door-01\",\"commandId\":\"" + id + "\",\"ack\":\"RECEIVED\"}");
        door.send("{\"v\":1,\"kind\":\"ack\",\"deviceId\":\"door-01\",\"commandId\":\"" + id + "\",\"ack\":\"EXECUTED\"}");
        assertTrue(eventually(() -> commands.findById(id).orElseThrow().getStatus() == DoorCommand.Status.EXECUTED));
        assertTrue(door.frames.isEmpty(), "exactly one frame per command");
    }

    @Test void deviceCannotIssueCommandsOverItsSocket() throws Exception {
        var door = connect(TOKEN);
        assertTrue(eventually(() -> channel.isConnected("door-01")));
        door.send("{\"v\":1,\"kind\":\"cmd\",\"type\":\"UNLOCK\",\"deviceId\":\"door-01\",\"commandId\":\"forged\",\"seq\":1}");
        Thread.sleep(300);
        assertEquals(0, commands.count());
        assertTrue(door.frames.isEmpty());
    }

    @Test void reconnectReplacesOldSocketAndCloseMeansOffline() throws Exception {
        var first = connect(TOKEN);
        assertTrue(eventually(() -> channel.isConnected("door-01")));
        var second = connect(TOKEN);
        assertNotNull(first.closed.get(5, TimeUnit.SECONDS), "old socket closed when the device reconnects");
        assertTrue(channel.isConnected("door-01"));
        assertTrue(devices.findById("door-01").orElseThrow().isOnline(), "closing the replaced socket must not mark offline");
        assertTrue(channel.send("door-01", "{\"v\":1,\"kind\":\"cmd\"}"));
        assertEquals("cmd", second.next(json).path("kind").asText());

        second.socket.sendClose(WebSocket.NORMAL_CLOSURE, "bye").join();
        assertTrue(eventually(() -> !devices.findById("door-01").orElseThrow().isOnline()));
        assertFalse(channel.send("door-01", "{}"));
        assertTrue(audits.findAll().stream().map(a -> a.getAction()).toList().containsAll(List.of("DEVICE_ONLINE", "DEVICE_OFFLINE")));
    }

    @Test void lockdownStatusReportedOnTheSocketIsResynced() throws Exception {
        var device = devices.findById("door-01").orElseThrow(); device.setDesiredLockdown(true); devices.save(device);
        var door = connect(TOKEN);
        door.send("{\"v\":1,\"kind\":\"status\",\"deviceId\":\"door-01\",\"lockdown\":false,\"fw\":\"test\"}");
        var resync = door.next(json);
        assertEquals("LOCKDOWN_ON", resync.path("type").asText());
    }
}
