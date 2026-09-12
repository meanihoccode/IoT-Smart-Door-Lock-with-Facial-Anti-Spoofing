package com.example.btl_iot.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "admin_accounts")
public class AdminAccount {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Version
    private Long rowVersion;
    @Column(nullable = false, unique = true, length = 64)
    private String username;
    @Column(nullable = false, length = 100)
    private String passwordHash;
    @Column(nullable = false)
    private boolean enabled = true;
    @Column(nullable = false)
    private long credentialVersion = 0;
    public Long getId() { return id; }
    public String getUsername() { return username; }
    public void setUsername(String value) { username = value; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String value) { passwordHash = value; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public long getCredentialVersion() { return credentialVersion; }
    public void invalidateSessions() { credentialVersion++; }
}
