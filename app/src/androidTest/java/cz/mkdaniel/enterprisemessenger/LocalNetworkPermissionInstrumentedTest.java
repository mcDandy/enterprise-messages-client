package cz.mkdaniel.enterprisemessenger;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

import cz.mkdaniel.enterprisemessenger.net.LocalNetworkPermission;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Verifies local-address classification using Android's numeric-address parser.
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class LocalNetworkPermissionInstrumentedTest {

    @Test
    public void classifiesPrivateAndLinkLocalAddressesAsLocal() {
        assertTrue(LocalNetworkPermission.needsLocalNetworkAccess("192.168.1.25"));
        assertTrue(LocalNetworkPermission.needsLocalNetworkAccess("169.254.10.2"));
    }

    @Test
    public void doesNotRequestLocalAccessForPublicAddressesOrLoopback() {
        assertFalse(LocalNetworkPermission.needsLocalNetworkAccess("8.8.8.8"));
        assertFalse(LocalNetworkPermission.needsLocalNetworkAccess("::1"));
    }

    @Test
    public void treatsHostnamesConservativelyButIgnoresBlankInput() {
        assertTrue(LocalNetworkPermission.needsLocalNetworkAccess("intranet.example"));
        assertFalse(LocalNetworkPermission.needsLocalNetworkAccess("  "));
        assertFalse(LocalNetworkPermission.needsLocalNetworkAccess(null));
    }
}
