package com.example.btl_iot.security;

import com.example.btl_iot.service.LegacyPinMigration;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.slf4j.LoggerFactory;

@Component
@ConditionalOnProperty(name = "smartlock.pin.migrate-legacy", havingValue = "true")
public class LegacyPinMigrationRunner implements ApplicationRunner {
    private final LegacyPinMigration migration;
    public LegacyPinMigrationRunner(LegacyPinMigration migration) { this.migration = migration; }
    public void run(ApplicationArguments args) {
        var result = migration.migrate();
        LoggerFactory.getLogger(getClass()).info("Legacy PIN migration: converted={}, resetRequired={}, preserved={}",
                result.converted(), result.resetRequired(), result.preserved());
    }
}
