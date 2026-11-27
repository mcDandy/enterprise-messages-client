package cz.mkdaniel.enterprisemessenger.ui.message;

/**
 * Model representing a single chat message.
 */
public class Message {

    /**
     * {@link #serverId} for a message this device wrote itself, which the server has not
     * acknowledged yet. A negative value marks every message as local, so no local id can ever
     * collide with a position the server handed out, and {@link #isPending()} stays the only way
     * to tell the two apart.
     */
    public static final long NO_SERVER_ID = -1L;

    private final String id;
    private final long serverId;
    private final String text;
    private final String sender;
    private final long timestamp;
    private final boolean pending;

    public Message(String id, String text, String sender, long timestamp) {
        this(id, NO_SERVER_ID, text, sender, timestamp, false);
    }

    public Message(String id, String text, String sender, long timestamp, boolean pending) {
        this(id, NO_SERVER_ID, text, sender, timestamp, pending);
    }

    /**
     * @param serverId the position the server assigned to this message, or {@link #NO_SERVER_ID}
     *                 for one this device wrote and the server has not acknowledged. It is the
     *                 value a read receipt reports, so it has to survive the whole trip from the
     *                 wire to the list.
     */
    public Message(String id, long serverId, String text, String sender, long timestamp,
                   boolean pending) {
        this.id = id;
        this.serverId = serverId;
        this.text = text;
        this.sender = sender;
        this.timestamp = timestamp;
        this.pending = pending;
    }

    public String getId() {
        return id;
    }

    /**
     * The server's own id for this message, or {@link #NO_SERVER_ID} when the server has not
     * assigned one. Unlike {@link #getId()} it is never a stand-in built from a timestamp, so it
     * is safe to report to the server and safe to compare against another message.
     */
    public long getServerId() {
        return serverId;
    }

    public String getText() {
        return text;
    }

    public String getSender() {
        return sender;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public boolean isPending() {
        return pending;
    }
}
