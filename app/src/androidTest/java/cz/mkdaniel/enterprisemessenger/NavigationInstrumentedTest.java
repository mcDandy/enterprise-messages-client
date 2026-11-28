package cz.mkdaniel.enterprisemessenger;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;

import org.junit.Test;
import org.junit.runner.RunWith;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;

/**
 * Tests navigation from the server list to a channel.
 */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class NavigationInstrumentedTest extends BaseInstrumentedTest {

    /**
     * Opens the first server and one of its channels.
     */
    @Test
    public void select_channel_success() {
        loginIfNeeded();
        openFirstServer();
        onView(withId(R.id.recyclerview_rooms)).check(matches(isDisplayed()));
        openFirstChannel();
        onView(withId(R.id.recyclerview_messages)).check(matches(isDisplayed()));
    }
}
