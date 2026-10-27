package com.example.btl_iot.config;

import com.example.btl_iot.channel.DeviceSocketHandler;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

// Device command channel (docs/DEVICE_CONTRACT.md). TLS terminates at the reverse proxy (wss://).
// Default origin policy (same origin only) is kept: devices send no Origin header, and browsers cannot
// set the bearer token a handshake needs anyway.
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    private final DeviceSocketHandler handler;
    public WebSocketConfig(DeviceSocketHandler handler) { this.handler = handler; }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/device");
    }

    // Small frames only; an idle socket (no ping, no status heartbeat) is closed so the device shows offline.
    @Bean
    public ServletServerContainerFactoryBean webSocketContainer(@Value("${smartlock.ws.idle-timeout:90s}") Duration idle) {
        var container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(4096);
        container.setMaxBinaryMessageBufferSize(1024);
        container.setMaxSessionIdleTimeout(idle.toMillis());
        return container;
    }
}
