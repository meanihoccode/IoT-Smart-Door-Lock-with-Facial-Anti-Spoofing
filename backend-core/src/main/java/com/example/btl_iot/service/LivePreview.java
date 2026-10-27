package com.example.btl_iot.service;

import com.example.btl_iot.channel.DeviceChannel;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

// Live camera preview for admins. Frames are display-only: they never reach the AI or create a command.
// The device streams only while an admin keeps asking (preview window renewed every few seconds).
@Component
public class LivePreview {
    public record Frame(byte[] jpeg, Instant at) {}
    static final int MAX_FRAME_BYTES = 100 * 1024;
    static final Duration FRESH = Duration.ofSeconds(3);
    static final Duration RENEW = Duration.ofSeconds(5);
    static final long WINDOW_MS = 15000;
    private final Map<String, Frame> frames = new ConcurrentHashMap<>();
    private final Map<String, Instant> requested = new ConcurrentHashMap<>();
    private final DeviceChannel channel;
    private final Clock clock;
    public LivePreview(DeviceChannel channel, Clock clock) { this.channel = channel; this.clock = clock; }

    // Called by the admin view; asks the device to (keep) streaming, at most once per RENEW.
    public Frame latest(String deviceId) {
        Instant now = clock.instant(), last = requested.get(deviceId);
        if ((last == null || last.plus(RENEW).isBefore(now))
                && channel.send(deviceId, "{\"v\":1,\"kind\":\"preview\",\"ms\":" + WINDOW_MS + "}"))
            requested.put(deviceId, now);
        Frame frame = frames.get(deviceId);
        return frame != null && frame.at().plus(FRESH).isAfter(now) ? frame : null;
    }

    // Only a JPEG from the authenticated device session is kept.
    public boolean accept(String deviceId, byte[] data) {
        if (data.length < 4 || data.length > MAX_FRAME_BYTES || (data[0] & 0xFF) != 0xFF || (data[1] & 0xFF) != 0xD8)
            return false;
        frames.put(deviceId, new Frame(data, clock.instant()));
        return true;
    }

    public void forget(String deviceId) { frames.remove(deviceId); requested.remove(deviceId); }
}
