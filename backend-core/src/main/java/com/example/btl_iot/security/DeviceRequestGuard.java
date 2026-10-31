package com.example.btl_iot.security;

import com.example.btl_iot.entity.DeviceRequest;
import com.example.btl_iot.repository.DeviceRequestRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

// Single-use request IDs inside a short time window: outside the window by clock, inside it by unique key.
@Component
public class DeviceRequestGuard {
    private final DeviceRequestRepository requests;
    private final Clock clock;
    private final Duration window;
    public DeviceRequestGuard(DeviceRequestRepository requests, Clock clock,
                              @Value("${smartlock.device.request-window:120s}") Duration window) {
        this.requests = requests; this.clock = clock; this.window = window;
    }
    // Must run outside any transaction so the claim commits even if later processing fails.
    public void claim(String deviceId, String requestId, String requestTime) {
        if (requestId == null || !requestId.matches("[A-Za-z0-9-]{8,64}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Thiếu hoặc sai X-Request-Id.");
        long epoch;
        try { epoch = Long.parseLong(requestTime); }
        catch (NumberFormatException | NullPointerException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Thiếu hoặc sai X-Request-Time.");
        }
        Instant now = clock.instant(), sent = Instant.ofEpochSecond(epoch);
        if (sent.isBefore(now.minus(window)) || sent.isAfter(now.plus(window)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Đồng hồ thiết bị lệch hoặc yêu cầu đã cũ.");
        try { requests.saveAndFlush(new DeviceRequest(deviceId, requestId, now)); }
        catch (DataIntegrityViolationException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Yêu cầu đã được xử lý (phát lại bị từ chối).");
        }
    }
}
