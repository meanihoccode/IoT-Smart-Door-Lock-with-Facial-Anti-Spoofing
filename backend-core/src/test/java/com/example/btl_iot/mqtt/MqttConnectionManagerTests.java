package com.example.btl_iot.mqtt;

import java.util.concurrent.*;
import org.eclipse.paho.client.mqttv3.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.Mockito.*;

class MqttConnectionManagerTests {
    final IMqttClient client = mock(IMqttClient.class);
    final DeviceMessageHandler handler = mock(DeviceMessageHandler.class);
    final ScheduledExecutorService connector = mock(ScheduledExecutorService.class);
    final ExecutorService direct = new AbstractExecutorService() {
        public void execute(Runnable task) { task.run(); }
        public void shutdown() {} public java.util.List<Runnable> shutdownNow() { return java.util.List.of(); }
        public boolean isShutdown() { return false; } public boolean isTerminated() { return false; }
        public boolean awaitTermination(long t, TimeUnit u) { return true; }
    };
    MqttConnectionManager manager(boolean enabled) {
        return new MqttConnectionManager(client, new MqttConnectOptions(), handler, "test", enabled, connector, direct);
    }

    @Test void brokerDownAtBootSchedulesBackoffRetryInsteadOfGivingUp() throws Exception {
        doThrow(new MqttException(MqttException.REASON_CODE_SERVER_CONNECT_ERROR)).when(client).connect(any(MqttConnectOptions.class));
        var manager = manager(true);
        doAnswer(invocation -> { invocation.<Runnable>getArgument(0).run(); return null; }).when(connector).execute(any());
        manager.start();
        verify(client).connect(any(MqttConnectOptions.class));
        verify(connector).schedule(any(Runnable.class), eq(1000L), eq(TimeUnit.MILLISECONDS));
        manager.connect(16_000);
        verify(connector).schedule(any(Runnable.class), eq(16_000L), eq(TimeUnit.MILLISECONDS));
    }

    @Test void everyConnectAndReconnectResubscribesAllDeviceTopics() throws Exception {
        var manager = manager(true);
        manager.connectComplete(false, "tcp://broker");
        manager.connectComplete(true, "tcp://broker");
        verify(client, times(2)).subscribe(aryEq(new String[]{"test/+/ack", "test/+/event", "test/+/status"}), aryEq(new int[]{1, 1, 1}));
    }

    @Test void failedSubscribeIsRetried() throws Exception {
        doThrow(new MqttException(0)).when(client).subscribe(any(String[].class), any(int[].class));
        var manager = manager(true);
        manager.start();
        manager.connectComplete(false, "tcp://broker");
        verify(connector).schedule(any(Runnable.class), eq(5L), eq(TimeUnit.SECONDS));
    }

    @Test void disabledManagerNeverConnects() throws Exception {
        manager(false).start();
        verify(client, never()).connect(any(MqttConnectOptions.class));
        verifyNoInteractions(connector);
    }

    @Test void inboundMessagesReachHandlerOffTheCallbackThread() throws Exception {
        var manager = manager(true);
        manager.messageArrived("test/door-01/ack", new MqttMessage("{\"v\":1}".getBytes()));
        verify(handler).handle("test/door-01/ack", "{\"v\":1}");
        doThrow(new IllegalStateException()).when(handler).handle(anyString(), anyString());
        assertDoesNotThrow(() -> manager.messageArrived("test/door-01/ack", new MqttMessage("{}".getBytes())));
    }
}
