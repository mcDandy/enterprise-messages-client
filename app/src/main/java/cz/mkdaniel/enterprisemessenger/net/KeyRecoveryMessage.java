package cz.mkdaniel.enterprisemessenger.net;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import cz.mkdaniel.enterprisemessenger.crypto.ChannelKeyRegistry;

/**
 * The key-recovery handshake, carried inside an ordinary end-to-end encrypted chat message.
 * <p>
 * <b>Why it travels this way.</b> A device that missed a key rotation has no way to ask for it: keys
 * move only inside an administrator's membership-change envelopes, the server has no handler for an
 * inbound {@code UNICAST_ROTATED_KEY} and drops it, and the queue holding a rotation's envelope is
 * drained by the first acknowledgement the recipient sends. The one thing the server does deliver
 * reliably to every member of a channel is a {@code CHAT_MESSAGE}, so the request and the answer ride
 * on it - already sealed with the channel key every current member shares, which is what keeps the
 * handshake member-only.
 * <p>
 * <b>Why the answer is sealed again.</b> The channel key is shared, so a reply written in the clear
 * would hand superseded keys to every member, including members who join later and are not meant to
 * read history they cannot already open. The offer is therefore sealed a second time with the ECDH
 * secret the responder shares with the requester, exactly like a rotation envelope.
 * <p>
 * <b>Framing.</b> A control message is recognised by its NUL-delimited marker, which the message input
 * cannot produce. Both directions are filtered out of the message list before rendering.
 */
public final class KeyRecoveryMessage {

    /**
     * Framing character between the parts of a handshake message. A NUL cannot be typed, so an
     * ordinary message can never look like one.
     */
    private static final char FIELD = (char) 0;

    /**
     * Marks a chat payload as handshake rather than text.
     */
    public static final String MARKER = "" + FIELD + "EMSKEY1" + FIELD;

    private static final String REQUEST = "REQ";
    private static final String OFFER = "OFFER";
    private static final char ROW_SEPARATOR = ',';
    private static final int KEY_BYTES = 32;
    private static final int UUID_BYTES = 16;
    private static final int KEY_ENTRY_BYTES = KEY_BYTES + UUID_BYTES;

    private KeyRecoveryMessage() {
    }

    /**
     * A request for the keys behind the server's key rows {@code serverRows}.
     */
    public static final class Request {
        private final long requesterUserId;
        private final List<UUID> serverRows;

        Request(long requesterUserId, List<UUID> serverRows) {
            this.requesterUserId = requesterUserId;
            this.serverRows = new ArrayList<>(serverRows);
        }

        /**
         * Requests the er user identifier.
         *
         * @return the requester user identifier result
         */
        public long requesterUserId() {
            return requesterUserId;
        }

        /**
         * The server key rows whose keys the requester is missing, in the order asked for.
         *
         * @return the server rows collection
         */
        public List<UUID> serverRows() {
            return new ArrayList<>(serverRows);
        }
    }

    /**
     * An answer addressed to {@code targetUserId}: the packed keys, sealed with the ECDH secret this
     * device shares with that user.
     */
    public static final class Offer {
        private final long targetUserId;
        private final long responderUserId;
        private final byte[] sealedKeys;

        Offer(long targetUserId, long responderUserId, byte[] sealedKeys) {
            this.targetUserId = targetUserId;
            this.responderUserId = responderUserId;
            this.sealedKeys = sealedKeys.clone();
        }

        /**
         * Returns the identifier of the user this offer targets.
         *
         * @return the target user identifier result
         */
        public long targetUserId() {
            return targetUserId;
        }

        /**
         * Whose private key sealed {@link #sealedKeys()}: the requester can only open the blob with
         * the ECDH secret it shares with this member, so its id has to travel with the answer.
         *
         * @return the responder user identifier result
         */
        public long responderUserId() {
            return responderUserId;
        }

        /**
         * Returns the sealed channel-key payload.
         *
         * @return the sealed keys data
         */
        public byte[] sealedKeys() {
            return sealedKeys.clone();
        }
    }

    /**
     * Returns whether control.
     *
     * @param text the text to process or send
     * @return control.
     */
    public static boolean isControl(final String text) {
        return text != null && text.startsWith(MARKER);
    }

    /**
     * Encodes the request.
     *
     * @param requesterUserId the ID of the user requesting key recovery
     * @param serverRows the server key-row versions included in the request
     * @return the encode request result
     */
    public static String encodeRequest(final long requesterUserId, final List<UUID> serverRows) {
        final StringBuilder rows = new StringBuilder();

        for (final UUID row : serverRows) {
            if (rows.length() > 0) {
                rows.append(ROW_SEPARATOR);
            }
            rows.append(row);
        }

        return MARKER + REQUEST + FIELD + requesterUserId + FIELD + rows;
    }

    /**
     * Encodes the offer.
     *
     * @param targetUserId the user ID that should receive the offer
     * @param responderUserId the ID of the user responding to the request
     * @param sealedKeys the encrypted channel-key data in the offer
     * @return the encode offer result
     */
    public static String encodeOffer(final long targetUserId, final long responderUserId,
                                     final byte[] sealedKeys) {
        return MARKER + OFFER + FIELD + targetUserId + FIELD + responderUserId + FIELD
                + toHex(sealedKeys);
    }

