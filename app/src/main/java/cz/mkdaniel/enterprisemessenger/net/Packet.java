package cz.mkdaniel.enterprisemessenger.net;

import java.io.IOException;
import java.io.InputStream;
import java.io.DataInputStream;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import cz.mkdaniel.enterprisemessenger.crypto.CryptoManager;

/**
 * Represents a binary WebSocket packet for Enterprise Messenger.
 * <p>
 * Must stay in sync with the server's {@code PacketCodec} / {@code PacketType}.
 * <p>
 * Binary Wire Format (one WebSocket binary frame = one packet), matching the server's
 * {@code PacketCodec} which uses the WebSocket frame boundary as framing and therefore has
 * no length prefix:
 * <pre>
 * +-----------------------------------+-----------------------------------+
 * | Field                             | Size                              |
 * +-----------------------------------+-----------------------------------+
 * | Packet Type                       | 1 byte                            |
 * | Payload Data                      | rest of the frame                 |
 * +-----------------------------------+-----------------------------------+
 * </pre>
 * Multi-byte fields inside a payload (user ids, channel ids, UUID most/least significant
 * bits) are big-endian {@code int64}.
 * <p>
 * Authentication is no longer a packet: the JWT is passed as the {@code token} query
 * parameter of the WebSocket handshake. End-to-end encryption of chat payloads is done
 * by the caller before the payload is put into a packet (the server only stores ciphertext).
 * <p>
 * <b>Which packets carry a queue.</b> The server keeps one FIFO queue per user and sends only its
 * head, so {@link #TYPE_CHAT_MESSAGE} and {@link #TYPE_UNICAST_ROTATED_KEY} are queued deliveries
 * and each needs exactly one {@link #TYPE_ACK}: one acknowledgement pops exactly one frame. There is
 * no per-packet id in an {@code ACK}, so acknowledging anything else - {@link #TYPE_CHANNEL_MEMBER_INFO}
 * or the {@link #TYPE_UNKNOWN} error packet, both of which the server sends directly - would silently
 * discard a different pending frame and lose a message.
 */
public class Packet {
    public static final byte TYPE_UNKNOWN = 0x0;
    public static final byte TYPE_CHAT_MESSAGE = 0x1;
    public static final byte TYPE_ACK = 0x2;
    public static final byte TYPE_READ = 0x3;
    public static final byte TYPE_UNICAST_ROTATED_KEY = 0xd;
    public static final byte TYPE_CHANNEL_MEMBER_INFO = 0x0e;

    private final byte type;
    private final int payloadLength;
    private final byte[] payload;

    /**
     * Creates a packet with the supplied wire type and payload.
     *
     * @param type packet type byte
     * @param payload packet payload bytes
     */
    public Packet(byte type, byte[] payload) {
        this.type = type;
        this.payloadLength = payload.length;
        this.payload = payload;
    }

    /**
     * Creates an unknown packet with a payload containing one 64-bit zero value.
     */
    public Packet() {
        this(TYPE_UNKNOWN, new byte[Long.BYTES]);
    }

    /**
     * Reads an incoming packet from a stream holding exactly one frame: the 1-byte type
     * followed by the payload. If encrypted, decrypts the payload using the provided key.
     *
     * @param inputStream stream containing one complete binary packet frame
     * @param cryptoManager cryptographic service used to decrypt the payload, or {@code null}
     * @param encryptionKey AES key used to decrypt the payload, or {@code null} to leave it encrypted
     * @return packet parsed from the frame
     * @throws IOException if reading the frame or validating its payload size fails
     * @throws Exception if payload decryption or packet parsing fails
     */
    public static Packet readFromStream(InputStream inputStream, CryptoManager cryptoManager, byte[] encryptionKey) throws Exception {
        final var dis = (inputStream instanceof DataInputStream) ? (DataInputStream) inputStream : new DataInputStream(inputStream);
        byte type = dis.readByte();

        final var rawPayload = dis.readAllBytes();

        if (rawPayload.length > 10 * 1024 * 1024) { // 10MB safety cap
            throw new IOException("Invalid packet payload length: " + rawPayload.length);
        }

        byte[] finalPayload = rawPayload;
        if (cryptoManager != null && encryptionKey != null && encryptionKey.length > 0) {
            finalPayload = cryptoManager.decryptPayload(rawPayload, encryptionKey);
        }

        return new Packet(type, finalPayload);
    }

