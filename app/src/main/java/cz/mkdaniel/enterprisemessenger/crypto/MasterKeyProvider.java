package cz.mkdaniel.enterprisemessenger.crypto;

import java.security.GeneralSecurityException;

import javax.crypto.SecretKey;

/**
 * Source of the symmetric key every secret this app writes to disk is sealed with.
 * <p>
 * The production implementation hands out a key that lives inside the hardware/TEE backed
 * {@code AndroidKeyStore} and can never be read out; tests inject a plain in-memory key so the
 * sealing logic can be verified on the JVM.
 */
public interface MasterKeyProvider {

    /**
     * @return the master key, ready for {@code Cipher.init}. Never {@code null}.
     * @throws GeneralSecurityException when the key does not exist, cannot be created, or cannot
     *                                  be used (e.g. it was permanently invalidated). Never
     *                                  replaced by a weaker, software-generated key.
     */
    SecretKey masterKey() throws GeneralSecurityException;
}
