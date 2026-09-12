package com.example.btl_iot.security;

import com.example.btl_iot.entity.AdminAccount;
import com.example.btl_iot.repository.AdminAccountRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class SecurityBootstrap implements ApplicationRunner {
    private final AdminAccountRepository admins;
    private final PasswordEncoder encoder;
    @Value("${smartlock.admin.username:}") private String username;
    @Value("${smartlock.admin.password:}") private String password;
    public SecurityBootstrap(AdminAccountRepository admins, PasswordEncoder encoder) {
        this.admins = admins; this.encoder = encoder;
    }
    public void run(ApplicationArguments args) {
        if (admins.count() == 0 && !username.isBlank() && !password.isBlank()) {
            InputRules.username(username);
            InputRules.password(password);
            var admin = new AdminAccount();
            admin.setUsername(username);
            admin.setPasswordHash(encoder.encode(password));
            admins.save(admin);
        }
    }
}
