package com.example.btl_iot.channel;

import com.example.btl_iot.security.DevicePrincipal;
import com.example.btl_iot.service.DeviceStateService;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

// wss://<domain>/ws/device. The handshake already passed DeviceAuthFilter (bearer token), so the session
// principal is the verified device; nothing in a message body can change which device it speaks for.
@Component
public class DeviceSocketHandler extends TextWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(DeviceSocketHandler.class);
    private final DeviceChannel channel;
    private final DeviceMessageHandler messages;
    private final DeviceStateService devices;

    public DeviceSocketHandler(DeviceChannel channel, DeviceMessageHandler messages, DeviceStateService devices) {
        this.channel = channel; this.messages = messages; this.devices = devices;
    }

    static String deviceId(WebSocketSession session) {
        return session.getPrincipal() instanceof Authentication auth && auth.getPrincipal() instanceof DevicePrincipal device
                ? device.deviceId() : null;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws IOException {
        String deviceId = deviceId(session);
        if (deviceId == null) { session.close(CloseStatus.POLICY_VIOLATION); return; }
        channel.register(deviceId, session);
        // Online now; lockdown stays "unknown" until the device reports it in its status message.
        devices.onStatus(deviceId, true, null, null);
        log.info("event=device_connected device={}", deviceId);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String deviceId = deviceId(session);
        if (deviceId != null) messages.handle(deviceId, message.getPayload());
    }

    // Equivalent of an MQTT last will: a dropped socket means offline, never "lockdown applied".
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String deviceId = deviceId(session);
        if (deviceId != null && channel.unregister(deviceId, session)) {
            devices.onStatus(deviceId, false, null, null);
            log.info("event=device_disconnected device={} code={}", deviceId, status.getCode());
        }
    }
}
