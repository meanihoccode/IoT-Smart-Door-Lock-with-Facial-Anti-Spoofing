package com.example.btl_iot.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "security_alerts")
public class SecurityAlert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "alert_type", nullable = false)
    private String alertType; // e.g. "PIR_INTRUDER_5_TIMES", "WRONG_PIN_3_TIMES"

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "image_url", columnDefinition = "TEXT")
    private String imageUrl;

    @Column(name = "resolved")
    private boolean resolved = false;

    public SecurityAlert() {}

    public SecurityAlert(String alertType, String description, String imageUrl) {
        this.alertType = alertType;
        this.description = description;
        this.imageUrl = imageUrl;
        this.createdAt = LocalDateTime.now();
        this.resolved = false;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public String getAlertType() { return alertType; }
    public void setAlertType(String alertType) { this.alertType = alertType; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }

    public boolean isResolved() { return resolved; }
    public void setResolved(boolean resolved) { this.resolved = resolved; }
}
