package com.example.btl_iot.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "access_logs")
public class AccessLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // The user who accessed the system, if authenticated. Can be null if failed attempt.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(name = "access_time")
    private LocalDateTime accessTime = LocalDateTime.now();

    @Column(name = "access_method") // e.g., "FACE", "PIN"
    private String accessMethod;

    @Column(name = "status") // e.g., "SUCCESS", "FAILED", "SPOOF_DETECTED"
    private String status;

    @Column(name = "image_url", columnDefinition = "TEXT")
    private String imageUrl; // Path or URL to the captured image for auditing

    public AccessLog() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }

    public LocalDateTime getAccessTime() { return accessTime; }
    public void setAccessTime(LocalDateTime accessTime) { this.accessTime = accessTime; }

    public String getAccessMethod() { return accessMethod; }
    public void setAccessMethod(String accessMethod) { this.accessMethod = accessMethod; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }
}
