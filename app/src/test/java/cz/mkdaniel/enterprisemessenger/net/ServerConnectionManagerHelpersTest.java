package cz.mkdaniel.enterprisemessenger.net;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Verifies deterministic channel-ID parsing independent of a server connection.
 */
public class ServerConnectionManagerHelpersTest {

    @Test
    public void parsesNumericAndLegacyRoomIds() {
        assertEquals(42L, ServerConnectionManager.parseChannelId("42"));
        assertEquals(12L, ServerConnectionManager.parseChannelId("room-12"));
        assertEquals(123L, ServerConnectionManager.parseChannelId("r1-2-3"));
    }

    @Test
    public void rejectsMissingOrUnrepresentableChannelIds() {
        assertEquals(-1L, ServerConnectionManager.parseChannelId(null));
        assertEquals(-1L, ServerConnectionManager.parseChannelId("room"));
        assertEquals(-1L,
                ServerConnectionManager.parseChannelId("999999999999999999999999999999"));
    }
}
