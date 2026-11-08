package cz.mkdaniel.enterprisemessenger.net;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;

/**
 * Persists this installation's account credentials and the servers it is logged in to.
 */
public class AccountStore {

    private static final String PREF_NAME = "EnterpriseMessenger_Account";
    private static final String KEY_USERNAME = "username";
    private static final String KEY_PASSWORD = "password";
    private static final String KEY_SERVERS = "authenticated_servers";
    private static final String KEY_JWT_TOKEN = "jwt_token";
    private static final String KEY_LOGGED_IN = "is_logged_in";

    /**
     * HTTP base URL of the server that issued the stored JWT. A token is only accepted again for
     * that address, otherwise the app offers it to a different server, gets a 401 back and the
     * session is cleared (a log-out the user did not ask for).
     */
    private static final String KEY_TOKEN_ISSUER = "jwt_token_issuer";

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
                .putBoolean(KEY_LOGGED_IN, false)
                .apply();
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

    /**
     * Servers this installation has successfully authenticated against; used to re-upload
     * the FCM token when Firebase rotates it.
     */
    public synchronized Set<String> getAuthenticatedServers() {
        return new HashSet<>(prefs.getStringSet(KEY_SERVERS, new HashSet<>()));
    }

    public synchronized void addAuthenticatedServer(String serverIp) {
        Set<String> servers = getAuthenticatedServers();
        if (servers.add(serverIp)) {
            prefs.edit().putStringSet(KEY_SERVERS, servers).apply();
        }
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
