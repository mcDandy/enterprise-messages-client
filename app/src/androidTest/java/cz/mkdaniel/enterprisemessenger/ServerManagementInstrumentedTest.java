package cz.mkdaniel.enterprisemessenger;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;

import org.junit.Test;
import org.junit.runner.RunWith;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;

/**
 * Tests server creation.
 */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class ServerManagementInstrumentedTest extends BaseInstrumentedTest {

    /**
     * Creates a uniquely named server and checks that it appears in the list.
     */
    @Test
    public void create_server_success() {
        loginIfNeeded();

        String serverName = "Test server " + System.currentTimeMillis();
        onView(withId(R.id.fab_add_server)).perform(click());
        replaceInto(R.id.edittext_name, serverName);
        onView(withText(R.string.action_add)).perform(click());
        onView(withText(serverName)).check(matches(isDisplayed()));
    }
}
