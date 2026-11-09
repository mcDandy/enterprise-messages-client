package cz.mkdaniel.enterprisemessenger.crypto;

import java.util.Set;

/**
 * The minimal persistence a {@link SecureBlobStore} needs: opaque string slots under string keys.
 * <p>
 * Production uses {@link SharedPreferencesSink}; unit tests use an in-memory fake, which is why the
 * sealing code never touches {@code android.content.Context} or {@code SharedPreferences} itself.
 */
public interface KeyValueSink {

    /** Stored value, or {@code null} when the slot is empty. */
    String getString(String key);

    /** Stores {@code value}; {@code null} removes the slot. */
    void putString(String key, String value);

    void remove(String key);

    /** Every key currently held by this sink, for {@link SecureBlobStore#clearAll()} and export. */
    Set<String> keys();
}
