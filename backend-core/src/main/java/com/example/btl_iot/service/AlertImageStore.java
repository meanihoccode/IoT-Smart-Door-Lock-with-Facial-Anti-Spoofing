package com.example.btl_iot.service;

import java.io.IOException;
import java.nio.file.*;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

// Alert photos live on a persistent volume (SMARTLOCK_ALERTS_DIR) and are served only to admins.
@Component
public class AlertImageStore {
    public static final String URL_PREFIX = "/api/security-alerts/images/";
    private static final String NAME = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.jpg";
    private final Path dir;
    public AlertImageStore(@Value("${smartlock.storage.alerts-dir:uploads/alerts}") String dir) throws IOException {
        this.dir = Paths.get(dir).toAbsolutePath().normalize();
        Files.createDirectories(this.dir);
    }
    public String save(MultipartFile file) throws IOException {
        FaceGateway.validateImage(file);
        String name = UUID.randomUUID() + ".jpg";
        try (var input = file.getInputStream()) { Files.copy(input, dir.resolve(name)); }
        return URL_PREFIX + name;
    }
    public Resource load(String name) {
        if (name == null || !name.matches(NAME)) return null;
        try {
            Resource resource = new UrlResource(dir.resolve(name).normalize().toUri());
            return resource.exists() && resource.isReadable() ? resource : null;
        } catch (IOException ex) { return null; }
    }
}
