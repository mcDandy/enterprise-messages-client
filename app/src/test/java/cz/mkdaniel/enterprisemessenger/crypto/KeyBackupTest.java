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

    /**
     * Creates the test fixture used by the test methods.
     *
     * @throws GeneralSecurityException if a cryptographic operation fails.
     */
    @Before
    public void setUp() throws GeneralSecurityException {
        sourceKeys = new ChannelKeyRegistry(
                new SecureBlobStore(new FakeKeyValueSink(), new FixedMasterKey()));

        backup = new KeyBackup(sourceKeys);
    }

    /**
     * Verifies keys round trip into a fresh store for this case.
     *
     * @throws Exception if the keys round trip into a fresh store operation cannot be completed.
     */
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

    /**
     * Verifies archive never contains a plaintext key for this case.
     *
     * @throws Exception if the archive never contains a plaintext key operation cannot be completed.
     */
    @Test
    public void archiveNeverContainsAPlaintextKey() throws Exception {
        final byte[] key = sourceKeys.ensureChannelKey(7L).key();

        final String archive = backup.export(PASSPHRASE);

        assertFalse("the armored text must not carry the key",
                archive.contains(java.util.Base64.getEncoder().encodeToString(key)));
        assertTrue(archive.contains("-----BEGIN EMS KEY BACKUP-----"));
    }

    /**
     * Verifies two exports differ because of salt and iv for this case.
     *
     * @throws Exception if the two exports differ because of salt and iv operation cannot be completed.
     */
    @Test
    public void twoExportsDifferBecauseOfSaltAndIv() throws Exception {
        sourceKeys.ensureChannelKey(7L);

        assertNotEquals(backup.export(PASSPHRASE), backup.export(PASSPHRASE));
    }

    /**
     * Verifies wrong passphrase fails as wrong passphrase and changes nothing for this case.
     *
     * @throws Exception if the wrong passphrase fails as wrong passphrase and changes nothing operation cannot be completed.
     */
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

    /**
     * Verifies foreign file is rejected as not an archive for this case.
     *
     * @throws Exception if the foreign file is rejected as not an archive operation cannot be completed.
     */
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

    /**
     * Verifies export without any key is refused for this case.
     *
     * @throws Exception if the export without any key is refused operation cannot be completed.
     */
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

    /**
     * Verifies peek does not touch the store for this case.
     *
     * @throws Exception if the peek does not touch the store operation cannot be completed.
     */
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

    /**
     * Verifies header of another app is not our archive for this case.
     *
     */
    @Test
    public void headerOfAnotherAppIsNotOurArchive() {
        assertFalse(KeyBackup.isOurArchive("hello".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertFalse(KeyBackup.isOurArchive(null));
    }

    /**
     * Verifies adopted version uuids survive the round trip for this case.
     *
     * @throws Exception if the adopted version uuids survive the round trip operation cannot be completed.
     */
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
