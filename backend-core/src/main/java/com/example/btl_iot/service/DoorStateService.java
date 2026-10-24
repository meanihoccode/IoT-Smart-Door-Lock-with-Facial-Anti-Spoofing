package com.example.btl_iot.service;

import com.example.btl_iot.mqtt.MqttPublisher;
import org.springframework.stereotype.Service;
import java.util.HashMap;
import java.util.Map;

@Service
public class DoorStateService {

    private final MqttPublisher mqttPublisher;

    private volatile boolean emergencyLockdown = false;
    private volatile boolean doorUnlocked = false;
    private volatile String lastMethod = "";
    private volatile String lastMessage = "";
    private volatile long lastUnlockTimestamp = 0;

    public DoorStateService(MqttPublisher mqttPublisher) {
        this.mqttPublisher = mqttPublisher;
    }

    public boolean isEmergencyLockdown() {
        return emergencyLockdown;
    }

    public synchronized void setEmergencyLockdown(boolean state) {
        this.emergencyLockdown = state;
        if (mqttPublisher != null) {
            mqttPublisher.publishCommand(state ? "EMERGENCY_LOCKDOWN_ON" : "EMERGENCY_LOCKDOWN_OFF");
        }
    }

    public synchronized void notifyDoorUnlocked(String method, String message) {
        this.doorUnlocked = true;
        this.lastMethod = method != null ? method : "UNKNOWN";
        this.lastMessage = message != null ? message : "Cửa đã mở khóa thành công";
        this.lastUnlockTimestamp = System.currentTimeMillis();
    }

    public long getLastUnlockTimestamp() {
        return lastUnlockTimestamp;
    }

    public Map<String, Object> getDoorStatus() {
        long now = System.currentTimeMillis();
        boolean currentlyUnlocked = doorUnlocked && (now - lastUnlockTimestamp <= 6000);
        if (!currentlyUnlocked) {
            doorUnlocked = false;
        }

        Map<String, Object> status = new HashMap<>();
        status.put("lockdown", emergencyLockdown);
        status.put("unlocked", currentlyUnlocked);
        status.put("lastMethod", lastMethod);
        status.put("lastMessage", lastMessage);
        status.put("timestamp", lastUnlockTimestamp);
        return status;
    }
}
