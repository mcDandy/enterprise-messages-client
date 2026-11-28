package cz.mkdaniel.enterprisemessenger;

import android.os.Bundle;
import android.os.SystemClock;

import androidx.test.espresso.NoMatchingViewException;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.GrantPermissionRule;

import org.junit.Rule;
import org.junit.rules.ExternalResource;
import org.junit.rules.RuleChain;
import org.junit.rules.TestRule;

import cz.mkdaniel.enterprisemessenger.net.AccountStore;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.Espresso.openActionBarOverflowOrOptionsMenu;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.action.ViewActions.typeText;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;

/**
 * Shared activity setup and Espresso helpers for instrumented app tests.
 */
public abstract class BaseInstrumentedTest {
    private static final long WAIT_TIMEOUT_MS = 30_000L;
    protected static final String DEFAULT_USERNAME = "android_user2";
    protected static final String DEFAULT_PASSWORD = "secret";

    @Rule
    public final TestRule appRule = RuleChain
            .outerRule(new ExternalResource() {
                @Override
                protected void before() {
                    new AccountStore(
                            InstrumentationRegistry.getInstrumentation().getTargetContext())
                            .clearSession();
                }
            })
            .around(GrantPermissionRule.grant(
                    android.Manifest.permission.POST_NOTIFICATIONS))
            .around(new ActivityScenarioRule<>(MainActivity.class));

    /**
     * Opens the server and channel configured by instrumentation arguments.
     */
    protected void openTestChannel() {
        onView(withText(requiredArg("serverName"))).perform(click());
        onView(withId(R.id.recyclerview_rooms)).check(matches(isDisplayed()));
        openChannelInRooms();
    }

    /**
     * Opens the configured channel while the rooms list is displayed.
     */
    protected void openChannelInRooms() {
        onView(withText(requiredArg("channelName"))).perform(click());
        onView(withId(R.id.recyclerview_messages)).check(matches(isDisplayed()));
    }

    /**
     * Opens the first server in the server list.
     */
    protected void openFirstServer() {
        onView(withId(R.id.image_view_item_transform)).perform(click());
    }

    /**
     * Opens the first channel in the current rooms list.
     */
    protected void openFirstChannel() {
        onView(withId(R.id.textview_room_name)).perform(click());
    }

    /**
     * Opens the activity overflow menu.
     */
    protected void openOptionsMenu() {
        openActionBarOverflowOrOptionsMenu(
                InstrumentationRegistry.getInstrumentation().getTargetContext());
    }

    /**
     * Logs in when the app starts on the login screen.
     */
    protected void loginIfNeeded() {
        if (isViewDisplayed(R.id.button_login)) {
            replaceInto(R.id.edittext_username,
                    argumentOrDefault("username", DEFAULT_USERNAME));
            replaceInto(R.id.edittext_password,
                    argumentOrDefault("password", DEFAULT_PASSWORD));
            onView(withId(R.id.button_login)).perform(click());
        }
        waitForViewDisplayed(R.id.recyclerview_transform);
    }

    /**
     * Waits for a view to become visible while asynchronous UI work completes.
     *
     * @param viewId resource ID of the view
     */
    protected void waitForViewDisplayed(int viewId) {
        long deadline = SystemClock.elapsedRealtime() + WAIT_TIMEOUT_MS;

        while (SystemClock.elapsedRealtime() < deadline) {
            if (isViewDisplayed(viewId)) {
                return;
            }
            SystemClock.sleep(250);
        }

        onView(withId(viewId)).check(matches(isDisplayed()));
    }

    /**
     * Sends text in the active message channel.
     *
     * @param message text to send
     */
    protected void sendMessage(String message) {
        replaceInto(R.id.edittext_message_input, message);
        onView(withId(R.id.button_send_message)).perform(click());
    }

    /**
     * Waits until text is visible or the test timeout is reached.
     *
     * @param message text that must become visible
     */
    protected void waitForMessage(String message) {
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
    protected boolean isViewDisplayed(int viewId) {
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
    protected String requiredArg(String key) {
        Bundle arguments = InstrumentationRegistry.getArguments();
        String value = arguments.getString(key);
        if (value == null || value.trim().isEmpty()) {
            throw new AssertionError("Missing instrumentation argument: " + key);
        }
        return value.trim();
    }

    /**
     * Reads an instrumentation argument, using a local test default when it is absent.
     *
     * @param key argument name
     * @param defaultValue fallback value
     * @return trimmed argument value or the fallback
     */
    protected String argumentOrDefault(String key, String defaultValue) {
        String value = InstrumentationRegistry.getArguments().getString(key);
        return value == null || value.trim().isEmpty() ? defaultValue : value.trim();
    }

    /**
     * Replaces text in an edit control and closes the soft keyboard.
     *
     * @param viewId resource ID of the edit control
     * @param text replacement text
     */
    protected void replaceInto(int viewId, String text) {
        onView(withId(viewId)).perform(replaceText(text), closeSoftKeyboard());
    }

    /**
     * Types text in an edit control and closes the soft keyboard.
     *
     * @param viewId resource ID of the edit control
     * @param text text to type
     */
    protected void typeInto(int viewId, String text) {
        onView(withId(viewId)).perform(typeText(text), closeSoftKeyboard());
    }
}
