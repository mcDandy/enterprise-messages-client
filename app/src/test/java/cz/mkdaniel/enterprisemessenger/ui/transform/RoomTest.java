package cz.mkdaniel.enterprisemessenger.ui.transform;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Verifies a room's unread-message state transitions.
 */
public class RoomTest {

    @Test
    public void unreadStateDefaultsToFalseAndCanBeUpdated() {
        Room room = new Room("42", "general");

        assertFalse(room.hasNewMessages());
        room.setHasNewMessages(true);
        assertTrue(room.hasNewMessages());
        room.setHasNewMessages(false);
        assertFalse(room.hasNewMessages());
    }

    @Test
    public void constructorPreservesInitialUnreadState() {
        assertTrue(new Room("42", "general", true).hasNewMessages());
    }
}
