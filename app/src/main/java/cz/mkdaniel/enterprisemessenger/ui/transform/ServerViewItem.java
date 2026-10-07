package cz.mkdaniel.enterprisemessenger.ui.transform;

/**
 * Model class representing a Server item in the Server list.
 * Holds server ID, display name, IP address / hostname, drawable resource ID for avatar,
 * and unread message count.
 */
public class ServerViewItem {

    private final String id;
    private final String text;
    private final String ipAddress;
    private final int drawableId;
    private int unreadCount;

    public ServerViewItem(String id, String text, String ipAddress, int drawableId, int unreadCount) {
        this.id = id;
        this.text = text;
        this.ipAddress = ipAddress;
        this.drawableId = drawableId;
        this.unreadCount = unreadCount;
    }

    public ServerViewItem(String id, String text, String ipAddress, int drawableId) {
        this(id, text, ipAddress, drawableId, 0);
    }

    public ServerViewItem(String text, int drawableId) {
        this("srv_" + text.hashCode(), text, "192.168.1.1", drawableId, 0);
    }

    public String getId() {
        return id;
    }

    public String getText() {
        return text;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public int getDrawableId() {
        return drawableId;
    }

    public int getUnreadCount() {
        return unreadCount;
    }

    public void setUnreadCount(int unreadCount) {
        this.unreadCount = unreadCount;
    }

    public boolean hasUnread() {
        return unreadCount > 0;
    }
}
