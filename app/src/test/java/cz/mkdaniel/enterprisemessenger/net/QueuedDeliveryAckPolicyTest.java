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
 * <p>
 * The acknowledgement of a {@code UNICAST_ROTATED_KEY} is the one deliberate exception to "always
 * answer a queued delivery": the ACK is what deletes the row, and the envelope in that row is the
 * only copy of a channel key this device will ever receive. Acknowledging one that could not be
 * opened destroys the key and leaves every message sealed with it unreadable forever, so
 * {@code ServerConnectionManager} answers a key unicast only once its key is stored or already known
 * to this device, and bounds the retries so an envelope that never opens cannot stall the queue.
 */
public class QueuedDeliveryAckPolicyTest {

    /**
     * Verifies chat messages come from the queue and are acknowledged for this case.
     *
     */
    @Test
    public void chatMessagesComeFromTheQueueAndAreAcknowledged() {
        assertTrue(Packet.isQueuedDelivery(Packet.TYPE_CHAT_MESSAGE));
    }

    /**
     * Verifies key unicasts come from the queue and are acknowledged for this case.
     *
     */
    @Test
    public void keyUnicastsComeFromTheQueueAndAreAcknowledged() {
        assertTrue(Packet.isQueuedDelivery(Packet.TYPE_UNICAST_ROTATED_KEY));
    }

    /**
     * Verifies member progress packets are never acknowledged for this case.
     *
     */
    @Test
    public void memberProgressPacketsAreNeverAcknowledged() {
        // CHANNEL_MEMBER_INFO is broadcast with a plain send, so an ACK for it would delete a
        // queued chat message or key that was never delivered.
        assertFalse(Packet.isQueuedDelivery(Packet.TYPE_CHANNEL_MEMBER_INFO));
    }

    /**
     * Verifies error packets are never acknowledged for this case.
     *
     */
    @Test
    public void errorPacketsAreNeverAcknowledged() {
        assertFalse(Packet.isQueuedDelivery(Packet.TYPE_UNKNOWN));
    }

    /**
     * Verifies an acknowledgement never acknowledges itself for this case.
     *
     */
    @Test
    public void anAcknowledgementNeverAcknowledgesItself() {
        // Acking an ACK would make the two peers answer each other indefinitely.
        assertFalse(Packet.isQueuedDelivery(Packet.TYPE_ACK));
    }

    /**
     * Verifies client to server only types are never acknowledged for this case.
     *
     */
    @Test
    public void clientToServerOnlyTypesAreNeverAcknowledged() {
        assertFalse(Packet.isQueuedDelivery(Packet.TYPE_READ));
    }
}
