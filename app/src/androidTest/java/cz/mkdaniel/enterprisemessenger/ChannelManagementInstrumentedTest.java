package cz.mkdaniel.enterprisemessenger;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;

import org.junit.Test;
import org.junit.runner.RunWith;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.action.ViewActions.longClick;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withHint;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;

/**
 * Tests channel creation and channel membership management.
 */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class ChannelManagementInstrumentedTest extends BaseInstrumentedTest {

    /**
     * Creates a uniquely named channel in the first server.
     */
    @Test
    public void create_channel_success() {
        loginIfNeeded();
        openFirstServer();

        String channelName = createUniqueChannel();
        onView(withText(channelName)).check(matches(isDisplayed()));
    }

    /**
     * Adds the configured member id to a newly created channel.
     */
    @Test
    public void add_member_to_channel_success() {
        loginIfNeeded();
        openFirstServer();
        String channelName = createUniqueChannel();

        onView(withText(channelName)).perform(longClick());
        onView(withText(R.string.action_add_channel_member)).perform(click());
        onView(withHint(R.string.hint_member_user_id))
                .perform(replaceText(argumentOrDefault("memberUserId", "2")),
                        closeSoftKeyboard());
        onView(withText(R.string.action_add)).perform(click());
        onView(withText(R.string.channel_member_added)).check(matches(isDisplayed()));
    }

    /**
     * Creates a new uniquely named channel in the open server.
     *
     * @return the new channel name
     */
    private String createUniqueChannel() {
        String channelName = "test-channel-" + System.currentTimeMillis();
        onView(withId(R.id.fab_add_channel)).perform(click());
        replaceInto(R.id.edittext_name, channelName);
        onView(withText(R.string.action_add)).perform(click());
        onView(withText(channelName)).check(matches(isDisplayed()));
        return channelName;
    }
}
