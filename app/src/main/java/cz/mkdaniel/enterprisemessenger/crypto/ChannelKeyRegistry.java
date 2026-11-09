package cz.mkdaniel.enterprisemessenger.crypto;

import android.util.Log;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * The AES key of every channel this device has ever opened, sealed in the Android KeyStore.
 * <p>
 * A channel key used to be {@code SHA-256("ems-channel-" + channelId)}: public, recomputable by
 * anyone who knows the (sequential, server-supplied) channel id, and therefore no encryption at all
 * for anyone but its author. Here a channel key is 32 random bytes, generated once per channel and
 * never derived from anything the server could know. Members learn it from a peer through the
 * existing ECDH unicast path ({@code TYPE_UNICAST_ROTATED_KEY}), and the server only ever sees
 * ciphertext.
 * <p>
 * Stored value per channel is {@code [16-byte version UUID][32-byte key]} under
 * {@code channel_<id>}, plus a bounded history under {@code channel_<id>_hist} so messages written
 * before a rotation stay readable.
 */
public class ChannelKeyRegistry {

    private static final String TAG = "ChannelKeyRegistry";

    /** Sink key prefix of the current channel keys, e.g. {@code channel_42}. */
    public static final String KEY_PREFIX = "channel_";

    /** Suffix of the per-channel readable-key history, e.g. {@code channel_42_hist}. */
    public static final String HISTORY_SUFFIX = "_hist";

    /**
     * How many superseded keys per channel stay readable. Rotation is rare (only when a member
     * joins the channel fresh or re-keys it), so eight versions cover a long history of them while
     * bounding both the sealed blob and the number of GCM attempts per incoming message.
     */
    static final int MAX_HISTORY = 8;

    private static final int KEY_LENGTH_BYTES = 32;
    private static final int UUID_LENGTH_BYTES = 16;

    private final SecureBlobStore blobs;
    private final SecureRandom random = new SecureRandom();

    public ChannelKeyRegistry(SecureBlobStore blobs) {
        this.blobs = blobs;
    }

    /** A key plus the version it is known by. */
    public static final class KeyVersion {
        private final UUID version;
        private final byte[] key;

        public KeyVersion(UUID version, byte[] key) {
            this.version = version;
            this.key = key;
        }

        public UUID version() {
            return version;
        }

        public byte[] key() {
            return key;
        }
    }

    /**
     * Outcome of {@link #ensureChannelKey(long)}: the key to use and whether this device just
     * invented it, which is exactly when it has to be distributed to the other members.
     */
    public static final class GenerationResult {
        private final KeyVersion keyVersion;
        private final boolean freshlyCreated;

        GenerationResult(KeyVersion keyVersion, boolean freshlyCreated) {
            this.keyVersion = keyVersion;
            this.freshlyCreated = freshlyCreated;
        }

        public byte[] key() {
            return keyVersion.key();
        }

        public UUID version() {
            return keyVersion.version();
        }

        public boolean freshlyCreated() {
            return freshlyCreated;
        }
    }

    /**
     * Returns this device's key for {@code channelId}, creating a random one on first use.
     * Idempotent: two calls in a row hand out the same key and report
     * {@code freshlyCreated == false} the second time, so opening a channel repeatedly does not
     * re-key it.
     *
     * @throws KeyStoreUnavailableException when the sealing key is unusable and no key can be
     *                                      stored; without persistence the key would be lost on
     *                                      the next restart, so this is a hard failure
     */
    public synchronized GenerationResult ensureChannelKey(long channelId) throws KeyStoreUnavailableException {
        final KeyVersion existing = currentKeyVersion(channelId);
        if (existing != null) {
            return new GenerationResult(existing, false);
        }

        final byte[] key = new byte[KEY_LENGTH_BYTES];
        random.nextBytes(key);

        final GenerationResult created = new GenerationResult(new KeyVersion(UUID.randomUUID(), key), true);

        persist(channelId, created.version(), created.key());

        Log.d(TAG, "Generated a channel key for channel " + channelId);

        return created;
    }

    /**
     * Replaces the channel's key with a fresh random one, in response to the server asking this
     * device to re-key the channel ({@code TYPE_KEY_ROTATION}). The superseded key moves into the
     * history, so messages already sent with it stay readable.
     */
    public synchronized GenerationResult rotateChannelKey(long channelId) throws KeyStoreUnavailableException {
	final byte[] key = new byte[KEY_LENGTH_BYTES];
	random.nextBytes(key);

	final GenerationResult created =
		new GenerationResult(new KeyVersion(UUID.randomUUID(), key), true);

	final KeyVersion previous = currentKeyVersion(channelId);
	if (previous != null) {
	    appendHistory(channelId, previous);
	}

	persist(channelId, created.version(), created.key());

	Log.d(TAG, "Rotated the key of channel " + channelId);

	return created;
    }

