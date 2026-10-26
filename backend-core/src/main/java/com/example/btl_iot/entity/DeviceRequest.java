package com.example.btl_iot.entity;

import jakarta.persistence.*;
import java.time.Instant;

// One row per device request ID: the unique key makes every upload single-use.
@Entity
@Table(name = "device_requests", uniqueConstraints = @UniqueConstraint(name = "uk_device_request", columnNames = {"device_id", "request_id"}))
public class DeviceRequest {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "device_id", length = 40, nullable = false)
    private String deviceId;
    @Column(name = "request_id", length = 64, nullable = false)
    private String requestId;
    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;
    protected DeviceRequest() {}
    public DeviceRequest(String deviceId, String requestId, Instant receivedAt) {
        this.deviceId = deviceId; this.requestId = requestId; this.receivedAt = receivedAt;
    }
    public String getDeviceId() { return deviceId; }
    public String getRequestId() { return requestId; }
}