    /**
     * Reads an incoming packet without decrypting its payload.
     *
     * @param inputStream stream containing one complete binary packet frame
     * @return packet parsed from the frame
     * @throws Exception if reading or parsing the frame fails
     */
    public static Packet readFromStream(InputStream inputStream) throws Exception {
        return readFromStream(inputStream, null, null);
    }

    /**
     * Reads an incoming packet from a byte array (e.g. from a WebSocket binary frame).
     *
     * @param data the encoded data to decode
     * @param cryptoManager the cryptographic service used for payload operations
     * @param encryptionKey the key used to encrypt or decrypt the payload
     * @return the read from byte array result
     * @throws Exception if the read from byte array operation cannot be completed.
     */
    public static Packet readFromByteArray(byte[] data, CryptoManager cryptoManager, byte[] encryptionKey) throws Exception {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(data)) {
            return readFromStream(bais, cryptoManager, encryptionKey);
        }
    }

    /**
     * Whether the server hands this type out of the per-user FIFO queue and therefore expects an
     * {@link #TYPE_ACK} for it. That acknowledgement carries no id and pops exactly one queue row, so
     * only these two may be acknowledged: {@link #TYPE_CHANNEL_MEMBER_INFO} and the
     * {@link #TYPE_UNKNOWN} error packet are sent directly and never queued, so acknowledging one of
     * them would silently discard a different pending frame. Not acknowledging a queued one stalls
     * that user's queue.
     *
     * @param type the packet or value type
     * @return queued delivery.
     */
    public static boolean isQueuedDelivery(final byte type) {
        return type == TYPE_CHAT_MESSAGE || type == TYPE_UNICAST_ROTATED_KEY;
    }

    /**
     * Returns the symbolic name of a packet type.
     *
     * @param type the packet or value type
     * @return the type name result
     */
    public static String typeName(final byte type) {
        switch (type) {
            case TYPE_UNKNOWN:
                return "UNKNOWN";
            case TYPE_CHAT_MESSAGE:
                return "CHAT_MESSAGE";
            case TYPE_ACK:
                return "ACK";
            case TYPE_READ:
                return "READ";
            case TYPE_UNICAST_ROTATED_KEY:
                return "UNICAST_ROTATED_KEY";
            case TYPE_CHANNEL_MEMBER_INFO:
                return "CHANNEL_MEMBER_INFO";
            default:
                return String.format("0x%02x", type);
        }
    }

    /**
     * Reads the from byte array.
     *
     * @param data the encoded data to decode
     * @return the read from byte array result
     * @throws Exception if the read from byte array operation cannot be completed.
     */
    public static Packet readFromByteArray(byte[] data) throws Exception {
        return readFromByteArray(data, null, null);
    }

    /**
     * Returns the type.
     *
     * @return the type value
     */
    public byte getType() {
        return type;
    }

    /**
     * Returns the payload.
     *
     * @return the payload value
     */
    public byte[] getPayload() {
        return payload;
    }

    /**
     * Serializes this packet into the raw bytes of one WebSocket binary frame.
     * Format: 1 type byte followed by the payload; the frame boundary is the framing, so no
     * length prefix is written (see the server's {@code PacketCodec#encode}).
     * If an encryption key is provided, the payload is encrypted using AES-GCM before output.
     *
     * @param cryptoManager the cryptographic service used for payload operations
     * @param encryptionKey the key used to encrypt or decrypt the payload
     * @return the serialize data
     * @throws Exception if the serialize operation cannot be completed.
     */
    public byte[] serialize(final CryptoManager cryptoManager, final byte[] encryptionKey) throws Exception {
        byte[] outgoing = payload != null ? payload : new byte[0];
        if (cryptoManager != null && encryptionKey != null && encryptionKey.length > 0) {
            outgoing = cryptoManager.encryptPayload(outgoing, encryptionKey);
        }

        final var buffer = ByteBuffer.allocate(1 + outgoing.length);
        buffer.order(ByteOrder.BIG_ENDIAN);
        buffer.put(type);
        buffer.put(outgoing);

        return buffer.array();
    }

    /**
     * Serializes this packet into bytes for a WebSocket frame.
     *
     * @return the serialized packet bytes
     * @throws Exception if the serialize operation cannot be completed.
     */
    public byte[] serialize() throws Exception {
        return serialize(null, null);
    }
}
