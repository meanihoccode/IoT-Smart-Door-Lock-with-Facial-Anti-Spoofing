package com.example.btl_iot.security;

import java.time.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import com.example.btl_iot.entity.AdminAccount;
import com.example.btl_iot.repository.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdminSessionPolicyTests {
    Clock clock;
    AdminSessionPolicy policy;
    MockHttpSession session;
    @BeforeEach void setup() {
        clock = mock(Clock.class); when(clock.millis()).thenReturn(0L);
        policy = new AdminSessionPolicy(Duration.ofMinutes(15),Duration.ofHours(4),clock);
        session = new MockHttpSession(); policy.start(session);
    }
    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); }
    @Test void idleExpiresAtBoundary() {
        when(clock.millis()).thenReturn(899999L); assertNull(policy.expiry(session));
        when(clock.millis()).thenReturn(900000L); assertEquals("SESSION_IDLE_EXPIRED",policy.expiry(session));
    }
    @Test void kioskAndCsrfAndMeDoNotExtendIdle() {
        when(clock.millis()).thenReturn(800000L);
        for (String path : new String[]{"/api/verify-pin","/api/verify-face","/api/auth/csrf","/api/auth/me"}) policy.touch(session,path);
        when(clock.millis()).thenReturn(900000L); assertEquals("SESSION_IDLE_EXPIRED",policy.expiry(session));
    }
    @Test void adminActivityExtendsIdleButNeverAbsoluteAge() {
        when(clock.millis()).thenReturn(800000L); policy.touch(session,"/api/users");
        when(clock.millis()).thenReturn(900000L); assertNull(policy.expiry(session));
        when(clock.millis()).thenReturn(14399999L); policy.touch(session,"/api/admin/security-audits");
        when(clock.millis()).thenReturn(14400000L); assertEquals("SESSION_ABSOLUTE_EXPIRED",policy.expiry(session));
    }
    @Test void oldOrMissingSessionsFailClosed() {
        assertEquals("SESSION_EXPIRED",policy.expiry(null));
        assertEquals("SESSION_EXPIRED",policy.expiry(new MockHttpSession()));
    }
    @Test void nonPositiveConfigurationRejected() {
        assertThrows(IllegalArgumentException.class,() -> new AdminSessionPolicy(Duration.ZERO,Duration.ofHours(4),clock));
    }
    @Test void expiredSessionIsInvalidatedBeforeProtectedHandlerAndAuditedOnce() throws Exception {
        var admins = mock(AdminAccountRepository.class); var audits = mock(SecurityAuditRepository.class);
        var account = new AdminAccount(); account.setUsername("testadmin");
        when(admins.findById(1L)).thenReturn(java.util.Optional.of(account));
        var principal = new AdminPrincipal(1L,"testadmin","test-only-hash",true,0);
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal,null,principal.getAuthorities()));
        when(clock.millis()).thenReturn(900000L);
        var request = new MockHttpServletRequest("GET","/api/users"); request.setSession(session);
        var filter = new AdminSessionFilter(admins,policy,audits);
        filter.doFilter(request,new MockHttpServletResponse(),(req,res) -> assertNull(SecurityContextHolder.getContext().getAuthentication()));
        assertTrue(session.isInvalid());
        verify(audits).save(argThat(a -> "SESSION_IDLE_EXPIRED".equals(a.getAction())));
    }
}
