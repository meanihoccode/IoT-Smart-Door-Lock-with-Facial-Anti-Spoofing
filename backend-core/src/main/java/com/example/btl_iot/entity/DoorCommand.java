package com.example.btl_iot.entity;

import jakarta.persistence.*;
import java.time.Instant;

// Persistent command record. Status changes only through conditional updates in DoorCommandRepository.
@Entity
@Table(name = "door_commands", indexes = @Index(name = "idx_door_commands_status_expiry", columnList = "status,expires_at"))
public class DoorCommand {
    public enum Type { UNLOCK, LOCKDOWN_ON, LOCKDOWN_OFF }
    // PUBLISHED = accepted by the broker only. EXECUTED = device drove the actuator; it does not prove the door moved.
    public enum Status { PENDING, PUBLISHED, RECEIVED, EXECUTED, REJECTED, FAILED, UNKNOWN }

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "device_id", length = 40, nullable = false)
    private String deviceId;

    @Column(nullable = false)
    private long seq;

    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    private Type type;

    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    private Status status = Status.PENDING;

    @Column(name = "status_reason", length = 40)
    private String statusReason;

    @Column(length = 20)
    private String method;

    @Column(name = "user_id")
    private Long userId;

    @Column(length = 64)
    private String actor;

    @Column(name = "request_id", length = 64)
    private String requestId;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "unlock_ms")
    private Integer unlockMs;

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected DoorCommand() {}
    public DoorCommand(String id, String deviceId, long seq, Type type, Instant issuedAt, Instant expiresAt) {
        this.id = id; this.deviceId = deviceId; this.seq = seq; this.type = type;
        this.issuedAt = issuedAt; this.expiresAt = expiresAt; this.updatedAt = issuedAt;
    }

    public String getId() { return id; }
    public String getDeviceId() { return deviceId; }
    public long getSeq() { return seq; }
    public Type getType() { return type; }
    public Status getStatus() { return status; }
    public String getStatusReason() { return statusReason; }
    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getActor() { return actor; }
    public void setActor(String actor) { this.actor = actor; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public Instant getIssuedAt() { return issuedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Integer getUnlockMs() { return unlockMs; }
    public void setUnlockMs(Integer unlockMs) { this.unlockMs = unlockMs; }
    public Instant getUpdatedAt() { return updatedAt; }
}
