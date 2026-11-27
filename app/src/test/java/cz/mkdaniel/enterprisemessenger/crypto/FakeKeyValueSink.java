package cz.mkdaniel.enterprisemessenger.crypto;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * In-memory {@link KeyValueSink} so the sealing code can be tested on the JVM, without Robolectric
 * and without an Android KeyStore. It also exposes the raw slots, which is how the tests assert that
 * nothing secret ever reaches storage in plaintext.
 */
public class FakeKeyValueSink implements KeyValueSink {

    private final Map<String, String> slots = new HashMap<>();

    /**
     * Returns the string.
     *
     * @param key the encryption key to use
     * @return the string value
     */
    @Override
    public String getString(String key) {
        return slots.get(key);
    }

    /**
     * Stores a string value in the in-memory test sink.
     *
     * @param key the encryption key to use
     * @param value the value to process
     */
    @Override
    public void putString(String key, String value) {
        if (value == null) {
            slots.remove(key);

            return;
        }

        slots.put(key, value);
    }

    /**
     * Removes the value associated with the supplied key.
     *
     * @param key the encryption key to use
     */
    @Override
    public void remove(String key) {
        slots.remove(key);
    }

    /**
     * Returns the keys currently present in the in-memory test sink.
     *
     * @return the keys collection
     */
    @Override
    public Set<String> keys() {
        return new HashSet<>(slots.keySet());
    }

    /**
     * Every stored blob, for the "no plaintext in storage" assertions.
     *
     * @return the values collection
     */
    public Set<String> values() {
        return new HashSet<>(slots.values());
    }
}
