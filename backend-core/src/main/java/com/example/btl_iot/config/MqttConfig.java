package com.example.btl_iot.config;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import org.eclipse.paho.client.mqttv3.IMqttClient;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// Creating the client does not connect; MqttConnectionManager connects in the background with retry.
@Configuration
public class MqttConfig {

    @Bean
    public IMqttClient mqttClient(@Value("${mqtt.broker.url}") String brokerUrl,
                                  @Value("${mqtt.client.id}") String clientId) throws MqttException {
        MqttClient client = new MqttClient(brokerUrl, clientId, new MemoryPersistence());
        // Do not hold a profile authorization lock indefinitely waiting for the broker.
        client.setTimeToWait(5000);
        return client;
    }

    @Bean
    public MqttConnectOptions mqttConnectOptions(@Value("${smartlock.mqtt.username:}") String username,
                                                 @Value("${smartlock.mqtt.password:}") String password,
                                                 @Value("${smartlock.mqtt.ca-file:}") String caFile) throws Exception {
        MqttConnectOptions options = new MqttConnectOptions();
        options.setAutomaticReconnect(true);
        options.setMaxReconnectDelay(30_000);
        // Clean session: nothing queued while Core was away is replayed; subscriptions are renewed on connect.
        options.setCleanSession(true);
        options.setConnectionTimeout(10);
        options.setKeepAliveInterval(30);
        options.setHttpsHostnameVerificationEnabled(true);
        if (!username.isBlank()) {
            options.setUserName(username);
            options.setPassword(password.toCharArray());
        }
        if (!caFile.isBlank()) options.setSocketFactory(trustOnly(Path.of(caFile)));
        return options;
    }

    // Trust exactly the configured CA bundle (e.g. a private broker CA); hostname verification stays on.
    static SSLSocketFactory trustOnly(Path pem) throws Exception {
        KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
        store.load(null, null);
        try (InputStream in = Files.newInputStream(pem)) {
            int index = 0;
            for (var cert : CertificateFactory.getInstance("X.509").generateCertificates(in))
                store.setCertificateEntry("ca-" + index++, cert);
            if (index == 0) throw new IllegalStateException("No certificate in " + pem);
        }
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(store);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trust.getTrustManagers(), null);
        return context.getSocketFactory();
    }
}
