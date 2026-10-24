package com.example.btl_iot.mqtt;

import com.example.btl_iot.entity.AccessLog;
import com.example.btl_iot.entity.SecurityAudit;
import com.example.btl_iot.repository.AccessLogRepository;
import com.example.btl_iot.repository.SecurityAuditRepository;
import com.example.btl_iot.service.DoorStateService;
import org.eclipse.paho.client.mqttv3.IMqttMessageListener;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.LocalDateTime;

@Service
public class MqttSubscriber {

    @Autowired(required = false)
    private MqttClient mqttClient;

    @Autowired
    private AccessLogRepository accessLogRepository;

    @Autowired
    private SecurityAuditRepository securityAuditRepository;

    @Autowired
    private DoorStateService doorStateService;

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
                    String payload = new String(message.getPayload()).trim();
                    System.out.println("[MQTT SUB] Nhan tin nhan tu topic " + topic + ": " + payload);

                    if (payload.startsWith("DOOR_UNLOCKED")) {
                        String method = "HARDWARE";
                        if (payload.contains(":")) {
                            String[] parts = payload.split(":");
                            if (parts.length > 1 && !parts[1].isBlank()) {
                                method = parts[1].trim();
                            }
                        }

                        long now = System.currentTimeMillis();
                        long timeSinceLastUnlock = now - doorStateService.getLastUnlockTimestamp();

                        // Nếu vừa có lệnh mở cửa từ DoorAccessService trong vòng 6 giây qua,
                        // bản tin MQTT này chỉ là xác nhận phần cứng từ ESP32, KHÔNG ghi đè / tạo trùng log!
                        if (timeSinceLastUnlock > 6000) {
                            AccessLog log = new AccessLog();
                            log.setAccessMethod("OFFLINE_" + method);
                            log.setStatus("SUCCESS");
                            log.setAccessTime(LocalDateTime.now());
                            accessLogRepository.save(log);

                            doorStateService.notifyDoorUnlocked("OFFLINE_" + method, "Mở cửa trực tiếp bằng phần cứng");
                            System.out.println("[MQTT SUB] Đã ghi nhận sự kiện mở cửa ngoại tuyến: " + method);
                        } else {
                            System.out.println("[MQTT SUB] Bỏ qua trùng lặp: Sự kiện mở cửa đã được ghi nhận bởi DoorAccessService (" + timeSinceLastUnlock + "ms trước)");
                        }

                    } else if ("INTRUDER_WRONG_PIN_3_TIMES".equals(payload)) {
                        securityAuditRepository.save(new SecurityAudit("ESP32_KEYPAD", "INTRUDER_WRONG_PIN_3_TIMES", "Ban phim ma tran"));
                        System.out.println("[MQTT SUB] Da luu canh bao: Nhap sai PIN 3 lan tren Keypad");

                    } else if (payload.startsWith("INTRUDER_ALERT")) {
                        securityAuditRepository.save(new SecurityAudit("ESP32_PIR", "INTRUDER_ALERT", payload));
                        System.out.println("[MQTT SUB] Da luu canh bao ke la: " + payload);

                    } else if ("PIR_MOTION_DETECTED".equals(payload)) {
                        System.out.println("[MQTT SUB] Phat hien chuyen dong PIR truoc cua");
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
