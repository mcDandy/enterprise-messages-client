package cz.mkdaniel.enterprisemessenger.ui.transform;

/**
 * Model representing a chat room/channel within a server.
 * Holds room ID, display name, and channel encryption key.
 */
public class Room {

    private final String id;
    private final String name;
    private final String encryptionKey;

    public Room(String id, String name, String encryptionKey) {
        this.id = id;
        this.name = name;
        this.encryptionKey = encryptionKey;
    }

    public Room(String id, String name) {
        this(id, name, "key_default_" + id);
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
}
