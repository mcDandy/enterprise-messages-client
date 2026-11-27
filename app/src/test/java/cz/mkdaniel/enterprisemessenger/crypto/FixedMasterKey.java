package cz.mkdaniel.enterprisemessenger.crypto;

import java.security.GeneralSecurityException;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * A software AES key handed out through {@link MasterKeyProvider}, so the sealed store can be
 * exercised on the JVM. Production uses {@link AndroidKeyStoreMasterKey}, whose key bytes never
 * exist outside the keystore.
 */
public class FixedMasterKey implements MasterKeyProvider {

    private final SecretKey key;
    private boolean unavailable;

    public FixedMasterKey() throws GeneralSecurityException {
        final KeyGenerator generator = KeyGenerator.getInstance("AES");
        generator.init(256);

        this.key = generator.generateKey();
    }

    /**
     * A store whose master key cannot be loaded, e.g. after a keystore reset.
     *
     * @return the unavailable result
     * @throws GeneralSecurityException if a cryptographic operation fails.
     */
    public static FixedMasterKey unavailable() throws GeneralSecurityException {
        final FixedMasterKey provider = new FixedMasterKey();
        provider.unavailable = true;

        return provider;
    }

    /**
     * A provider holding a different key: what the app sees after the master key was regenerated.
     *
     * @return the different result
     * @throws GeneralSecurityException if a cryptographic operation fails.
     */
    public FixedMasterKey different() throws GeneralSecurityException {
        return new FixedMasterKey();
    }

    /**
     * Returns the master key used by this provider.
     *
     * @return the master key result
     * @throws GeneralSecurityException if a cryptographic operation fails.
     */
    @Override
    public SecretKey masterKey() throws GeneralSecurityException {
        if (unavailable) {
            throw new GeneralSecurityException("KeyPermanentlyInvalidatedException");
        }

        // A copy, so a caller cannot tell it apart from the handle a KeyStore would hand out.
        return new SecretKeySpec(key.getEncoded(), key.getAlgorithm());
    }
}
