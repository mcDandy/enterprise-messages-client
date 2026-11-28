package cz.mkdaniel.enterprisemessenger.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/**
 * Which positions a {@code CHANNEL_MEMBER_INFO} cursor is allowed to hand out.
 * <p>
 * A {@code CHAT_MESSAGE} frame carries no position of its own, and the server never echoes a message
 * back to the device that wrote it, so the read cursors are the only thing that names the messages
 * this device sent. The cursor is a single high-water mark, and the queue of unnamed messages is
 * emptied the moment ids are handed out - so a second frame reporting the same cursor used to match
 * that cursor against whatever had arrived in the meantime and stamp positions those messages
 * already carried. Two rows ending up with the same id is what crashed the {@code RecyclerView},
 * because {@code indexOfMessageId} then replaced the wrong row and the poll re-added the server's
 * copy of a message already on screen.
 * <p>
 * {@code serverIdsToAssign} is the whole rule, free of Android and network dependencies.
 */
public class ServerIdAssignmentTest {

    @Test
    public void aCursorThatMovesAheadNamesTheMessagesWaitingForOne() {
        assertEquals(List.of(11L, 12L),
                ServerConnectionManager.serverIdsToAssign(12L, 10L, 2));
    }

    @Test
    public void theFirstCursorNamesMessagesWithNothingComparedAgainst() {
        assertEquals(List.of(1L, 2L, 3L),
                ServerConnectionManager.serverIdsToAssign(3L, -1L, 3));
    }

    @Test
    public void aCursorAlreadyNamedIsNotMatchedAgainstNewerMessages() {
        // The frame that assigned 130 cleared the queue; a later frame reporting 130 again describes
        // those same messages, and must not name the message that arrived since.
        assertTrue(ServerConnectionManager.serverIdsToAssign(130L, 130L, 1).isEmpty());
    }

    @Test
    public void aStaleCursorNamesNothing() {
        // Frames can arrive out of order across a reconnect.
        assertTrue(ServerConnectionManager.serverIdsToAssign(129L, 130L, 1).isEmpty());
    }

    @Test
    public void aCursorBelowTheQueueLengthIsNotGuessedAt() {
        // Three messages waiting cannot be the three ending at 2 - the arithmetic would hand out
        // positions that never existed.
        assertTrue(ServerConnectionManager.serverIdsToAssign(2L, -1L, 3).isEmpty());
    }

    @Test
    public void aCursorWithNothingWaitingNamesNothing() {
        assertTrue(ServerConnectionManager.serverIdsToAssign(140L, 130L, 0).isEmpty());
    }

    @Test
    public void idsAreHandedOutOldestFirstUpToTheCursor() {
        final List<Long> ids = ServerConnectionManager.serverIdsToAssign(200L, 196L, 4);

        assertEquals(List.of(197L, 198L, 199L, 200L), ids);
        assertEquals(200L, ids.get(ids.size() - 1).longValue());
    }
}
