package com.example.btl_iot.security;

import com.example.btl_iot.entity.Device;
import com.example.btl_iot.repository.DeviceRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

// Provisions (or rotates) the single door node from config. Only the token hash is ever configured server-side.
@Component
public class DeviceBootstrap implements ApplicationRunner {
    private final DeviceRepository devices;
    @Value("${smartlock.device.id:door-01}") private String deviceId;
    @Value("${smartlock.device.token-sha256:}") private String tokenHash;
    public DeviceBootstrap(DeviceRepository devices) { this.devices = devices; }
    public void run(ApplicationArguments args) {
        if (tokenHash == null || tokenHash.isBlank()) return;
        if (!DeviceTokens.validId(deviceId) || !DeviceTokens.validHash(tokenHash))
            throw new IllegalStateException("smartlock.device.id or smartlock.device.token-sha256 is malformed");
        String hash = tokenHash.toLowerCase(java.util.Locale.ROOT);
        var device = devices.findById(deviceId).orElseGet(() -> new Device(deviceId, hash));
        device.setTokenHash(hash);
        devices.save(device);
    }
}
