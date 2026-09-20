package com.example.btl_iot.security;

public final class ProfileRules {
    private ProfileRules() {}
    public static String name(String value) {
        if (value == null || value.isBlank() || value.length() > 100 || value.chars().anyMatch(Character::isISOControl))
            InputRules.bad("Họ tên cần từ 1 đến 100 ký tự, không chứa ký tự điều khiển.");
        return value.strip();
    }
    public static void pin(String value) {
        if (value == null || !value.matches("[0-9]{6,10}"))
            InputRules.bad("PIN cần từ 6 đến 10 chữ số; giữ nguyên số 0 ở đầu.");
    }
}
