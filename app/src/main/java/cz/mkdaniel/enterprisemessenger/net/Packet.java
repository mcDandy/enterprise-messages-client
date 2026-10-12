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
 * | Packet Type                       | 1 bytes (int16, Big-Endian)       |
 * | Payload Length                    | 4 bytes (int32, Big-Endian)       |
 * | Payload Data                      | [Payload Length] bytes            |
 * +-----------------------------------+-----------------------------------+
 * </pre>
 * <p>
 * The 4-byte Payload Length header remains UNENCRYPTED so the TCP receiver can
 * frame incoming stream packets without needing to decrypt the header first.
 */
public class Packet {
	public static final short TYPE_UNKNOWN = 0x0000;
	public static final short TYPE_CHAT_MESSAGE = 0x0002;
	public static final short TYPE_ROTATE_KEY = 0xa;
	public static final short TYPE_MULTICAST_ROTATED_KEY = 0xb;

	private final int type;
	private final int payloadLength;
	private final byte[] payload;

	public Packet(int type, byte[] payload) {
		this.type = type;
		this.payloadLength = payload.length;
		this.payload = payload;
	}

	public Packet() {
		this(TYPE_UNKNOWN, new byte[Long.SIZE]);
	}

	public int getType() {
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

	public static Packet readFromByteArray(byte[] data) throws Exception {
		return readFromByteArray(data, null, null);
	}
}
