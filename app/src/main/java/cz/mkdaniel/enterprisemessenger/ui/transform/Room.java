package cz.mkdaniel.enterprisemessenger.ui.transform;

/**
 * Model representing a chat room/channel within a server.
 * <p>
 * It carries display data only. There used to be an {@code encryptionKey} field holding
 * {@code "ems-channel-" + id}, which was never a secret (anyone who can list channels can recompute
 * it) yet still travelled through navigation arguments and log lines; the real keys live sealed in
 * {@code ChannelKeyRegistry} and are read by id when a channel is opened.
 */
public class Room {

    private final String id;
    private final String name;
    private boolean hasNewMessages;

    public Room(String id, String name, boolean hasNewMessages) {
        this.id = id;
        this.name = name;
        this.hasNewMessages = hasNewMessages;
    }

    public Room(String id, String name) {
        this(id, name, false);
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public boolean hasNewMessages() {
        return hasNewMessages;
    }

    public void setHasNewMessages(boolean hasNewMessages) {
        this.hasNewMessages = hasNewMessages;
    }
}
