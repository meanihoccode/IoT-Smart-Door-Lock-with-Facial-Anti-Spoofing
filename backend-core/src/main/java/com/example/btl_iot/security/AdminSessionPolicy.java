package com.example.btl_iot.security;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class AdminSessionPolicy {
    private static final String START = "smartlock.admin.started", LAST = "smartlock.admin.lastActivity";
    private final Clock clock;
    private final Duration idle, absolute;
    @Autowired
    public AdminSessionPolicy(@Value("${smartlock.admin.idle-timeout:15m}") Duration idle,
                              @Value("${smartlock.admin.absolute-timeout:4h}") Duration absolute) {
        this(idle, absolute, Clock.systemUTC());
    }
    public AdminSessionPolicy(Duration idle, Duration absolute, Clock clock) {
        if (idle.isNegative() || idle.isZero() || absolute.isNegative() || absolute.isZero())
            throw new IllegalArgumentException("Session timeouts must be positive");
        this.idle = idle; this.absolute = absolute; this.clock = clock;
    }
    public void start(HttpSession session) {
        long now = clock.millis(); session.setAttribute(START, now); session.setAttribute(LAST, now);
    }
    public String expiry(HttpSession session) {
        if (session == null || !(session.getAttribute(START) instanceof Long start)
                || !(session.getAttribute(LAST) instanceof Long last)) return "SESSION_EXPIRED";
        long now = clock.millis();
        if (now - start >= absolute.toMillis()) return "SESSION_ABSOLUTE_EXPIRED";
        return now - last >= idle.toMillis() ? "SESSION_IDLE_EXPIRED" : null;
    }
    public void touch(HttpSession session, String path) {
        // Public Kiosk, CSRF and status checks must not extend admin activity.
        if (path.equals("/api/overview") || path.equals("/api/register") || path.equals("/api/users")
                || path.startsWith("/api/users/") || path.startsWith("/api/admin/")
                || path.equals("/api/auth/password")) session.setAttribute(LAST, clock.millis());
    }
}
