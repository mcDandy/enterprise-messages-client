package cz.mkdaniel.enterprisemessenger.ui.transform;

/**
 * Model class representing a Server item in the Server list.
 * Holds server ID, display name, IP address / hostname, and drawable resource ID for avatar.
 */
public class ServerViewItem {

    private final String id;
    private final String text;
    private final String ipAddress;
    private final int drawableId;

    public ServerViewItem(String id, String text, String ipAddress, int drawableId) {
        this.id = id;
        this.text = text;
        this.ipAddress = ipAddress;
        this.drawableId = drawableId;
    }

    public ServerViewItem(String text, int drawableId) {
        this("srv_" + text.hashCode(), text, "192.168.1.1", drawableId);
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
}
