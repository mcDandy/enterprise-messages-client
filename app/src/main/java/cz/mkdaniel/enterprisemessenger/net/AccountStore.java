package cz.mkdaniel.enterprisemessenger.net;

import android.content.Context;
import android.util.Log;

import java.nio.charset.StandardCharsets;

import cz.mkdaniel.enterprisemessenger.crypto.AppSecrets;
import cz.mkdaniel.enterprisemessenger.crypto.KeyStoreUnavailableException;
import cz.mkdaniel.enterprisemessenger.crypto.KeyValueSink;
import cz.mkdaniel.enterprisemessenger.crypto.SecureBlobStore;
import cz.mkdaniel.enterprisemessenger.crypto.SharedPreferencesSink;

/**
 * Persists this installation's account.
 * <p>
 * The account password is not stored anywhere: it is used to obtain a token during login and then
 * forgotten. Earlier revisions kept it in plaintext so the app could re-login itself forever, which
 * turned a file that every backup and every {@code adb} reader can reach into a copy of the user's
 * password. Session continuity is the token's job now, refreshed through
 * {@code POST /api/auth/refresh} (see {@code ServerConnectionManager#refreshToken()}).
 * <p>
 * The token is sealed by the Android KeyStore master key (see {@link SecureBlobStore}) together with
 * its expiry and its issuer. Non-secret values (username, user id) stay in ordinary preferences,
 * which is why the two stores are separate.
 */
public class AccountStore {

    private static final String TAG = "AccountStore";

    private static final String PREF_NAME = "EnterpriseMessenger_Account";
    private static final String KEY_USERNAME = "username";
    private static final String KEY_LOGGED_IN = "is_logged_in";

    /**
     * The account this device's encryption identity belongs to. The identity key pair is generated
     * once and published as that account's public key during registration, so a second account
     * signing in here would inherit the first one's channel keys and peer secrets while the server
     * kept trusting a public key this device can no longer back with a private one. Login and
     * registration are therefore refused for anybody else.
     * <p>
     * Not a secret (a username already sits in {@link #KEY_USERNAME} beside it) and deliberately not
     * cleared by {@link #clearSession()}: logging out must not hand the device to the next account.
     */
    private static final String KEY_BOUND_ACCOUNT = "bound_account";

    /**
     * Numeric id the physical server assigned to this account ({@code GET /api/users/me}). Cached so
     * the app can recognise itself in a virtual server's member list and tell whether the signed-in
     * user is its ADMINISTRATOR, which is what gates the create/rename/delete controls.
     */
    private static final String KEY_USER_ID = "user_id";

    /** Sealed slot of the access token. */
    private static final String SLOT_JWT_TOKEN = "jwt_token";
    /** Sealed slot of the address that issued the token, so it is not offered to another server. */
    private static final String SLOT_TOKEN_ISSUER = "jwt_token_issuer";
    /** Sealed slot of the token's expiry as an epoch-millisecond decimal. */
    private static final String SLOT_TOKEN_EXPIRES_AT = "jwt_token_expires_at";

    /**
     * Key of an earlier revision that held the password in plaintext. Deleted on every construction,
     * never read.
     */
    private static final String LEGACY_KEY_PASSWORD = "password";
    /** Key of an earlier revision that held the JWT in plaintext. Deleted, never read. */
    private static final String LEGACY_KEY_JWT_TOKEN = "jwt_token";

    /**
     * How long before expiry the app proactively refreshes the token. Without a margin the token can
     * expire between the decision and the request that uses it, which looks like a spontaneous
     * log-out.
     */
    private static final long REFRESH_MARGIN_MILLIS = 60_000L;

    private final KeyValueSink plain;
    private final SecureBlobStore blobs;

