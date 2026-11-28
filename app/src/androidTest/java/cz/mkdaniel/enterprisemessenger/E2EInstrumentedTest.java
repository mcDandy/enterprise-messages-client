package cz.mkdaniel.enterprisemessenger;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.Espresso.openActionBarOverflowOrOptionsMenu;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.action.ViewActions.longClick;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withHint;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.junit.Assert.assertEquals;

/**
 * Single-device UI tests for the Android enterprise messenger.
 */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class E2EInstrumentedTest extends BaseInstrumentedTest {
    public static final String USERNAME = "android_user2";
    public static final String PASSWORD = "secret";

    /**
     * Verifies that the target package is the enterprise messenger package.
     */
    @Test
    public void useAppContext() {
        Context appContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("cz.mkdaniel.enterprisemessenger", appContext.getPackageName());
    }

    /**
     * Verifies login using the class-level test credentials.
     */
    @Test
    public void login_success() {
        typeInto(R.id.edittext_username, USERNAME);
        typeInto(R.id.edittext_password, PASSWORD);
        onView(withId(R.id.button_login)).perform(click());
        onView(withId(R.id.recyclerview_transform)).check(matches(isDisplayed()));
    }

    /**
     * Exercises the first server and room navigation controls.
     */
    @Test
    public void select_random_channel_success() {
        openFirstServer();
        onView(withId(R.id.recyclerview_rooms)).check(matches(isDisplayed()));
        onView(withId(R.id.textview_room_name)).perform(click());
        onView(withId(R.id.recyclerview_messages)).check(matches(isDisplayed()));
    }

    /**
     * Sends and verifies a message in the first available channel.
     */
    @Test
    public void send_test_message_success() {
        openFirstServer();
        openFirstChannel();

        String message = "Test message " + System.currentTimeMillis();
        sendMessage(message);
        onView(withText(message)).check(matches(isDisplayed()));
    }

    /**
     * Exercises server creation.
     */
    @Test
    public void create_test_message_server_success() {
        String serverName = "Test server " + System.currentTimeMillis();
        onView(withId(R.id.fab_add_server)).perform(click());
        replaceInto(R.id.edittext_name, serverName);
        onView(withText(R.string.action_add)).perform(click());
        onView(withText(serverName)).check(matches(isDisplayed()));
    }

    /**
     * Exercises channel creation inside the first server.
     */
    @Test
    public void create_test_channel_success() {
        openFirstServer();

        String channelName = "test-channel-" + System.currentTimeMillis();
        onView(withId(R.id.fab_add_channel)).perform(click());
        replaceInto(R.id.edittext_name, channelName);
        onView(withText(R.string.action_add)).perform(click());
        onView(withText(channelName)).check(matches(isDisplayed()));
    }

    /**
     * Exercises adding a channel member through the channel options UI.
     */
    @Test
    public void add_member_to_channel_success() {
        openFirstServer();
        onView(withId(R.id.textview_room_name)).perform(longClick());
        onView(withText(R.string.action_add_channel_member)).perform(click());
        onView(withHint(R.string.hint_member_user_id))
                .perform(replaceText("2"), closeSoftKeyboard());
        onView(withText(R.string.action_add)).perform(click());
        onView(withText(R.string.channel_member_added)).check(matches(isDisplayed()));
    }

    /**
     * Exercises display-name editing.
     */
    @Test
    public void change_name_success() {
        openOptionsMenu();
        onView(withText(R.string.menu_settings)).perform(click());

        String displayName = "Test display " + System.currentTimeMillis();
        onView(withId(R.id.button_edit_display_name)).perform(click());
        replaceInto(R.id.edittext_name, displayName);
        onView(withText(R.string.action_save)).perform(click());
        onView(withText(displayName)).check(matches(isDisplayed()));
    }

    /**
     * Exercises logout and returns to the login screen.
     */
    @Test
    public void logout_success() {
        openOptionsMenu();
        onView(withText(R.string.menu_settings)).perform(click());
        onView(withId(R.id.button_logout)).perform(click());
        onView(withId(R.id.edittext_username)).check(matches(isDisplayed()));
    }

    /**
     * Exercises registration with a unique username.
     */
    @Test
    public void register_success() {
        onView(withId(R.id.button_go_to_register)).perform(click());

        String username = "android_user_" + System.currentTimeMillis();
        typeInto(R.id.edittext_username, username);
        typeInto(R.id.edittext_password, PASSWORD);
        typeInto(R.id.edittext_confirm_password, PASSWORD);
        onView(withId(R.id.button_register)).perform(click());
        onView(withId(R.id.recyclerview_transform)).check(matches(isDisplayed()));
    }

    /**
     * Opens the first server in the server RecyclerView.
     */
    private void openFirstServer() {
        onView(withId(R.id.image_view_item_transform)).perform(click());
    }

    /**
     * Opens the first channel in the rooms RecyclerView.
     */
    private void openFirstChannel() {
        onView(withId(R.id.textview_room_name)).perform(click());
    }

    /**
     * Opens the overflow options menu.
     */
    private void openOptionsMenu() {
        openActionBarOverflowOrOptionsMenu(
                InstrumentationRegistry.getInstrumentation().getTargetContext());
    }
}
