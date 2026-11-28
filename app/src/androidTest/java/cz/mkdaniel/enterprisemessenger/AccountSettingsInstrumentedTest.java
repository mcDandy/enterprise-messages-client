package cz.mkdaniel.enterprisemessenger;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;

import org.junit.Test;
import org.junit.runner.RunWith;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;

/**
 * Tests user-facing account settings.
 */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class AccountSettingsInstrumentedTest extends BaseInstrumentedTest {

    /**
     * Changes the display name to a unique test value.
     */
    @Test
    public void change_display_name_success() {
        loginIfNeeded();
        openOptionsMenu();
        onView(withText(R.string.menu_settings)).perform(click());

        String displayName = "Test display " + System.currentTimeMillis();
        onView(withId(R.id.button_edit_display_name)).perform(click());
        replaceInto(R.id.edittext_name, displayName);
        onView(withText(R.string.action_save)).perform(click());
        onView(withText(displayName)).check(matches(isDisplayed()));
    }

    /**
     * Logs out and verifies that the login screen is shown.
     */
    @Test
    public void logout_success() {
        loginIfNeeded();
        openOptionsMenu();
        onView(withText(R.string.menu_settings)).perform(click());
        onView(withId(R.id.button_logout)).perform(click());
        onView(withId(R.id.edittext_username)).check(matches(isDisplayed()));
    }
}
