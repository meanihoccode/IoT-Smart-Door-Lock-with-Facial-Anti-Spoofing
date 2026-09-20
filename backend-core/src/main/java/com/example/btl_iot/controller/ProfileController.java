package com.example.btl_iot.controller;

import com.example.btl_iot.service.ProfileService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/users")
public class ProfileController {
    public record Update(String fullName, Boolean enabled, Long version) {}
    public record PinReset(String pinCode, Long version) {}
    private final ProfileService profiles;
    public ProfileController(ProfileService profiles) { this.profiles = profiles; }
    @GetMapping
    public List<ProfileService.ProfileView> list() { return profiles.list(); }
    @PatchMapping("/{id}")
    public ProfileService.ProfileView update(@PathVariable Long id, @RequestBody Update body, Authentication auth) {
        return profiles.update(id, body.fullName(), body.enabled(), body.version(), auth.getName());
    }
    @PutMapping("/{id}/pin")
    public ProfileService.ProfileView resetPin(@PathVariable Long id, @RequestBody PinReset body, Authentication auth) {
        return profiles.resetPin(id, body.pinCode(), body.version(), auth.getName());
    }
}
