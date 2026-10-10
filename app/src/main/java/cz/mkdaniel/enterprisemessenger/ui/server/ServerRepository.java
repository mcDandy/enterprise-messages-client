package cz.mkdaniel.enterprisemessenger.ui.server;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import cz.mkdaniel.enterprisemessenger.R;
import cz.mkdaniel.enterprisemessenger.ui.transform.ServerViewItem;

/**
 * Manages storage and retrieval of Server entries in SharedPreferences.
 */
public class ServerRepository {

    private static final String TAG = "ServerRepository";
    private static final String PREF_NAME = "EnterpriseMessenger_Servers";
    private static final String KEY_SERVER_LIST = "server_list_json";

    private static final int[] AVATAR_DRAWABLES = {
            R.drawable.avatar_1,
            R.drawable.avatar_2,
            R.drawable.avatar_3,
            R.drawable.avatar_4,
            R.drawable.avatar_5,
            R.drawable.avatar_6,
            R.drawable.avatar_7,
            R.drawable.avatar_8,
            R.drawable.avatar_9,
            R.drawable.avatar_10,
            R.drawable.avatar_11,
            R.drawable.avatar_12,
            R.drawable.avatar_13,
            R.drawable.avatar_14,
            R.drawable.avatar_15,
            R.drawable.avatar_16
    };

    private static volatile ServerRepository instance;

    private ServerRepository() {}

    public static ServerRepository getInstance() {
        if (instance == null) {
            synchronized (ServerRepository.class) {
                if (instance == null) {
                    instance = new ServerRepository();
                }
            }
        }
        return instance;
    }

    private SharedPreferences getPrefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public synchronized List<ServerViewItem> getServers(Context context) {
        SharedPreferences prefs = getPrefs(context);
        String json = prefs.getString(KEY_SERVER_LIST, null);

        if (json == null || json.isEmpty()) {
            List<ServerViewItem> defaultServers = createDefaultServers();
            saveServers(context, defaultServers);
            return defaultServers;
        }

        List<ServerViewItem> servers = new ArrayList<>();
        try {
            JSONArray jsonArray = new JSONArray(json);
            for (int i = 0; i < jsonArray.length(); i++) {
                JSONObject obj = jsonArray.getJSONObject(i);
                String id = obj.getString("id");
                String name = obj.getString("name");
                String ipAddress = obj.getString("ipAddress");
                int avatarIndex = obj.optInt("avatarIndex", i % AVATAR_DRAWABLES.length);
                int drawableId = AVATAR_DRAWABLES[avatarIndex % AVATAR_DRAWABLES.length];
                int unreadCount = obj.optInt("unreadCount", 0);

                servers.add(new ServerViewItem(id, name, ipAddress, drawableId, unreadCount));
            }
        } catch (JSONException e) {
            Log.e(TAG, "Error parsing stored servers JSON", e);
            List<ServerViewItem> defaultServers = createDefaultServers();
            saveServers(context, defaultServers);
            return defaultServers;
        }

        return servers;
    }

    public synchronized ServerViewItem addServer(Context context, String name, String ipAddress) {
        List<ServerViewItem> servers = getServers(context);

        String trimmedIp = ipAddress.trim();
        String trimmedName = (name != null && !name.trim().isEmpty())
                ? name.trim()
                : "Server " + (servers.size() + 1);

        String id = "srv_" + System.currentTimeMillis();
        int avatarIndex = servers.size() % AVATAR_DRAWABLES.length;
        int drawableId = AVATAR_DRAWABLES[avatarIndex];

        ServerViewItem newServer = new ServerViewItem(id, trimmedName, trimmedIp, drawableId, 0);
        servers.add(newServer);

        saveServers(context, servers);
        return newServer;
    }

    private synchronized void saveServers(Context context, List<ServerViewItem> servers) {
        JSONArray jsonArray = new JSONArray();
        for (int i = 0; i < servers.size(); i++) {
            ServerViewItem item = servers.get(i);
            try {
                JSONObject obj = new JSONObject();
                obj.put("id", item.getId());
                obj.put("name", item.getText());
                obj.put("ipAddress", item.getIpAddress());
                obj.put("avatarIndex", i % AVATAR_DRAWABLES.length);
                obj.put("unreadCount", item.getUnreadCount());
                jsonArray.put(obj);
            } catch (JSONException e) {
                Log.e(TAG, "Error serializing server item: " + item.getId(), e);
            }
        }

        getPrefs(context).edit().putString(KEY_SERVER_LIST, jsonArray.toString()).apply();
    }

    private List<ServerViewItem> createDefaultServers() {
        List<ServerViewItem> items = new ArrayList<>();
        // First server default to local server IP (10.0.2.2:8080 for Android Emulator, 127.0.0.1:8080)
        items.add(new ServerViewItem("srv_local", "Local Development Server", "10.0.2.2:8080", AVATAR_DRAWABLES[0], 0));

        for (int i = 1; i < AVATAR_DRAWABLES.length; i++) {
            String serverId = "srv_" + (i + 1);
            String serverName = "Server #" + (i + 1);
            String ipAddress = "10.0.0." + (i + 1) + ":8080";
            int unreadCount = (i == 1) ? 3 : ((i == 3) ? 1 : 0);
            items.add(new ServerViewItem(serverId, serverName, ipAddress, AVATAR_DRAWABLES[i], unreadCount));
        }
        return items;
    }
}
