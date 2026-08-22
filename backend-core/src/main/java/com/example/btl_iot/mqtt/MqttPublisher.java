package com.example.btl_iot.mqtt;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class MqttPublisher {

    @Autowired
    private MqttClient mqttClient;

    @Value("${mqtt.topic.pub}")
    private String topicPub;

    public void publishCommand(String command) {
        if (mqttClient == null || !mqttClient.isConnected()) {
            System.err.println("MqttClient is not connected. Cannot publish: " + command);
            return;
        }
        try {
            MqttMessage message = new MqttMessage(command.getBytes());
            message.setQos(1);
            mqttClient.publish(topicPub, message);
            System.out.println("Published command: " + command + " to topic: " + topicPub);
        } catch (MqttException e) {
            System.err.println("Failed to publish command: " + command);
            e.printStackTrace();
        }
    }
    
    public void sendOpenDoorCommand() {
        publishCommand("OPEN_DOOR");
    }
    
    public void sendWarningAlarmCommand() {
        publishCommand("ALARM_ON");
    }
}
