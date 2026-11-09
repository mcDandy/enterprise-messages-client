package cz.mkdaniel.enterprisemessenger.crypto;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.HashSet;
import java.util.Set;

/**
 * {@link KeyValueSink} over a private {@link SharedPreferences} file. It holds nothing but sealed
 * blobs (see {@link SecureBlobStore}), so losing the file or reading it in plaintext is harmless:
 * the values are AES-GCM ciphertexts of a key that lives in the Android KeyStore.
 */
public class SharedPreferencesSink implements KeyValueSink {

    private static final String TAG = "SharedPreferencesSink";

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

    /**
     * An earlier revision of the app wrote some of these slots as other types (a boolean
     * {@code is_logged_in}, a long {@code user_id}). SharedPreferences throws {@link ClassCastException}
     * for a type mismatch instead of converting, so such a slot is dropped and read as empty: the
     * value is either a flag that will be written again on the next login or a cached id the app
     * re-fetches from the server.
     */
    @Override
    public String getString(String key) {
        try {
            return prefs.getString(key, null);
        } catch (ClassCastException mismatchedType) {
            Log.w(TAG, "Slot '" + key + "' was written as another type by an earlier version, dropping it");
            prefs.edit().remove(key).apply();

            return null;
        }
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
