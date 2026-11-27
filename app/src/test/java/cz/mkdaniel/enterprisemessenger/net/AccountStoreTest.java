package cz.mkdaniel.enterprisemessenger.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.security.GeneralSecurityException;

import cz.mkdaniel.enterprisemessenger.crypto.FakeKeyValueSink;
import cz.mkdaniel.enterprisemessenger.crypto.FixedMasterKey;
import cz.mkdaniel.enterprisemessenger.crypto.KeyValueSink;
import cz.mkdaniel.enterprisemessenger.crypto.SecureBlobStore;

/**
 * The credential store. Its reason for existing is a regression test: this app used to persist the
 * account password in plaintext so it could re-login forever (audit F-5). Nothing here may ever
 * write a password, and the token may only exist sealed.
 */
public class AccountStoreTest {

    private static final String TOKEN = "eyJhbGciOiJIUzI1NiJ9.payload.signature";
    private static final String ISSUER = "https://192.168.1.115:8443";

    private FakeKeyValueSink plain;
    private SecureBlobStore blobs;
    private AccountStore store;

    /**
     * Creates the test fixture used by the test methods.
     *
     * @throws GeneralSecurityException if a cryptographic operation fails.
     */
    @Before
    public void setUp() throws GeneralSecurityException {
        plain = new FakeKeyValueSink();
        blobs = new SecureBlobStore(new FakeKeyValueSink(), new FixedMasterKey());
        store = new AccountStore(plain, blobs);
    }

    /**
     * Verifies session round trips without a password for this case.
     *
     */
    @Test
    public void sessionRoundTripsWithoutAPassword() {
        store.saveSession("alice", TOKEN, ISSUER, System.currentTimeMillis() + 3_600_000L);

        assertTrue(store.isLoggedIn());
        assertEquals("alice", store.getUsername());
        assertEquals(TOKEN, store.getJwtToken());
        assertEquals(ISSUER, store.getTokenIssuer());
    }

    /**
     * Verifies nothing secret reaches the plain slots for this case.
     *
     */
    @Test
    public void nothingSecretReachesThePlainSlots() {
        store.saveSession("alice", TOKEN, ISSUER, 0L);

        assertFalse("the password key must never be written",
                plain.keys().contains("password"));

        for (final String value : plain.values()) {
            assertFalse("a plain slot must not contain the token", value.contains(TOKEN));
            assertFalse("a plain slot must not contain the issuer", value.contains(ISSUER));
        }
    }

    /**
     * Verifies legacy plaintext credentials are purged on open for this case.
     *
     */
    @Test
    public void legacyPlaintextCredentialsArePurgedOnOpen() {
        // An installation upgrading from the version that stored these in the clear.
        final KeyValueSink upgrading = new FakeKeyValueSink();
        upgrading.putString("password", "hunter2");
        upgrading.putString("jwt_token", TOKEN);

        new AccountStore(upgrading, blobs);

        assertFalse(upgrading.keys().contains("password"));
        assertFalse(upgrading.keys().contains("jwt_token"));
    }

    /**
     * Verifies clear session drops both stores for this case.
     *
     */
    @Test
    public void clearSessionDropsBothStores() {
        store.saveSession("alice", TOKEN, ISSUER, 0L);
        store.saveUserId(17L);
        store.saveProfileDetails("Alice A", "2026-11-20T15:23:27.526124Z");

        store.clearSession();

        assertFalse(store.isLoggedIn());
        assertEquals("", store.getUsername());
        assertEquals("", store.getJwtToken());
        assertEquals(-1L, store.getUserId());
        assertEquals("the display name belongs to the session", "", store.getDisplayName());
        assertEquals("the registration date belongs to the session", "", store.getRegisteredAt());
    }

    /**
     * Verifies user identifier round trips for this case.
     *
     */
    @Test
    public void userIdRoundTrips() {
        assertEquals(-1L, store.getUserId());

        store.saveUserId(4242L);

        assertEquals(4242L, store.getUserId());
    }

    /**
     * Verifies profile details round trip for this case.
     *
     */
    @Test
    public void profileDetailsRoundTrip() {
        assertEquals("an account that was never read has no display name", "", store.getDisplayName());
        assertEquals("an account that was never read has no registration date", "",
                store.getRegisteredAt());

        store.saveProfileDetails("Alice A", "2026-11-20T15:23:27.526124Z");

        assertEquals("Alice A", store.getDisplayName());
        assertEquals("2026-11-20T15:23:27.526124Z", store.getRegisteredAt());
    }

