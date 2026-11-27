package cz.mkdaniel.enterprisemessenger.crypto;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

/**
 * The at-rest sealing: nothing secret may reach storage in plaintext, and a store whose key is gone
 * must read back as empty rather than throw.
 */
public class SecureBlobStoreTest {

    private static final String SLOT = "jwt_token";
    private static final String SECRET = "eyJhbGciOiJIUzI1NiJ9.super-secret-token";

    private FakeKeyValueSink sink;
    private SecureBlobStore store;

    /**
     * Creates the test fixture used by the test methods.
     *
     * @throws GeneralSecurityException if a cryptographic operation fails.
     */
    @Before
    public void setUp() throws GeneralSecurityException {
        sink = new FakeKeyValueSink();
        store = new SecureBlobStore(sink, new FixedMasterKey());
    }

    /**
     * Verifies value round trips for this case.
     *
     * @throws Exception if the value round trips operation cannot be completed.
     */
    @Test
    public void valueRoundTrips() throws Exception {
        final byte[] secret = SECRET.getBytes(StandardCharsets.UTF_8);

        store.put(SLOT, secret);

        assertArrayEquals(secret, store.load(SLOT));
    }

    /**
     * Verifies stored value is never the plaintext for this case.
     *
     * @throws Exception if the stored value is never the plaintext operation cannot be completed.
     */
    @Test
    public void storedValueIsNeverThePlaintext() throws Exception {
        store.put(SLOT, SECRET.getBytes(StandardCharsets.UTF_8));

        final String stored = sink.getString(SLOT);

        assertFalse("the sealed blob must not contain the plaintext",
                stored.contains(SECRET));
        assertFalse("the blob must not be the Base64 of the plaintext either",
                stored.contains(java.util.Base64.getEncoder()
                        .encodeToString(SECRET.getBytes(StandardCharsets.UTF_8))));
    }

    /**
     * Verifies two writes of the same value produce different blobs for this case.
     *
     * @throws Exception if the two writes of the same value produce different blobs operation cannot be completed.
     */
    @Test
    public void twoWritesOfTheSameValueProduceDifferentBlobs() throws Exception {
        final byte[] secret = SECRET.getBytes(StandardCharsets.UTF_8);

        store.put("a", secret);
        final String first = sink.getString("a");

        store.put("a", secret);
        final String second = sink.getString("a");

        // A repeated IV under one GCM key would destroy both confidentiality and integrity, so every
        // write has to mint a fresh one.
        assertFalse(first.equals(second));
    }

    /**
     * Verifies every blob carries the iv its provider minted for it for this case.
     *
     * @throws Exception if the every blob carries the iv its provider minted for it operation cannot be completed.
     */
    @Test
    public void everyBlobCarriesTheIvItsProviderMintedForIt() throws Exception {
        final byte[] secret = SECRET.getBytes(StandardCharsets.UTF_8);

        store.put("a", secret);
        final byte[] first = java.util.Base64.getDecoder().decode(sink.getString("a"));

        store.put("a", secret);
        final byte[] second = java.util.Base64.getDecoder().decode(sink.getString("a"));

        // The IV is the first IV_LENGTH_BYTES of the blob and the cipher's provider chooses it (an
        // AndroidKeyStore key refuses one handed in by the caller), so the two blobs cannot agree on
        // it - and the store must still open the one it wrote.
        assertFalse(java.util.Arrays.equals(
                java.util.Arrays.copyOfRange(first, 0, 12),
                java.util.Arrays.copyOfRange(second, 0, 12)));
        assertArrayEquals(secret, store.load("a"));
    }

    /**
     * Verifies missing slot reads as null for this case.
     *
     */
    @Test
    public void missingSlotReadsAsNull() {
        assertNull(store.load("never-written"));
        assertFalse(store.contains("never-written"));
    }

    /**
     * Verifies removed slot reads as null for this case.
     *
     * @throws Exception if the removed slot reads as null operation cannot be completed.
     */
    @Test
    public void removedSlotReadsAsNull() throws Exception {
        store.put(SLOT, SECRET.getBytes(StandardCharsets.UTF_8));
        store.remove(SLOT);

        assertNull(store.load(SLOT));
    }

    /**
     * Verifies corrupted blob reads as null and is dropped for this case.
     *
     * @throws Exception if the corrupted blob reads as null and is dropped operation cannot be completed.
     */
    @Test
    public void corruptedBlobReadsAsNullAndIsDropped() throws Exception {
        sink.putString(SLOT, "not base64 at all !!");

        assertNull(store.load(SLOT));
        assertNull(sink.getString(SLOT));
    }

    /**
     * Verifies blob sealed by another key reads as null for this case.
     *
     * @throws Exception if the blob sealed by another key reads as null operation cannot be completed.
     */
    @Test
    public void blobSealedByAnotherKeyReadsAsNull() throws Exception {
        store.put(SLOT, SECRET.getBytes(StandardCharsets.UTF_8));

        // What the app sees after the master key was regenerated (OS reinstall, keystore reset).
        final SecureBlobStore withNewKey = new SecureBlobStore(sink, new FixedMasterKey().different());

        assertNull(withNewKey.load(SLOT));
    }

    /**
     * Verifies store without a usable master key is reported unavailable for this case.
     *
     * @throws Exception if the store without a usable master key is reported unavailable operation cannot be completed.
     */
    @Test
    public void storeWithoutAUsableMasterKeyIsReportedUnavailable() throws Exception {
        final SecureBlobStore broken = new SecureBlobStore(sink, FixedMasterKey.unavailable());

        assertFalse(broken.isAvailable());

        boolean thrown = false;

        try {
            broken.put(SLOT, SECRET.getBytes(StandardCharsets.UTF_8));
        } catch (KeyStoreUnavailableException expected) {
            thrown = true;
        }

        assertTrue("a secret that cannot be sealed must fail loudly, not fall back to plaintext",
                thrown);
    }

    /**
     * Verifies clear all empties the sink for this case.
     *
     * @throws Exception if the clear all empties the sink operation cannot be completed.
     */
    @Test
    public void clearAllEmptiesTheSink() throws Exception {
        store.put("a", "one".getBytes(StandardCharsets.UTF_8));
        store.put("b", "two".getBytes(StandardCharsets.UTF_8));

        store.clearAll();

        assertEquals(0, sink.keys().size());
    }

    /**
     * Verifies keys with prefix selects a category for this case.
     *
     * @throws Exception if the keys with prefix selects a category operation cannot be completed.
     */
    @Test
    public void keysWithPrefixSelectsACategory() throws Exception {
        store.put("peer_7", "x".getBytes(StandardCharsets.UTF_8));
        store.put("peer_9", "y".getBytes(StandardCharsets.UTF_8));
        store.put("channel_3", "z".getBytes(StandardCharsets.UTF_8));

        assertEquals(2, store.keysWithPrefix("peer_").size());
        assertTrue(store.keysWithPrefix("channel_").contains("channel_3"));
    }
}
