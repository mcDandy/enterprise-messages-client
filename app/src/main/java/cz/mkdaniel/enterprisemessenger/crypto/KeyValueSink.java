package cz.mkdaniel.enterprisemessenger.crypto;

import java.util.Set;

/**
 * The minimal persistence a {@link SecureBlobStore} needs: opaque string slots under string keys.
 * <p>
 * Production uses {@link SharedPreferencesSink}; unit tests use an in-memory fake, which is why the
 * sealing code never touches {@code android.content.Context} or {@code SharedPreferences} itself.
 */
public interface KeyValueSink {

    /**
     * Stored value, or {@code null} when the slot is empty.
     *
     * @param key the encryption key to use
     * @return the string value
     */
    String getString(String key);

    /**
     * Stores {@code value}; {@code null} removes the slot.
     *
     * @param key the encryption key to use
     * @param value the value to process
     */
    void putString(String key, String value);

    /**
     * Removes the value associated with the supplied key.
     *
     * @param key the encryption key to use
     */
    void remove(String key);

    /**
     * Every key currently held by this sink, for {@link SecureBlobStore#clearAll()} and export.
     *
     * @return the keys collection
     */
    Set<String> keys();
}
