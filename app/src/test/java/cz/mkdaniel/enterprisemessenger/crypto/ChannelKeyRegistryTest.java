package cz.mkdaniel.enterprisemessenger.crypto;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.security.GeneralSecurityException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The channel keys: random per channel, stable across "joins", and with a bounded readable history
 * so a message written under a superseded key can still be opened.
 */
public class ChannelKeyRegistryTest {

    private static final long CHANNEL = 42L;
    private static final int KEY_LENGTH = 32;

    private SecureBlobStore store;
    private ChannelKeyRegistry registry;

    /**
     * Creates the test fixture used by the test methods.
     *
     * @throws GeneralSecurityException if a cryptographic operation fails.
     */
    @Before
    public void setUp() throws GeneralSecurityException {
        store = new SecureBlobStore(new FakeKeyValueSink(), new FixedMasterKey());
        registry = new ChannelKeyRegistry(store);
    }

    /**
     * Verifies first use invents a random key of the right length for this case.
     *
     * @throws Exception if the first use invents a random key of the right length operation cannot be completed.
     */
    @Test
    public void firstUseInventsARandomKeyOfTheRightLength() throws Exception {
        final ChannelKeyRegistry.GenerationResult created = registry.ensureChannelKey(CHANNEL);

        assertTrue(created.freshlyCreated());
        assertEquals(KEY_LENGTH, created.key().length);
        assertNotNull(created.version());
    }

    /**
     * Verifies ensure is idempotent so opening a channel does not rekey it for this case.
     *
     * @throws Exception if the ensure is idempotent so opening a channel does not rekey it operation cannot be completed.
     */
    @Test
    public void ensureIsIdempotentSoOpeningAChannelDoesNotRekeyIt() throws Exception {
        final ChannelKeyRegistry.GenerationResult first = registry.ensureChannelKey(CHANNEL);
        final ChannelKeyRegistry.GenerationResult second = registry.ensureChannelKey(CHANNEL);

        assertFalse(second.freshlyCreated());
        assertArrayEquals(first.key(), second.key());
        assertEquals(first.version(), second.version());
    }

    /**
     * Verifies two channels never share a key for this case.
     *
     * @throws Exception if the two channels never share a key operation cannot be completed.
     */
    @Test
    public void twoChannelsNeverShareAKey() throws Exception {
        final byte[] first = registry.ensureChannelKey(1L).key();
        final byte[] second = registry.ensureChannelKey(2L).key();

        assertFalse(java.util.Arrays.equals(first, second));
    }

    /**
     * Verifies keys do not depend on the channel identifier for this case.
     *
     * @throws Exception if the keys do not depend on the channel identifier operation cannot be completed.
     */
    @Test
    public void keysDoNotDependOnTheChannelId() throws Exception {
        // The old scheme was SHA-256("ems-channel-" + id), which anyone could recompute. Neighbouring
        // ids must therefore produce unrelated keys.
        final byte[] seven = registry.ensureChannelKey(7L).key();
        final byte[] eight = registry.ensureChannelKey(8L).key();

        assertFalse(java.util.Arrays.equals(seven, eight));

        final Set<String> seen = new HashSet<>();

        for (long id = 1; id <= 20; ++id) {
            assertTrue(seen.add(java.util.Arrays.toString(registry.ensureChannelKey(id).key())));
        }
    }

    /**
     * Verifies adopted key supersedes the current one and keeps it readable for this case.
     *
     * @throws Exception if the adopted key supersedes the current one and keeps it readable operation cannot be completed.
     */
    @Test
    public void adoptedKeySupersedesTheCurrentOneAndKeepsItReadable() throws Exception {
        final byte[] own = registry.ensureChannelKey(CHANNEL).key();

        final byte[] fromPeer = new byte[KEY_LENGTH];
        java.util.Arrays.fill(fromPeer, (byte) 0x5A);
        final UUID peerVersion = UUID.randomUUID();

        assertTrue(registry.adoptChannelKey(CHANNEL, peerVersion, fromPeer));

        assertArrayEquals(fromPeer, registry.currentKey(CHANNEL));
        assertEquals(peerVersion, registry.currentVersion(CHANNEL));

        final List<ChannelKeyRegistry.KeyVersion> candidates = registry.decryptCandidates(CHANNEL);

        assertEquals(2, candidates.size());
        assertArrayEquals(fromPeer, candidates.get(0).key());
        assertArrayEquals(own, candidates.get(1).key());
    }

