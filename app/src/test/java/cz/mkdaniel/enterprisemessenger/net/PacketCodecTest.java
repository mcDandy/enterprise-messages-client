package cz.mkdaniel.enterprisemessenger.net;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

/**
 * Verifies the client's framing matches the server's {@code PacketCodec}: one WebSocket binary
 * frame is {@code [1 type byte][payload]} with no length prefix, and the payload must survive
 * the trip untouched because the server stores and rebroadcasts it verbatim.
 */
public class PacketCodecTest {

    @Test
    public void serializeWritesTypeByteFollowedByRawPayload() throws Exception {
        final byte[] payload = "hello roundtrip".getBytes(StandardCharsets.UTF_8);

        final byte[] frame = new Packet(Packet.TYPE_CHAT_MESSAGE, payload).serialize();

        assertEquals("frame must be 1 type byte + payload, no length prefix",
                1 + payload.length, frame.length);
        assertEquals(Packet.TYPE_CHAT_MESSAGE, frame[0]);
    }

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

    @Test
    public void readFromByteArrayMatchesWebSocketFraming() throws Exception {
        final byte[] ciphertext = {(byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF};

        final byte[] frame = new Packet(Packet.TYPE_UNICAST_ROTATED_KEY, ciphertext).serialize();
        final Packet decoded = Packet.readFromByteArray(frame);

        assertEquals(Packet.TYPE_UNICAST_ROTATED_KEY, decoded.getType());
        assertArrayEquals(ciphertext, decoded.getPayload());
    }

    @Test
    public void errorPacketPayloadIsReadableText() throws Exception {
        final byte[] frame = new Packet(Packet.TYPE_UNKNOWN,
                "User is not in an active channel.".getBytes(StandardCharsets.UTF_8)).serialize();

        final Packet decoded = Packet.readFromByteArray(frame);

        assertEquals(Packet.TYPE_UNKNOWN, decoded.getType());
        assertEquals("User is not in an active channel.",
                new String(decoded.getPayload(), StandardCharsets.UTF_8));
    }

    @Test
    public void emptyPayloadIsAccepted() throws Exception {
        // The server accepts a zero-length ROTATE_KEY request body.
        final byte[] frame = new Packet(Packet.TYPE_KEY_ROTATION, new byte[0]).serialize();

        assertEquals(1, frame.length);

        final Packet decoded = Packet.readFromByteArray(frame);
        assertNotNull(decoded);
        assertEquals(0, decoded.getPayload().length);
    }
}
