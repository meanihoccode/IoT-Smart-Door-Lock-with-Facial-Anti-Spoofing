package com.example.btl_iot.entity;

import jakarta.persistence.*;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.LocalDateTime;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    @Column(nullable = false, columnDefinition = "bigint default 0")
    private long version;

    @Column(nullable = false, columnDefinition = "boolean default true")
    private boolean enabled = true;

    @JsonIgnore
    @Column(name = "pin_hash", length = 100)
    private String pinHash;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(name = "full_name")
    private String fullName;

    @Column(name = "pin_code", length = 10)
    @JsonIgnore // Legacy column: migration only, never used to authenticate.
    private String pinCode;

    // We can store face embedding as a JSON string, a comma-separated string, 
    // or a byte array depending on the AI model output (typically a 512-dim float array).
    @Column(name = "face_embedding", columnDefinition = "TEXT")
    @JsonIgnore
    private String faceEmbedding;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    public User() {}

    public Long getId() { return id; }
    public long getVersion() { return version; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getPinHash() { return pinHash; }
    public void setPinHash(String pinHash) { this.pinHash = pinHash; }
    public void setId(Long id) { this.id = id; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }

    public String getPinCode() { return pinCode; }
    public void setPinCode(String pinCode) { this.pinCode = pinCode; }

    public String getFaceEmbedding() { return faceEmbedding; }
    public void setFaceEmbedding(String faceEmbedding) { this.faceEmbedding = faceEmbedding; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
