package com.example.btl_iot.service;

import com.example.btl_iot.entity.*;
import com.example.btl_iot.repository.*;
import com.example.btl_iot.security.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import org.springframework.security.crypto.password.PasswordEncoder;

@Service
public class ProfileService {
    public record ProfileView(Long id, String username, String name, boolean enabled, long version,
                              boolean hasPin, boolean pinResetRequired, boolean hasFace, String method,
                              String status, LocalDateTime lastActive) {}
    private final UserRepository users;
    private final AccessLogRepository logs;
    private final SecurityAuditRepository audits;
    private final PasswordEncoder encoder;
    public ProfileService(UserRepository users, AccessLogRepository logs, SecurityAuditRepository audits, PasswordEncoder encoder) {
        this.users = users; this.logs = logs; this.audits = audits;
        this.encoder = encoder;
    }
    public ProfileView view(User user) {
        boolean pin = user.getPinHash() != null && !user.getPinHash().isBlank();
        boolean face = user.getFaceEmbedding() != null && !user.getFaceEmbedding().isBlank();
        return new ProfileView(user.getId(), user.getUsername(), user.getFullName(), user.isEnabled(), user.getVersion(),
                pin, !pin, face, pin && face ? "Face + PIN" : pin ? "PIN Only" : face ? "Face Only" : "N/A",
                user.isEnabled() ? "Active" : "Disabled", logs.findLastAccessTimeByUserId(user.getId()));
    }
    @Transactional(readOnly = true)
    public List<ProfileView> list() { return users.findAll().stream().map(this::view).toList(); }

    @Transactional
    public ProfileView create(String username, String name, String pin, String embedding, String actor) {
        InputRules.username(username); ProfileRules.pin(pin);
        String normalized = username.toLowerCase(Locale.ROOT);
        if (users.existsByUsernameIgnoreCase(normalized))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Mã hồ sơ đã tồn tại.");
        var user = new User();
        user.setUsername(normalized); user.setFullName(ProfileRules.name(name));
        user.setPinHash(encoder.encode(pin)); user.setPinCode(null); user.setFaceEmbedding(embedding);
        users.saveAndFlush(user);
        audits.save(new SecurityAudit(actor, "PROFILE_CREATED", user.getId().toString()));
        return view(user);
    }

    @Transactional
    public ProfileView resetPin(Long id, String pin, Long version, String actor) {
        ProfileRules.pin(pin);
        var user = locked(id, version);
        user.setPinHash(encoder.encode(pin)); user.setPinCode(null);
        users.saveAndFlush(user);
        audits.save(new SecurityAudit(actor, "PROFILE_PIN_RESET", id.toString()));
        return view(user);
    }

    // Never hard-delete profiles: access logs must retain their original subject.
    @Transactional
    public ProfileView update(Long id, String name, Boolean enabled, Long version, String actor) {
        String validName = name == null ? null : ProfileRules.name(name);
        if (enabled == null) InputRules.bad("Cần chỉ định trạng thái quyền ra vào.");
        var user = locked(id, version);
        boolean changedAccess = enabled != user.isEnabled();
        if (validName != null) user.setFullName(validName);
        user.setEnabled(enabled);
        users.saveAndFlush(user);
        audits.save(new SecurityAudit(actor, changedAccess ? (enabled ? "PROFILE_ENABLED" : "PROFILE_DISABLED") : "PROFILE_UPDATED", id.toString()));
        return view(user);
    }
    public User locked(Long id, Long version) {
        var user = users.findLockedById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy hồ sơ."));
        if (version == null || version != user.getVersion())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Hồ sơ đã thay đổi. Hãy tải lại danh sách rồi thử lại.");
        return user;
    }
}
