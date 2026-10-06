package cz.mkdaniel.enterprisemessenger.net;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import cz.mkdaniel.enterprisemessenger.crypto.CryptoManager;

/**
 * Represents a binary TCP packet for Enterprise Messenger.
 * <p>
 * Binary Wire Format:
 * <pre>
 * +-----------------------------------+-----------------------------------+
 * | Field                             | Size                              |
 * +-----------------------------------+-----------------------------------+
 * | Payload Length (Unencrypted)      | 4 bytes (int32, Big-Endian)       |
 * | Packet Type                       | 2 bytes (int16, Big-Endian)       |
 * | Flags (0x01 = Encrypted)          | 1 byte                            |
 * | Reserved                          | 1 byte                            |
 * | Timestamp                         | 8 bytes (int64, Big-Endian)       |
 * | Payload Data                      | [Payload Length] bytes            |
 * +-----------------------------------+-----------------------------------+
 * </pre>
 * <p>
 * The 4-byte Payload Length header remains UNENCRYPTED so the TCP receiver can
 * frame incoming stream packets without needing to decrypt the header first.
 */
public class Packet {

    // Packet Types
    public static final short TYPE_HANDSHAKE = 0x0001;
    public static final short TYPE_CHAT_MESSAGE = 0x0002;
    public static final short TYPE_ROOM_JOIN = 0x0003;
    public static final short TYPE_PING = 0x0004;
    public static final short TYPE_PONG = 0x0005;

    // Flags
    public static final byte FLAG_NONE = 0x00;
    public static final byte FLAG_ENCRYPTED = 0x01;

    private final short type;
    private final byte flags;
    private final long timestamp;
    private final byte[] payload;

    public Packet(short type, byte flags, long timestamp, byte[] payload) {
        this.type = type;
        this.flags = flags;
        this.timestamp = timestamp;
        this.payload = payload != null ? payload : new byte[0];
    }

    public Packet(short type, byte[] payload) {
        this(type, FLAG_NONE, System.currentTimeMillis(), payload);
    }

    public short getType() {
        return type;
    }

    public byte getFlags() {
        return flags;
    }

    public boolean isEncrypted() {
        return (flags & FLAG_ENCRYPTED) != 0;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public byte[] getPayload() {
        return payload;
    }

    /**
     * Serializes this packet into raw binary bytes for TCP transport.
     * If an encryption key is provided, the payload is encrypted using AES-GCM before output.
     * The 4-byte length field in the output remains unencrypted.
     */
    public byte[] serialize(CryptoManager cryptoManager, byte[] encryptionKey) throws Exception {
        byte[] dataToSend = payload;
        byte finalFlags = flags;

        if (encryptionKey != null && encryptionKey.length > 0 && cryptoManager != null) {
            dataToSend = cryptoManager.encryptPayload(payload, encryptionKey);
            finalFlags |= FLAG_ENCRYPTED;
        }

        int payloadLength = dataToSend.length;
        // Total wire size = 4 (length) + 2 (type) + 1 (flags) + 1 (reserved) + 8 (timestamp) + payloadLength
        int totalWireSize = 4 + 2 + 1 + 1 + 8 + payloadLength;

        byte[] result = new byte[totalWireSize];
        ByteBuffer buffer = ByteBuffer.wrap(result);
        buffer.order(ByteOrder.BIG_ENDIAN);

        buffer.putInt(payloadLength);  // Unencrypted length (4 bytes)
        buffer.putShort(type);         // Packet type (2 bytes)
        buffer.put(finalFlags);        // Flags (1 byte)
        buffer.put((byte) 0x00);       // Reserved (1 byte)
        buffer.putLong(timestamp);     // Timestamp (8 bytes)
        buffer.put(dataToSend);        // Payload (encrypted or plain)

        return result;
    }

    public byte[] serialize() throws Exception {
        return serialize(null, null);
    }

    /**
     * Reads an incoming packet from the TCP InputStream.
     * First reads the unencrypted 4-byte payload length, then reads the remaining
     * header fields and payload bytes. If encrypted, decrypts the payload using the provided key.
     */
    public static Packet readFromStream(InputStream inputStream, CryptoManager cryptoManager, byte[] encryptionKey) throws Exception {
        DataInputStream dis = (inputStream instanceof DataInputStream)
                ? (DataInputStream) inputStream
                : new DataInputStream(inputStream);

        // 1. Read unencrypted 4-byte payload length
        int payloadLength = dis.readInt();
        if (payloadLength < 0 || payloadLength > 10 * 1024 * 1024) { // 10MB safety cap
            throw new IOException("Invalid packet payload length: " + payloadLength);
        }

        // 2. Read remaining fixed header fields
        short type = dis.readShort();
        byte flags = dis.readByte();
        byte reserved = dis.readByte(); // reserved
        long timestamp = dis.readLong();

        // 3. Read raw payload bytes
        byte[] rawPayload = new byte[payloadLength];
        dis.readFully(rawPayload);

        // 4. Decrypt payload if encrypted flag is set
        byte[] finalPayload = rawPayload;
        if ((flags & FLAG_ENCRYPTED) != 0) {
            if (cryptoManager != null && encryptionKey != null && encryptionKey.length > 0) {
                finalPayload = cryptoManager.decryptPayload(rawPayload, encryptionKey);
            } else {
                throw new IllegalStateException("Received encrypted packet but no decryption key was supplied");
            }
        }

        return new Packet(type, flags, timestamp, finalPayload);
    }

    public static Packet readFromStream(InputStream inputStream) throws Exception {
        return readFromStream(inputStream, null, null);
    }

    /**
     * Reads an incoming packet from a byte array (e.g. from a WebSocket binary frame).
     */
    public static Packet readFromByteArray(byte[] data, CryptoManager cryptoManager, byte[] encryptionKey) throws Exception {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(data)) {
            return readFromStream(bais, cryptoManager, encryptionKey);
        }
    }

    public static Packet readFromByteArray(byte[] data) throws Exception {
        return readFromByteArray(data, null, null);
    }
}
