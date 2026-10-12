package cz.mkdaniel.enterprisemessenger.notification;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import cz.mkdaniel.enterprisemessenger.MainActivity;
import cz.mkdaniel.enterprisemessenger.R;

/**
 * Helper class for managing and posting Android notifications.
 * Displays total unread message count and server name according to requirements.
 */
public class NotificationHelper {

    public static final String CHANNEL_ID = "channel_enterprise_messages";
    public static final String CHANNEL_NAME = "Enterprise Messages";
    public static final String CHANNEL_DESC = "Notifications for new messages";
    public static final int PERMISSION_REQUEST_CODE = 1001;
    private static final String PREF_UNREAD = "EnterpriseMessenger_Unread";

    private static int notificationIdCounter = 1000;

    /**
     * Creates the notification channel required on Android 8.0 (API 26) and higher.
     */
    public static void createNotificationChannel(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription(CHANNEL_DESC);

            NotificationManager manager = context.getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    /**
     * Checks if notification permission is granted on Android 13+ (API 33+),
     * and requests it from the activity if it has not been granted.
     */
    public static void checkAndRequestPermission(Activity activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                        activity,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        PERMISSION_REQUEST_CODE
                );
            }
        }
    }

    /**
     * Displays a notification showing only the number of new messages and the server name.
     *
     * @param context      App context
     * @param serverName   Display name of the server (e.g. "Server #1")
     * @param messageCount Total number of unread/new messages on this server
     */
    public static void showMessageNotification(Context context, String serverName, int messageCount) {
        if (context == null) return;

        // Ensure channel exists
        createNotificationChannel(context);

        // Check permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                return;
            }
        }

        // Prepare content text: "1 new message" or "X new messages"
        String countText = (messageCount <= 1)
                ? "1 new message"
                : messageCount + " new messages";

        String titleText = serverName != null ? serverName : "Enterprise Messenger";
        String contentText = countText + " on " + titleText;

        Intent intent = new Intent(context, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);

        PendingIntent pendingIntent = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(titleText)
                .setContentText(countText)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(contentText))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true);

        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            // Use serverName hashcode or counter so notifications group or update per server
            int notificationId = serverName != null ? Math.abs(serverName.hashCode()) : ++notificationIdCounter;
            manager.notify(notificationId, builder.build());
        }
    }

    /**
     * Shows (or updates) the "N new messages" notification of a channel, used for pushes
     * that arrive while the app is in the foreground.
     */
    public static void showChannelMessageNotification(Context context, long channelId, String channelName) {
        // Keyed by channel id so cancelNotificationForChannel() finds it again
        String name = (channelId >= 0 || channelName == null) ? "Channel #" + channelId : channelName;
        int count;
        synchronized (NotificationHelper.class) {
            SharedPreferences prefs = context.getSharedPreferences(PREF_UNREAD, Context.MODE_PRIVATE);
            count = prefs.getInt(unreadKey(name), 0) + 1;
            prefs.edit().putInt(unreadKey(name), count).apply();
        }
        showMessageNotification(context, name, count);
    }

    /**
     * Cancels the notification of a channel and resets its unread counter (user opened the channel).
     */
    public static void cancelNotificationForChannel(Context context, long channelId) {
        if (context == null) return;
        String name = "Channel #" + channelId;
        context.getSharedPreferences(PREF_UNREAD, Context.MODE_PRIVATE).edit().remove(unreadKey(name)).apply();
        cancelNotificationForServer(context, name);
    }

    private static String unreadKey(String name) {
        return "unread_" + name;
    }

    /**
     * Cancels notifications for a specific server when user reads the messages.
     */
    public static void cancelNotificationForServer(Context context, String serverName) {
        if (context == null || serverName == null) return;
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.cancel(Math.abs(serverName.hashCode()));
        }
    }
}
