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

    @Before
    public void setUp() throws GeneralSecurityException {
        store = new SecureBlobStore(new FakeKeyValueSink(), new FixedMasterKey());
        registry = new ChannelKeyRegistry(store);
    }

    @Test
    public void firstUseInventsARandomKeyOfTheRightLength() throws Exception {
        final ChannelKeyRegistry.GenerationResult created = registry.ensureChannelKey(CHANNEL);

        assertTrue(created.freshlyCreated());
        assertEquals(KEY_LENGTH, created.key().length);
        assertNotNull(created.version());
    }

    @Test
    public void ensureIsIdempotentSoOpeningAChannelDoesNotRekeyIt() throws Exception {
        final ChannelKeyRegistry.GenerationResult first = registry.ensureChannelKey(CHANNEL);
        final ChannelKeyRegistry.GenerationResult second = registry.ensureChannelKey(CHANNEL);

        assertFalse(second.freshlyCreated());
        assertArrayEquals(first.key(), second.key());
        assertEquals(first.version(), second.version());
    }

    @Test
    public void twoChannelsNeverShareAKey() throws Exception {
        final byte[] first = registry.ensureChannelKey(1L).key();
        final byte[] second = registry.ensureChannelKey(2L).key();

        assertFalse(java.util.Arrays.equals(first, second));
    }

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

    @Test
    public void adoptingTheKeyWeAlreadyHaveIsANoOp() throws Exception {
        final ChannelKeyRegistry.GenerationResult own = registry.ensureChannelKey(CHANNEL);

        assertFalse(registry.adoptChannelKey(CHANNEL, own.version(), own.key()));
        assertFalse(registry.adoptChannelKey(CHANNEL, own.version(), own.key()));

        assertEquals(1, registry.decryptCandidates(CHANNEL).size());
    }

    @Test
    public void malformedAdoptionIsRejected() throws Exception {
        registry.ensureChannelKey(CHANNEL);

        final byte[] tooShort = new byte[8];

        assertFalse(registry.adoptChannelKey(CHANNEL, UUID.randomUUID(), tooShort));
        assertFalse(registry.adoptChannelKey(CHANNEL, null, new byte[KEY_LENGTH]));

        assertNotEquals(0, registry.decryptCandidates(CHANNEL).size());
    }

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

    @Test
    public void rotationReplacesTheKeyAndKeepsTheOldReadable() throws Exception {
        final byte[] original = registry.ensureChannelKey(CHANNEL).key();

        final ChannelKeyRegistry.GenerationResult rotated = registry.rotateChannelKey(CHANNEL);

        assertTrue(rotated.freshlyCreated());
        assertFalse(java.util.Arrays.equals(original, rotated.key()));
        assertArrayEquals(rotated.key(), registry.currentKey(CHANNEL));
        assertEquals(2, registry.decryptCandidates(CHANNEL).size());
    }

    @Test
    public void forgetRemovesBothTheKeyAndItsHistory() throws Exception {
        registry.ensureChannelKey(CHANNEL);
        registry.rotateChannelKey(CHANNEL);

        registry.forget(CHANNEL);

        assertNull(registry.currentKey(CHANNEL));
        assertTrue(registry.decryptCandidates(CHANNEL).isEmpty());
    }

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

    @Test
    public void forgetAllClearsEveryChannelKey() throws Exception {
        registry.ensureChannelKey(1L);
        registry.ensureChannelKey(2L);

        registry.forgetAll();

        assertNull(registry.currentKey(1L));
        assertNull(registry.currentKey(2L));
    }
}
