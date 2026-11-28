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
 * Tests message composition and display in a channel.
 */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class MessagingInstrumentedTest extends BaseInstrumentedTest {

    /**
     * Sends a unique message to the first channel and checks that it appears.
     */
    @Test
    public void send_message_success() {
        loginIfNeeded();
        openFirstServer();
        openFirstChannel();

        String message = "Instrumented message " + System.currentTimeMillis();
        sendMessage(message);
        onView(withText(message)).check(matches(isDisplayed()));
    }
}
