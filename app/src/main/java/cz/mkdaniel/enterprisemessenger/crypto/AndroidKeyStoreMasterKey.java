package cz.mkdaniel.enterprisemessenger.crypto;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Log;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/**
 * The master key is an AES-256-GCM key generated inside {@code AndroidKeyStore} on first use and
 * loaded from there ever after. Its bytes never leave the keystore: every {@code Cipher} this app
 * uses for at-rest sealing is initialised with the {@link SecretKey} handle the keystore hands out.
 * <p>
 * No user authentication is required, deliberately: the sealed JWT has to be readable when the app
 * reconnects from the background, and the chat keys have to be usable right after a reboot.
 * Biometric gating is a product decision that can be added later through
 * {@link KeyGenParameterSpec.Builder#setUserAuthenticationRequired(boolean)}; it would make every
 * sealed secret unreadable until the user unlocks the device.
 */
public class AndroidKeyStoreMasterKey implements MasterKeyProvider {

    private static final String TAG = "AndroidKeyStoreMasterKey";

    /** Alias of the app's master key. Renaming it orphans every sealed blob the app has written. */
    public static final String MASTER_KEY_ALIAS = "EnterpriseMessenger_Master_Key";

    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";

    @Override
    public SecretKey masterKey() throws GeneralSecurityException {
        final KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);

        try {
            keyStore.load(null);
        } catch (IOException ioException) {
            throw new GeneralSecurityException("AndroidKeyStore could not be loaded", ioException);
        }

        final KeyStore.Entry existing = keyStore.getEntry(MASTER_KEY_ALIAS, null);
        if (existing instanceof KeyStore.SecretKeyEntry) {
            return ((KeyStore.SecretKeyEntry) existing).getSecretKey();
        }

        Log.d(TAG, "No master key in KeyStore, generating one");

        final KeyGenerator generator =
                KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);

        generator.init(new KeyGenParameterSpec
                .Builder(MASTER_KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());

        return generator.generateKey();
    }
}
