package com.example.btl_iot.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "security_audits")
public class SecurityAudit {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 64)
    private String actor;
    @Column(nullable = false, length = 64)
    private String action;
    @Column(length = 100)
    private String target;
    private LocalDateTime time = LocalDateTime.now();
    protected SecurityAudit() {}
    public SecurityAudit(String actor, String action, String target) {
        this.actor = actor; this.action = action; this.target = target;
    }
    public Long getId() { return id; }
    public String getActor() { return actor; }
    public String getAction() { return action; }
    public String getTarget() { return target; }
    public LocalDateTime getTime() { return time; }
}
