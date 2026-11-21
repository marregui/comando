package dev.marregui.comando.frontear.protocol;

/**
 * A PUBLISH as bytes and back, for whatever keeps a message outside a connection (a link's queue log): the packet as
 * MQTT 5 writes it (§3.3), fixed header included, so a record is readable by any MQTT tool.
 */
public final class PublishRecord {

    private PublishRecord() {
    }

    /** {@code p}'s bytes. A QoS 1 or 2 message must carry a packet id; the log's own offset is the usual one. */
    public static byte[] encode(final Packet.Publish p) throws MqttException {
        return Codec.encode(p);
    }

    /**
     * The PUBLISH {@code record} holds, read in place; fails with {@link Packet#MALFORMED} when it holds anything else.
     */
    public static Packet.Publish decode(final byte[] record) throws MqttException {
        final int header = record.length == 0 ? -1 : record[0] & 0xFF;
        if (header >>> 4 != Codec.PUBLISH) {
            throw new MqttException(Packet.MALFORMED, "record not a publish [header=" + header + ']');
        }
        final Wire.In in = new Wire.In(record, 1, record.length);
        final int length = in.vbi();
        if (length > in.remaining()) {
            throw new MqttException(Packet.MALFORMED, "record truncated [bytes=" + record.length + ']');
        }
        if (length < in.remaining()) {
            throw new MqttException(Packet.MALFORMED, "bytes after the publish [bytes=" + (in.remaining() - length)
                    + ']');
        }
        return (Packet.Publish) Codec.decode(header, in, false);
    }
}
