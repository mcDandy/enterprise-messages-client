package cz.mkdaniel.enterprisemessenger;

import cz.mkdaniel.enterprisemessenger.net.AccountStore;

import org.junit.Before;

import androidx.test.espresso.NoMatchingViewException;

import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.*;

import android.os.Bundle;
import androidx.test.platform.app.InstrumentationRegistry;
import android.app.Activity;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import androidx.test.espresso.action.ViewActions;
import androidx.test.espresso.matcher.ViewMatchers;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.rule.GrantPermissionRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;
import android.os.Bundle;
import android.os.SystemClock;

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;

import androidx.test.espresso.NoMatchingViewException;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.action.ViewActions.typeText;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;

import androidx.test.espresso.Espresso;
import androidx.test.espresso.contrib.RecyclerViewActions;

import static androidx.test.espresso.Espresso.openActionBarOverflowOrOptionsMenu;
import static androidx.test.espresso.Espresso.pressBack;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.action.ViewActions.longClick;
import static androidx.test.espresso.matcher.ViewMatchers.withHint;

/**
 * Instrumented test, which will execute on an Android device.
 *
 * @see <a href="http://d.android.com/tools/testing">Testing documentation</a>
 */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class ExampleInstrumentedTest {
    /**
     * This is not a real account
     */
    public static final String USERNAME = "android_user2";
    /**
     * This is NOT a REAL account password.
     */
    public static final String PASSWORD = "secret";
    /**
     * Use {@link ActivityScenarioRule} to create and launch the activity under test, and close it
     * after test completes. This is a replacement for {@link androidx.test.rule.ActivityTestRule}.
     */
    @Rule
    public ActivityScenarioRule<MainActivity> activityScenarioRule = new ActivityScenarioRule<>(MainActivity.class);
    @Rule
    public GrantPermissionRule mRuntimePermissionRule = GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS); 

    private void openFirstServer() {
	onView(withId(R.id.image_view_item_transform)).perform(click());
    }

    private void openFirstChannel() {
	onView(withId(R.id.textview_room_name)).perform(click());
    }

    private void openServerOptionsForFirstServer() {
	onView(withId(R.id.image_view_item_transform)).perform(longClick());
    }

    private void openOptionsMenu() {
	openActionBarOverflowOrOptionsMenu(
		InstrumentationRegistry.getInstrumentation().getTargetContext());
    }

    private void createTestServer() {
	String name = "Delete test " + System.currentTimeMillis();

	onView(withId(R.id.fab_add_server)).perform(click());
	onView(withId(R.id.edittext_name))
	    .perform(replaceText(name), closeSoftKeyboard());
	onView(withText(R.string.action_add)).perform(click());
    }

    private String testRole() {
	Bundle args = InstrumentationRegistry.getArguments();

	return args.getString("role", "");
    }

    private String testRunId() {
	return InstrumentationRegistry.getArguments().getString("runId", "");
    }

    @Before
    public void setUp() {
	activityScenarioRule.getScenario();

	new AccountStore(InstrumentationRegistry.getInstrumentation().getTargetContext())
	    .clearSession();
    }

    /**
     * Context of the app under test.
     */
    @Test
    public void useAppContext() {
	Context appContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
	assertEquals("cz.mkdaniel.enterprisemessenger", appContext.getPackageName());
    }

    @Test
    public void login_success() {
	onView(withId(R.id.edittext_username))
	    .perform(typeText(USERNAME), closeSoftKeyboard());
	onView(withId(R.id.edittext_password))
	    .perform(typeText(PASSWORD), closeSoftKeyboard());
	onView(withId(R.id.button_login))
	    .perform(click());

	onView(withId(R.id.recyclerview_transform))
	    .check(matches(isDisplayed()));
    }

    private Bundle testArgs() {
	return InstrumentationRegistry.getArguments();
    }

    private String requiredArg(String key) {
	String value = testArgs().getString(key);
	if (value == null || value.trim().isEmpty()) {
	    throw new AssertionError("Missing instrumentation argument: " + key);
	}
	return value;
    }

    private void login() {
	onView(withId(R.id.edittext_username))
	    .perform(replaceText(requiredArg("username")), closeSoftKeyboard());
	onView(withId(R.id.edittext_password))
	    .perform(replaceText(requiredArg("password")), closeSoftKeyboard());
	onView(withId(R.id.button_login)).perform(click());

	onView(withId(R.id.recyclerview_transform))
	    .check(matches(isDisplayed()));
    }

    private void openTestChannel() {
	onView(withText(requiredArg("serverName"))).perform(click());
	onView(withId(R.id.recyclerview_rooms)).check(matches(isDisplayed()));

	onView(withText(requiredArg("channelName"))).perform(click());
	onView(withId(R.id.recyclerview_messages)).check(matches(isDisplayed()));
    }
   
    private void sendMessage(String message) {
	onView(withId(R.id.edittext_message_input))
	    .perform(replaceText(message), closeSoftKeyboard());
	onView(withId(R.id.button_send_message)).perform(click());
    }

    private void waitForMessage(String message) {
	long deadline = SystemClock.elapsedRealtime() + 30_000;

	while (SystemClock.elapsedRealtime() < deadline) {
	    try {
		onView(withText(message)).check(matches(isDisplayed()));
		return;
	    } catch (NoMatchingViewException | AssertionError notVisibleYet) {
		SystemClock.sleep(250);
	    }
	}

	// Final check reports the usual Espresso failure with the view matcher.
	onView(withText(message)).check(matches(isDisplayed()));
    }

    private boolean isDisplayed0(int viewId) {
	try {
	    onView(withId(viewId)).check(matches(isDisplayed()));
	    return true;
	} catch (NoMatchingViewException | AssertionError notVisible) {
	    return false;
	}
    }

    private void loginIfNeeded() {
	if (isDisplayed0(R.id.button_login)) {
	    onView(withId(R.id.edittext_username))
		.perform(replaceText(requiredArg("username")), closeSoftKeyboard());
	    onView(withId(R.id.edittext_password))
		.perform(replaceText(requiredArg("password")), closeSoftKeyboard());
	    onView(withId(R.id.button_login)).perform(click());
	}

	// Already signed in, or login just completed.
	onView(withId(R.id.recyclerview_transform))
	    .check(matches(isDisplayed()));
    }
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

    @Test
    public void select_random_message_server_success() {
	onView(withId(R.id.recyclerview_transform))
	    .check(matches(isDisplayed()));
	onView(withId(R.id.image_view_item_transform))
	    .perform(click());

	onView(withId(R.id.recyclerview_rooms))
	    .check(matches(isDisplayed()));
    }

    @Test
    public void select_random_channel_success() {	
	openFirstServer();

	onView(withId(R.id.recyclerview_rooms))
	    .check(matches(isDisplayed()));
	onView(withId(R.id.textview_room_name))
	    .perform(click());

	onView(withId(R.id.recyclerview_messages))
	    .check(matches(isDisplayed()));
    }

    @Test
    public void send_test_message_success() {	
	openFirstServer();
	openFirstChannel();

	String message = "Test message " + System.currentTimeMillis();

	onView(withId(R.id.edittext_message_input))
	    .perform(typeText(message), closeSoftKeyboard());
	onView(withId(R.id.button_send_message))
	    .perform(click());

	onView(withText(message))
	    .check(matches(isDisplayed()));
    }

    @Test
    public void rename_server_success() {	
	openServerOptionsForFirstServer();

	onView(withText(R.string.action_rename)).perform(click());

	String name = "Renamed server " + System.currentTimeMillis();
	onView(withId(R.id.edittext_name))
	    .perform(replaceText(name), closeSoftKeyboard());
	onView(withText(R.string.action_save)).perform(click());

	onView(withText(name)).check(matches(isDisplayed()));
    }

    @Test
    public void delete_server_success() {	
	createTestServer();
	openServerOptionsForFirstServer();

	onView(withText(R.string.action_delete)).perform(click());
	onView(withText(R.string.action_delete)).perform(click());

	// Verify that at least the RecyclerView remains usable after deletion.
	onView(withId(R.id.recyclerview_transform))
	    .check(matches(isDisplayed()));
    }

    @Test
    public void switch_server_success() {
	onView(withId(R.id.image_view_item_transform)).perform(click());
	onView(withId(R.id.recyclerview_rooms)).check(matches(isDisplayed()));

	pressBack();

	onView(withId(R.id.recyclerview_transform)).check(matches(isDisplayed()));
	onView(withId(R.id.image_view_item_transform)).perform(click());
	onView(withId(R.id.recyclerview_rooms)).check(matches(isDisplayed()));
    }

    @Test
    public void create_test_message_server_success() {
	String serverName = "Test server " + System.currentTimeMillis();

	onView(withId(R.id.fab_add_server)).perform(click());
	onView(withId(R.id.edittext_name))
	    .perform(replaceText(serverName), closeSoftKeyboard());
	onView(withText(R.string.action_add)).perform(click());

	onView(withText(serverName)).check(matches(isDisplayed()));
    }

    @Test
    public void create_test_channel_success() {	
	openFirstServer();

	String channelName = "test-channel-" + System.currentTimeMillis();

	onView(withId(R.id.fab_add_channel)).perform(click());
	onView(withId(R.id.edittext_name))
	    .perform(replaceText(channelName), closeSoftKeyboard());
	onView(withText(R.string.action_add)).perform(click());

	onView(withText(channelName)).check(matches(isDisplayed()));
    }

    @Test
    public void add_member_to_server_success() {	
	openServerOptionsForFirstServer();

	onView(withText(R.string.action_add_member)).perform(click());

	onView(withHint(R.string.hint_member_user_id))
	    .perform(replaceText("2"), closeSoftKeyboard());
	onView(withText(R.string.action_add)).perform(click());

	onView(withText(R.string.member_added)).check(matches(isDisplayed()));
    }

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

    @Test
    public void change_name_success() {
	openOptionsMenu();
	onView(withText(R.string.menu_settings)).perform(click());

	String displayName = "Test display " + System.currentTimeMillis();

	onView(withId(R.id.button_edit_display_name)).perform(click());
	onView(withId(R.id.edittext_name))
	    .perform(replaceText(displayName), closeSoftKeyboard());
	onView(withText(R.string.action_save)).perform(click());

	onView(withText(displayName)).check(matches(isDisplayed()));
    }

    @Test
    public void logout_success() {
	openOptionsMenu();
	onView(withText(R.string.menu_settings)).perform(click());
	onView(withId(R.id.button_logout)).perform(click());

	onView(withId(R.id.edittext_username))
	    .check(matches(isDisplayed()));
    }

    @Test
    public void register_success() {
	onView(withId(R.id.button_go_to_register)).perform(click());

	String username = "android_user_" + System.currentTimeMillis();

	onView(withId(R.id.edittext_username))
	    .perform(typeText(username), closeSoftKeyboard());
	onView(withId(R.id.edittext_password))
	    .perform(typeText(PASSWORD), closeSoftKeyboard());
	onView(withId(R.id.edittext_confirm_password))
	    .perform(typeText(PASSWORD), closeSoftKeyboard());
	onView(withId(R.id.button_register))
	    .perform(click());

	onView(withId(R.id.recyclerview_transform))
	    .check(matches(isDisplayed()));
    }
}
