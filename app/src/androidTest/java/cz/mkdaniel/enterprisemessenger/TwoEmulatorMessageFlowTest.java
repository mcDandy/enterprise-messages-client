package cz.mkdaniel.enterprisemessenger;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;

import org.junit.Test;
import org.junit.runner.RunWith;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withText;

/**
 * Two-emulator tests for exchanging ordinary channel messages.
 */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class TwoEmulatorMessageFlowTest extends BaseInstrumentedTest {

    /**
     * Verifies that a sender's message appears on the receiver emulator.
     */
    @Test
    public void twoEmulatorMessageFlow() {
        loginIfNeeded();

        String role = requiredArg("role");
        String runId = requiredArg("runId");
        String readyMessage = "E2E receiver ready " + runId;
        String message = "E2E message " + runId;
        openTestChannel();

        if ("sender".equals(role)) {
            waitForMessage(readyMessage);
            sendMessage(message);
            onView(withText(message)).check(matches(isDisplayed()));
        } else if ("receiver".equals(role)) {
            sendMessage(readyMessage);
            onView(withText(readyMessage)).check(matches(isDisplayed()));
            waitForMessage(message);
        } else {
            throw new AssertionError("role must be 'sender' or 'receiver', got: " + role);
        }
    }
}
