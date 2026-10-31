package com.example.btl_iot.channel;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

// One live WebSocket per device (Exclusive Pair). true from send() means the frame was handed to the
// connection, not that the device received or executed it; that is what ACKs are for.
@Component
public class DeviceChannel {
    private static final Logger log = LoggerFactory.getLogger(DeviceChannel.class);
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    void register(String deviceId, WebSocketSession raw) {
        WebSocketSession session = new ConcurrentWebSocketSessionDecorator(raw, 5000, 64 * 1024);
        WebSocketSession previous = sessions.put(deviceId, session);
        // A device has exactly one connection; a reconnect replaces a half-open old one.
        if (previous != null && !previous.getId().equals(raw.getId())) close(previous, CloseStatus.POLICY_VIOLATION.withReason("replaced"));
    }

    // Returns false when this session was already replaced, so the caller must not mark the device offline.
    boolean unregister(String deviceId, WebSocketSession raw) {
        return sessions.computeIfPresent(deviceId, (id, current) -> current.getId().equals(raw.getId()) ? null : current) == null;
    }

    public boolean isConnected(String deviceId) {
        WebSocketSession session = sessions.get(deviceId);
        return session != null && session.isOpen();
    }

    public boolean send(String deviceId, String json) {
        WebSocketSession session = sessions.get(deviceId);
        if (session == null || !session.isOpen()) {
            log.warn("event=command_not_sent reason=device_not_connected device={}", deviceId);
            return false;
        }
        try {
            session.sendMessage(new TextMessage(json));
            return true;
        } catch (IOException | IllegalStateException e) {
            log.warn("event=command_not_sent reason=send_failed device={} error={}", deviceId, e.getClass().getSimpleName());
            return false;
        }
    }

    private static void close(WebSocketSession session, CloseStatus status) {
        try { session.close(status); } catch (IOException ignored) { }
    }
}
