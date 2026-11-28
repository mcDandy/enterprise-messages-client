package cz.mkdaniel.enterprisemessenger.ui.message;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Verifies message state used to distinguish local pending sends from server-confirmed messages.
 */
public class MessageTest {

    @Test
    public void locallyPendingMessageHasNoServerIdAndIsNotDelivered() {
        Message message = new Message("local-1", "hello", "alice", 123L, true);

        assertEquals(Message.NO_SERVER_ID, message.getServerId());
        assertTrue(message.isPending());
        assertFalse(message.isDelivered());
        assertFalse(message.isPeerDelivered());
    }

    @Test
    public void serverAcknowledgementStateIsPreserved() {
        Message message = new Message("wire-1", 42L, "hello", "alice", 123L,
                false, true, true);

        assertEquals(42L, message.getServerId());
        assertFalse(message.isPending());
        assertTrue(message.isDelivered());
        assertTrue(message.isPeerDelivered());
    }

    @Test
    public void legacyConstructorMarksReceivedMessageAsDeliveredButNotPeerDelivered() {
        Message message = new Message("wire-2", "hello", "bob", 456L);

        assertTrue(message.isDelivered());
        assertFalse(message.isPeerDelivered());
    }
}
