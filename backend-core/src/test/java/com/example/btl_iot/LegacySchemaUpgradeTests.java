package com.example.btl_iot;

import com.example.btl_iot.repository.UserRepository;
import com.example.btl_iot.service.LegacyPinMigration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import static org.junit.jupiter.api.Assertions.*;

// Simulate the previous schema before Hibernate adds phase 2 columns.
// This validates H2 upgrade behavior, not a production MySQL migration.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.config.import=", "spring.datasource.url=jdbc:h2:mem:legacy-schema;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect", "spring.jpa.hibernate.ddl-auto=update",
    "spring.sql.init.mode=always", "spring.sql.init.schema-locations=classpath:legacy-users.sql",
    "smartlock.mqtt.enabled=false", "smartlock.pin.migrate-legacy=false",
    "smartlock.admin.username=", "smartlock.admin.password=", "server.address=127.0.0.1"
})
class LegacySchemaUpgradeTests {
    @Autowired UserRepository users;
    @Autowired LegacyPinMigration migration;
    @Autowired PasswordEncoder encoder;
    @Test void addingColumnsPreservesOldProfileAndMigrationMustBeExplicit() {
        var before = users.findByUsername("legacy").orElseThrow();
        assertTrue(before.isEnabled()); assertEquals(0,before.getVersion());
        assertNull(before.getPinHash()); assertEquals("001234",before.getPinCode());
        assertEquals("[1,2,3]",before.getFaceEmbedding());
        assertEquals(new LegacyPinMigration.Summary(1,0,0),migration.migrate());
        var after = users.findById(before.getId()).orElseThrow();
        assertEquals(before.getCreatedAt(),after.getCreatedAt());
        assertEquals(before.getFaceEmbedding(),after.getFaceEmbedding());
        assertNull(after.getPinCode()); assertTrue(encoder.matches("001234",after.getPinHash()));
        assertTrue(after.isEnabled()); assertEquals(1,users.count());
    }
}
