package com.example.btl_iot.service;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

// Latest face photo per device, kept in memory only (lost on restart) and served only to admins.
// Lets the admin see what the door camera actually captured and what the cloud decided.
@Component
public class LastCaptureStore {
    public record Capture(long seq, byte[] image, String contentType, Instant capturedAt,
                          String reasonCode, String message, String commandId, String person) {}
    private final Map<String, Capture> captures = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong();
    private final Clock clock;
    public LastCaptureStore(Clock clock) { this.clock = clock; }

    public void save(String deviceId, MultipartFile file, String reasonCode, String message, String commandId,
                     String person) {
        try {
            String type = file.getContentType() == null ? "image/jpeg" : file.getContentType();
            if (!type.equals("image/png")) type = "image/jpeg";
            captures.put(deviceId, new Capture(seq.incrementAndGet(), file.getBytes(), type, clock.instant(),
                    reasonCode, message, commandId, person));
        } catch (IOException ignored) {
            // Preview only; never affects the access decision.
        }
    }

    public Capture latest(String deviceId) { return captures.get(deviceId); }
}
