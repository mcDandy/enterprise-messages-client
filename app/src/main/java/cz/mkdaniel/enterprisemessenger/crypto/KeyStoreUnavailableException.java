package cz.mkdaniel.enterprisemessenger.crypto;

import java.security.GeneralSecurityException;

/**
 * The Android KeyStore master key could not be created or used.
 * <p>
 * There is deliberately no software fallback anywhere in this package: a key that leaves the
 * KeyStore is a different identity and a weaker secret, and silently substituting one would make
 * the user's old ciphertexts unreadable without him ever noticing. Callers must surface this as an
 * error (and offer a restore from a {@code KeyBackup} archive) instead.
 */
public class KeyStoreUnavailableException extends GeneralSecurityException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates an exception that describes a KeyStore failure and retains its underlying cause.
     *
     * @param message human-readable description of the failed key operation
     * @param cause underlying security exception or other failure
     */
    public KeyStoreUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Creates an exception that describes a KeyStore failure.
     *
     * @param message human-readable description of the failed key operation
     */
    public KeyStoreUnavailableException(String message) {
        super(message);
    }
}
