package cz.mkdaniel.enterprisemessenger.crypto;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

/**
 * Verifies AES-GCM payload encryption, nonce freshness, and authentication failure handling.
 */
public class CryptoManagerTest {
    private static final byte[] KEY = new byte[32];
    private static final byte[] PLAINTEXT =
            "message payload".getBytes(StandardCharsets.UTF_8);

    @Test
    public void encryptedPayloadRoundTripsAndUsesFreshIv() throws Exception {
        CryptoManager crypto = CryptoManager.getInstance(null);

        byte[] first = crypto.encryptPayload(PLAINTEXT, KEY);
        byte[] second = crypto.encryptPayload(PLAINTEXT, KEY);

        assertArrayEquals(PLAINTEXT, crypto.decryptPayload(first, KEY));
        assertFalse(Arrays.equals(Arrays.copyOfRange(first, 0, 12),
                Arrays.copyOfRange(second, 0, 12)));
    }

    @Test
    public void wrongKeyCannotAuthenticatePayload() throws Exception {
        CryptoManager crypto = CryptoManager.getInstance(null);
        byte[] encrypted = crypto.encryptPayload(PLAINTEXT, KEY);
        byte[] wrongKey = new byte[32];
        wrongKey[0] = 1;

        try {
            crypto.decryptPayload(encrypted, wrongKey);
            fail("AES-GCM must reject a payload sealed by another key");
        } catch (javax.crypto.AEADBadTagException expected) {
            // Expected authentication failure.
        }
    }

    @Test
    public void malformedPayloadAndEmptyKeyAreRejected() throws Exception {
        CryptoManager crypto = CryptoManager.getInstance(null);

        try {
            crypto.decryptPayload(new byte[12], KEY);
            fail("payload without ciphertext and tag must be rejected");
        } catch (IllegalArgumentException expected) {
            // Expected validation failure.
        }

        try {
            crypto.encryptPayload(PLAINTEXT, new byte[0]);
            fail("empty AES key must be rejected");
        } catch (IllegalArgumentException expected) {
            // Expected validation failure.
        }
    }

    @Test
    public void sha256ReturnsDeterministicDigest() throws GeneralSecurityException {
        byte[] digest = CryptoManager.sha256(PLAINTEXT);

        assertEquals(32, digest.length);
        assertArrayEquals(digest, CryptoManager.sha256(PLAINTEXT));
    }
}
