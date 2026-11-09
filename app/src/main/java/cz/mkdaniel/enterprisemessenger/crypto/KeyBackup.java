package cz.mkdaniel.enterprisemessenger.crypto;

import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * User-driven backup of this device's channel keys.
 * <p>
 * A channel key exists only on the members' devices, and the Android KeyStore key that seals it
 * cannot be exported and does not survive a restore onto another device, a bootloader unlock or a
 * keystore reset. Without a backup, losing the phone means losing the ability to read any history of
 * a channel - and if this device was the only member that had ever generated that channel's key, the
 * other members lose it too as soon as they all reinstall.
 * <p>
 * The archive is a single armored text file the user keeps himself; the app never uploads it. Its
 * only protection is the passphrase he types, so the key is derived with PBKDF2-HMAC-SHA256 at a high
 * iteration count with a random per-archive salt, and the payload is AES-256-GCM. A wrong passphrase
 * fails the GCM tag, which is reported as a wrong passphrase rather than as a damaged file.
 * <p>
 * What is inside: the channel keys, current version plus readable history. What is deliberately not:
 * the identity EC key pair (non-exportable by design, and {@link CryptoManager} generates a fresh one
 * on a new device anyway) and the per-peer ECDH secrets (re-derivable on the new device from the
 * identity key pair and the peers' public keys, which the server already has).
 * <p>
 * The plaintext body is a small line format rather than JSON so the whole format stays readable in a
 * test JVM, where {@code org.json} is only a stub:
 * <pre>
 * EMSKEYBACKUP1
 * &lt;channelId&gt;|&lt;keyVersionUuid&gt;|&lt;base64 key&gt;
 * ...
 * </pre>
 */
public class KeyBackup {

    private static final String TAG = "KeyBackup";

    private static final String BEGIN_MARKER = "-----BEGIN EMS KEY BACKUP-----";
    private static final String END_MARKER = "-----END EMS KEY BACKUP-----";

    /** First line of the decrypted body; also guards against importing some other text file. */
    private static final String BODY_HEADER = "EMSKEYBACKUP1";

    private static final byte[] MAGIC = "EMSKEY1".getBytes(StandardCharsets.UTF_8);

    /** Field separator inside a body line; never appears in a UUID or in Base64. */
    private static final String FIELD_SEPARATOR = "|";

    /** {@link #FIELD_SEPARATOR} escaped for {@link String#split(String)}. */
    private static final String FIELD_SEPARATOR_REGEX = "\\|";

    private static final int SALT_LENGTH_BYTES = 16;
    private static final int IV_LENGTH_BYTES = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final int KEY_LENGTH_BITS = 256;
    private static final int CHANNEL_KEY_LENGTH_BYTES = 32;

    /**
     * OWASP's recommendation for PBKDF2-HMAC-SHA256. Deriving takes a few hundred milliseconds on a
     * phone, which is exactly the point: guessing the passphrase offline must stay expensive.
     */
    private static final int PBKDF2_ITERATIONS = 210_000;

    private final ChannelKeyRegistry channelKeys;
    private final SecureRandom random = new SecureRandom();

    public KeyBackup(ChannelKeyRegistry channelKeys) {
        this.channelKeys = channelKeys;
    }

    /** Thrown when an archive cannot be read: not our format, damaged, or the wrong passphrase. */
    public static class BackupFormatException extends Exception {

        private final boolean wrongPassphrase;

        BackupFormatException(String message, boolean wrongPassphrase) {
            this(message, wrongPassphrase, null);
        }

        BackupFormatException(String message, boolean wrongPassphrase, Throwable cause) {
            super(message, cause);

            this.wrongPassphrase = wrongPassphrase;
        }

        /** True when the only problem is the passphrase, so the UI can simply ask for it again. */
        public boolean isWrongPassphrase() {
            return wrongPassphrase;
        }
    }

    /**
     * Serialises every readable channel key of this device into an armored archive.
     *
     * @return the text to write into the file the user picked; never contains a plaintext key
     */
    public String export(final char[] passphrase) throws GeneralSecurityException {
        if (passphrase == null || passphrase.length == 0) {
            throw new IllegalArgumentException("The backup needs a passphrase");
        }

        final List<ChannelKeyRegistry.ChannelKeyEntry> entries = channelKeys.exportableEntries();

        if (entries.isEmpty()) {
            throw new IllegalStateException("This device has no channel keys to back up yet");
        }

        final byte[] salt = new byte[SALT_LENGTH_BYTES];
        random.nextBytes(salt);

        final byte[] iv = new byte[IV_LENGTH_BYTES];
        random.nextBytes(iv);

        final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(passphrase, salt),
                new GCMParameterSpec(TAG_LENGTH_BITS, iv));

        final byte[] ciphertext = cipher.doFinal(
                bodyOf(entries).getBytes(StandardCharsets.UTF_8));

        final byte[] blob = new byte[MAGIC.length + salt.length + iv.length + ciphertext.length];

        System.arraycopy(MAGIC, 0, blob, 0, MAGIC.length);
        System.arraycopy(salt, 0, blob, MAGIC.length, salt.length);
        System.arraycopy(iv, 0, blob, MAGIC.length + salt.length, iv.length);
        System.arraycopy(ciphertext, 0, blob, MAGIC.length + salt.length + iv.length, ciphertext.length);

        Log.d(TAG, "Exported " + entries.size() + " channel keys");

        return armor(Base64.getEncoder().encodeToString(blob));
    }

    /**
     * Adopts the keys of an archive into this device's store. Everything is decrypted and parsed
     * first and written only afterwards, so a wrong passphrase or a torn file leaves the store
     * exactly as it was.
     *
     * @return how many channels received at least one key
     */
    public int importArchive(final String armored, final char[] passphrase)
            throws GeneralSecurityException, KeyStoreUnavailableException, BackupFormatException {
        if (passphrase == null || passphrase.length == 0) {
            throw new IllegalArgumentException("The backup needs a passphrase");
        }

        final List<ChannelKeyRegistry.ChannelKeyEntry> entries =
                parseBody(decryptToString(armored, passphrase));

        if (entries.isEmpty()) {
            throw new BackupFormatException("The backup contains no channel keys", false);
        }

        final int imported = channelKeys.importEntries(entries);

        Log.d(TAG, "Imported keys of " + imported + " channels");

        return imported;
    }

    /**
     * How many channels an archive would restore, without touching the store. Useful to confirm a
     * file is the right one before adopting its keys.
     */
    public int peekChannelCount(final String armored, final char[] passphrase)
            throws GeneralSecurityException, BackupFormatException {
        return parseBody(decryptToString(armored, passphrase)).size();
    }

    private String decryptToString(final String armored, final char[] passphrase)
            throws GeneralSecurityException, BackupFormatException {
        final byte[] blob = dearmor(armored);

        if (!isOurArchive(blob)) {
            throw new BackupFormatException("That file is not an Enterprise Messenger key backup", false);
        }

        final byte[] salt = new byte[SALT_LENGTH_BYTES];
        System.arraycopy(blob, MAGIC.length, salt, 0, SALT_LENGTH_BYTES);

        final byte[] iv = new byte[IV_LENGTH_BYTES];
        System.arraycopy(blob, MAGIC.length + SALT_LENGTH_BYTES, iv, 0, IV_LENGTH_BYTES);

        final int ciphertextOffset = MAGIC.length + SALT_LENGTH_BYTES + IV_LENGTH_BYTES;

        try {
            final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(passphrase, salt),
                    new GCMParameterSpec(TAG_LENGTH_BITS, iv));

            return new String(cipher.doFinal(blob, ciphertextOffset, blob.length - ciphertextOffset),
                    StandardCharsets.UTF_8);
        } catch (AEADBadTagException badTag) {
            throw new BackupFormatException("Wrong passphrase", true, badTag);
        } catch (GeneralSecurityException securityException) {
            throw new BackupFormatException("The backup could not be decrypted", false, securityException);
        }
    }

    /** Whether {@code blob} carries our header, i.e. it is a file this app wrote. */
    public static boolean isOurArchive(byte[] blob) {
        if (blob == null || blob.length <= MAGIC.length + SALT_LENGTH_BYTES + IV_LENGTH_BYTES) {
            return false;
        }

        for (int index = 0; index < MAGIC.length; ++index) {
            if (blob[index] != MAGIC[index]) {
                return false;
            }
        }

        return true;
    }

    private static String bodyOf(List<ChannelKeyRegistry.ChannelKeyEntry> entries) {
        final StringBuilder body = new StringBuilder(BODY_HEADER).append('\n');

        for (final ChannelKeyRegistry.ChannelKeyEntry entry : entries) {
            for (final ChannelKeyRegistry.KeyVersion version : entry.versions()) {
                body.append(entry.channelId())
                        .append(FIELD_SEPARATOR)
                        .append(version.version())
                        .append(FIELD_SEPARATOR)
                        .append(Base64.getEncoder().encodeToString(version.key()))
                        .append('\n');
            }
        }

        return body.toString();
    }

    private static List<ChannelKeyRegistry.ChannelKeyEntry> parseBody(String body)
            throws BackupFormatException {
        final List<ChannelKeyRegistry.ChannelKeyEntry> entries = new ArrayList<>();

        final String[] lines = body.split("\n");

        if (lines.length == 0 || !BODY_HEADER.equals(lines[0].trim())) {
            throw new BackupFormatException("The backup file is not readable", false);
        }

        for (int i = 1; i < lines.length; ++i) {
            final String line = lines[i].trim();
            if (line.isEmpty()) {
                continue;
            }

            final String[] fields = line.split(FIELD_SEPARATOR_REGEX);
            if (fields.length != 3) {
                // A torn line skips only itself; the rest of the archive stays importable.
                continue;
            }

            final byte[] key;
            final UUID version;
            final long channelId;

            try {
                channelId = Long.parseLong(fields[0]);
                version = UUID.fromString(fields[1]);
                key = Base64.getDecoder().decode(fields[2]);
            } catch (IllegalArgumentException malformed) {
                continue;
            }

            if (channelId < 0 || key.length != CHANNEL_KEY_LENGTH_BYTES) {
                continue;
            }

            appendVersion(entries, channelId, new ChannelKeyRegistry.KeyVersion(version, key));
        }

        return entries;
    }

    /**
     * Groups a version under its channel, keeping the order of the archive: the first entry of a
     * channel is its current key, the later ones are the readable history.
     */
    private static void appendVersion(List<ChannelKeyRegistry.ChannelKeyEntry> entries,
                                      long channelId, ChannelKeyRegistry.KeyVersion version) {
        for (int i = 0; i < entries.size(); ++i) {
            if (entries.get(i).channelId() == channelId) {
                entries.get(i).addVersion(version);

                return;
            }
        }

        final List<ChannelKeyRegistry.KeyVersion> versions = new ArrayList<>();
        versions.add(version);
        entries.add(new ChannelKeyRegistry.ChannelKeyEntry(channelId, versions));
    }

    private static SecretKeySpec deriveKey(char[] passphrase, byte[] salt) throws GeneralSecurityException {
        // PBEKeySpec reads the char[] itself and wipes its copy, so the passphrase never becomes a
        // String that would sit in the string pool until the garbage collector says otherwise.
        final PBEKeySpec spec = new PBEKeySpec(passphrase, salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS);

        try {
            final SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            final byte[] derived = factory.generateSecret(spec).getEncoded();

            return new SecretKeySpec(derived, "AES");
        } finally {
            spec.clearPassword();
        }
    }

    private static String armor(String base64) {
        final StringBuilder builder = new StringBuilder(base64.length() + 64);

        builder.append(BEGIN_MARKER).append('\n');

        for (int offset = 0; offset < base64.length(); offset += 64) {
            builder.append(base64, offset, Math.min(offset + 64, base64.length())).append('\n');
        }

        builder.append(END_MARKER).append('\n');

        return builder.toString();
    }

    private static byte[] dearmor(String armored) throws BackupFormatException {
        if (armored == null) {
            throw new BackupFormatException("No backup was selected", false);
        }

        final StringBuilder cleaned = new StringBuilder(armored.length());

        for (final String line : armored.split("\\R")) {
            final String trimmed = line.trim();

            if (trimmed.isEmpty() || trimmed.startsWith("-----")) {
                continue;
            }

            cleaned.append(trimmed);
        }

        try {
            return Base64.getDecoder().decode(cleaned.toString());
        } catch (IllegalArgumentException malformed) {
            throw new BackupFormatException("The backup file is not readable", false, malformed);
        }
    }
}
