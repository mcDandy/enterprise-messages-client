package cz.mkdaniel.enterprisemessenger.net;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import cz.mkdaniel.enterprisemessenger.crypto.ChannelKeyRegistry;

/**
 * The key-recovery handshake rides inside an ordinary encrypted chat message, so the two must never be
 * confused: a typed message must never parse as a handshake, and a malformed handshake must be refused
 * rather than half-read.
 */
public class KeyRecoveryMessageTest {

    private static final char FIELD = (char) 0;

    @Test
    public void ordinaryTextIsNeverAHandshake() {
        assertFalse(KeyRecoveryMessage.isControl("hello"));
        assertFalse(KeyRecoveryMessage.isControl("EMSKEY1"));
        assertFalse(KeyRecoveryMessage.isControl(""));
        assertFalse(KeyRecoveryMessage.isControl(null));

        assertNull(KeyRecoveryMessage.parseRequest("hello"));
        assertNull(KeyRecoveryMessage.parseOffer("hello"));
    }

    @Test
    public void requestRoundTripsEveryServerRow() {
        final List<UUID> rows = Arrays.asList(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

        final String wire = KeyRecoveryMessage.encodeRequest(17L, rows);

        assertTrue(KeyRecoveryMessage.isControl(wire));

        final KeyRecoveryMessage.Request parsed = KeyRecoveryMessage.parseRequest(wire);

        assertNotNull(parsed);
        assertEquals(17L, parsed.requesterUserId());
        assertEquals(rows, parsed.serverRows());

        // The two kinds are not interchangeable.
        assertNull(KeyRecoveryMessage.parseOffer(wire));
    }

    @Test
    public void offerRoundTripsTargetResponderAndSealedBlob() {
        final byte[] sealed = new byte[]{0, 1, 2, (byte) 255, (byte) 128, 42};

        final String wire = KeyRecoveryMessage.encodeOffer(17L, 22L, sealed);

        final KeyRecoveryMessage.Offer parsed = KeyRecoveryMessage.parseOffer(wire);

        assertNotNull(parsed);
        assertEquals(17L, parsed.targetUserId());
        assertEquals(22L, parsed.responderUserId());
        assertArrayEquals(sealed, parsed.sealedKeys());

        assertNull(KeyRecoveryMessage.parseRequest(wire));
    }

    @Test
    public void keyBlobRoundTripsVersionsAndKeys() {
        final byte[] firstKey = new byte[32];
        final byte[] secondKey = new byte[32];
        Arrays.fill(firstKey, (byte) 0x11);
        Arrays.fill(secondKey, (byte) 0x22);

        final List<ChannelKeyRegistry.KeyVersion> keys = Arrays.asList(
                new ChannelKeyRegistry.KeyVersion(UUID.randomUUID(), firstKey),
                new ChannelKeyRegistry.KeyVersion(UUID.randomUUID(), secondKey));

        final List<ChannelKeyRegistry.KeyVersion> read =
                KeyRecoveryMessage.unpackKeys(KeyRecoveryMessage.packKeys(keys));

        assertEquals(2, read.size());
        assertEquals(keys.get(0).version(), read.get(0).version());
        assertArrayEquals(firstKey, read.get(0).key());
        assertEquals(keys.get(1).version(), read.get(1).version());
        assertArrayEquals(secondKey, read.get(1).key());
    }

    @Test
    public void malformedHandshakesAreRefused() {
        // A requester id that is not a number, and rows that are not uuids.
        assertNull(KeyRecoveryMessage.parseRequest(
                KeyRecoveryMessage.MARKER + "REQ" + FIELD + "member-7" + FIELD + UUID.randomUUID()));
        assertNull(KeyRecoveryMessage.parseRequest(
                KeyRecoveryMessage.MARKER + "REQ" + FIELD + "7" + FIELD + "not-a-uuid"));

        // An offer missing the responder, or with a blob that is not hex.
        assertNull(KeyRecoveryMessage.parseOffer(
                KeyRecoveryMessage.MARKER + "OFFER" + FIELD + "7" + FIELD + "9"));
        assertNull(KeyRecoveryMessage.parseOffer(
                KeyRecoveryMessage.MARKER + "OFFER" + FIELD + "7" + FIELD + "9" + FIELD + "zz"));

        // A blob that is not a whole number of key entries cannot be unpacked.
        try {
            KeyRecoveryMessage.unpackKeys(new byte[33]);
            fail("A torn key blob must be refused");
        } catch (final IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }

        try {
            KeyRecoveryMessage.unpackKeys(new byte[0]);
            fail("An empty key blob must be refused");
        } catch (final IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
    }
}
