package cz.mkdaniel.enterprisemessenger.ui.transform;

import cz.mkdaniel.enterprisemessenger.R;

import java.util.Locale;

/**
 * Model class representing a <b>virtual</b> server (the server's {@code MessageServer} entity) in
 * the server list. It carries no address: every virtual server lives on the one physical server
 * whose address comes from {@code @string/server_ip} / {@code @integer/server_port}.
 * <p>
 * Holds the numeric id the physical server assigned to it, the display name, the server type
 * ("ORGANIZATION" or "DIRECT"), whether the signed-in user is its ADMINISTRATOR - which is what
 * gates the rename/delete affordances - an avatar resource and the unread message count.
 */
public class ServerViewItem {

    /**
     * Avatar palette the virtual servers are stamped with in list order. The avatars are bundled
     * drawables, so a virtual server needs nothing but its position to look stable across reloads.
     */
    private static final int[] AVATAR_DRAWABLES = {
            R.drawable.avatar_1,
            R.drawable.avatar_2,
            R.drawable.avatar_3,
            R.drawable.avatar_4,
            R.drawable.avatar_5,
            R.drawable.avatar_6,
            R.drawable.avatar_7,
            R.drawable.avatar_8,
            R.drawable.avatar_9,
            R.drawable.avatar_10,
            R.drawable.avatar_11,
            R.drawable.avatar_12,
            R.drawable.avatar_13,
            R.drawable.avatar_14,
            R.drawable.avatar_15,
            R.drawable.avatar_16
    };

    /**
     * Avatar for the server at {@code index}; the id is accepted as well, so a freshly created
     * server keeps the same avatar as the same entry fetched again later.
     */
    public static int avatarDrawableForIndex(int index) {
        final int safeIndex = index < 0 ? -index : index;

        return AVATAR_DRAWABLES[safeIndex % AVATAR_DRAWABLES.length];
    }

    private final long serverId;
    private final String text;
    private final String type;
    private final boolean isAdmin;
    private final int drawableId;
    private int unreadCount;

    public ServerViewItem(long serverId, String text, String type, boolean isAdmin, int drawableId, int unreadCount) {
        this.serverId = serverId;
        this.text = text;
        this.type = type;
        this.isAdmin = isAdmin;
        this.drawableId = drawableId;
        this.unreadCount = unreadCount;
    }

    public ServerViewItem(long serverId, String text, String type, boolean isAdmin, int drawableId) {
        this(serverId, text, type, isAdmin, drawableId, 0);
    }

    /** Numeric id of the virtual server, as handed out by {@code GET /api/servers}. */
    public long getServerId() {
        return serverId;
    }

    /**
     * String form of {@link #getServerId()}, used as the {@code DiffUtil} item key.
     */
    public String getId() {
        return String.format(Locale.US, "%d", serverId);
    }

    public String getText() {
        return text;
    }

    /** {@code "ORGANIZATION"} or {@code "DIRECT"} as the server reports it. */
    public String getType() {
        return type;
    }

    /**
     * Whether the signed-in user is {@code ADMINISTRATOR} of this virtual server; mirrors the
     * server-side membership check, so the UI only offers mutations the server will accept.
     */
    public boolean isAdmin() {
        return isAdmin;
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
