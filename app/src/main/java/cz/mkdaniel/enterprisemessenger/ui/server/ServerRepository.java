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

    /** Id of the entry that mirrors the {@code @string/server_ip} deployment address. */
    private static final String DEFAULT_SERVER_ID = "srv_default";

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
            List<ServerViewItem> defaultServers = createDefaultServers(context);
            saveServers(context, defaultServers);
            return defaultServers;
        }

        List<ServerViewItem> servers = new ArrayList<>();
        boolean reserialized = false;
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

                // The built-in entry is not user input, so it always follows @string/server_ip.
                // Without this the copy persisted on the first launch would keep pointing at the
                // address of an older build, and every request would go to a server the user never
                // entered - it is rejected with 401, which clears the session ("logs the user out")
                // while the WebSocket keeps retrying that stale address.
                if (DEFAULT_SERVER_ID.equals(id)) {
                    String currentDefaultIp = defaultServerAddress(context);
                    if (!currentDefaultIp.equals(ipAddress)) {
                        Log.i(TAG, "Refreshing built-in server address to " + currentDefaultIp);
                        ipAddress = currentDefaultIp;
                        reserialized = true;
                    }
                }

                servers.add(new ServerViewItem(id, name, ipAddress, drawableId, unreadCount));
            }
        } catch (JSONException e) {
            Log.e(TAG, "Error parsing stored servers JSON", e);
            List<ServerViewItem> defaultServers = createDefaultServers(context);
            saveServers(context, defaultServers);
            return defaultServers;
        }

        if (reserialized) {
            saveServers(context, servers);
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

    public synchronized boolean updateServer(Context context, String serverId, String newName, String newIpAddress) {
        List<ServerViewItem> servers = getServers(context);
        for (int i = 0; i < servers.size(); i++) {
            ServerViewItem item = servers.get(i);
            if (item.getId().equals(serverId)) {
                String trimmedIp = newIpAddress.trim();
                String trimmedName = (newName != null && !newName.trim().isEmpty())
                        ? newName.trim()
                        : item.getText();

                ServerViewItem updatedItem = new ServerViewItem(
                        item.getId(),
                        trimmedName,
                        trimmedIp,
                        item.getDrawableId(),
                        item.getUnreadCount()
                );
                servers.set(i, updatedItem);
                saveServers(context, servers);
                return true;
            }
        }
        return false;
    }

    public synchronized boolean deleteServer(Context context, String serverId) {
        List<ServerViewItem> servers = getServers(context);
        for (int i = 0; i < servers.size(); i++) {
            if (servers.get(i).getId().equals(serverId)) {
                servers.remove(i);
                saveServers(context, servers);
                return true;
            }
        }
        return false;
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

    /**
     * Address of the built-in server: {@code @string/server_ip} plus {@code @integer/server_port},
     * the only configuration of the deployment address.
     */
    private String defaultServerAddress(Context context) {
        String serverIp = context.getString(R.string.server_ip);
        int serverPort = context.getResources().getInteger(R.integer.server_port);
        return serverIp + ":" + serverPort;
    }

    private List<ServerViewItem> createDefaultServers(Context context) {
        List<ServerViewItem> items = new ArrayList<>();
        items.add(new ServerViewItem(
                DEFAULT_SERVER_ID,
                context.getString(R.string.default_server_name),
                defaultServerAddress(context),
                AVATAR_DRAWABLES[0],
                0));
        return items;
    }
}
