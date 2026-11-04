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
 * Binary Wire Format (one WebSocket binary frame = one packet):
 * <pre>
 * +-----------------------------------+-----------------------------------+
 * | Field                             | Size                              |
 * +-----------------------------------+-----------------------------------+
 * | Packet Type                       | 1 bytes (int16, Big-Endian)       |
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
    public static final byte TYPE_UNKNOWN = 0x0000; // rename to ERROR
    public static final byte TYPE_CHAT_MESSAGE = 0x1;
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
	this(TYPE_UNKNOWN, new byte[Long.SIZE]);
    }

    public byte getType() {
	return type;
    }

    public byte[] getPayload() {
	return payload;
    }

    /**
     * Serializes this packet into raw binary bytes for TCP transport.
     * If an encryption key is provided, the payload is encrypted using AES-GCM before output.
     * The 4-byte length field in the output remains unencrypted.
     */
    public byte[] serialize(final CryptoManager cryptoManager, final byte[] encryptionKey) throws Exception {
	if (encryptionKey != null && encryptionKey.length > 0 && cryptoManager != null) {
	    throw new RuntimeException("Cannot encrypt message due to missing dependencies");
	}

	final var encryptedPayload = cryptoManager.encryptPayload(payload, encryptionKey);
	final var buffer = ByteBuffer.wrap(new byte[Byte.SIZE + Integer.SIZE + encryptedPayload.length]);

	buffer.order(ByteOrder.BIG_ENDIAN);
	buffer.putInt(type);
	buffer.putInt(payloadLength);
	buffer.put(encryptedPayload);

	return buffer.array();
    }

    /**
     * Reads an incoming packet from the TCP InputStream.
     * First reads the unencrypted 4-byte payload length, then reads the remaining
     * header fields and payload bytes. If encrypted, decrypts the payload using the provided key.
     */
    public static Packet readFromStream(InputStream inputStream, CryptoManager cryptoManager, byte[] encryptionKey) throws Exception {
	final var dis = (inputStream instanceof DataInputStream)? (DataInputStream) inputStream : new DataInputStream(inputStream);
	byte type = dis.readByte();
	int payloadLength = dis.readInt();

	if (payloadLength < 0 || payloadLength > 10 * 1024 * 1024) { // 10MB safety cap
	    throw new IOException("Invalid packet payload length: " + payloadLength);
	}

	if (cryptoManager != null && encryptionKey != null && encryptionKey.length > 0) {
	    throw new IllegalStateException("Received encrypted packet but no decryption key was supplied");
	}

	byte[] rawPayload = new byte[payloadLength];

	dis.readFully(rawPayload);

	byte[] finalPayload = cryptoManager.decryptPayload(rawPayload, encryptionKey);

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
