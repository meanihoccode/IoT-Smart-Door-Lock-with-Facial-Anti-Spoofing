package com.example.btl_iot.security;

import com.example.btl_iot.entity.AdminAccount;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import java.io.Serializable;
import java.util.Collection;
import java.util.List;

public record AdminPrincipal(Long id, String username, String passwordHash,
                             boolean enabled, long version) implements UserDetails, Serializable {
    public static AdminPrincipal from(AdminAccount account) {
        return new AdminPrincipal(account.getId(), account.getUsername(), account.getPasswordHash(),
                account.isEnabled(), account.getCredentialVersion());
    }
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }
    public String getPassword() { return passwordHash; }
    public String getUsername() { return username; }
    public boolean isEnabled() { return enabled; }
}
