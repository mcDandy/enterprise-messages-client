package cz.mkdaniel.enterprisemessenger.ui.message;

/**
 * Model representing a single chat message.
 */
public class Message {

    private final String id;
    private final String text;
    private final String sender;
    private final long timestamp;
    private final boolean pending;

    public Message(String id, String text, String sender, long timestamp) {
        this(id, text, sender, timestamp, false);
    }

    public Message(String id, String text, String sender, long timestamp, boolean pending) {
        this.id = id;
        this.text = text;
        this.sender = sender;
        this.timestamp = timestamp;
        this.pending = pending;
    }

    public String getId() {
        return id;
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
