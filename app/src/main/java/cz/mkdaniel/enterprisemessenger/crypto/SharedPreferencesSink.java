package cz.mkdaniel.enterprisemessenger.crypto;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

/**
 * {@link KeyValueSink} over a private {@link SharedPreferences} file. It holds nothing but sealed
 * blobs (see {@link SecureBlobStore}), so losing the file or reading it in plaintext is harmless:
 * the values are AES-GCM ciphertexts of a key that lives in the Android KeyStore.
 */
public class SharedPreferencesSink implements KeyValueSink {

    /** File name of the sealed store. Kept out of the system backup (see {@code allowBackup}). */
    public static final String PREF_NAME = "EnterpriseMessenger_Secrets";

    private final SharedPreferences prefs;

    public SharedPreferencesSink(Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public SharedPreferencesSink(SharedPreferences prefs) {
        this.prefs = prefs;
    }

    @Override
    public String getString(String key) {
        return prefs.getString(key, null);
    }

    @Override
    public void putString(String key, String value) {
        if (value == null) {
            remove(key);
            return;
        }

        prefs.edit().putString(key, value).apply();
    }

    @Override
    public void remove(String key) {
        prefs.edit().remove(key).apply();
    }

    @Override
    public Set<String> keys() {
        return new HashSet<>(prefs.getAll().keySet());
    }
}