    /**
     * Verifies adopting the key we already have is a no op for this case.
     *
     * @throws Exception if the adopting the key we already have is a no op operation cannot be completed.
     */
    @Test
    public void adoptingTheKeyWeAlreadyHaveIsANoOp() throws Exception {
        final ChannelKeyRegistry.GenerationResult own = registry.ensureChannelKey(CHANNEL);

        assertFalse(registry.adoptChannelKey(CHANNEL, own.version(), own.key()));
        assertFalse(registry.adoptChannelKey(CHANNEL, own.version(), own.key()));

        assertEquals(1, registry.decryptCandidates(CHANNEL).size());
    }

    /**
     * Verifies malformed adoption is rejected for this case.
     *
     * @throws Exception if the malformed adoption is rejected operation cannot be completed.
     */
    @Test
    public void malformedAdoptionIsRejected() throws Exception {
        registry.ensureChannelKey(CHANNEL);

        final byte[] tooShort = new byte[8];

        assertFalse(registry.adoptChannelKey(CHANNEL, UUID.randomUUID(), tooShort));
        assertFalse(registry.adoptChannelKey(CHANNEL, null, new byte[KEY_LENGTH]));

        assertNotEquals(0, registry.decryptCandidates(CHANNEL).size());
    }

    /**
     * Verifies history stays within its bound for this case.
     *
     * @throws Exception if the history stays within its bound operation cannot be completed.
     */
    @Test
    public void historyStaysWithinItsBound() throws Exception {
        registry.ensureChannelKey(CHANNEL);

        for (int i = 0; i < ChannelKeyRegistry.MAX_HISTORY + 5; ++i) {
            final byte[] key = new byte[KEY_LENGTH];
            java.util.Arrays.fill(key, (byte) i);

            registry.adoptChannelKey(CHANNEL, UUID.randomUUID(), key);
        }

        final List<ChannelKeyRegistry.KeyVersion> candidates = registry.decryptCandidates(CHANNEL);

        assertEquals(ChannelKeyRegistry.MAX_HISTORY + 1, candidates.size());
    }

    /**
     * Verifies rotation replaces the key and keeps the old readable for this case.
     *
     * @throws Exception if the rotation replaces the key and keeps the old readable operation cannot be completed.
     */
    @Test
    public void rotationReplacesTheKeyAndKeepsTheOldReadable() throws Exception {
        final byte[] original = registry.ensureChannelKey(CHANNEL).key();

        final ChannelKeyRegistry.GenerationResult rotated = registry.rotateChannelKey(CHANNEL);

        assertTrue(rotated.freshlyCreated());
        assertFalse(java.util.Arrays.equals(original, rotated.key()));
        assertArrayEquals(rotated.key(), registry.currentKey(CHANNEL));
        assertEquals(2, registry.decryptCandidates(CHANNEL).size());
    }

    /**
     * Verifies forget removes both the key and its history for this case.
     *
     * @throws Exception if the forget removes both the key and its history operation cannot be completed.
     */
    @Test
    public void forgetRemovesBothTheKeyAndItsHistory() throws Exception {
        registry.ensureChannelKey(CHANNEL);
        registry.rotateChannelKey(CHANNEL);

        registry.forget(CHANNEL);

        assertNull(registry.currentKey(CHANNEL));
        assertTrue(registry.decryptCandidates(CHANNEL).isEmpty());
    }

    /**
     * Verifies exportable entries carry current key and history for this case.
     *
     * @throws Exception if the exportable entries carry current key and history operation cannot be completed.
     */
    @Test
    public void exportableEntriesCarryCurrentKeyAndHistory() throws Exception {
        registry.ensureChannelKey(1L);
        registry.rotateChannelKey(1L);
        registry.ensureChannelKey(2L);

        final List<ChannelKeyRegistry.ChannelKeyEntry> entries = registry.exportableEntries();

        assertEquals(2, entries.size());

        for (final ChannelKeyRegistry.ChannelKeyEntry entry : entries) {
            assertTrue(entry.channelId() >= 0);

            for (final ChannelKeyRegistry.KeyVersion version : entry.versions()) {
                assertEquals(KEY_LENGTH, version.key().length);
                assertNotNull(version.version());
            }
        }

        assertEquals(2, entries.get(0).versions().size() + entries.get(1).versions().size() - 1);
    }

