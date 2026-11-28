package cz.mkdaniel.enterprisemessenger;

import android.os.SystemClock;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;

import org.junit.Test;
import org.junit.runner.RunWith;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.Espresso.pressBack;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.action.ViewActions.longClick;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.assertion.ViewAssertions.doesNotExist;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withHint;
import static androidx.test.espresso.matcher.ViewMatchers.withText;

/**
 * Two-emulator verification of channel-key rotation after membership changes.
 */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class ChannelKeyRotationTest extends BaseInstrumentedTest {
    private static final long POST_REMOVAL_WAIT_MS = 30_000L;

    /**
     * Verifies that an added member can read messages, then cannot read messages after removal.
     *
     * <p>The administrator and member use separate emulator runs with the same {@code runId}.
     * The administrator adds the member, sends a post-add marker, waits for the member's receipt,
     * removes the member, and sends a post-removal marker. The member waits for the post-add marker
     * and confirms the post-removal marker is not decrypted and displayed.</p>
     */
    @Test
    public void channelKeyRotationAfterAddAndRemove() {
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
     * Opens the configured channel's administrator options from the rooms screen.
     */
    private void openChannelMemberDialog() {
        onView(withText(requiredArg("channelName"))).perform(longClick());
    }
}
