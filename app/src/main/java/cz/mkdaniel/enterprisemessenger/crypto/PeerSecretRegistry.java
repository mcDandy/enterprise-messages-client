package cz.mkdaniel.enterprisemessenger.crypto;

import java.util.ArrayList;
import java.util.List;

/**
 * The ECDH secrets this device shares with other users, one per peer id.
 * <p>
 * They used to live in a {@code ConcurrentHashMap} inside {@link CryptoManager}, which meant they
 * vanished on every restart: {@code retrieveSecretSymmetricKey} returned {@code null} after a
 * relaunch and the key-rotation handler then dropped rotated keys with "No shared ECDH secret with
 * peer N yet". Sealing them here makes a secret survive a restart (the public keys needed to derive
 * them stay on the server, so nothing else has to be persisted).
 * <p>
 * Nothing is cached in RAM: {@link #secret(long)} decrypts on demand, right before the {@code Cipher}
 * is initialised, so the plaintext key lives only in the caller's local variable.
 */
public class PeerSecretRegistry {

    /** Sink key prefix that identifies peer secrets, e.g. for a backup scan. */
    public static final String KEY_PREFIX = "peer_";

    private final SecureBlobStore blobs;

    public PeerSecretRegistry(SecureBlobStore blobs) {
        this.blobs = blobs;
    }

    /**
     * Remembers the shared secret with {@code peerId}, overwriting an older one.
     *
     * @throws KeyStoreUnavailableException when the sealing key is unusable
     */
    public void store(long peerId, byte[] secret) throws KeyStoreUnavailableException {
        if (secret == null || secret.length == 0) {
            throw new IllegalArgumentException("An empty secret cannot be stored");
        }

        blobs.put(alias(peerId), secret);
    }

    /**
     * @return the shared secret, or {@code null} when none was ever derived for this peer (or the
     *         sealing key no longer opens it)
     */
    public byte[] secret(long peerId) {
        return blobs.load(alias(peerId));
    }

    public boolean knows(long peerId) {
        return blobs.contains(alias(peerId));
    }

    public void forget(long peerId) {
        blobs.remove(alias(peerId));
    }

    /** Peer ids this device holds a sealed secret for. */
    public List<Long> knownPeers() {
        final List<Long> peers = new ArrayList<>();

        for (final String key : blobs.keysWithPrefix(KEY_PREFIX)) {
            try {
                peers.add(Long.parseLong(key.substring(KEY_PREFIX.length())));
            } catch (NumberFormatException ignored) {
                // A slot that is not a peer alias (a manual edit, an older naming) - harmless.
            }
        }

        return peers;
    }

    /** Drops every peer secret: used on log-out, together with {@link SecureBlobStore#clearAll()}. */
    public void forgetAll() {
        for (final String key : blobs.keysWithPrefix(KEY_PREFIX)) {
            blobs.remove(key);
        }
    }

    private static String alias(long peerId) {
        return KEY_PREFIX + peerId;
    }
}
