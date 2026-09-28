package cz.mkdaniel.enterprisemessenger.ui.transform;

/**
 * Model class representing a single item in the Transform list.
 * Holds both the display text and the drawable resource ID for the avatar image.
 */
public class ServerViewItem {

    private final String text;
    private final int drawableId;

    public ServerViewItem(String text, int drawableId) {
        this.text = text;
        this.drawableId = drawableId;
    }

    public String getText() {
        return text;
    }

    public int getDrawableId() {
        return drawableId;
    }
}
