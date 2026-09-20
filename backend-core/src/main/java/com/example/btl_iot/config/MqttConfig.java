package com.example.btl_iot.config;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MqttConfig {

    @Value("${mqtt.broker.url}")
    private String brokerUrl;

    @Value("${mqtt.client.id}")
    private String clientId;

    @Value("${smartlock.mqtt.enabled:true}")
    private boolean enabled;

    @Bean
    public MqttClient mqttClient() {
        try {
            if (!enabled) return new MqttClient(brokerUrl, clientId,
                    new org.eclipse.paho.client.mqttv3.persist.MemoryPersistence());
            MqttClient client = new MqttClient(brokerUrl, clientId);
            // Do not hold a profile authorization lock indefinitely waiting for the broker.
            client.setTimeToWait(5000);
            MqttConnectOptions options = new MqttConnectOptions();
            options.setAutomaticReconnect(true);
            options.setCleanSession(true);
            options.setConnectionTimeout(10);
            
            client.connect(options);
            return client;
        } catch (MqttException e) {
            System.err.println("WARNING: Could not connect to MQTT broker at " + brokerUrl + ". " + e.getMessage());
            // Return a disconnected client or handle it properly.
            // Returning null might break autowiring in subscriber.
            try {
                return new MqttClient(brokerUrl, clientId);
            } catch(MqttException ex) {
                return null;
            }
        }
    }
}
