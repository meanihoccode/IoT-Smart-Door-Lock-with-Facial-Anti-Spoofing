package com.example.btl_iot.security;

import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class AttemptLimiterTests {
    static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
    @Test void windowExpiresAndClearResetsOnlyOneBucket() {
        var clock = new MutableClock(); var limiter = new AttemptLimiter(clock);
        for (int i=0;i<3;i++) limiter.consume("one",3,Duration.ofMinutes(5));
        assertThrows(ResponseStatusException.class,() -> limiter.consume("one",3,Duration.ofMinutes(5)));
        limiter.consume("two",1,Duration.ofMinutes(5)); limiter.clear("one");
        assertEquals(1,limiter.consume("one",3,Duration.ofMinutes(5)));
        assertThrows(ResponseStatusException.class,() -> limiter.consume("two",1,Duration.ofMinutes(5)));
        clock.now = clock.now.plusSeconds(300);
        assertEquals(1,limiter.consume("two",1,Duration.ofMinutes(5)));
    }
    @Test void concurrentRequestsCannotConsumeMoreThanTheLimit() throws Exception {
        var limiter = new AttemptLimiter(); var accepted = new AtomicInteger();
        var start = new CountDownLatch(1); var pool = Executors.newFixedThreadPool(12);
        try {
            var tasks = new java.util.ArrayList<Future<?>>();
            for (int i=0;i<30;i++) tasks.add(pool.submit(() -> {
                try { start.await(); limiter.consume("same",3,Duration.ofMinutes(5)); accepted.incrementAndGet(); }
                catch (ResponseStatusException expected) { assertEquals(429,expected.getStatusCode().value()); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new RuntimeException(ex); }
            }));
            start.countDown(); for (var task : tasks) task.get(10,TimeUnit.SECONDS);
            assertEquals(3,accepted.get());
        } finally { pool.shutdownNow(); }
    }
}
