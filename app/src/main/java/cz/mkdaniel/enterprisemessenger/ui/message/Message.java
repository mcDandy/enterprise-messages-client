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
    /**
     * Whether the server has accepted this message. Only a message this device sent itself ever
     * has an opinion on this; for anything received it is {@code true}, because the device holding
     * it is by definition the destination.
     * <p>
     * This says nothing about any other member having it - see {@link #peerDelivered}.
     */
    private final boolean delivered;
    /**
     * Whether another member's client has confirmed it holds this message.
     * <p>
     * Nothing on the wire reports this today. The server's {@code CHANNEL_MEMBER_INFO} carries a
     * {@code userId}, but it is always this member's own row, so the cursors it carries describe
     * what <i>this</i> device has read and say nothing about anyone else. Until the server
     * broadcasts other members' cursors this stays {@code false} for every message, and the UI
     * must not render a delivered state from {@link #delivered} alone.
     */
    private final boolean peerDelivered;
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

    public Message(String id, long serverId, String text, String sender, long timestamp,
                   boolean pending) {
        this(id, serverId, text, sender, timestamp, pending, !pending);
    }

    /**
     * @param serverId  the position the server assigned to this message, or {@link #NO_SERVER_ID}
     *                  for one this device wrote and the server has not acknowledged. It is the
     *                  value a read receipt reports, so it has to survive the whole trip from the
     *                  wire to the list.
     * @param delivered whether the server has confirmed it holds the message. Meaningful only for
     *                  a message this device sent; anything received is delivered by definition.
     */
    public Message(String id, long serverId, String text, String sender, long timestamp,
                   boolean pending, boolean delivered) {
        this(id, serverId, text, sender, timestamp, pending, delivered, false);
    }

    public Message(String id, long serverId, String text, String sender, long timestamp,
                   boolean pending, boolean delivered, boolean peerDelivered) {
        this.id = id;
        this.serverId = serverId;
        this.text = text;
        this.sender = sender;
        this.timestamp = timestamp;
        this.pending = pending;
        this.delivered = delivered;
        this.peerDelivered = peerDelivered;
    }

    /**
     * Returns the identifier.
     *
     * @return the identifier value
     */
    public String getId() {
        return id;
    }

    /**
     * The server's own id for this message, or {@link #NO_SERVER_ID} when the server has not
     * assigned one. Unlike {@link #getId()} it is never a stand-in built from a timestamp, so it
     * is safe to report to the server and safe to compare against another message.
     *
     * @return the server identifier value
     */
    public long getServerId() {
        return serverId;
    }

    /**
     * Returns the text.
     *
     * @return the text value
     */
    public String getText() {
        return text;
    }

    /**
     * Returns the sender.
     *
     * @return the sender value
     */
    public String getSender() {
        return sender;
    }

    /**
     * Returns the timestamp.
     *
     * @return the timestamp value
     */
    public long getTimestamp() {
        return timestamp;
    }

    /**
     * Returns whether pending.
     *
     * @return pending.
     */
    public boolean isPending() {
        return pending;
    }

    /**
     * Whether the server has accepted this message into the channel's history. A message this
     * device wrote starts out unconfirmed and becomes confirmed once the server's own copy of it
     * comes back in a poll, which is the only confirmation the server gives.
     * <p>
     * Not the same as another member having it: the server can hold a message indefinitely for a
     * member who is offline.
     *
     * @return delivered.
     */
    public boolean isDelivered() {
        return delivered;
    }

    /**
     * Whether another member's client has confirmed it holds this message. Always {@code false}
     * until the server reports other members' cursors - see {@link #peerDelivered}.
     *
     * @return peer delivered.
     */
    public boolean isPeerDelivered() {
        return peerDelivered;
    }
}
