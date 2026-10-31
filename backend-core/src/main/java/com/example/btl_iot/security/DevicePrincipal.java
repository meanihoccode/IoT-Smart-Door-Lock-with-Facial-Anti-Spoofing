package com.example.btl_iot.security;

// Identity derived from a verified device token, never from a client-supplied body field.
public record DevicePrincipal(String deviceId) {
    @Override public String toString() { return deviceId; }
}