    /**
     * Verifies imported entries end with the newest version current for this case.
     *
     * @throws Exception if the imported entries end with the newest version current operation cannot be completed.
     */
    @Test
    public void importedEntriesEndWithTheNewestVersionCurrent() throws Exception {
        registry.ensureChannelKey(CHANNEL);
        final ChannelKeyRegistry.KeyVersion newest = registry.decryptCandidates(CHANNEL).get(0);

        final List<ChannelKeyRegistry.ChannelKeyEntry> entries = registry.exportableEntries();

        final SecureBlobStore otherStore = new SecureBlobStore(new FakeKeyValueSink(), new FixedMasterKey());
        final ChannelKeyRegistry other = new ChannelKeyRegistry(otherStore);

        assertEquals(1, other.importEntries(entries));

        assertArrayEquals(newest.key(), other.currentKey(CHANNEL));
        assertEquals(newest.version(), other.currentVersion(CHANNEL));
    }

    /**
     * Verifies forget all clears every channel key for this case.
     *
     * @throws Exception if the forget all clears every channel key operation cannot be completed.
     */
    @Test
    public void forgetAllClearsEveryChannelKey() throws Exception {
        registry.ensureChannelKey(1L);
        registry.ensureChannelKey(2L);

        registry.forgetAll();

        assertNull(registry.currentKey(1L));
        assertNull(registry.currentKey(2L));
    }

    /**
     * Verifies key epoch moves only when the key material changes for this case.
     *
     * @throws Exception if the key epoch moves only when the key material changes operation cannot be completed.
     */
    @Test
    public void keyEpochMovesOnlyWhenTheKeyMaterialChanges() throws Exception {
        // A reader caches the verdict "no key of this device opens that message" and retries the
        // message once the epoch moves. The counter therefore has to react to every way a key can
        // appear or disappear, and to nothing that leaves the keys untouched.
        long previous = registry.keyEpoch();

        registry.ensureChannelKey(CHANNEL);
        assertTrue(registry.keyEpoch() > previous);

        // Reading must not look like a change: a poll would otherwise throw away the very cache it
        // has just filled.
        previous = registry.keyEpoch();
        registry.ensureChannelKey(CHANNEL);
        registry.currentKey(CHANNEL);
        registry.currentVersion(CHANNEL);
        registry.decryptCandidates(CHANNEL);
        registry.exportableEntries();
        assertEquals(previous, registry.keyEpoch());

        final byte[] fromPeer = new byte[KEY_LENGTH];
        java.util.Arrays.fill(fromPeer, (byte) 0x5A);
        final UUID peerVersion = UUID.randomUUID();
        assertTrue(registry.adoptChannelKey(CHANNEL, peerVersion, fromPeer));
        assertTrue(registry.keyEpoch() > previous);

        // Being handed the key this device already holds is not a change.
        previous = registry.keyEpoch();
        assertFalse(registry.adoptChannelKey(CHANNEL, peerVersion, fromPeer));
        assertEquals(previous, registry.keyEpoch());

        registry.rotateChannelKey(CHANNEL);
        assertTrue(registry.keyEpoch() > previous);

        registry.forget(CHANNEL);
        assertTrue(registry.keyEpoch() > previous);

        registry.ensureChannelKey(CHANNEL);
        previous = registry.keyEpoch();
        registry.forgetAll();
        assertTrue(registry.keyEpoch() > previous);
    }

