package cz.mkdaniel.enterprisemessenger.net;

import org.junit.Test;

import cz.mkdaniel.enterprisemessenger.crypto.CryptoManager;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Verifies token validation without opening a network connection.
 */
public class WebSocketClientTest {

    @Test
    public void missingAndInvalidTokensAreRejected() {
        WebSocketClient client = new WebSocketClient(
                CryptoManager.getInstance(null), "wss://example.invalid/ws");

        assertFalse(client.isTokenValid(null));
        assertFalse(client.isTokenValid(""));
        assertFalse(client.isTokenValid("Invalid"));
    }

    @Test
    public void tokenProviderCanSupplyAndCacheFreshToken() {
        WebSocketClient client = new WebSocketClient(
                CryptoManager.getInstance(null), "wss://example.invalid/ws");
        AtomicInteger providerCalls = new AtomicInteger();
        client.setTokenProvider(() -> providerCalls.getAndIncrement() == 0
                ? "fresh-token"
                : null);

        assertTrue(client.isTokenValid(null));
        assertTrue(client.isTokenValid(null));
        assertTrue(client.isTokenValid("explicit-token"));
        assertEquals(1, providerCalls.get());
    }
}
