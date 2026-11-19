package dev.marregui.comando.app;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import java.util.stream.Collectors;

import dev.marregui.comando.frontear.mqtt.Packet;

/// One parity scenario run against one broker: the connections it opened, and its transcript, every packet the broker
/// sent back in the order the scenario read them, as fields to compare. What two conforming brokers may answer
/// differently is masked as it is recorded: packet ids the broker chose, an assigned client id (kept as whether one
/// came), Server Keep Alive, reason strings, the user properties of acknowledgements and CONNACK (the codec drops
/// them), and the remaining Message Expiry Interval (kept as whether one came; a scenario notes how it fell). Topics
/// are written relative to the run's own prefix, `~`, so two runs, and two brokers, read alike.
final class ParityRun implements AutoCloseable {

    private final InetSocketAddress at;
    private final String broker;
    private final List<Event> events = new ArrayList<>();
    private final String prefix;
    private final List<ParityProbe> probes = new ArrayList<>();

    /// A run on `broker`, listening at `at`, whose topics and client ids start with `prefix`.
    ParityRun(final String broker, final InetSocketAddress at, final String prefix) {
        this.broker = broker;
        this.at = at;
        this.prefix = prefix;
    }

    /// Closes every connection the run opened, each attempted; the first failure is thrown, the others suppressed.
    @Override
    public void close() {
        RuntimeException failed = null;
        for (final ParityProbe p : probes) {
            try {
                p.close();
            } catch (final RuntimeException e) {
                if (failed == null) {
                    failed = e;
                } else {
                    failed.addSuppressed(e);
                }
            }
        }
        probes.clear();
        if (failed != null) {
            throw failed;
        }
    }

    /// The broker's name, as the report writes it.
    String broker() {
        return broker;
    }

    /// The run's topic `suffix`, under its prefix.
    String topic(final String suffix) {
        return prefix + '/' + suffix;
    }

    /// The run's client id `name`, unique to the run, so sessions of other runs and brokers never meet.
    String clientId(final String name) {
        return prefix.replace('/', '-') + '-' + name;
    }

    /// A new connection labelled `label`, its socket open, nothing sent yet.
    ParityProbe open(final String label) throws IOException {
        final ParityProbe p = new ParityProbe(this, label, at);
        probes.add(p);
        return p;
    }

    /// Notes `key` as `value` beside the packets, under `label`: what a scenario measured, as a field to compare.
    void note(final String label, final String key, final Object value) {
        final SequencedMap<String, String> f = new LinkedHashMap<>();
        f.put(key, String.valueOf(value));
        events.add(new Event(label, "NOTE", f));
    }

    /// The transcript so far.
    List<Event> events() {
        return List.copyOf(events);
    }

    /// Records `packet` as connection `label` read it; a CONNACK's capabilities only when `capabilities`.
    void record(final String label, final Packet packet, final boolean capabilities) {
        final SequencedMap<String, String> f = new LinkedHashMap<>();
        final String type = switch (packet) {
            case final Packet.ConnAck a -> {
                f.put("reasonCode", Packet.hex(a.reasonCode()));
                f.put("sessionPresent", String.valueOf(a.sessionPresent()));
                f.put("assignedClientId", a.assignedClientId() == null ? "none" : "set");
                if (capabilities) {
                    f.put("receiveMaximum", String.valueOf(a.receiveMaximum()));
                    f.put("maximumPacketSize", String.valueOf(a.maximumPacketSize()));
                    f.put("topicAliasMaximum", String.valueOf(a.topicAliasMaximum()));
                    f.put("maximumQos", String.valueOf(a.capabilities().maximumQos()));
                    f.put("retainAvailable", String.valueOf(a.capabilities().retainAvailable()));
                    f.put("wildcardAvailable", String.valueOf(a.capabilities().wildcardAvailable()));
                    f.put("subscriptionIdsAvailable", String.valueOf(a.capabilities().subscriptionIdsAvailable()));
                    f.put("sharedAvailable", String.valueOf(a.capabilities().sharedAvailable()));
                }
                yield "CONNACK";
            }
            case final Packet.Publish p -> {
                publish(p, f);
                yield "PUBLISH";
            }
            case final Packet.PubAck a -> {
                f.put("reasonCode", Packet.hex(a.reasonCode()));
                yield "PUBACK";
            }
            case final Packet.SubAck a -> {
                f.put("reasonCodes", codes(a.reasonCodes()));
                yield "SUBACK";
            }
            case final Packet.UnsubAck a -> {
                f.put("reasonCodes", codes(a.reasonCodes()));
                yield "UNSUBACK";
            }
            case final Packet.Disconnect d -> {
                f.put("reasonCode", Packet.hex(d.reasonCode()));
                yield "DISCONNECT";
            }
            case final Packet.PingResp _ -> "PINGRESP";
            case final Packet.Connect _, final Packet.Subscribe _, final Packet.Unsubscribe _,
                    final Packet.PingReq _ -> "UNEXPECTED-" + packet.getClass().getSimpleName().toUpperCase();
        };
        events.add(new Event(label, type, f));
    }