    /**
     * Adopts a key received from another member, superseding whatever this device had. The old key
     * is moved into the history so older messages stay readable.
     *
     * @return {@code false} when {@code key} is not a usable channel key or is already the current
     *         one (nothing to do, and no reason to re-distribute it)
     */
    public synchronized boolean adoptChannelKey(long channelId, UUID version, byte[] key)
            throws KeyStoreUnavailableException {
        if (version == null || key == null || key.length != KEY_LENGTH_BYTES) {
            Log.w(TAG, "Rejected a channel key for channel " + channelId + " with a wrong shape");

            return false;
        }

        final KeyVersion existing = currentKeyVersion(channelId);
        if (existing != null
                && existing.version().equals(version)
                && Arrays.equals(existing.key(), key)) {
            return false;
        }

        if (existing != null && !existing.version().equals(version)) {
            appendHistory(channelId, existing);
        }

        persist(channelId, version, key);

        Log.d(TAG, "Adopted channel key " + version + " for channel " + channelId);

        return true;
    }

    /** Current key of the channel, or {@code null} when this device has never opened it. */
    public synchronized byte[] currentKey(long channelId) {
        final KeyVersion current = currentKeyVersion(channelId);

        return current == null ? null : current.key();
    }

    /** Version of {@link #currentKey(long)}, or {@code null} when the channel has no key. */
    public synchronized UUID currentVersion(long channelId) {
        final KeyVersion current = currentKeyVersion(channelId);

        return current == null ? null : current.version();
    }

    /**
     * Every key that could open a message of {@code channelId}: the current one first, then the
     * superseded ones newest first. A wrong candidate only fails its GCM tag check, so trying them
     * in order is safe.
     */
    public synchronized List<KeyVersion> decryptCandidates(long channelId) {
        final List<KeyVersion> candidates = new ArrayList<>();

        final KeyVersion current = currentKeyVersion(channelId);
        if (current != null) {
            candidates.add(current);
        }

        candidates.addAll(readHistory(channelId));

        return candidates;
    }

    /** Forgets a channel's key and its history, e.g. after the channel was deleted. */
    public synchronized void forget(long channelId) {
        blobs.remove(currentAlias(channelId));
        blobs.remove(historyAlias(channelId));
    }

    public void forgetAll() {
        for (final String key : blobs.keysWithPrefix(KEY_PREFIX)) {
            blobs.remove(key);
        }
    }

    /**
     * Every channel key this device holds, flattened for a backup archive. Entries whose sealed
     * blob cannot be opened (a master key that no longer exists) are skipped, which is what makes
     * an export of a partially damaged store still useful.
     */
    public synchronized List<ChannelKeyEntry> exportableEntries() {
        final List<ChannelKeyEntry> entries = new ArrayList<>();

        for (final String alias : blobs.keysWithPrefix(KEY_PREFIX)) {
            if (alias.endsWith(HISTORY_SUFFIX)) {
                continue;
            }

            final long channelId = parseChannelId(alias);
            if (channelId < 0) {
                continue;
            }

            final List<KeyVersion> versions = decryptCandidates(channelId);
            if (versions.isEmpty()) {
                continue;
            }

            entries.add(new ChannelKeyEntry(channelId, versions));
        }

        return entries;
    }

    /** One channel's keys as they travel through a backup archive. */
    public static final class ChannelKeyEntry {
        private final long channelId;
        private final List<KeyVersion> versions;

        public ChannelKeyEntry(long channelId, List<KeyVersion> versions) {
            this.channelId = channelId;
            this.versions = new ArrayList<>(versions);
        }

        public long channelId() {
            return channelId;
        }

        /** Current version first, then superseded ones newest first. */
        public List<KeyVersion> versions() {
            return new ArrayList<>(versions);
        }

        /** Appends a superseded version, used while reading a backup archive. */
        void addVersion(KeyVersion version) {
            versions.add(version);
        }
    }

