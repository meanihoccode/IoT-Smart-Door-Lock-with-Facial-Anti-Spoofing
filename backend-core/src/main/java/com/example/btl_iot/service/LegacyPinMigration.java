package com.example.btl_iot.service;

import com.example.btl_iot.entity.SecurityAudit;
import com.example.btl_iot.repository.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LegacyPinMigration {
    public record Summary(int converted, int resetRequired, int preserved) {}
    private final UserRepository users;
    private final SecurityAuditRepository audits;
    private final PasswordEncoder encoder;
    public LegacyPinMigration(UserRepository users, SecurityAuditRepository audits, PasswordEncoder encoder) {
        this.users = users; this.audits = audits; this.encoder = encoder;
    }
    // Opt-in only after backup. Atomic, idempotent; never overwrite a newer hashed PIN.
    @Transactional
    public Summary migrate() {
        int converted = 0, reset = 0, preserved = 0;
        for (Long id : users.findLegacyPinIds()) {
            var user = users.findLockedById(id).orElseThrow();
            if (user.getPinCode() == null) continue;
            if (user.getPinHash() != null && !user.getPinHash().isBlank()) preserved++;
            else if (user.getPinCode().matches("[0-9]{6,10}")) {
                user.setPinHash(encoder.encode(user.getPinCode())); converted++;
            } else { user.setPinHash(null); reset++; }
            user.setPinCode(null);
            users.save(user);
            audits.save(new SecurityAudit("system", "LEGACY_PIN_CLEARED", id.toString()));
        }
        return new Summary(converted, reset, preserved);
    }
}
