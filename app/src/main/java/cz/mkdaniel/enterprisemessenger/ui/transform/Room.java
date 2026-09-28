package cz.mkdaniel.enterprisemessenger.ui.transform;

/**
 * Model representing a chat room/channel within a server.
 */
public class Room {

    private final String id;
    private final String name;

    public Room(String id, String name) {
        this.id = id;
        this.name = name;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }
}
