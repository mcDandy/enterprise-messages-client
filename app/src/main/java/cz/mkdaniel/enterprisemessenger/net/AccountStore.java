package cz.mkdaniel.enterprisemessenger.net;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.SecureRandom;

/**
 * Persists this installation's account credentials, the JWT of the single physical server it is
 * logged in to (its address is compile-time configuration only, so nothing here stores it), and the
 * user id that server assigned to the account.
 */
public class AccountStore {

    private static final String PREF_NAME = "EnterpriseMessenger_Account";
    private static final String KEY_USERNAME = "username";
    private static final String KEY_PASSWORD = "password";
    private static final String KEY_JWT_TOKEN = "jwt_token";
    private static final String KEY_LOGGED_IN = "is_logged_in";

    /**
     * HTTP base URL of the server that issued the stored JWT. A token is only accepted again for
     * that address, otherwise the app offers it to a different server, gets a 401 back and the
     * session is cleared (a log-out the user did not ask for).
     */
    private static final String KEY_TOKEN_ISSUER = "jwt_token_issuer";

    /**
     * Numeric id the physical server assigned to this account ({@code GET /api/users/me}). It is
     * cached so the app can recognise itself in a virtual server's member list and tell whether the
     * signed-in user is its ADMINISTRATOR, which is what gates the create/rename/delete controls.
     */
    private static final String KEY_USER_ID = "user_id";

    private final SharedPreferences prefs;

    public AccountStore(Context context) {
        this.prefs = context.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public synchronized boolean isLoggedIn() {
        return prefs.getBoolean(KEY_LOGGED_IN, false);
    }

    public synchronized void saveSession(String username, String password, String token) {
        saveSession(username, password, token, "");
    }

    /**
     * Stores the session together with the {@code tokenIssuer} that signed {@code token}, so a
     * later launch can tell whether the persisted token still applies to the server being asked.
     */
    public synchronized void saveSession(String username, String password, String token, String tokenIssuer) {
        prefs.edit()
                .putString(KEY_USERNAME, username)
                .putString(KEY_PASSWORD, password)
                .putString(KEY_JWT_TOKEN, token)
                .putString(KEY_TOKEN_ISSUER, tokenIssuer == null ? "" : tokenIssuer)
                .putBoolean(KEY_LOGGED_IN, true)
                .apply();
    }

    public synchronized void saveJwtToken(String token, String tokenIssuer) {
        prefs.edit()
                .putString(KEY_JWT_TOKEN, token)
                .putString(KEY_TOKEN_ISSUER, tokenIssuer == null ? "" : tokenIssuer)
                .apply();
    }

    public synchronized void clearSession() {
        prefs.edit()
                .remove(KEY_USERNAME)
                .remove(KEY_PASSWORD)
                .remove(KEY_JWT_TOKEN)
                .remove(KEY_TOKEN_ISSUER)
                .remove(KEY_USER_ID)
                .putBoolean(KEY_LOGGED_IN, false)
                .apply();
    }

    /**
     * Caches the numeric id the physical server handed to {@code GET /api/users/me}, so member
     * lists can be scanned for it without asking for it again on every screen.
     */
    public synchronized void saveUserId(long userId) {
        prefs.edit().putLong(KEY_USER_ID, userId).apply();
    }

    /** Cached user id, or {@code -1L} when this installation has never fetched it. */
    public synchronized long getUserId() {
        return prefs.getLong(KEY_USER_ID, -1L);
    }

    public synchronized String getJwtToken() {
        return prefs.getString(KEY_JWT_TOKEN, "");
    }

    /** Address of the server that issued the stored token, or "" when it was never recorded. */
    public synchronized String getTokenIssuer() {
        return prefs.getString(KEY_TOKEN_ISSUER, "");
    }

    public synchronized String getUsername() {
        return prefs.getString(KEY_USERNAME, "");
    }

    public synchronized String getPassword() {
        return prefs.getString(KEY_PASSWORD, "");
    }

    private static String randomHex(int bytes) {
        byte[] data = new byte[bytes];
        new SecureRandom().nextBytes(data);
        StringBuilder sb = new StringBuilder(bytes * 2);
        for (byte b : data) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
