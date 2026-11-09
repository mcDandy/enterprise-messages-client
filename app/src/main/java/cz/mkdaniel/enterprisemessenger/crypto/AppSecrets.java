package cz.mkdaniel.enterprisemessenger.crypto;

import android.content.Context;

/**
 * The single place the app's sealed-secret machinery is assembled, so {@code AccountStore},
 * {@code ServerConnectionManager} and the settings screen all walk the same store and the same
 * master key rather than each creating their own.
 * <p>
 * Everything here is cheap to construct; the only slow part (touching the keystore) happens inside
 * the registries on first read, which is why the network code can build this on a background thread
 * during startup.
 */
public final class AppSecrets {

    private static volatile AppSecrets instance;

    private final SecureBlobStore blobs;
    private final ChannelKeyRegistry channelKeys;
    private final PeerSecretRegistry peerSecrets;

    private AppSecrets(Context context) {
        this.blobs = new SecureBlobStore(
                new SharedPreferencesSink(context), new AndroidKeyStoreMasterKey());

        this.channelKeys = new ChannelKeyRegistry(blobs);
        this.peerSecrets = new PeerSecretRegistry(blobs);
    }

    public static AppSecrets getInstance(Context context) {
        if (instance == null) {
            synchronized (AppSecrets.class) {
                if (instance == null) {
                    instance = new AppSecrets(context.getApplicationContext());
                }
            }
        }

        return instance;
    }

    /** The sealed key/value store itself, for secrets that belong to no registry (the JWT). */
    public SecureBlobStore blobs() {
        return blobs;
    }

    /** Per-channel AES keys, sealed. */
    public ChannelKeyRegistry channelKeys() {
        return channelKeys;
    }

    /** ECDH secrets shared with other users, sealed. */
    public PeerSecretRegistry peerSecrets() {
        return peerSecrets;
    }

    /** Backup of {@link #channelKeys()} to a passphrase-protected archive the user keeps. */
    public KeyBackup keyBackup() {
        return new KeyBackup(channelKeys);
    }
}