    /**
     * Re-inserts backup entries into this device's store. Oldest versions are adopted first so the
     * newest one ends up current, and each adoption pushes the previous one into the history.
     *
     * @return how many channels received at least one key
     */
    public synchronized int importEntries(List<ChannelKeyEntry> entries) throws KeyStoreUnavailableException {
        if (entries == null || entries.isEmpty()) {
            return 0;
        }

        int imported = 0;

        for (final ChannelKeyEntry entry : entries) {
            final List<KeyVersion> versions = entry.versions();
            if (versions.isEmpty()) {
                continue;
            }

            // decryptCandidates() is newest-first; adopting oldest-first leaves the newest current.
            for (int i = versions.size() - 1; i >= 0; --i) {
                final KeyVersion version = versions.get(i);
                adoptChannelKey(entry.channelId(), version.version(), version.key());
            }

            ++imported;
        }

        return imported;
    }

    private KeyVersion currentKeyVersion(long channelId) {
        final byte[] stored = blobs.load(currentAlias(channelId));
        if (stored == null || stored.length != UUID_LENGTH_BYTES + KEY_LENGTH_BYTES) {
            return null;
        }

        return toKeyVersion(stored, 0);
    }

    private void persist(long channelId, UUID version, byte[] key) throws KeyStoreUnavailableException {
        final byte[] stored = new byte[UUID_LENGTH_BYTES + KEY_LENGTH_BYTES];

        writeUuid(version, stored, 0);
        System.arraycopy(key, 0, stored, UUID_LENGTH_BYTES, KEY_LENGTH_BYTES);

        blobs.put(currentAlias(channelId), stored);
    }

    private void appendHistory(long channelId, KeyVersion superseded) throws KeyStoreUnavailableException {
        final List<KeyVersion> history = readHistory(channelId);

        history.add(0, superseded);

        while (history.size() > MAX_HISTORY) {
            history.remove(history.size() - 1);
        }

        final byte[] packed = new byte[history.size() * (UUID_LENGTH_BYTES + KEY_LENGTH_BYTES)];

        for (int i = 0; i < history.size(); ++i) {
            final KeyVersion version = history.get(i);
            final int offset = i * (UUID_LENGTH_BYTES + KEY_LENGTH_BYTES);

            writeUuid(version.version(), packed, offset);
            System.arraycopy(version.key(), 0, packed, offset + UUID_LENGTH_BYTES, KEY_LENGTH_BYTES);
        }

        blobs.put(historyAlias(channelId), packed);
    }

    private List<KeyVersion> readHistory(long channelId) {
        final List<KeyVersion> history = new ArrayList<>();

        final byte[] packed = blobs.load(historyAlias(channelId));
        if (packed == null || packed.length == 0) {
            return history;
        }

        final int entryLength = UUID_LENGTH_BYTES + KEY_LENGTH_BYTES;
        if (packed.length % entryLength != 0) {
            Log.w(TAG, "History of channel " + channelId + " has a torn entry, ignoring it");

            return history;
        }

        for (int offset = 0; offset + entryLength <= packed.length; offset += entryLength) {
            history.add(toKeyVersion(packed, offset));
        }

        return history;
    }

    private static KeyVersion toKeyVersion(byte[] source, int offset) {
        final long mostSignificant = readLong(source, offset);
        final long leastSignificant = readLong(source, offset + Long.BYTES);

        final byte[] key = new byte[KEY_LENGTH_BYTES];
        System.arraycopy(source, offset + UUID_LENGTH_BYTES, key, 0, KEY_LENGTH_BYTES);

        return new KeyVersion(new UUID(mostSignificant, leastSignificant), key);
    }

    private static void writeUuid(UUID uuid, byte[] target, int offset) {
        writeLong(uuid.getMostSignificantBits(), target, offset);
        writeLong(uuid.getLeastSignificantBits(), target, offset + Long.BYTES);
    }

    private static void writeLong(long value, byte[] target, int offset) {
        for (int i = 0; i < Long.BYTES; ++i) {
            target[offset + i] = (byte) (value >>> (8 * (Long.BYTES - 1 - i)));
        }
    }

    private static long readLong(byte[] source, int offset) {
        long value = 0;

        for (int i = 0; i < Long.BYTES; ++i) {
            value = (value << 8) | (source[offset + i] & 0xFFL);
        }

        return value;
    }

    private static long parseChannelId(String alias) {
        try {
            return Long.parseLong(alias.substring(KEY_PREFIX.length()).replace(HISTORY_SUFFIX, ""));
        } catch (NumberFormatException malformed) {
            return -1L;
        }
    }

    private static String currentAlias(long channelId) {
        return KEY_PREFIX + channelId;
    }

    private static String historyAlias(long channelId) {
        return KEY_PREFIX + channelId + HISTORY_SUFFIX;
    }
}
