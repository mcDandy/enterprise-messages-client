package cz.mkdaniel.enterprisemessenger.net;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The server keeps one FIFO queue per user, sends only its head and deletes exactly one row per
 * ACKNOWLEDGE it receives (its {@code AcknowledgePacketHandler} → {@code PostgresUserQueue}
 * → {@code pgmq.delete} of the oldest message). An {@code ACK} carries no packet id, so the client
 * has to acknowledge queued deliveries and nothing else: acknowledging a directly-sent packet deletes
 * an unrelated pending frame, and staying silent about a queued one stalls that user's queue.
 */
public class QueuedDeliveryAckPolicyTest {

    @Test
    public void chatMessagesComeFromTheQueueAndAreAcknowledged() {
        assertTrue(Packet.isQueuedDelivery(Packet.TYPE_CHAT_MESSAGE));
    }

    @Test
    public void keyUnicastsComeFromTheQueueAndAreAcknowledged() {
        assertTrue(Packet.isQueuedDelivery(Packet.TYPE_UNICAST_ROTATED_KEY));
    }

    @Test
    public void memberProgressPacketsAreNeverAcknowledged() {
        // CHANNEL_MEMBER_INFO is broadcast with a plain send, so an ACK for it would delete a
        // queued chat message or key that was never delivered.
        assertFalse(Packet.isQueuedDelivery(Packet.TYPE_CHANNEL_MEMBER_INFO));
    }

    @Test
    public void errorPacketsAreNeverAcknowledged() {
        assertFalse(Packet.isQueuedDelivery(Packet.TYPE_UNKNOWN));
    }

    @Test
    public void anAcknowledgementNeverAcknowledgesItself() {
        // Acking an ACK would make the two peers answer each other indefinitely.
        assertFalse(Packet.isQueuedDelivery(Packet.TYPE_ACK));
    }

    @Test
    public void clientToServerOnlyTypesAreNeverAcknowledged() {
        assertFalse(Packet.isQueuedDelivery(Packet.TYPE_READ));
    }
}
