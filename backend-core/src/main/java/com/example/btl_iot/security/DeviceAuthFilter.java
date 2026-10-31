package com.example.btl_iot.security;

import com.example.btl_iot.entity.SecurityAudit;
import com.example.btl_iot.repository.DeviceRepository;
import com.example.btl_iot.repository.SecurityAuditRepository;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;

// Authorization: Bearer <token> plus X-Device-Id. Stateless; no session or CSRF cookie is created.
public class DeviceAuthFilter extends OncePerRequestFilter {
    private final DeviceRepository devices;
    private final AttemptLimiter limiter;
    private final SecurityAuditRepository audits;
    public DeviceAuthFilter(DeviceRepository devices, AttemptLimiter limiter, SecurityAuditRepository audits) {
        this.devices = devices; this.limiter = limiter; this.audits = audits;
    }
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String ipKey = "device-auth:ip:" + request.getRemoteAddr();
        try { limiter.check(ipKey, 20); }
        catch (ResponseStatusException ex) { reject(response, 429, "Thử quá nhiều lần."); return; }
        String id = request.getHeader("X-Device-Id"), header = request.getHeader("Authorization");
        String token = header != null && header.startsWith("Bearer ") ? header.substring(7).trim() : null;
        var device = DeviceTokens.validId(id) && token != null && token.length() <= 256 ? devices.findById(id).orElse(null) : null;
        if (device == null || !device.isEnabled() || !DeviceTokens.matches(token, device.getTokenHash())) {
            limiter.record(ipKey, Duration.ofMinutes(5));
            audits.save(new SecurityAudit("DEVICE_AUTH", "DEVICE_AUTH_FAILED", DeviceTokens.validId(id) ? id : null));
            reject(response, 401, "Thiết bị chưa được xác thực.");
            return;
        }
        var auth = UsernamePasswordAuthenticationToken.authenticated(new DevicePrincipal(device.getId()), null,
                List.of(new SimpleGrantedAuthority("ROLE_DEVICE")));
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        try { chain.doFilter(request, response); }
        finally { SecurityContextHolder.clearContext(); }
    }
    private static void reject(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status); response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"status\":\"error\",\"message\":\"" + message + "\"}");
    }
}
