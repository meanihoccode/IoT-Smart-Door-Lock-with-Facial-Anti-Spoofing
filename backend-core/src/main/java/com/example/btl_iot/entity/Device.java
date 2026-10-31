package com.example.btl_iot.entity;

import jakarta.persistence.*;
import java.time.Instant;

// One physical door node. The token is stored only as a SHA-256 hash; the plaintext lives in firmware config.
@Entity
@Table(name = "devices")
public class Device {
    @Id
    @Column(length = 40)
    private String id;

    @Column(name = "token_hash", length = 64, nullable = false)
    private String tokenHash;

    @Column(nullable = false, columnDefinition = "boolean default true")
    private boolean enabled = true;

    // Last command sequence issued. Firmware persists the last accepted seq and rejects anything not greater.
    @Column(name = "last_seq", nullable = false, columnDefinition = "bigint default 0")
    private long lastSeq;

    @Column(name = "desired_lockdown", nullable = false, columnDefinition = "boolean default false")
    private boolean desiredLockdown;

    // Null until the device has reported its lockdown state since the last change.
    @Column(name = "reported_lockdown")
    private Boolean reportedLockdown;

    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean online;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    @Column(length = 32)
    private String firmware;

    protected Device() {}
    public Device(String id, String tokenHash) { this.id = id; this.tokenHash = tokenHash; }

    public String getId() { return id; }
    public String getTokenHash() { return tokenHash; }
    public void setTokenHash(String tokenHash) { this.tokenHash = tokenHash; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public long getLastSeq() { return lastSeq; }
    public void setLastSeq(long lastSeq) { this.lastSeq = lastSeq; }
    public boolean isDesiredLockdown() { return desiredLockdown; }
    public void setDesiredLockdown(boolean desiredLockdown) { this.desiredLockdown = desiredLockdown; }
    public Boolean getReportedLockdown() { return reportedLockdown; }
    public void setReportedLockdown(Boolean reportedLockdown) { this.reportedLockdown = reportedLockdown; }
    public boolean isOnline() { return online; }
    public void setOnline(boolean online) { this.online = online; }
    public Instant getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(Instant lastSeenAt) { this.lastSeenAt = lastSeenAt; }
    public String getFirmware() { return firmware; }
    public void setFirmware(String firmware) { this.firmware = firmware; }
}
