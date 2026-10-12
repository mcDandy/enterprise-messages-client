package cz.mkdaniel.enterprisemessenger.net;

import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Represents a binary WebSocket packet for Enterprise Messenger.
 * <p>
 * Must stay in sync with the server's {@code PacketCodec} / {@code PacketType}.
 * <p>
 * Binary Wire Format (one WebSocket binary frame = one packet):
 * <pre>
 * +-----------------------------------+-----------------------------------+
 * | Field                             | Size                              |
 * +-----------------------------------+-----------------------------------+
 * | Packet Type                       | 1 byte                            |
 * | Payload Length                    | 4 bytes (int32, Big-Endian)       |
 * | Payload Data                      | [Payload Length] bytes            |
 * +-----------------------------------+-----------------------------------+
 * </pre>
 * <p>
 * Authentication is no longer a packet: the JWT is passed as the {@code token} query
 * parameter of the WebSocket handshake. End-to-end encryption of chat payloads is done
 * by the caller before the payload is put into a packet (the server only stores ciphertext).
 */
public class Packet {

    // Packet Types (server: PacketType)
    /** Error from the server, payload is a UTF-8 error message. */
    public static final byte TYPE_UNKNOWN = 0x00;
    /** End-to-end encrypted chat message, payload is opaque ciphertext. */
    public static final byte TYPE_CHAT_MESSAGE_ENC = 0x02;
    /** Payload: [messageId int64][action 1 byte: 1 = DELIVERED, 2 = READ]. */
    public static final byte TYPE_MESSAGE_RECEIPT = 0x03;
    /** Payload: [channelId int64] optionally followed by [channelKeyId int64]. */
    public static final byte TYPE_CHANNEL_JOIN = 0x04;
    /** Server -> client: payload is a 36-char UUID request id. Client -> server: response to it. */
    public static final byte TYPE_ROTATE_KEY = 0x0a;
    /** Rotated channel key data distributed to the channel. */
    public static final byte TYPE_MULTICAST_ROTATED_KEY = 0x0b;

    public static final byte RECEIPT_DELIVERED = 1;
    public static final byte RECEIPT_READ = 2;

    private static final int HEADER_SIZE = 1 + 4;
    private static final int MAX_PAYLOAD_LENGTH = 10 * 1024 * 1024; // 10MB safety cap

    private final byte type;
    private final byte[] payload;

    public Packet(byte type, byte[] payload) {
        this.type = type;
        this.payload = payload != null ? payload : new byte[0];
    }

    public byte getType() {
        return type;
    }

    public byte[] getPayload() {
        return payload;
    }

    /**
     * Serializes this packet into raw binary bytes for WebSocket transport.
     */
    public byte[] serialize() {
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_SIZE + payload.length);
        buffer.order(ByteOrder.BIG_ENDIAN);

        buffer.put(type);              // Packet type (1 byte)
        buffer.putInt(payload.length); // Payload length (4 bytes)
        buffer.put(payload);           // Payload

        return buffer.array();
    }

    /**
     * Reads an incoming packet from a byte array (a WebSocket binary frame).
     */
    public static Packet readFromByteArray(byte[] data) throws IOException {
        try {
            ByteBuffer buffer = ByteBuffer.wrap(data);
            buffer.order(ByteOrder.BIG_ENDIAN);

            byte type = buffer.get();
            int payloadLength = buffer.getInt();
            if (payloadLength < 0 || payloadLength > MAX_PAYLOAD_LENGTH || payloadLength > buffer.remaining()) {
                throw new IOException("Invalid packet payload length: " + payloadLength);
            }

            byte[] payload = new byte[payloadLength];
            buffer.get(payload);
            return new Packet(type, payload);
        } catch (BufferUnderflowException e) {
            throw new IOException("Truncated packet (" + data.length + " bytes)", e);
        }
    }

    public static String typeName(byte type) {
        switch (type) {
            case TYPE_UNKNOWN: return "UNKNOWN";
            case TYPE_CHAT_MESSAGE_ENC: return "CHAT_MESSAGE_ENC";
            case TYPE_MESSAGE_RECEIPT: return "MESSAGE_RECEIPT";
            case TYPE_CHANNEL_JOIN: return "CHANNEL_JOIN";
            case TYPE_ROTATE_KEY: return "ROTATE_KEY";
            case TYPE_MULTICAST_ROTATED_KEY: return "MULTICAST_ROTATED_KEY";
            default: return String.format("0x%02x", type);
        }
    }
}
