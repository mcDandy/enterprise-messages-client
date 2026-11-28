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

/**
 * Isolated sign-in and registration-screen tests.
 */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class AuthenticationInstrumentedTest extends BaseInstrumentedTest {

    /**
     * Signs in with the configured or default instrumented-test account.
     */
    @Test
    public void login_success() {
        loginIfNeeded();
    }

    /**
     * Opens the registration form without creating a device-bound account.
     */
    @Test
    public void register_screen_opens() {
        onView(withId(R.id.button_go_to_register)).perform(click());
        onView(withId(R.id.edittext_username)).check(matches(isDisplayed()));
        onView(withId(R.id.edittext_password)).check(matches(isDisplayed()));
        onView(withId(R.id.edittext_confirm_password)).check(matches(isDisplayed()));
    }
}