    /**
     * @return the parsed request, or {@code null} when {@code text} is not one or is malformed
     *
     * @param text the text to process or send
     */
    public static Request parseRequest(final String text) {
        final String[] fields = fields(text, REQUEST, 2);
        if (fields == null) {
            return null;
        }

        final long requesterUserId = parseLong(fields[0]);
        if (requesterUserId <= 0) {
            return null;
        }

        final List<UUID> rows = new ArrayList<>();

        for (final String row : fields[1].split(String.valueOf(ROW_SEPARATOR))) {
            final UUID parsed = parseUuid(row);

            if (parsed != null) {
                rows.add(parsed);
            }
        }

        return rows.isEmpty() ? null : new Request(requesterUserId, rows);
    }

    /**
     * @return the parsed offer, or {@code null} when {@code text} is not one or is malformed
     *
     * @param text the text to process or send
     */
    public static Offer parseOffer(final String text) {
        final String[] fields = fields(text, OFFER, 3);
        if (fields == null) {
            return null;
        }

        final long targetUserId = parseLong(fields[0]);
        final long responderUserId = parseLong(fields[1]);
        final byte[] sealedKeys = fromHex(fields[2]);

        if (targetUserId <= 0 || responderUserId <= 0 || sealedKeys == null || sealedKeys.length == 0) {
            return null;
        }

        return new Offer(targetUserId, responderUserId, sealedKeys);
    }

    /**
     * Packs recovered keys into the blob an offer carries: {@code [16-byte version][32-byte key]} per
     * key.
     *
     * @param keys the channel keys to process
     * @return the pack keys data
     */
    public static byte[] packKeys(final List<ChannelKeyRegistry.KeyVersion> keys) {
        final ByteBuffer buffer = ByteBuffer.allocate(keys.size() * KEY_ENTRY_BYTES);

        for (final ChannelKeyRegistry.KeyVersion key : keys) {
            buffer.putLong(key.version().getMostSignificantBits());
            buffer.putLong(key.version().getLeastSignificantBits());
            buffer.put(key.key());
        }

        return buffer.array();
    }

    /**
     * Reads back what {@link #packKeys(List)} wrote.
     *
     * @throws IllegalArgumentException when the blob is not a whole number of entries
     *
     * @param blob the encrypted blob to store or process
     * @return the unpack keys collection
     */
    public static List<ChannelKeyRegistry.KeyVersion> unpackKeys(final byte[] blob) {
        if (blob == null || blob.length == 0 || blob.length % KEY_ENTRY_BYTES != 0) {
            throw new IllegalArgumentException("A recovered key blob must hold whole key entries");
        }

        final List<ChannelKeyRegistry.KeyVersion> keys = new ArrayList<>();
        final ByteBuffer buffer = ByteBuffer.wrap(blob);

        while (buffer.hasRemaining()) {
            final UUID version = new UUID(buffer.getLong(), buffer.getLong());
            final byte[] key = new byte[KEY_BYTES];
            buffer.get(key);

            keys.add(new ChannelKeyRegistry.KeyVersion(version, key));
        }

        return keys;
    }

    /**
     * Splits a handshake into its fields, refusing anything that does not carry the number of fields
     * its kind requires - an offer missing the responder's id must be refused rather than read past
     * the end.
     *
     * @param text the text to process or send
     * @param expectedKind the expected key kind
     * @param required whether the value is required
     * @return the fields data
     */
    private static String[] fields(final String text, final String expectedKind, final int required) {
        if (!isControl(text)) {
            return null;
        }

        final String body = text.substring(MARKER.length());
        final int kindEnd = body.indexOf(FIELD);

        if (kindEnd <= 0 || !expectedKind.equals(body.substring(0, kindEnd))) {
            return null;
        }

        // MARKER + KIND + FIELD + field1 + FIELD + field2 + ... ; the last field keeps any NUL it
        // happens to contain, since only the separators are consumed.
        final String[] parts = body.substring(kindEnd + 1).split(String.valueOf(FIELD));

        return parts.length < required ? null : parts;
    }

    /**
     * Parses the long.
     *
     * @param value the value to process
     * @return the parse long result
     */
    private static long parseLong(final String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (final NumberFormatException malformed) {
            return -1L;
        }
    }

    /**
     * Parses the UUID.
     *
     * @param value the value to process
     * @return the parse UUID result
     */
    private static UUID parseUuid(final String value) {
        try {
            return UUID.fromString(value.trim());
        } catch (final IllegalArgumentException malformed) {
            return null;
        }
    }

    /**
     * Hex rather than Base64 so this class stays free of {@code android.util} and can be tested as
     * plain Java. An offer carries a few dozen bytes, so the doubled length costs nothing.
     *
     * @param bytes the byte array to process
     * @return the to hex result
     */
    private static String toHex(final byte[] bytes) {
        final StringBuilder hex = new StringBuilder(bytes.length * 2);

        for (final byte value : bytes) {
            hex.append(Character.forDigit((value >> 4) & 0xF, 16));
            hex.append(Character.forDigit(value & 0xF, 16));
        }

        return hex.toString();
    }

    /**
     * Decodes a hexadecimal string into its byte representation.
     *
     * @param hex the hexadecimal value to decode
     * @return the from hex data
     */
    private static byte[] fromHex(final String hex) {
        if (hex.isEmpty() || hex.length() % 2 != 0) {
            return null;
        }

        final byte[] bytes = new byte[hex.length() / 2];

        for (int i = 0; i < bytes.length; ++i) {
            final int high = Character.digit(hex.charAt(i * 2), 16);
            final int low = Character.digit(hex.charAt(i * 2 + 1), 16);

            if (high < 0 || low < 0) {
                return null;
            }

            bytes[i] = (byte) ((high << 4) | low);
        }

        return bytes;
    }
}
