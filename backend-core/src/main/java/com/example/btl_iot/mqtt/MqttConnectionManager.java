package com.example.btl_iot.mqtt;

import java.util.concurrent.*;
import org.eclipse.paho.client.mqttv3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

// Connects in the background so Core boots while the broker is down, and (re)subscribes on every connect.
@Component
public class MqttConnectionManager implements SmartLifecycle, MqttCallbackExtended {
    private static final Logger log = LoggerFactory.getLogger(MqttConnectionManager.class);
    private final IMqttClient client;
    private final MqttConnectOptions options;
    private final DeviceMessageHandler handler;
    private final String topicPrefix;
    private final boolean enabled;
    private final ScheduledExecutorService connector;
    private final ExecutorService worker;
    private volatile boolean running;

    @org.springframework.beans.factory.annotation.Autowired
    public MqttConnectionManager(IMqttClient client, MqttConnectOptions options, DeviceMessageHandler handler,
                                 @Value("${smartlock.mqtt.topic-prefix:smartlock}") String topicPrefix,
                                 @Value("${smartlock.mqtt.enabled:true}") boolean enabled) {
        this(client, options, handler, topicPrefix, enabled,
                Executors.newSingleThreadScheduledExecutor(named("mqtt-connect")),
                Executors.newSingleThreadExecutor(named("mqtt-inbound")));
    }

    MqttConnectionManager(IMqttClient client, MqttConnectOptions options, DeviceMessageHandler handler, String topicPrefix,
                          boolean enabled, ScheduledExecutorService connector, ExecutorService worker) {
        this.client = client; this.options = options; this.handler = handler; this.topicPrefix = topicPrefix;
        this.enabled = enabled; this.connector = connector; this.worker = worker;
    }

    String[] topics() { return new String[]{topicPrefix + "/+/ack", topicPrefix + "/+/event", topicPrefix + "/+/status"}; }

    @Override public void start() {
        running = true;
        if (!enabled) return;
        client.setCallback(this);
        connector.execute(() -> connect(1000));
    }

    // Paho's automatic reconnect only takes over after a first successful connect.
    void connect(long delayMs) {
        if (!running || client.isConnected()) return;
        try {
            client.connect(options);
        } catch (MqttException e) {
            long next = Math.min(delayMs * 2, 30_000);
            log.warn("event=mqtt_connect_failed code={} retryInMs={}", e.getReasonCode(), delayMs);
            if (running) connector.schedule(() -> connect(next), delayMs, TimeUnit.MILLISECONDS);
        }
    }

    // Clean session: the broker forgets subscriptions on every disconnect, so subscribe each time.
    @Override public void connectComplete(boolean reconnect, String serverURI) {
        log.info("event=mqtt_connected reconnect={}", reconnect);
        worker.execute(this::subscribe);
    }

    void subscribe() {
        try {
            client.subscribe(topics(), new int[]{1, 1, 1});
            log.info("event=mqtt_subscribed topics={}", String.join(",", topics()));
        } catch (MqttException e) {
            log.warn("event=mqtt_subscribe_failed code={}", e.getReasonCode());
            if (running) connector.schedule(() -> { if (client.isConnected()) worker.execute(this::subscribe); }, 5, TimeUnit.SECONDS);
        }
    }

    @Override public void connectionLost(Throwable cause) {
        log.warn("event=mqtt_connection_lost reason={}", cause == null ? "unknown" : cause.getClass().getSimpleName());
    }

    // Handle off the Paho callback thread; DB work and publishing from the callback thread can stall the client.
    @Override public void messageArrived(String topic, MqttMessage message) {
        byte[] payload = message.getPayload();
        worker.execute(() -> {
            try { handler.handle(topic, new String(payload, java.nio.charset.StandardCharsets.UTF_8)); }
            catch (RuntimeException e) { log.warn("event=mqtt_message_failed topic={} error={}", topic, e.getClass().getSimpleName()); }
        });
    }

    @Override public void deliveryComplete(IMqttDeliveryToken token) {}

    @Override public void stop() {
        running = false;
        connector.shutdownNow();
        try { if (client.isConnected()) client.disconnect(2000); } catch (MqttException ignored) { }
        worker.shutdown();
    }

    @Override public boolean isRunning() { return running; }

    private static ThreadFactory named(String name) {
        return runnable -> { Thread thread = new Thread(runnable, name); thread.setDaemon(true); return thread; };
    }
}
