package com.example.btl_iot.security;

import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.time.Duration;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;

// Single-server limiter: bounded memory, server-derived IP, independent account/IP buckets.
@Component
public class AttemptLimiter {
    private record Bucket(int count, long expiresAt) {}
    private final Map<String, Bucket> buckets = new HashMap<>();
    private final Clock clock;
    public AttemptLimiter() { this(Clock.systemUTC()); }
    public AttemptLimiter(Clock clock) { this.clock = clock; }
    public synchronized int consume(String key, int limit, Duration window) {
        check(key, limit);
        record(key, window);
        return buckets.get(key).count;
    }
    public synchronized void check(String key, int limit) {
        Bucket bucket = buckets.get(key);
        if (bucket != null && bucket.expiresAt > clock.millis() && bucket.count >= limit)
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Thử quá nhiều lần. Vui lòng chờ rồi thử lại.");
    }
    public synchronized void record(String key, Duration window) {
        long now = clock.millis();
        buckets.entrySet().removeIf(entry -> entry.getValue().expiresAt <= now);
        Bucket previous = buckets.get(key);
        if (previous == null && buckets.size() >= 10000)
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Hệ thống đang bận.");
        buckets.put(key, previous == null ? new Bucket(1, now + window.toMillis())
                : new Bucket(previous.count + 1, previous.expiresAt));
    }
    public synchronized void clear(String key) { buckets.remove(key); }
    public synchronized void clearAll() { buckets.clear(); }
}
