package cz.mkdaniel.enterprisemessenger;

import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;

import androidx.test.espresso.NoMatchingViewException;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.GrantPermissionRule;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import cz.mkdaniel.enterprisemessenger.net.AccountStore;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.Espresso.openActionBarOverflowOrOptionsMenu;
import static androidx.test.espresso.Espresso.pressBack;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.action.ViewActions.longClick;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.action.ViewActions.typeText;
import static androidx.test.espresso.assertion.ViewAssertions.doesNotExist;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withHint;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.junit.Assert.assertEquals;

/**
 * Instrumented end-to-end coverage for the Android enterprise messenger.
 *
 * <p>Tests that require two devices receive synchronized role and run arguments from the
 * instrumentation runner. They use unique message markers as barriers between devices.</p>
 */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class E2EInstrumentedTest {
    public static final String USERNAME = "android_user2";
    public static final String PASSWORD = "secret";
    private static final long WAIT_TIMEOUT_MS = 30_000L;
    private static final long POST_REMOVAL_WAIT_MS = 30_000L;

    @Rule
    public ActivityScenarioRule<MainActivity> activityScenarioRule =
            new ActivityScenarioRule<>(MainActivity.class);

    @Rule
    public GrantPermissionRule runtimePermissionRule =
            GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS);

    /**
     * Clears persisted session data before each test.
     */
    @Before
    public void setUp() {
        activityScenarioRule.getScenario();
        new AccountStore(InstrumentationRegistry.getInstrumentation().getTargetContext())
                .clearSession();
    }

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
     * Verifies the existing two-emulator message flow.
     */
    @Test
    public void twoEmulatorMessageFlow() {
        loginIfNeeded();

        String role = requiredArg("role");
        String runId = requiredArg("runId");
        String message = "E2E message " + runId;

        openTestChannel();

        if ("sender".equals(role)) {
            sendMessage(message);
            onView(withText(message)).check(matches(isDisplayed()));
        } else if ("receiver".equals(role)) {
            waitForMessage(message);
        } else {
            throw new AssertionError("role must be 'sender' or 'receiver', got: " + role);
        }
    }

    /**
     * Verifies channel key rotation when an administrator adds and removes a member.
     *
     * <p>The administrator role adds the member, sends a post-add marker, waits for the member's
     * receipt marker, removes the member, and sends a post-removal marker. The member role waits
     * for the post-add marker, acknowledges receipt, and verifies that the post-removal marker
     * remains undecrypted and absent.</p>
     */
    @Test
    public void twoEmulatorChannelKeyRotation() {
        loginIfNeeded();

        String role = requiredArg("role");
        String runId = requiredArg("runId");
        String postAddMarker = "key-rotation-added-" + runId;
        String memberReceiptMarker = "key-rotation-member-read-" + runId;
        String postRemovalMarker = "key-rotation-removed-" + runId;

        openTestChannel();

        if ("administrator".equals(role)) {
            String memberUserId = requiredArg("memberUserId");

            pressBack();
            openChannelMemberDialog();
            onView(withText(R.string.action_add_channel_member)).perform(click());
            onView(withHint(R.string.hint_member_user_id))
                    .perform(replaceText(memberUserId), closeSoftKeyboard());
            onView(withText(R.string.action_add)).perform(click());
            onView(withText(R.string.channel_member_added)).check(matches(isDisplayed()));

            openChannelInRooms();
            sendMessage(postAddMarker);
            onView(withText(postAddMarker)).check(matches(isDisplayed()));
            waitForMessage(memberReceiptMarker);

            pressBack();
            openChannelMemberDialog();
            onView(withText(R.string.action_remove_channel_member)).perform(click());
            onView(withText("User ID: " + memberUserId)).perform(click());
            onView(withText(R.string.action_remove)).perform(click());
            onView(withText(R.string.channel_member_removed)).check(matches(isDisplayed()));

            openChannelInRooms();
            sendMessage(postRemovalMarker);
            onView(withText(postRemovalMarker)).check(matches(isDisplayed()));
        } else if ("member".equals(role)) {
            waitForMessage(postAddMarker);
            sendMessage(memberReceiptMarker);
            onView(withText(memberReceiptMarker)).check(matches(isDisplayed()));

            SystemClock.sleep(POST_REMOVAL_WAIT_MS);
            onView(withText(postRemovalMarker)).check(doesNotExist());
        } else {
            throw new AssertionError(
                    "role must be 'administrator' or 'member', got: " + role);
        }
    }

    /**
     * Opens the server and channel configured by instrumentation arguments.
     */
    private void openTestChannel() {
        onView(withText(requiredArg("serverName"))).perform(click());
        onView(withId(R.id.recyclerview_rooms)).check(matches(isDisplayed()));
        onView(withText(requiredArg("channelName"))).perform(click());
        onView(withId(R.id.recyclerview_messages)).check(matches(isDisplayed()));
    }

    /**
     * Opens the configured channel while the rooms RecyclerView is already displayed.
     */
    private void openChannelInRooms() {
        onView(withText(requiredArg("channelName"))).perform(click());
        onView(withId(R.id.recyclerview_messages)).check(matches(isDisplayed()));
    }

    /**
     * Returns to the rooms list and opens the configured channel's administrator options.
     */
    private void openChannelMemberDialog() {
        onView(withText(requiredArg("channelName"))).perform(longClick());
    }

    /**
     * Logs in when the app starts on the login screen.
     */
    private void loginIfNeeded() {
        if (isDisplayed(R.id.button_login)) {
            replaceInto(R.id.edittext_username, requiredArg("username"));
            replaceInto(R.id.edittext_password, requiredArg("password"));
            onView(withId(R.id.button_login)).perform(click());
        }
        onView(withId(R.id.recyclerview_transform)).check(matches(isDisplayed()));
    }

    /**
     * Sends text in the active message channel.
     *
     * @param message text to send
     */
    private void sendMessage(String message) {
        replaceInto(R.id.edittext_message_input, message);
        onView(withId(R.id.button_send_message)).perform(click());
    }

    /**
     * Waits until text is visible or the test timeout is reached.
     *
     * @param message text that must become visible
     */
    private void waitForMessage(String message) {
        long deadline = SystemClock.elapsedRealtime() + WAIT_TIMEOUT_MS;

        while (SystemClock.elapsedRealtime() < deadline) {
            try {
                onView(withText(message)).check(matches(isDisplayed()));
                return;
            } catch (NoMatchingViewException | AssertionError notVisibleYet) {
                SystemClock.sleep(250);
            }
        }

        onView(withText(message)).check(matches(isDisplayed()));
    }

    /**
     * Returns whether a view is currently displayed.
     *
     * @param viewId resource ID of the view
     * @return {@code true} when the view is displayed
     */
    private boolean isDisplayed(int viewId) {
        try {
            onView(withId(viewId)).check(matches(isDisplayed()));
            return true;
        } catch (NoMatchingViewException | AssertionError notVisible) {
            return false;
        }
    }

    /**
     * Reads a required instrumentation argument.
     *
     * @param key argument name
     * @return trimmed argument value
     * @throws AssertionError if the argument is absent or blank
     */
    private String requiredArg(String key) {
        Bundle arguments = InstrumentationRegistry.getArguments();
        String value = arguments.getString(key);
        if (value == null || value.trim().isEmpty()) {
            throw new AssertionError("Missing instrumentation argument: " + key);
        }
        return value.trim();
    }

    /**
     * Replaces text in an edit control and closes the soft keyboard.
     *
     * @param viewId resource ID of the edit control
     * @param text replacement text
     */
    private void replaceInto(int viewId, String text) {
        onView(withId(viewId)).perform(replaceText(text), closeSoftKeyboard());
    }

    /**
     * Types text in an edit control and closes the soft keyboard.
     *
     * @param viewId resource ID of the edit control
     * @param text text to type
     */
    private void typeInto(int viewId, String text) {
        onView(withId(viewId)).perform(typeText(text), closeSoftKeyboard());
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