    public AccountStore(Context context) {
        this(new SharedPreferencesSink(context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)),
                AppSecrets.getInstance(context).blobs());
    }

    /**
     * The seam unit tests use: the plaintext slots and the sealed store are both injected, so the
     * whole account layer is verifiable on the JVM.
     */
    public AccountStore(KeyValueSink plain, SecureBlobStore blobs) {
        this.plain = plain;
        this.blobs = blobs;

        purgeLegacyPlaintextSecrets();
    }

    /**
     * One-time repair of installations that predate the sealed store. The values are removed without
     * ever being read into a variable, so the password and the old token never enter the heap or the
     * log; the session goes with them, which costs the user one login.
     */
    private void purgeLegacyPlaintextSecrets() {
        final boolean hadLegacySecret =
                plain.getString(LEGACY_KEY_PASSWORD) != null
                        || plain.getString(LEGACY_KEY_JWT_TOKEN) != null;

        if (!hadLegacySecret) {
            return;
        }

        plain.remove(LEGACY_KEY_PASSWORD);
        plain.remove(LEGACY_KEY_JWT_TOKEN);

        Log.d(TAG, "Removed the credentials an earlier version stored in plaintext");
    }

    public synchronized boolean isLoggedIn() {
        return Boolean.parseBoolean(plain.getString(KEY_LOGGED_IN));
    }

    /**
     * Stores a fresh session. There is no password parameter: nothing here may hold one.
     *
     * @param tokenIssuer address of the server that signed {@code token}; a token is only offered
     *                    back to that address, otherwise it produces a 401 and a log-out the user
     *                    did not ask for
     * @param expiresAt   epoch milliseconds from the server's {@code expiresAt}, or 0 when unknown
     */
    public synchronized void saveSession(String username, String token, String tokenIssuer, long expiresAt) {
        plain.putString(KEY_USERNAME, username);
        plain.putString(KEY_LOGGED_IN, Boolean.TRUE.toString());

        // The first account to sign in on an installation owns its identity: it either registered
        // here, publishing this device's public key, or it is the account whose keys are already
        // sealed on the device. Every later sign-in is gated by boundAccount(), never claimed.
        if (boundAccount().isEmpty()) {
            plain.putString(KEY_BOUND_ACCOUNT, username);
        }

        saveJwtToken(token, tokenIssuer, expiresAt);
    }

    public synchronized void saveJwtToken(String token, String tokenIssuer, long expiresAt) {
        try {
            blobs.put(SLOT_JWT_TOKEN, bytesOf(token));
            blobs.put(SLOT_TOKEN_ISSUER, bytesOf(tokenIssuer == null ? "" : tokenIssuer));
            blobs.put(SLOT_TOKEN_EXPIRES_AT, bytesOf(Long.toString(expiresAt)));
        } catch (KeyStoreUnavailableException failure) {
            // Without a usable master key the token cannot be persisted. The in-memory copy inside
            // ServerConnectionManager still works for this run, so the session survives it; only the
            // next launch has to sign in again.
            Log.w(TAG, "The token could not be sealed, it will not survive a restart: "
                    + failure.getMessage());
        }
    }

    /** Convenience for callers that do not know the expiry. */
    public synchronized void saveJwtToken(String token, String tokenIssuer) {
        saveJwtToken(token, tokenIssuer, 0L);
    }

    /**
     * Ends the session. The device binding survives on purpose: a log-out is not a hand-over, and
     * forgetting it here is exactly what let another account inherit this device's keys.
     */
    public synchronized void clearSession() {
        plain.remove(KEY_USERNAME);
        plain.remove(KEY_USER_ID);
        plain.remove(KEY_LOGGED_IN);

        blobs.remove(SLOT_JWT_TOKEN);
        blobs.remove(SLOT_TOKEN_ISSUER);
        blobs.remove(SLOT_TOKEN_EXPIRES_AT);
    }

    /**
     * Caches the numeric id the physical server handed to {@code GET /api/users/me}, so member lists
     * can be scanned for it without asking again on every screen. Not secret: the id appears in every
     * member list the user can read anyway.
     */
    public synchronized void saveUserId(long userId) {
        plain.putString(KEY_USER_ID, Long.toString(userId));
    }

    /** Cached user id, or {@code -1L} when this installation has never fetched it. */
    public synchronized long getUserId() {
        final String cached = plain.getString(KEY_USER_ID);

        if (cached == null) {
            return -1L;
        }

        try {
            return Long.parseLong(cached);
        } catch (NumberFormatException unparseable) {
            return -1L;
        }
    }

    public synchronized String getJwtToken() {
        return stringOf(blobs.load(SLOT_JWT_TOKEN));
    }

    /** Address of the server that issued the stored token, or "" when it was never recorded. */
    public synchronized String getTokenIssuer() {
        return stringOf(blobs.load(SLOT_TOKEN_ISSUER));
    }

    /**
     * Expiry of the stored token in epoch milliseconds, or 0 when the store does not know it (an
     * older installation, or a sealed slot that no longer opens).
     */
    public synchronized long getTokenExpiresAt() {
        final String raw = stringOf(blobs.load(SLOT_TOKEN_EXPIRES_AT));

        if (raw.isEmpty()) {
            return 0L;
        }

        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException unparseable) {
            return 0L;
        }
    }

    /**
     * Whether the stored token should be exchanged before being used again. A token with no known
     * expiry is used as-is: the server rejects it if it is really dead, and the caller then clears
     * the session.
     */
    public synchronized boolean shouldRefreshToken() {
        final long expiresAt = getTokenExpiresAt();

        return expiresAt > 0L && expiresAt - REFRESH_MARGIN_MILLIS <= System.currentTimeMillis();
    }

    public synchronized String getUsername() {
        final String username = plain.getString(KEY_USERNAME);

        return username == null ? "" : username;
    }

    /**
     * The account this device's encryption identity is bound to, or {@code ""} when no account has
     * ever signed in here (a fresh installation, or a reset device identity).
     */
    public synchronized String boundAccount() {
        final String bound = plain.getString(KEY_BOUND_ACCOUNT);

        return bound == null ? "" : bound;
    }

    /**
     * Whether {@code username} may use this device's encryption identity: an unbound device accepts
     * anybody, a bound one only its own account.
     */
    public synchronized boolean isBoundTo(String username) {
        final String bound = boundAccount();

        return bound.isEmpty() || bound.equalsIgnoreCase(username);
    }

    /**
     * Releases the device for another account. Only the reset of the device identity may call this,
     * and only together with deleting the identity key pair and emptying the sealed registries;
     * clearing the binding alone would leave the next account reading the previous one's channels.
     */
    public synchronized void unbindAccount() {
        plain.remove(KEY_BOUND_ACCOUNT);
    }

    private static byte[] bytesOf(String value) {
        return (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
    }

    private static String stringOf(byte[] value) {
        return value == null ? "" : new String(value, StandardCharsets.UTF_8);
    }
}
