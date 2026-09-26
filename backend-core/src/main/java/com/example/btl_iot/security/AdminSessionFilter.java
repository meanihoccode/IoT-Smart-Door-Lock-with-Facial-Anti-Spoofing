package com.example.btl_iot.security;

import com.example.btl_iot.repository.AdminAccountRepository;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class AdminSessionFilter extends OncePerRequestFilter {
    private final AdminAccountRepository admins;
    private final AdminSessionPolicy policy;
    private final com.example.btl_iot.repository.SecurityAuditRepository audits;
    public AdminSessionFilter(AdminAccountRepository admins, AdminSessionPolicy policy,
            com.example.btl_iot.repository.SecurityAuditRepository audits) {
        this.admins = admins; this.policy = policy; this.audits = audits;
    }
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AdminPrincipal principal) {
            var current = admins.findById(principal.id());
            var session = request.getSession(false);
            String reason = policy.expiry(session);
            if (current.isEmpty() || !current.get().isEnabled()
                    || current.get().getCredentialVersion() != principal.version()) reason = "SESSION_REVOKED";
            if (reason != null) {
                if (session != null) session.invalidate();
                SecurityContextHolder.clearContext();
                audits.save(new com.example.btl_iot.entity.SecurityAudit(principal.getUsername(),reason,null));
            } else policy.touch(session, request.getServletPath());
        }
        chain.doFilter(request, response);
    }
}
