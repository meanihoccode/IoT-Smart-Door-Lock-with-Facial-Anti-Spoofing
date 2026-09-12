package com.example.btl_iot.security;

import com.example.btl_iot.repository.AdminAccountRepository;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class AdminSessionFilter extends OncePerRequestFilter {
    private final AdminAccountRepository admins;
    public AdminSessionFilter(AdminAccountRepository admins) { this.admins = admins; }
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AdminPrincipal principal) {
            var current = admins.findById(principal.id());
            if (current.isEmpty() || !current.get().isEnabled()
                    || current.get().getCredentialVersion() != principal.version()) {
                var session = request.getSession(false);
                if (session != null) session.invalidate();
                SecurityContextHolder.clearContext();
            }
        }
        chain.doFilter(request, response);
    }
}
