package cz.mkdaniel.enterprisemessenger.net;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Verifies the client's framing matches the server's {@code PacketCodec}: one WebSocket binary
 * frame is {@code [1 type byte][payload]} with no length prefix, and the payload must survive
 * the trip untouched because the server stores and rebroadcasts it verbatim.
 */
public class PacketCodecTest {

    /**
     * Verifies serialize writes type byte followed by raw payload for this case.
     *
     * @throws Exception if the serialize writes type byte followed by raw payload operation cannot be completed.
     */
    @Test
    public void serializeWritesTypeByteFollowedByRawPayload() throws Exception {
        final byte[] payload = "hello roundtrip".getBytes(StandardCharsets.UTF_8);

        final byte[] frame = new Packet(Packet.TYPE_CHAT_MESSAGE, payload).serialize();

        assertEquals("frame must be 1 type byte + payload, no length prefix",
                1 + payload.length, frame.length);
        assertEquals(Packet.TYPE_CHAT_MESSAGE, frame[0]);
    }

    /**
     * Verifies frame round trips through frame decode for this case.
     *
     * @throws Exception if the frame round trips through frame decode operation cannot be completed.
     */
    @Test
    public void frameRoundTripsThroughFrameDecode() throws Exception {
        final byte[] payload = new byte[512];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i % 251);
        }

        final byte[] frame = new Packet(Packet.TYPE_CHAT_MESSAGE, payload).serialize();

        // Same decode the WebSocket listener performs on an incoming frame.
        final Packet decoded = new Packet(frame[0], java.util.Arrays.copyOfRange(frame, 1, frame.length));

        assertEquals(Packet.TYPE_CHAT_MESSAGE, decoded.getType());
        assertArrayEquals("server echoes the ciphertext byte for byte", payload, decoded.getPayload());
    }

    /**
     * Verifies read from byte array matches web socket framing for this case.
     *
     * @throws Exception if the read from byte array matches web socket framing operation cannot be completed.
     */
    @Test
    public void readFromByteArrayMatchesWebSocketFraming() throws Exception {
        final byte[] ciphertext = {(byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF};

        final byte[] frame = new Packet(Packet.TYPE_UNICAST_ROTATED_KEY, ciphertext).serialize();
        final Packet decoded = Packet.readFromByteArray(frame);

        assertEquals(Packet.TYPE_UNICAST_ROTATED_KEY, decoded.getType());
        assertArrayEquals(ciphertext, decoded.getPayload());
    }

    /**
     * Verifies error packet payload is readable text for this case.
     *
     * @throws Exception if the error packet payload is readable text operation cannot be completed.
     */
    @Test
    public void errorPacketPayloadIsReadableText() throws Exception {
        final byte[] frame = new Packet(Packet.TYPE_UNKNOWN,
                "User is not in an active channel.".getBytes(StandardCharsets.UTF_8)).serialize();

        final Packet decoded = Packet.readFromByteArray(frame);

        assertEquals(Packet.TYPE_UNKNOWN, decoded.getType());
        assertEquals("User is not in an active channel.",
                new String(decoded.getPayload(), StandardCharsets.UTF_8));
    }

    /**
     * Verifies empty payload is accepted for this case.
     *
     * @throws Exception if the empty payload is accepted operation cannot be completed.
     */
    @Test
    public void emptyPayloadIsAccepted() throws Exception {
        // The server accepts a zero-length ACKNOWLEDGE body, and an ACK must never carry a payload.
        final byte[] frame = new Packet(Packet.TYPE_ACK, new byte[0]).serialize();

        assertEquals(1, frame.length);

        final Packet decoded = Packet.readFromByteArray(frame);
        assertNotNull(decoded);
        assertEquals(0, decoded.getPayload().length);
    }

    /**
     * Verifies acknowledgement packet round trips and has readable type name for this case.
     *
     * @throws Exception if the acknowledgement packet round trips and has readable type name operation cannot be completed.
     */
    @Test
    public void acknowledgementPacketRoundTripsAndHasReadableTypeName() throws Exception {
        final byte[] frame = new Packet(Packet.TYPE_ACK, new byte[0]).serialize();

        final Packet decoded = Packet.readFromByteArray(frame);

        assertEquals(Packet.TYPE_ACK, decoded.getType());
        assertEquals(0, decoded.getPayload().length);
        assertEquals("ACK", Packet.typeName(decoded.getType()));
    }

    /**
     * Verifies chat message payload starts with unsigned channel identifier for this case.
     *
     * @throws Exception if the chat message payload starts with unsigned channel identifier operation cannot be completed.
     */
    @Test
    public void chatMessagePayloadStartsWithUnsignedChannelId() throws Exception {
        final byte[] ciphertext = {(byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF};
        final int channelId = 0x0001_2345;
        final ByteBuffer payload = ByteBuffer.allocate(Integer.BYTES + ciphertext.length);
        payload.putInt(channelId);
        payload.put(ciphertext);

        final Packet decoded = Packet.readFromByteArray(
                new Packet(Packet.TYPE_CHAT_MESSAGE, payload.array()).serialize());

        assertEquals(Packet.TYPE_CHAT_MESSAGE, decoded.getType());
        final ByteBuffer decodedPayload = ByteBuffer.wrap(decoded.getPayload());
        assertEquals(channelId, decodedPayload.getInt());
        assertArrayEquals(ciphertext, java.util.Arrays.copyOfRange(
                decoded.getPayload(), Integer.BYTES, decoded.getPayload().length));
    }
}
