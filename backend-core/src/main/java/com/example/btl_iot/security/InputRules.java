package com.example.btl_iot.security;

import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class InputRules {
    private InputRules() {}
    public static void username(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{1,64}"))
            bad("Mã người dùng chỉ gồm chữ, số, dấu gạch ngang hoặc gạch dưới (tối đa 64 ký tự).");
    }
    public static void password(String value) {
        if (value == null || value.length() < 12 || value.getBytes(StandardCharsets.UTF_8).length > 72)
            bad("Mật khẩu cần ít nhất 12 ký tự và tối đa 72 byte UTF-8.");
    }
    public static void bad(String message) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
