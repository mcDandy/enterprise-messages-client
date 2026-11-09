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

    @Override
    public String getString(String key) {
        return slots.get(key);
    }

    @Override
    public void putString(String key, String value) {
        if (value == null) {
            slots.remove(key);

            return;
        }

        slots.put(key, value);
    }

    @Override
    public void remove(String key) {
        slots.remove(key);
    }

    @Override
    public Set<String> keys() {
        return new HashSet<>(slots.keySet());
    }

    /** Every stored blob, for the "no plaintext in storage" assertions. */
    public Set<String> values() {
        return new HashSet<>(slots.values());
    }
}
