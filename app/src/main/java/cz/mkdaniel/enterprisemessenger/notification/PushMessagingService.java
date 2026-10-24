package cz.mkdaniel.enterprisemessenger.notification;

import android.util.Log;

import androidx.annotation.NonNull;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import java.util.Map;

import cz.mkdaniel.enterprisemessenger.net.ServerConnectionManager;

/**
 * Receives Firebase Cloud Messaging pushes sent by the server's {@code NotificationService}.
 * <p>
 * When the app is in the background or not running, Android shows the push's notification
 * payload by itself (in {@link NotificationHelper#CHANNEL_ID}) and this service is not called.
 * When the app is in the foreground, FCM hands the push to {@link #onMessageReceived} instead,
 * so it is shown here - unless the user is already looking at that channel.
 */
public class PushMessagingService extends FirebaseMessagingService {

    private static final String TAG = "PushMessagingService";
    private static final String TYPE_NEW_MESSAGE = "NEW_MESSAGE";

    @Override
    public void onNewToken(@NonNull String token) {
        Log.d(TAG, "FCM registration token refreshed");
        ServerConnectionManager.getInstance(this).onNewPushToken(token);
    }

    @Override
    public void onMessageReceived(@NonNull RemoteMessage remoteMessage) {
        Map<String, String> data = remoteMessage.getData();
        Log.d(TAG, "Push received: " + data);

        if (!TYPE_NEW_MESSAGE.equals(data.get("type"))) {
            return;
        }

        long channelId = ServerConnectionManager.parseChannelId(data.get("channelId"));
        if (ServerConnectionManager.getInstance(this).isChannelVisible(channelId)) {
            return; // Message is already arriving over the WebSocket into the open chat
        }

        String channelName = data.get("channelName");
        if (channelName == null && remoteMessage.getNotification() != null) {
            channelName = remoteMessage.getNotification().getTitle();
        }
        NotificationHelper.showChannelMessageNotification(this, channelId, channelName);
    }
}
