package cz.mkdaniel.enterprisemessenger.crypto;

import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.InvalidAlgorithmParameterException;
import java.util.Base64;
import java.util.ArrayList;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Key-value store whose values are only ever written as AES-256-GCM blobs sealed by the
 * {@link MasterKeyProvider}. Nothing secret is kept in plaintext anywhere on disk.
 * <p>
 * Blob layout: {@code [12-byte IV][AES-GCM ciphertext + 16-byte tag]}, Base64 (no wrap) so it fits
 * into a {@code String} slot. Every {@link #put} seals under a fresh IV, because GCM loses both
 * confidentiality and integrity if a nonce is ever reused with the same key. The IV is minted by the
 * provider of the master key rather than by this class: an Android KeyStore key is generated with
 * randomized encryption required, which is precisely the promise that no caller picks its nonces,
 * and it answers {@code Cipher.init} carrying an IV with
 * {@code InvalidAlgorithmParameterException: Caller-provided IV not permitted}. Reading is not
 * affected - a nonce is not secret, so {@link #load} passes the stored one in.
 * <p>
 * Reading never throws for recoverable damage: a blob that cannot be opened (corrupted file, master
 * key replaced by an OS reinstall) reads back as {@code null}, which callers treat as "the user has
 * to sign in again / restore from backup" rather than as a crash.
 */
public class SecureBlobStore {

    private static final String TAG = "SecureBlobStore";

    private static final String AES_GCM_TRANSFORM = "AES/GCM/NoPadding";
    private static final int IV_LENGTH_BYTES = 12;
    private static final int TAG_LENGTH_BITS = 128;

    private final KeyValueSink sink;
    private final MasterKeyProvider masterKeyProvider;

    public SecureBlobStore(KeyValueSink sink, MasterKeyProvider masterKeyProvider) {
        this.sink = sink;
        this.masterKeyProvider = masterKeyProvider;
    }

    /**
     * Seals {@code value} under {@code key} with a fresh IV handed out by the provider of the master
     * key, so no value is ever sealed twice under the same nonce.
     *
     * @throws KeyStoreUnavailableException when the master key cannot be created or used
     */
    public synchronized void put(String key, byte[] value) throws KeyStoreUnavailableException {
        if (value == null) {
            remove(key);

            return;
        }

        final byte[] blob;

        try {
            final Cipher cipher = Cipher.getInstance(AES_GCM_TRANSFORM);
            cipher.init(Cipher.ENCRYPT_MODE, masterKeyProvider.masterKey());

            // The nonce has to be read back before doFinal(): finishing the operation resets the
            // cipher, and without the IV in front of the ciphertext the blob could never be opened.
            final byte[] iv = cipher.getIV();
            if (iv == null || iv.length != IV_LENGTH_BYTES) {
                throw new InvalidAlgorithmParameterException("The master key offered no "
                        + IV_LENGTH_BYTES + "-byte IV, so nothing was sealed");
            }

            final byte[] ciphertext = cipher.doFinal(value);
            blob = new byte[iv.length + ciphertext.length];

            System.arraycopy(iv, 0, blob, 0, iv.length);
            System.arraycopy(ciphertext, 0, blob, iv.length, ciphertext.length);
        } catch (GeneralSecurityException securityException) {
            throw new KeyStoreUnavailableException("Could not seal '" + key + "'", securityException);
        }

        // java.util.Base64 rather than android.util.Base64: it is available since API 26 (this
        // app's minSdk is 35) and it works in JVM unit tests, which android.util.Base64 does not.
        sink.putString(key, Base64.getEncoder().encodeToString(blob));
    }

    /**
     * @return the plaintext previously stored under {@code key}, or {@code null} when the slot is
     *         empty, the blob is malformed, or no known key opens it
     */
    public synchronized byte[] load(String key) {
        final String encoded = sink.getString(key);
        if (encoded == null || encoded.isEmpty()) {
            return null;
        }

        final byte[] blob;

        try {
            blob = Base64.getDecoder().decode(encoded.getBytes(StandardCharsets.US_ASCII));
        } catch (IllegalArgumentException malformed) {
            Log.w(TAG, "Blob for '" + key + "' is not valid Base64, dropping it");
            sink.remove(key);

            return null;
        }

        if (blob.length <= IV_LENGTH_BYTES) {
            Log.w(TAG, "Blob for '" + key + "' is too short to hold an IV, dropping it");
            sink.remove(key);

            return null;
        }

        try {
            final Cipher cipher = Cipher.getInstance(AES_GCM_TRANSFORM);
            cipher.init(Cipher.DECRYPT_MODE, masterKeyProvider.masterKey(),
                    new GCMParameterSpec(TAG_LENGTH_BITS, blob, 0, IV_LENGTH_BYTES));

            return cipher.doFinal(blob, IV_LENGTH_BYTES, blob.length - IV_LENGTH_BYTES);
        } catch (GeneralSecurityException securityException) {
            // Wrong key (the master key was regenerated after an OS reinstall) or a damaged blob.
            // Not fatal: the affected secret is simply gone and the caller falls back to re-auth or
            // to a restore from a backup archive.
            Log.w(TAG, "Blob for '" + key + "' could not be opened: " + securityException.getMessage());

            return null;
        }
    }

    public synchronized void remove(String key) {
        sink.remove(key);
    }

    /** True when the slot holds a value, sealed or not. */
    public synchronized boolean contains(String key) {
        final String encoded = sink.getString(key);

        return encoded != null && !encoded.isEmpty();
    }

    public synchronized void clearAll() {
        for (final String key : new ArrayList<>(sink.keys())) {
            sink.remove(key);
        }
    }

    /**
     * Keys currently held, so a backup can walk them (see {@code KeyBackup}). The prefixes decide
     * which entries belong to a category, e.g. {@link ChannelKeyRegistry#KEY_PREFIX}.
     */
    public synchronized List<String> keysWithPrefix(String prefix) {
        final List<String> matches = new ArrayList<>();

        for (final String key : sink.keys()) {
            if (key.startsWith(prefix)) {
                matches.add(key);
            }
        }

        return matches;
    }

    /**
     * Whether the master key can be used at all. {@code false} means every sealed blob in this
     * store is unrecoverable and the UI should offer a restore instead of failing silently.
     */
    public boolean isAvailable() {
        try {
            final SecretKey key = masterKeyProvider.masterKey();

            return key != null;
        } catch (GeneralSecurityException securityException) {
            Log.w(TAG, "Master key unavailable: " + securityException.getMessage());

            return false;
        }
    }
}
