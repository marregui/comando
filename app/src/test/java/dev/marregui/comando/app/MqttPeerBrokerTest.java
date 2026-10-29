package dev.marregui.comando.app;

import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.mqtt5.message.publish.Mqtt5Publish;
import dev.marregui.comando.mcp.PeerBroker;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/// The instances' broker on the MQTT client (record 123), without a broker: no test here connects or opens a port.
class MqttPeerBrokerTest {

    private static final String INBOX = "comando/v1/local/instance/b/inbox";
    private static final String REPLY = "comando/v1/local/instance/main/reply";
    private static final String PRESENCE = "comando/v1/local/instance/b/presence";

    /// A publish carries its payload, response topic and correlation data into the client's message at least once and
    /// unretained, and reads back out of it unchanged; a retained presence with no payload, response topic or
    /// correlation reads back empty and null.
    @Test
    void aQuestionKeepsItsResponseTopicAndCorrelationDataThroughTheClientsTypes() {
        Mqtt5Publish p = MqttPeerBroker.publishOf(INBOX, bytes("{\"text\":\"what is X?\"}"), false, REPLY, bytes("id-1"));
        assertThat(p.getQos()).isEqualTo(MqttQos.AT_LEAST_ONCE);
        assertThat(p.isRetain()).isFalse();
        PeerBroker.Message m = MqttPeerBroker.message(p);
        assertThat(m.topic()).isEqualTo(INBOX);
        assertThat(m.payload()).isEqualTo(bytes("{\"text\":\"what is X?\"}"));
        assertThat(m.responseTopic()).isEqualTo(REPLY);
        assertThat(m.correlation()).isEqualTo(bytes("id-1"));

        PeerBroker.Message presence = MqttPeerBroker.message(MqttPeerBroker.publishOf(PRESENCE, new byte[0], true, null, null));
        assertThat(presence.payload()).isEmpty();
        assertThat(presence.responseTopic()).isNull();
        assertThat(presence.correlation()).isNull();
    }

    /// A delivered message goes to the handlers whose topic filter matches it, wildcard or exact, and to no other:
    /// presence reaches the presence handler, the inbox message the inbox handler, and a third instance's inbox
    /// neither.
    @Test
    void aMessageReachesOnlyTheHandlersWhoseFiltersMatchItsTopic() {
        MqttPeerBroker broker = new MqttPeerBroker("comando-test", 1, () -> false);
        List<String> presence = new CopyOnWriteArrayList<>();
        List<String> inbox = new CopyOnWriteArrayList<>();
        broker.subscribe("comando/v1/local/instance/+/presence", m -> presence.add(m.topic()));
        broker.subscribe(INBOX, m -> inbox.add(m.topic()));
        broker.deliver(MqttPeerBroker.publishOf(PRESENCE, bytes("{}"), true, null, null));
        broker.deliver(MqttPeerBroker.publishOf(INBOX, bytes("{}"), false, REPLY, bytes("id-1")));
        broker.deliver(MqttPeerBroker.publishOf("comando/v1/local/instance/c/inbox", bytes("{}"), false, null, null));
        assertThat(presence).containsExactly(PRESENCE);
        assertThat(inbox).containsExactly(INBOX);
        broker.close();
    }

    /// While the Edge is not running, following asks the question each time and never connects, and a publish fails at
    /// once instead of waiting; a second close does nothing.
    @Test
    void withNoEdgeRunningItNeverConnectsAndAPublishFailsAtOnce() {
        AtomicInteger looked = new AtomicInteger();
        MqttPeerBroker broker = new MqttPeerBroker("comando-test", 1, () -> {
            looked.incrementAndGet();
            return false;
        });
        broker.follow();
        broker.follow();
        assertThat(looked).hasValue(2);
        assertThat(broker.connected()).isFalse();
        assertThat(broker.publish(INBOX, bytes("{}"), false, REPLY, bytes("id-1"))).isCompletedExceptionally();
        broker.close();
        broker.close();
    }

    /// The reconnect delay doubles from one second (1, 2, 4) and then settles at the follow interval, however many
    /// attempts have failed.
    @Test
    void aDroppedConnectionIsRetriedSoonThenAtMostEveryFiveSeconds() {
        assertThat(MqttPeerBroker.reconnectDelay(0)).isEqualTo(1_000);
        assertThat(MqttPeerBroker.reconnectDelay(1)).isEqualTo(2_000);
        assertThat(MqttPeerBroker.reconnectDelay(2)).isEqualTo(4_000);
        assertThat(MqttPeerBroker.reconnectDelay(3)).isEqualTo(MqttPeerBroker.FOLLOW_EVERY.toMillis());
        assertThat(MqttPeerBroker.reconnectDelay(1_000)).isEqualTo(MqttPeerBroker.FOLLOW_EVERY.toMillis());
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
