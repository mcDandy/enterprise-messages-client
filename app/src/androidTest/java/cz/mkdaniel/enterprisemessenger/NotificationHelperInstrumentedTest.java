package cz.mkdaniel.enterprisemessenger;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import cz.mkdaniel.enterprisemessenger.notification.NotificationHelper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

/**
 * Verifies notification channel setup and unread-count lifecycle on Android.
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class NotificationHelperInstrumentedTest extends BaseInstrumentedTest {

    @Test
    public void createsTheExpectedNotificationChannel() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

        NotificationHelper.createNotificationChannel(context);

        NotificationManager manager = context.getSystemService(NotificationManager.class);
        assertNotNull(manager);
        NotificationChannel channel =
                manager.getNotificationChannel(NotificationHelper.CHANNEL_ID);
        assertNotNull(channel);
        assertEquals(NotificationHelper.CHANNEL_NAME, channel.getName());
        assertEquals(NotificationHelper.CHANNEL_DESC, channel.getDescription());
    }

    @Test
    public void channelUnreadCountIncrementsAndResetsWhenOpened() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        long channelId = System.currentTimeMillis();
        String key = "unread_Channel #" + channelId;
        SharedPreferences prefs = context.getSharedPreferences(
                "EnterpriseMessenger_Unread", Context.MODE_PRIVATE);
        prefs.edit().remove(key).commit();

        NotificationHelper.showChannelMessageNotification(context, channelId, "general");
        NotificationHelper.showChannelMessageNotification(context, channelId, "general");
        assertEquals(2, prefs.getInt(key, 0));

        NotificationHelper.cancelNotificationForChannel(context, channelId);

        assertFalse(prefs.contains(key));
    }
}
