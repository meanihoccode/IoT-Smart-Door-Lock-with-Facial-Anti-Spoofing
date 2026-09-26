package com.example.btl_iot.controller;

import com.example.btl_iot.entity.SecurityAudit;
import com.example.btl_iot.repository.*;
import com.example.btl_iot.security.*;
import jakarta.servlet.http.*;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.*;
import org.springframework.security.core.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    public record Login(String username, String password) {}
    public record PasswordChange(String currentPassword, String newPassword) {}
    private final AuthenticationManager manager;
    private final AdminAccountRepository admins;
    private final SecurityAuditRepository audits;
    private final AttemptLimiter limiter;
    private final PasswordEncoder encoder;
    private final AdminSessionPolicy sessions;
    public AuthController(AuthenticationManager manager, AdminAccountRepository admins, SecurityAuditRepository audits,
                          AttemptLimiter limiter, PasswordEncoder encoder, AdminSessionPolicy sessions) {
        this.manager=manager; this.admins=admins; this.audits=audits; this.limiter=limiter; this.encoder=encoder;
        this.sessions=sessions;
    }
    @GetMapping("/csrf")
    public Map<String,String> csrf(CsrfToken token) {
        return Map.of("token", token.getToken(), "headerName", token.getHeaderName());
    }
    @PostMapping("/login")
    public Map<String,String> login(@RequestBody Login body, HttpServletRequest request, HttpServletResponse response) {
        InputRules.username(body.username());
        if (body.password() == null || body.password().length() > 256) InputRules.bad("Thông tin đăng nhập không hợp lệ.");
        String accountKey="login:user:"+body.username().toLowerCase(java.util.Locale.ROOT), ipKey="login:ip:"+request.getRemoteAddr();
        limiter.consume(ipKey,20,Duration.ofMinutes(5));
        limiter.consume(accountKey,5,Duration.ofMinutes(5));
        Authentication auth;
        try {
            auth = manager.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(body.username(),body.password()));
        } catch (AuthenticationException ex) {
            audits.save(new SecurityAudit(body.username(),"LOGIN_FAILED",null));
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Tên đăng nhập hoặc mật khẩu không đúng.");
        }
        new ChangeSessionIdAuthenticationStrategy().onAuthentication(auth,request,response);
        new CsrfAuthenticationStrategy(new HttpSessionCsrfTokenRepository()).onAuthentication(auth,request,response);
        var context=SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        new HttpSessionSecurityContextRepository().saveContext(context,request,response);
        sessions.start(request.getSession());
        limiter.clear(accountKey);
        audits.save(new SecurityAudit(auth.getName(),"LOGIN_SUCCESS",null));
        return Map.of("username",auth.getName(),"role","ADMIN");
    }
    @GetMapping("/me")
    public Map<String,String> me(Authentication auth) { return Map.of("username",auth.getName(),"role","ADMIN"); }
    @PostMapping("/logout")
    public Map<String,String> logout(Authentication auth,HttpServletRequest request,HttpServletResponse response) {
        try { audits.save(new SecurityAudit(auth.getName(),"LOGOUT",null)); }
        finally { new SecurityContextLogoutHandler().logout(request,response,auth); }
        return Map.of("status","success");
    }
    @PostMapping("/kiosk")
    public Map<String,String> kiosk(Authentication auth,HttpServletRequest request,HttpServletResponse response) {
        try {
            if (auth != null && auth.getPrincipal() instanceof AdminPrincipal)
                audits.save(new SecurityAudit(auth.getName(),"KIOSK_HANDOFF",null));
        } finally { new SecurityContextLogoutHandler().logout(request,response,auth); }
        return Map.of("status","success");
    }
    @PostMapping("/password")
    public Map<String,String> password(@RequestBody PasswordChange body,Authentication auth,
                                       HttpServletRequest request,HttpServletResponse response) {
        InputRules.password(body.newPassword());
        String key="password:"+auth.getName(); limiter.consume(key,5,Duration.ofMinutes(5));
        var principal=(AdminPrincipal)auth.getPrincipal();
        var account=admins.findById(principal.id()).orElseThrow();
        if (body.currentPassword()==null || body.currentPassword().length()>256 ||
                !encoder.matches(body.currentPassword(),account.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Mật khẩu hiện tại không đúng.");
        }
        account.setPasswordHash(encoder.encode(body.newPassword()));
        account.invalidateSessions(); admins.save(account);
        limiter.clear(key);
        audits.save(new SecurityAudit(auth.getName(),"PASSWORD_CHANGED",null));
        new SecurityContextLogoutHandler().logout(request,response,auth);
        return Map.of("status","success","message","Đã đổi mật khẩu. Vui lòng đăng nhập lại.");
    }
}
