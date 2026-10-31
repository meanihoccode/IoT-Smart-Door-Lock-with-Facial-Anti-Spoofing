package com.example.btl_iot.channel;

import com.example.btl_iot.entity.SecurityAudit;
import com.example.btl_iot.repository.SecurityAuditRepository;
import com.example.btl_iot.service.CommandService;
import com.example.btl_iot.service.DeviceStateService;
import com.example.btl_iot.service.KioskFeed;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// Device → Core messages: {"v":1,"kind":"ack|event|status",...}. deviceId comes from the authenticated
// WebSocket session; a payload deviceId that disagrees is rejected. Devices can never send "cmd".
@Component
public class DeviceMessageHandler {
    private static final Logger log = LoggerFactory.getLogger(DeviceMessageHandler.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, String> AUDITED_EVENTS = Map.of(
            "KEYPAD_LOCKOUT", "INTRUDER_WRONG_PIN_3_TIMES",
            "FACE_FAIL_STREAK", "INTRUDER_ALERT",
            "BOOT", "DEVICE_BOOT");
    private final CommandService commands;
    private final DeviceStateService devices;
    private final SecurityAuditRepository audits;
    private final KioskFeed kiosk;
    // Only suppresses duplicate audits after a resend; bounded and per Core instance.
    private final Set<String> recentEvents = Collections.synchronizedSet(Collections.newSetFromMap(new LinkedHashMap<>() {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) { return size() > 1000; }
    }));

    public DeviceMessageHandler(CommandService commands, DeviceStateService devices, SecurityAuditRepository audits,
                                KioskFeed kiosk) {
        this.commands = commands; this.devices = devices; this.audits = audits; this.kiosk = kiosk;
    }

    public void handle(String deviceId, String payload) {
        if (payload.length() > 2048) return;
        JsonNode body;
        try { body = JSON.readTree(payload); }
        catch (JacksonException e) { log.warn("event=device_message_invalid_json device={}", deviceId); return; }
        if (!body.isObject() || body.path("v").asInt(0) != 1) return;
        if (body.has("deviceId") && !deviceId.equals(body.path("deviceId").asText())) {
            audits.save(new SecurityAudit(deviceId, "DEVICE_ID_MISMATCH", null));
            return;
        }
        switch (body.path("kind").asText("")) {
            case "ack" -> ack(deviceId, body);
            case "status" -> status(deviceId, body);
            case "event" -> event(deviceId, body);
            default -> { }
        }
    }

    private void ack(String deviceId, JsonNode body) {
        CommandService.Ack ack;
        try { ack = CommandService.Ack.valueOf(body.path("ack").asText()); }
        catch (IllegalArgumentException e) { return; }
        String commandId = body.path("commandId").isString() ? body.path("commandId").asText() : null;
        String reason = body.path("reason").isString() ? body.path("reason").asText() : null;
        commands.applyAck(deviceId, commandId, ack, reason);
        devices.touch(deviceId);
    }

    private void status(String deviceId, JsonNode body) {
        // The open socket is the online signal; a status frame on it can only report lockdown/firmware.
        Boolean lockdown = body.path("lockdown").isBoolean() ? body.path("lockdown").asBoolean() : null;
        String firmware = body.path("fw").isString() ? body.path("fw").asText() : null;
        var resync = devices.onStatus(deviceId, true, lockdown, firmware);
        if (resync != null) commands.dispatch(resync.getId());
    }

    private void event(String deviceId, JsonNode body) {
        devices.touch(deviceId);
        String eventId = body.path("eventId").asText("");
        if (!eventId.isEmpty() && !recentEvents.add(deviceId + ":" + eventId)) return;
        // PIR saw someone: the kiosk shows "analysing" until the face result arrives.
        if ("PIR_MOTION".equals(body.path("type").asText(""))) kiosk.motion(deviceId);
        String action = AUDITED_EVENTS.get(body.path("type").asText(""));
        if (action != null) audits.save(new SecurityAudit(deviceId, action, null));
    }
}
