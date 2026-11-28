package cz.mkdaniel.enterprisemessenger;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;

/**
 * Verifies Android instrumentation is running against the intended application.
 */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class AppContextInstrumentedTest extends BaseInstrumentedTest {

    /**
     * Checks the target package name.
     */
    @Test
    public void useAppContext() {
        Context appContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("cz.mkdaniel.enterprisemessenger", appContext.getPackageName());
    }
}
