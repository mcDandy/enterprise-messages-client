package cz.mkdaniel.enterprisemessenger.ui.message;

/**
 * Model representing a single chat message.
 */
public class Message {

    private final String id;
    private final String text;
    private final String sender;
    private final long timestamp;

    public Message(String id, String text, String sender, long timestamp) {
        this.id = id;
        this.text = text;
        this.sender = sender;
        this.timestamp = timestamp;
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
}
