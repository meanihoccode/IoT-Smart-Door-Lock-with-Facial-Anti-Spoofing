package com.example.btl_iot.config;

import com.example.btl_iot.repository.AdminAccountRepository;
import com.example.btl_iot.security.*;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.*;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.*;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

@Configuration
public class SecurityConfig {
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }
    @Bean UserDetailsService adminDetails(AdminAccountRepository repository) {
        return username -> repository.findByUsername(username).map(AdminPrincipal::from)
                .orElseThrow(() -> new UsernameNotFoundException("Invalid credentials"));
    }
    @Bean AuthenticationManager authenticationManager(UserDetailsService details, PasswordEncoder encoder) {
        var provider = new DaoAuthenticationProvider(details);
        provider.setPasswordEncoder(encoder);
        return new ProviderManager(provider);
    }
    @Bean SecurityFilterChain securityFilterChain(HttpSecurity http, AdminAccountRepository admins,
            AdminSessionPolicy sessions, com.example.btl_iot.repository.SecurityAuditRepository audits) throws Exception {
        http
            .csrf(csrf -> csrf.csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
            .requestCache(cache -> cache.disable())
            .formLogin(form -> form.disable())
            .httpBasic(basic -> basic.disable())
            .logout(logout -> logout.disable())
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.GET, "/api/auth/csrf").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/auth/login", "/api/auth/kiosk", "/api/verify-pin", "/api/verify-face").permitAll()
                .requestMatchers("/api/auth/**", "/api/admin/**", "/api/register", "/api/users", "/api/users/**", "/api/overview").hasRole("ADMIN")
                .anyRequest().denyAll())
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((req, res, ex) -> {
                    res.setStatus(401); res.setContentType("application/json;charset=UTF-8");
                    res.getWriter().write("{\"status\":\"error\",\"message\":\"Vui lòng đăng nhập.\"}");
                })
                .accessDeniedHandler((req, res, ex) -> {
                    res.setStatus(403); res.setContentType("application/json;charset=UTF-8");
                    res.getWriter().write("{\"status\":\"error\",\"message\":\"Không được phép hoặc phiên bảo mật đã hết hạn.\"}");
                }))
            .addFilterAfter(new AdminSessionFilter(admins, sessions, audits), SecurityContextHolderFilter.class);
        return http.build();
    }
}
