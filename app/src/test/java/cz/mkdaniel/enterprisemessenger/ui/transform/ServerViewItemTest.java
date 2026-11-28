package cz.mkdaniel.enterprisemessenger.ui.transform;

import org.junit.Test;

import cz.mkdaniel.enterprisemessenger.R;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Verifies stable avatar selection and unread badge behavior for virtual servers.
 */
public class ServerViewItemTest {

    @Test
    public void avatarSelectionWrapsForPositiveAndNegativeIndices() {
        assertEquals(R.drawable.avatar_1, ServerViewItem.avatarDrawableForIndex(0));
        assertEquals(R.drawable.avatar_2, ServerViewItem.avatarDrawableForIndex(1));
        assertEquals(R.drawable.avatar_2, ServerViewItem.avatarDrawableForIndex(-1));
        assertEquals(R.drawable.avatar_1, ServerViewItem.avatarDrawableForIndex(16));
    }

    @Test
    public void unreadBadgeTracksPositiveCounts() {
        ServerViewItem item = new ServerViewItem(7L, "Engineering", "ORGANIZATION",
                true, R.drawable.avatar_1);

        assertFalse(item.hasUnread());
        item.setUnreadCount(2);
        assertTrue(item.hasUnread());
        item.setUnreadCount(0);
        assertFalse(item.hasUnread());
    }
}
