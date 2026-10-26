package com.example.btl_iot.mqtt;

import org.eclipse.paho.client.mqttv3.IMqttClient;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

// true means the broker accepted the message (QoS 1 PUBACK), nothing more. Commands are never retained.
@Service
public class MqttPublisher {
    private static final Logger log = LoggerFactory.getLogger(MqttPublisher.class);
    private final IMqttClient mqttClient;
    private final String topicPrefix;

    public MqttPublisher(IMqttClient mqttClient, @Value("${smartlock.mqtt.topic-prefix:smartlock}") String topicPrefix) {
        this.mqttClient = mqttClient;
        this.topicPrefix = topicPrefix;
    }

    public String commandTopic(String deviceId) { return topicPrefix + "/" + deviceId + "/cmd"; }

    public boolean publishCommand(String deviceId, String json) {
        if (mqttClient == null || !mqttClient.isConnected()) {
            log.warn("event=command_not_published reason=broker_disconnected device={}", deviceId);
            return false;
        }
        try {
            MqttMessage message = new MqttMessage(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            message.setQos(1);
            message.setRetained(false);
            mqttClient.publish(commandTopic(deviceId), message);
            return true;
        } catch (MqttException e) {
            log.warn("event=command_not_published reason=publish_failed device={} code={}", deviceId, e.getReasonCode());
            return false;
        }
    }
}
