package com.example.btl_iot.mqtt;

import org.eclipse.paho.client.mqttv3.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MqttPublisherTests {
    @Test void disconnectedBrokerReturnsFalseAndPublishesNothing() throws Exception {
        var client = mock(IMqttClient.class); var publisher = new MqttPublisher(client, "test");
        assertFalse(publisher.publishCommand("door-01", "{}"));
        verify(client, never()).publish(anyString(), any(MqttMessage.class));
    }
    @Test void commandsArePerDeviceQos1AndNeverRetained() throws Exception {
        var client = mock(IMqttClient.class); var publisher = new MqttPublisher(client, "test");
        when(client.isConnected()).thenReturn(true);
        assertTrue(publisher.publishCommand("door-01", "{\"v\":1}"));
        verify(client).publish(eq("test/door-01/cmd"), argThat(message -> message.getQos() == 1 && !message.isRetained()
                && new String(message.getPayload(), java.nio.charset.StandardCharsets.UTF_8).equals("{\"v\":1}")));
        doThrow(new MqttException(0)).when(client).publish(anyString(), any(MqttMessage.class));
        assertFalse(publisher.publishCommand("door-01", "{}"));
    }
}
