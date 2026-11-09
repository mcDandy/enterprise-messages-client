package cz.mkdaniel.enterprisemessenger.crypto;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * The user-facing backup: an archive that round-trips, that is useless to anyone without the
 * passphrase, and that never leaves the store half-imported.
 */
public class KeyBackupTest {

    private static final char[] PASSPHRASE = "correct horse battery".toCharArray();
    private static final char[] WRONG_PASSPHRASE = "wrong horse battery".toCharArray();

    private ChannelKeyRegistry sourceKeys;
    private KeyBackup backup;

    @Before
    public void setUp() throws GeneralSecurityException {
        sourceKeys = new ChannelKeyRegistry(
                new SecureBlobStore(new FakeKeyValueSink(), new FixedMasterKey()));

        backup = new KeyBackup(sourceKeys);
    }

    @Test
    public void keysRoundTripIntoAFreshStore() throws Exception {
        final byte[] first = sourceKeys.ensureChannelKey(7L).key();
        sourceKeys.rotateChannelKey(7L);
        sourceKeys.ensureChannelKey(9L);

        final String archive = backup.export(PASSPHRASE);

        final ChannelKeyRegistry restored = new ChannelKeyRegistry(
                new SecureBlobStore(new FakeKeyValueSink(), new FixedMasterKey()));

        assertEquals(2, new KeyBackup(restored).importArchive(archive, PASSPHRASE));

        // The current version of channel 7 is the rotated one; the key it replaced stays readable.
        final List<ChannelKeyRegistry.KeyVersion> candidates = restored.decryptCandidates(7L);

        assertEquals(2, candidates.size());
        assertArrayEquals(sourceKeys.currentKey(7L), candidates.get(0).key());
        assertArrayEquals(first, candidates.get(1).key());
        assertArrayEquals(sourceKeys.currentKey(9L), restored.currentKey(9L));
    }

    @Test
    public void archiveNeverContainsAPlaintextKey() throws Exception {
        final byte[] key = sourceKeys.ensureChannelKey(7L).key();

        final String archive = backup.export(PASSPHRASE);

        assertFalse("the armored text must not carry the key",
                archive.contains(java.util.Base64.getEncoder().encodeToString(key)));
        assertTrue(archive.contains("-----BEGIN EMS KEY BACKUP-----"));
    }

    @Test
    public void twoExportsDifferBecauseOfSaltAndIv() throws Exception {
        sourceKeys.ensureChannelKey(7L);

        assertNotEquals(backup.export(PASSPHRASE), backup.export(PASSPHRASE));
    }

    @Test
    public void wrongPassphraseFailsAsWrongPassphraseAndChangesNothing() throws Exception {
        final byte[] key = sourceKeys.ensureChannelKey(7L).key();
        final String archive = backup.export(PASSPHRASE);

        final ChannelKeyRegistry target = new ChannelKeyRegistry(
                new SecureBlobStore(new FakeKeyValueSink(), new FixedMasterKey()));
        final byte[] existing = target.ensureChannelKey(7L).key();

        KeyBackup.BackupFormatException failure = null;

        try {
            new KeyBackup(target).importArchive(archive, WRONG_PASSPHRASE);
        } catch (KeyBackup.BackupFormatException expected) {
            failure = expected;
        }

        assertTrue("a wrong passphrase must be reported as such",
                failure != null && failure.isWrongPassphrase());
        assertArrayEquals(existing, target.currentKey(7L));
        assertFalse("the untouched store must still hold its own key",
                Arrays.equals(key, target.currentKey(7L)));
    }

    @Test
    public void foreignFileIsRejectedAsNotAnArchive() throws Exception {
        final ChannelKeyRegistry target = new ChannelKeyRegistry(
                new SecureBlobStore(new FakeKeyValueSink(), new FixedMasterKey()));

        KeyBackup.BackupFormatException failure = null;

        try {
            new KeyBackup(target).importArchive("just some text\nnot a backup", PASSPHRASE);
        } catch (KeyBackup.BackupFormatException expected) {
            failure = expected;
        }

        assertTrue(failure != null && !failure.isWrongPassphrase());
    }

    @Test
    public void exportWithoutAnyKeyIsRefused() throws Exception {
        boolean refused = false;

        try {
            backup.export(PASSPHRASE);
        } catch (IllegalStateException expected) {
            refused = true;
        }

        assertTrue(refused);
    }

    @Test
    public void peekDoesNotTouchTheStore() throws Exception {
        sourceKeys.ensureChannelKey(7L);
        sourceKeys.ensureChannelKey(9L);

        final String archive = backup.export(PASSPHRASE);

        final ChannelKeyRegistry target = new ChannelKeyRegistry(
                new SecureBlobStore(new FakeKeyValueSink(), new FixedMasterKey()));

        assertEquals(2, new KeyBackup(target).peekChannelCount(archive, PASSPHRASE));
        assertEquals(0, target.exportableEntries().size());
    }

    @Test
    public void headerOfAnotherAppIsNotOurArchive() {
        assertFalse(KeyBackup.isOurArchive("hello".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertFalse(KeyBackup.isOurArchive(null));
    }

    @Test
    public void adoptedVersionUuidsSurviveTheRoundTrip() throws Exception {
        sourceKeys.ensureChannelKey(7L);
        final UUID version = sourceKeys.currentVersion(7L);

        final ChannelKeyRegistry target = new ChannelKeyRegistry(
                new SecureBlobStore(new FakeKeyValueSink(), new FixedMasterKey()));

        new KeyBackup(target).importArchive(backup.export(PASSPHRASE), PASSPHRASE);

        assertEquals(version, target.currentVersion(7L));
    }
}
