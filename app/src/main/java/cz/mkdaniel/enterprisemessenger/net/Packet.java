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
 */
public class Packet {
    public static final byte TYPE_UNKNOWN = 0x0;
    public static final byte TYPE_CHAT_MESSAGE = 0x1;
    public static final byte TYPE_ACK = 0x2;
    public static final byte TYPE_KEY_ROTATION = 0xa;
    public static final byte TYPE_UNICAST_ROTATED_KEY = 0xd;

    private final byte type;
    private final int payloadLength;
    private final byte[] payload;

    public Packet(byte type, byte[] payload) {
	this.type = type;
	this.payloadLength = payload.length;
	this.payload = payload;
    }

    public Packet() {
	this(TYPE_UNKNOWN, new byte[Long.BYTES]);
    }

    public byte getType() {
	return type;
    }

    public byte[] getPayload() {
	return payload;
    }

    /**
     * Serializes this packet into the raw bytes of one WebSocket binary frame.
     * Format: 1 type byte followed by the payload; the frame boundary is the framing, so no
     * length prefix is written (see the server's {@code PacketCodec#encode}).
     * If an encryption key is provided, the payload is encrypted using AES-GCM before output.
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

    public byte[] serialize() throws Exception {
	return serialize(null, null);
    }

    /**
     * Reads an incoming packet from a stream holding exactly one frame: the 1-byte type
     * followed by the payload. If encrypted, decrypts the payload using the provided key.
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

    public static String typeName(final byte type) {
	switch (type) {
	    case TYPE_UNKNOWN: return "UNKNOWN";
	    case TYPE_CHAT_MESSAGE: return "CHAT_MESSAGE";
	    case TYPE_KEY_ROTATION: return "KEY_ROTATION";
	    case TYPE_UNICAST_ROTATED_KEY: return "UNICAST_ROTATED_KEY";
	    default: return String.format("0x%02x", type);
	}
    }

    public static Packet readFromByteArray(byte[] data) throws Exception {
	return readFromByteArray(data, null, null);
    }
}
