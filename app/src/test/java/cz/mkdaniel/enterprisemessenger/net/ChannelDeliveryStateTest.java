package cz.mkdaniel.enterprisemessenger.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * Peer delivery state, held per channel rather than as one global pair of fields.
 * <p>
 * The two ways the old global state went stale are both per channel. A member removed from a channel
 * kept his entry in the shared cursor map, so he went on counting as a recipient of that channel's
 * messages and the bubble faded in against a total that included someone who had left. And opening a
 * second channel inherited the first one's cursors, because the listing only ever merged into the
 * shared map and never replaced it.
 * <p>
 * {@code ChannelDeliveryState} is package-private and free of Android and network dependencies, so
 * the reset semantics are testable without a server.
 */
public class ChannelDeliveryStateTest {

    private static Map<Long, Long> cursorsOf(final long... userIdAndCursor) {
        final Map<Long, Long> cursors = new HashMap<>();
        for (int i = 0; i + 1 < userIdAndCursor.length; i += 2) {
            cursors.put(userIdAndCursor[i], userIdAndCursor[i + 1]);
        }

        return cursors;
    }

    @Test
    public void cursorsOfOneChannelAreInvisibleToAnother() {
        final ServerConnectionManager.ChannelDeliveryState first =
                new ServerConnectionManager.ChannelDeliveryState();
        final ServerConnectionManager.ChannelDeliveryState second =
                new ServerConnectionManager.ChannelDeliveryState();

        first.raiseCursor(7L, 42L);

        assertTrue(first.cursors.containsKey(7L));
        assertFalse("a cursor of one channel must not appear in another",
                second.cursors.containsKey(7L));
        assertEquals(0, second.cursors.size());
    }

    @Test
    public void aMemberAbsentFromAFreshListingIsDropped() {
        final ServerConnectionManager.ChannelDeliveryState state =
                new ServerConnectionManager.ChannelDeliveryState();

        // Three members, all of whom have acknowledged up to message 10.
        state.replaceWith(cursorsOf(7L, 10L, 8L, 10L, 9L, 10L), 3);
        assertEquals(3, state.peerCount);

        // The administrator removes member 9. The next listing has only two rows.
        state.replaceWith(cursorsOf(7L, 10L, 8L, 10L), 2);

        assertFalse("a removed member must not linger as a phantom recipient",
                state.cursors.containsKey(9L));
        assertEquals(2, state.peerCount);
        assertEquals(2, state.cursors.size());
    }

    @Test
    public void peerCountIsTheListingSizeNotTheCursorMapSize() {
        final ServerConnectionManager.ChannelDeliveryState state =
                new ServerConnectionManager.ChannelDeliveryState();

        // A member who has acknowledged nothing has no cursor to broadcast, so he is absent from
        // every frame. Without a total the UI cannot tell two recipients out of two from two out of
        // twenty, and a bubble would reach full opacity in a channel of twenty.
        state.replaceWith(cursorsOf(7L, 10L, 8L, 10L), 20);

        assertEquals(2, state.cursors.size());
        assertEquals(20, state.peerCount);
    }

    @Test
    public void aStaleFrameCannotUnDeliverAMessage() {
        final ServerConnectionManager.ChannelDeliveryState state =
                new ServerConnectionManager.ChannelDeliveryState();

        assertTrue(state.raiseCursor(7L, 90L));

        // Frames can arrive out of order across a reconnect; the older one must be ignored, and it
        // must not schedule a repaint either.
        assertFalse(state.raiseCursor(7L, 80L));
        assertEquals(Long.valueOf(90L), state.cursors.get(7L));

        // A duplicate of the current cursor is likewise no change. CHANNEL_MEMBER_INFO is broadcast
        // to every online member, so the same cursor arrives repeatedly; reporting it as a change
        // would resubmit the whole list on every frame.
        assertFalse(state.raiseCursor(7L, 90L));

        assertTrue(state.raiseCursor(7L, 91L));
        assertEquals(Long.valueOf(91L), state.cursors.get(7L));
    }

    @Test
    public void aListingReplacesRatherThanMergesLiveCursors() {
        final ServerConnectionManager.ChannelDeliveryState state =
                new ServerConnectionManager.ChannelDeliveryState();

        // Live frames arrive first, then the listing lands.
        state.raiseCursor(7L, 50L);
        state.raiseCursor(9L, 50L);

        // Member 9 left before the listing was taken. A merge would have kept his live cursor.
        state.replaceWith(cursorsOf(7L, 12L), 1);

        assertEquals(1, state.cursors.size());
        assertFalse(state.cursors.containsKey(9L));
        assertEquals("the listing is authoritative, even below a live cursor",
                Long.valueOf(12L), state.cursors.get(7L));
    }

    @Test
    public void clearingOneChannelLeavesTheOthersIntact() {
        final ServerConnectionManager.ChannelDeliveryState first =
                new ServerConnectionManager.ChannelDeliveryState();
        final ServerConnectionManager.ChannelDeliveryState second =
                new ServerConnectionManager.ChannelDeliveryState();

        final Map<Long, ServerConnectionManager.ChannelDeliveryState> byChannel = new HashMap<>();
        byChannel.put(1L, first);
        byChannel.put(2L, second);

        first.raiseCursor(7L, 10L);
        second.raiseCursor(8L, 20L);

        byChannel.remove(1L);

        assertFalse(byChannel.containsKey(1L));
        assertEquals(Long.valueOf(20L), second.cursors.get(8L));
    }

    @Test
    public void snapshotIsACopySoTheUiCannotMutateTheState() {
        final ServerConnectionManager.ChannelDeliveryState state =
                new ServerConnectionManager.ChannelDeliveryState();
        state.raiseCursor(7L, 10L);

        final Map<Long, Long> snapshot = state.cursorsSnapshot();
        snapshot.remove(7L);

        assertTrue("the published snapshot must not alias the live map",
                state.cursors.containsKey(7L));
    }
}
