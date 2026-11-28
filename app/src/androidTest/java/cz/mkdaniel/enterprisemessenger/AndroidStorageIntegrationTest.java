package cz.mkdaniel.enterprisemessenger;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import cz.mkdaniel.enterprisemessenger.crypto.AndroidKeyStoreMasterKey;
import cz.mkdaniel.enterprisemessenger.crypto.SecureBlobStore;
import cz.mkdaniel.enterprisemessenger.crypto.SharedPreferencesSink;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

/**
 * Exercises the Android SharedPreferences and KeyStore storage adapters together on-device.
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class AndroidStorageIntegrationTest extends BaseInstrumentedTest {

    @Test
    public void sharedPreferencesSinkDropsLegacyTypeMismatches() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences prefs = context.getSharedPreferences(
                "AndroidStorageIntegrationTest", Context.MODE_PRIVATE);
        prefs.edit().clear().putBoolean("legacy_slot", true).commit();
        SharedPreferencesSink sink = new SharedPreferencesSink(prefs);

        assertNull(sink.getString("legacy_slot"));
        assertFalse(prefs.contains("legacy_slot"));
    }

    @Test
    public void sealedValueCanBeOpenedByANewStoreUsingTheAndroidKeyStore() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences prefs = context.getSharedPreferences(
                "AndroidStorageIntegrationTest", Context.MODE_PRIVATE);
        SharedPreferencesSink sink = new SharedPreferencesSink(prefs);
        String slot = "integration_" + UUID.randomUUID();
        byte[] secret = "device-backed-secret".getBytes(StandardCharsets.UTF_8);

        try {
            SecureBlobStore writer = new SecureBlobStore(sink, new AndroidKeyStoreMasterKey());
            writer.put(slot, secret);
            SecureBlobStore reader = new SecureBlobStore(sink, new AndroidKeyStoreMasterKey());

            assertArrayEquals(secret, reader.load(slot));
        } finally {
            sink.remove(slot);
        }
    }
}
