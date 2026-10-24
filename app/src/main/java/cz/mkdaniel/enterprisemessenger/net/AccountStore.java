package cz.mkdaniel.enterprisemessenger.net;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;

/**
 * Persists this installation's account credentials and the servers it is logged in to.
 * <p>
 * Each installation gets its own generated username so that two devices are two different
 * users on the server (the server keys WebSocket sessions by username and skips the sender
 * when sending push notifications).
 */
public class AccountStore {

    private static final String PREF_NAME = "EnterpriseMessenger_Account";
    private static final String KEY_USERNAME = "username";
    private static final String KEY_PASSWORD = "password";
    private static final String KEY_SERVERS = "authenticated_servers";

    private final SharedPreferences prefs;

    public AccountStore(Context context) {
        this.prefs = context.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public synchronized String getUsername() {
        String username = prefs.getString(KEY_USERNAME, null);
        if (username == null) {
            username = "android_" + randomHex(4);
            prefs.edit().putString(KEY_USERNAME, username).apply();
        }
        return username;
    }

    public synchronized String getPassword() {
        String password = prefs.getString(KEY_PASSWORD, null);
        if (password == null) {
            password = randomHex(16);
            prefs.edit().putString(KEY_PASSWORD, password).apply();
        }
        return password;
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
