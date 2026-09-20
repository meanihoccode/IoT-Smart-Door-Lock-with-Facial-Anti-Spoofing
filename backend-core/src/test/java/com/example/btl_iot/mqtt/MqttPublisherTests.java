package com.example.btl_iot.mqtt;

import org.eclipse.paho.client.mqttv3.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MqttPublisherTests {
    @Test void disconnectedBrokerReturnsFalseAndPublishesNothing() throws Exception {
        var client = mock(MqttClient.class); var publisher = new MqttPublisher();
        ReflectionTestUtils.setField(publisher,"mqttClient",client);
        ReflectionTestUtils.setField(publisher,"topicPub","test/commands");
        assertFalse(publisher.sendOpenDoorCommand());
        verify(client,never()).publish(anyString(),any(MqttMessage.class));
    }
    @Test void failedPublishReturnsFalseAndSuccessfulPublishKeepsExistingProtocol() throws Exception {
        var client = mock(MqttClient.class); var publisher = new MqttPublisher();
        ReflectionTestUtils.setField(publisher,"mqttClient",client);
        ReflectionTestUtils.setField(publisher,"topicPub","test/commands");
        when(client.isConnected()).thenReturn(true);
        assertTrue(publisher.sendOpenDoorCommand());
        verify(client).publish(eq("test/commands"),argThat(message -> message.getQos()==1 && new String(message.getPayload(),java.nio.charset.StandardCharsets.UTF_8).equals("OPEN_DOOR")));
        doThrow(new MqttException(0)).when(client).publish(anyString(),any(MqttMessage.class));
        assertFalse(publisher.sendOpenDoorCommand());
    }
}
