package com.example.btl_iot.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

// What the public kiosk may show about recent door activity, kept in memory only.
// Names are masked (D11 follow-up, option 2): "Vương Quốc Hưng" -> "V*** Q*** Hưng". No images, no IDs.
@Component
public class KioskFeed {
    public record Attempt(long seq, String method, String reasonCode, String person, String commandId, Instant at) {}
    private final Map<String, Instant> motion = new ConcurrentHashMap<>();
    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong();
    private final Clock clock;
    public KioskFeed(Clock clock) { this.clock = clock; }

    public void motion(String deviceId) { motion.put(deviceId, clock.instant()); }

    public void attempt(String deviceId, String method, String reasonCode, String fullName, String commandId) {
        attempts.put(deviceId, new Attempt(seq.incrementAndGet(), method, reasonCode, mask(fullName), commandId, clock.instant()));
    }

    public Instant lastMotion(String deviceId) { return motion.get(deviceId); }
    public Attempt lastAttempt(String deviceId) { return attempts.get(deviceId); }

    // Every word but the last becomes its first letter + "***"; a single word keeps only its first letter.
    public static String mask(String fullName) {
        if (fullName == null || fullName.isBlank()) return null;
        String[] words = fullName.strip().split("\\s+");
        var out = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            boolean keep = i == words.length - 1 && words.length > 1;
            if (i > 0) out.append(' ');
            out.append(keep ? words[i] : words[i].substring(0, words[i].offsetByCodePoints(0, 1)) + "***");
        }
        return out.length() > 60 ? out.substring(0, 60) : out.toString();
    }
}