    /**
     * Verifies absent profile details store empty rather than null for this case.
     *
     */
    @Test
    public void absentProfileDetailsStoreEmptyRatherThanNull() {
        // GET /api/users/me answers JSON null for an account that never set a display name; the store
        // must not turn that into the string "null" nor leave a stale value from a previous account.
        store.saveProfileDetails("Alice A", "2026-11-20T15:23:27.526124Z");
        store.saveProfileDetails(null, null);

        assertEquals("", store.getDisplayName());
        assertEquals("", store.getRegisteredAt());
    }

    /**
     * Verifies the first account to sign in owns the device for this case.
     *
     */
    @Test
    public void theFirstAccountToSignInOwnsTheDevice() {
        assertEquals("an untouched installation is unbound", "", store.boundAccount());
        assertTrue("an unbound device accepts anybody", store.isBoundTo("alice"));

        store.saveSession("alice", TOKEN, ISSUER, 0L);

        assertEquals("alice", store.boundAccount());
        assertTrue(store.isBoundTo("alice"));
        assertTrue("the name is compared as the server compares it", store.isBoundTo("Alice"));
        assertFalse("a second account may not inherit the device", store.isBoundTo("bob"));
    }

    /**
     * Verifies a second account cannot steal the binding for this case.
     *
     */
    @Test
    public void aSecondAccountCannotStealTheBinding() {
        store.saveSession("alice", TOKEN, ISSUER, 0L);
        store.saveSession("bob", TOKEN, ISSUER, 0L);

        assertEquals("only the reset in Settings may rebind a device", "alice", store.boundAccount());
    }

    /**
     * Verifies logging out keeps the device bound for this case.
     *
     */
    @Test
    public void loggingOutKeepsTheDeviceBound() {
        store.saveSession("alice", TOKEN, ISSUER, 0L);

        store.clearSession();

        assertFalse(store.isLoggedIn());
        assertEquals("a log-out is not a hand-over", "alice", store.boundAccount());
    }

    /**
     * Verifies unbinding is the only way to accept another account for this case.
     *
     */
    @Test
    public void unbindingIsTheOnlyWayToAcceptAnotherAccount() {
        store.saveSession("alice", TOKEN, ISSUER, 0L);

        store.unbindAccount();

        assertEquals("", store.boundAccount());
        assertTrue(store.isBoundTo("bob"));

        store.saveSession("bob", TOKEN, ISSUER, 0L);

        assertEquals("bob", store.boundAccount());
    }

    /**
     * Verifies token expiry drives the refresh decision for this case.
     *
     */
    @Test
    public void tokenExpiryDrivesTheRefreshDecision() {
        store.saveSession("alice", TOKEN, ISSUER, 0L);

        assertFalse("a token of unknown expiry is used as-is", store.shouldRefreshToken());

        store.saveJwtToken(TOKEN, ISSUER, System.currentTimeMillis() - 1L);
        assertTrue("an expired token must be exchanged first", store.shouldRefreshToken());

        store.saveJwtToken(TOKEN, ISSUER, System.currentTimeMillis() + 3_600_000L);
        assertFalse(store.shouldRefreshToken());
    }

    /**
     * Verifies the blob store holds the token only sealed for this case.
     *
     * @throws Exception if the the blob store holds the token only sealed operation cannot be completed.
     */
    @Test
    public void theBlobStoreHoldsTheTokenOnlySealed() throws Exception {
        // The same sink the account layer writes to, read directly under its slot name.
        final FakeKeyValueSink sealedSink = new FakeKeyValueSink();
        final AccountStore overSealedSink =
                new AccountStore(plain, new SecureBlobStore(sealedSink, new FixedMasterKey()));

        overSealedSink.saveSession("alice", TOKEN, ISSUER, 0L);

        final String sealed = sealedSink.getString("jwt_token");

        assertTrue("the token slot must exist", sealed != null && !sealed.isEmpty());
        assertFalse("the stored blob must not be the token", sealed.contains(TOKEN));
        assertEquals(TOKEN, overSealedSink.getJwtToken());
    }
}
