package com.example.btl_iot.mqtt;

import org.eclipse.paho.client.mqttv3.IMqttMessageListener;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;

@Service
public class MqttSubscriber {

    @Autowired
    private MqttClient mqttClient;

    @Value("${mqtt.topic.sub}")
    private String topicSub;

    @PostConstruct
    public void subscribe() {
        if (mqttClient == null || !mqttClient.isConnected()) {
            System.err.println("MqttClient is not connected. Skipping subscription.");
            return;
        }
        try {
            mqttClient.subscribe(topicSub, new IMqttMessageListener() {
                @Override
                public void messageArrived(String topic, MqttMessage message) throws Exception {
                    String payload = new String(message.getPayload());
                    System.out.println("Message received from topic " + topic + ": " + payload);
                    
                    // Logic to handle message from ESP32
                    // e.g. If payload == "PIR_MOTION_DETECTED", notify Kiosk UI via WebSocket to wake up and take a photo
                    if ("PIR_MOTION_DETECTED".equals(payload)) {
                        System.out.println("Action: Waking up Kiosk UI to start Face Detection pipeline...");
                        // TODO: Implement WebSocket notification to Kiosk UI
                    } else if (payload.startsWith("PIN:")) {
                        String pin = payload.split(":")[1];
                        System.out.println("Action: Checking PIN code: " + pin);
                        // TODO: Implement PIN verification logic
                    }
                }
            });
            System.out.println("Subscribed to MQTT topic: " + topicSub);
        } catch (MqttException e) {
            System.err.println("Failed to subscribe to MQTT topic: " + topicSub);
            e.printStackTrace();
        }
    }
}
