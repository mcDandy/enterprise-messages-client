package cz.mkdaniel.enterprisemessenger;

import android.content.Context;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.*;

import android.app.Activity;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import androidx.test.espresso.action.ViewActions;
import androidx.test.espresso.matcher.ViewMatchers;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.rule.GrantPermissionRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.action.ViewActions.typeText;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;

/**
 * Instrumented test, which will execute on an Android device.
 *
 * @see <a href="http://d.android.com/tools/testing">Testing documentation</a>
 */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class ExampleInstrumentedTest {
    public static final String USERNAME = "android_user2";
    /**
     * This is NOT a REAL account password.
     */
    public static final String PASSWORD = "secret";

    /**
     * Context of the app under test.
     */
    @Test
    public void useAppContext() {
	Context appContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
	assertEquals("cz.mkdaniel.enterprisemessenger", appContext.getPackageName());
    }

    /**
     * Use {@link ActivityScenarioRule} to create and launch the activity under test, and close it
     * after test completes. This is a replacement for {@link androidx.test.rule.ActivityTestRule}.
     */
    @Rule
    public ActivityScenarioRule<MainActivity> activityScenarioRule = new ActivityScenarioRule<>(MainActivity.class);
    @Rule
    public GrantPermissionRule mRuntimePermissionRule = GrantPermissionRule.grant(android.Manifest.permission.POST_NITIFICATIONS);

    @Test
    public void login_success() {
	onView(withId(R.id.edittext_username)).perform(typeText(USERNAME), closeSoftKeyboard());
	onView(withId(R.id.edittext_password)).perform(typeText(PASSWORD), closeSoftKeyboard());
	onView(withId(R.id.button_login)).perform(click());
	onView(withId(R.id.image_view_item_transform));
    }

    @Test
    public void register_success() {
	onView(withId(R.id.button_go_to_register)).perform(click());
	onView(withId(R.id.edittext_username)).perform(typeText(USERNAME), closeSoftKeyboard());
	onView(withId(R.id.edittext_password)).perform(typeText(PASSWORD), closeSoftKeyboard());
	onView(withId(R.id.edittext_confirm_password)).perform(typeText(PASSWORD), closeSoftKeyboard());
	onView(withId(R.id.button_register)).perform(click());
	onView(withId(R.id.image_view_item_transform));
    }
}