    /// Records a packet the codec does not read, of `type` (`PUBREC`, `PUBCOMP`), with its reason code.
    void recordQos2(final String label, final String type, final int reasonCode) {
        final SequencedMap<String, String> f = new LinkedHashMap<>();
        f.put("reasonCode", Packet.hex(reasonCode));
        events.add(new Event(label, type, f));
    }

    /// Records that connection `label` read something other than a packet: `CLOSED` at the stream's end, `NOTHING`
    /// when no packet came within the wait, `UNREADABLE` with the codec's reason code.
    void recordOther(final String label, final String type, final String detail) {
        final SequencedMap<String, String> f = new LinkedHashMap<>();
        if (!detail.isEmpty()) {
            f.put("detail", detail);
        }
        events.add(new Event(label, type, f));
    }

    /// `bytes` as text when they are short printable ASCII, else their length and hash: a payload compares by content.
    private static String text(final byte[] bytes) {
        boolean printable = bytes.length <= 64;
        for (int i = 0; printable && i < bytes.length; i++) {
            printable = bytes[i] >= 0x20 && bytes[i] < 0x7F;
        }
        return printable ? '"' + new String(bytes, StandardCharsets.US_ASCII) + '"'
                : bytes.length + " bytes #" + HexFormat.of().toHexDigits(Arrays.hashCode(bytes));
    }

    private static String codes(final List<Integer> codes) {
        return codes.stream().map(Packet::hex).collect(Collectors.joining(",", "[", "]"));
    }

    private void publish(final Packet.Publish p, final SequencedMap<String, String> f) {
        f.put("topic", relative(p.topic()));
        f.put("payload", text(p.payload()));
        f.put("qos", String.valueOf(p.qos()));
        f.put("retain", String.valueOf(p.retain()));
        f.put("dup", String.valueOf(p.dup()));
        f.put("responseTopic", p.responseTopic() == null ? "none" : relative(p.responseTopic()));
        f.put("correlation", p.correlation() == null ? "none" : text(p.correlation()));
        f.put("payloadFormat", String.valueOf(p.meta().payloadFormat()));
        f.put("messageExpiry", p.meta().messageExpirySeconds() < 0 ? "none" : "set");
        f.put("contentType", String.valueOf(p.meta().contentType()));
        f.put("userProperties", p.meta().userProperties().stream().map(u -> u.name() + '=' + u.value())
                .collect(Collectors.joining(",", "[", "]")));
    }

    private String relative(final String topic) {
        return topic.replace(prefix, "~");
    }

    /// One entry of a transcript: the connection that read it, the packet's type, and its fields as compared.
    record Event(String label, String type, SequencedMap<String, String> fields) {

        /// The entry as the report writes it.
        String describe() {
            return label + ' ' + type + (fields.isEmpty() ? "" : " " + fields);
        }
    }
}
