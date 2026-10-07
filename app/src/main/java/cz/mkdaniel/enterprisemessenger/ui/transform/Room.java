package cz.mkdaniel.enterprisemessenger.ui.transform;

/**
 * Model representing a chat room/channel within a server.
 * Holds room ID, display name, channel encryption key, and unread message state.
 */
public class Room {

    private final String id;
    private final String name;
    private final String encryptionKey;
    private boolean hasNewMessages;

    public Room(String id, String name, String encryptionKey, boolean hasNewMessages) {
        this.id = id;
        this.name = name;
        this.encryptionKey = encryptionKey;
        this.hasNewMessages = hasNewMessages;
    }

    public Room(String id, String name, String encryptionKey) {
        this(id, name, encryptionKey, false);
    }

    public Room(String id, String name) {
        this(id, name, "key_default_" + id, false);
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getEncryptionKey() {
        return encryptionKey;
    }

    public boolean hasNewMessages() {
        return hasNewMessages;
    }

    public void setHasNewMessages(boolean hasNewMessages) {
        this.hasNewMessages = hasNewMessages;
    }
}
