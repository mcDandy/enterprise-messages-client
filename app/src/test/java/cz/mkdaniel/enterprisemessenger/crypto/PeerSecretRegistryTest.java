package cz.mkdaniel.enterprisemessenger.crypto;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.security.GeneralSecurityException;
import java.util.Arrays;

/**
 * Peer ECDH secrets: sealed rather than kept in a map, so a derived secret survives a restart (it
 * used to vanish, which made the rotation handler drop keys with "No shared ECDH secret with peer N
 * yet" on every fresh launch).
 */
public class PeerSecretRegistryTest {

    private SecureBlobStore store;
    private PeerSecretRegistry registry;

    /**
     * Verifies random key for this case.
     *
     * @return the random key data
     */
    private static byte[] randomKey() {
        final byte[] key = new byte[32];
        new java.security.SecureRandom().nextBytes(key);

        return Arrays.equals(key, new byte[32]) ? randomKey() : key;
    }

    /**
     * Creates the test fixture used by the test methods.
     *
     * @throws GeneralSecurityException if a cryptographic operation fails.
     */
    @Before
    public void setUp() throws GeneralSecurityException {
        store = new SecureBlobStore(new FakeKeyValueSink(), new FixedMasterKey());
        registry = new PeerSecretRegistry(store);
    }

    /**
     * Verifies secret survives a simulated restart for this case.
     *
     * @throws Exception if the secret survives a simulated restart operation cannot be completed.
     */
    @Test
    public void secretSurvivesASimulatedRestart() throws Exception {
        final byte[] secret = randomKey();

        registry.store(7L, secret);

        // Same sealed file, a brand-new registry: what a fresh process sees.
        final PeerSecretRegistry afterRestart = new PeerSecretRegistry(store);

        assertTrue(afterRestart.knows(7L));
        assertArrayEquals(secret, afterRestart.secret(7L));
    }

    /**
     * Verifies unknown peer has no secret for this case.
     *
     */
    @Test
    public void unknownPeerHasNoSecret() {
        assertFalse(registry.knows(99L));
        assertNull(registry.secret(99L));
    }

    /**
     * Verifies peers are listed and can be forgotten for this case.
     *
     * @throws Exception if the peers are listed and can be forgotten operation cannot be completed.
     */
    @Test
    public void peersAreListedAndCanBeForgotten() throws Exception {
        registry.store(7L, randomKey());
        registry.store(9L, randomKey());

        assertEquals(2, registry.knownPeers().size());
        assertTrue(registry.knownPeers().contains(7L));

        registry.forget(7L);

        assertFalse(registry.knows(7L));
        assertEquals(1, registry.knownPeers().size());
    }

    /**
     * Verifies forget all drops every peer secret but keeps other slots for this case.
     *
     * @throws Exception if the forget all drops every peer secret but keeps other slots operation cannot be completed.
     */
    @Test
    public void forgetAllDropsEveryPeerSecretButKeepsOtherSlots() throws Exception {
        registry.store(7L, randomKey());
        store.put("channel_3", randomKey());

        registry.forgetAll();

        assertEquals(0, registry.knownPeers().size());
        assertTrue(store.contains("channel_3"));
    }

    /**
     * Verifies empty secret is rejected for this case.
     *
     * @throws Exception if the empty secret is rejected operation cannot be completed.
     */
    @Test
    public void emptySecretIsRejected() throws Exception {
        boolean rejected = false;

        try {
            registry.store(7L, new byte[0]);
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }

        assertTrue(rejected);
    }
}