    /**
     * Verifies replayed envelope of a superseded key does not downgrade the channel for this case.
     *
     * @throws Exception if the replayed envelope of a superseded key does not downgrade the channel operation cannot be completed.
     */
    @Test
    public void replayedEnvelopeOfASupersededKeyDoesNotDowngradeTheChannel() throws Exception {
        final UUID firstVersion = registry.ensureChannelKey(CHANNEL).version();
        final byte[] firstKey = registry.currentKey(CHANNEL);

        final byte[] fromPeer = new byte[KEY_LENGTH];
        java.util.Arrays.fill(fromPeer, (byte) 0x5A);
        final UUID newerVersion = UUID.randomUUID();
        assertTrue(registry.adoptChannelKey(CHANNEL, newerVersion, fromPeer));

        // The server re-offers a key envelope until it is acknowledged, so an old envelope can arrive
        // after a newer rotation had already been adopted. The version uuids are random and carry no
        // order, so the history is the only evidence of which key is the newer one.
        assertFalse(registry.adoptChannelKey(CHANNEL, firstVersion, firstKey));

        assertArrayEquals(fromPeer, registry.currentKey(CHANNEL));
        assertEquals(newerVersion, registry.currentVersion(CHANNEL));

        // The superseded key stays readable, which is all a replay has left to offer: newest first.
        final List<ChannelKeyRegistry.KeyVersion> candidates = registry.decryptCandidates(CHANNEL);
        assertEquals(2, candidates.size());
        assertEquals(newerVersion, candidates.get(0).version());
        assertEquals(firstVersion, candidates.get(1).version());
    }

    /**
     * Verifies server row map names the key version that sealed a message for this case.
     *
     * @throws Exception if the server row map names the key version that sealed a message operation cannot be completed.
     */
    @Test
    public void serverRowMapNamesTheKeyVersionThatSealedAMessage() throws Exception {
        final UUID ownVersion = registry.ensureChannelKey(CHANNEL).version();
        final UUID serverRow = UUID.randomUUID();

        assertNull(registry.clientVersionFor(CHANNEL, serverRow));

        registry.rememberServerVersion(CHANNEL, serverRow, ownVersion);
        assertEquals(ownVersion, registry.clientVersionFor(CHANNEL, serverRow));

        // A later rotation reported under the same row replaces the pairing instead of leaving a
        // stale one behind.
        final byte[] fromPeer = new byte[KEY_LENGTH];
        java.util.Arrays.fill(fromPeer, (byte) 0x7C);
        final UUID peerVersion = UUID.randomUUID();
        assertTrue(registry.adoptChannelKey(CHANNEL, peerVersion, fromPeer));

        registry.rememberServerVersion(CHANNEL, serverRow, peerVersion);
        assertEquals(peerVersion, registry.clientVersionFor(CHANNEL, serverRow));

        // A rotation this device never took part in has no key to name - the precise answer for a
        // payload it cannot read.
        assertNull(registry.clientVersionFor(CHANNEL, UUID.randomUUID()));

        registry.forget(CHANNEL);
        assertNull(registry.clientVersionFor(CHANNEL, serverRow));
    }

    /**
     * Verifies recovered key becomes readable without becoming current for this case.
     *
     * @throws Exception if the recovered key becomes readable without becoming current operation cannot be completed.
     */
    @Test
    public void recoveredKeyBecomesReadableWithoutBecomingCurrent() throws Exception {
        registry.ensureChannelKey(CHANNEL);

        final byte[] fromPeer = new byte[KEY_LENGTH];
        java.util.Arrays.fill(fromPeer, (byte) 0x39);
        final UUID currentVersion = UUID.randomUUID();
        assertTrue(registry.adoptChannelKey(CHANNEL, currentVersion, fromPeer));

        final long epochBefore = registry.keyEpoch();
        final byte[] recovered = new byte[KEY_LENGTH];
        java.util.Arrays.fill(recovered, (byte) 0x51);
        final UUID recoveredVersion = UUID.randomUUID();

        // A key recovered from a peer has to open old messages without sending this device back to an
        // older key for new ones.
        assertTrue(registry.addReadableKey(CHANNEL, recoveredVersion, recovered));
        assertEquals(currentVersion, registry.currentVersion(CHANNEL));
        assertTrue(registry.keyEpoch() > epochBefore);

        final List<ChannelKeyRegistry.KeyVersion> candidates = registry.decryptCandidates(CHANNEL);
        assertEquals(3, candidates.size());
        assertEquals(currentVersion, candidates.get(0).version());
        assertEquals(recoveredVersion, candidates.get(1).version());

        // Known keys are refused, including the current one and a wrong length.
        assertFalse(registry.addReadableKey(CHANNEL, recoveredVersion, recovered));
        assertFalse(registry.addReadableKey(CHANNEL, currentVersion, fromPeer));
        assertFalse(registry.addReadableKey(CHANNEL, UUID.randomUUID(), new byte[KEY_LENGTH - 1]));
    }
}
