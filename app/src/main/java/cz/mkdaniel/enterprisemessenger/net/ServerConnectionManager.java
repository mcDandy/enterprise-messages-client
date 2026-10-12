package cz.mkdaniel.enterprisemessenger.net;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.android.gms.tasks.Tasks;
import com.google.firebase.messaging.FirebaseMessaging;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import cz.mkdaniel.enterprisemessenger.crypto.CryptoManager;
import cz.mkdaniel.enterprisemessenger.ui.message.Message;
import cz.mkdaniel.enterprisemessenger.ui.transform.Room;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Singleton manager handling WebSocket protocol exchanges and HTTP Auth with Enterprise Messenger servers.
 * <p>
 * Flow: REST login/register -> FCM token upload -> WebSocket {@code /ws?token=JWT} -> CHANNEL_JOIN ->
 * end-to-end encrypted CHAT_MESSAGE_ENC packets.
 */
public class ServerConnectionManager implements WebSocketClient.WebSocketClientListener {

    private static final String TAG = "ServerConnectionManager";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static volatile ServerConnectionManager instance;

    private final CryptoManager cryptoManager;
    private final AccountStore accountStore;
    private final WebSocketClient webSocketClient;
    private final OkHttpClient httpClient;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Set<ConnectionListener> listeners = new CopyOnWriteArraySet<>();

    private volatile String currentServerIp = "";
    private volatile long currentChannelId = -1L;
    private volatile byte[] currentChannelKey;
    private volatile String jwtToken = "";
    /** Channel whose chat screen is currently in the foreground, -1 if none. */
    private volatile long visibleChannelId = -1L;

    public interface ConnectionListener {
        void onConnectionStateChanged(boolean connected, String serverIp);
        void onMessageReceived(Message message);
        void onError(String errorMessage);
    }

    public interface Callback<T> {
        void onResult(T result);
        void onFailure(String error);
    }

    private ServerConnectionManager(Context context) {
        this.cryptoManager = CryptoManager.getInstance(context);
        this.accountStore = new AccountStore(context);
        this.webSocketClient = new WebSocketClient();
        this.webSocketClient.setListener(this);
        this.webSocketClient.setTokenProvider(() -> {
            jwtToken = authenticate(currentServerIp);
            return jwtToken;
        });
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build();
    }

    public static ServerConnectionManager getInstance(Context context) {
        if (instance == null) {
            synchronized (ServerConnectionManager.class) {
                if (instance == null) {
                    instance = new ServerConnectionManager(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    public void addListener(ConnectionListener listener) {
        listeners.add(listener);
    }

    public void removeListener(ConnectionListener listener) {
        listeners.remove(listener);
    }

    public boolean isConnected() {
        return webSocketClient.isConnected();
    }

    public String getCurrentServerIp() {
        return currentServerIp;
    }

    public String getUsername() {
        return accountStore.getUsername();
    }

    public void setVisibleChannel(long channelId) {
        visibleChannelId = channelId;
    }

    public boolean isChannelVisible(long channelId) {
        return channelId >= 0 && channelId == visibleChannelId;
    }

    /**
     * Authenticates via REST API and connects to the remote WebSocket server.
     */
    public void connectToServer(String serverIp) {
        if (webSocketClient.isConnected() && serverIp.equalsIgnoreCase(currentServerIp)) {
            Log.d(TAG, "Already connected to " + serverIp);
            notifyStateChanged(true, serverIp);
            return;
        }

        this.currentServerIp = serverIp;
        Log.d(TAG, "Initiating authentication & connection to: " + serverIp);

        executor.execute(() -> {
            jwtToken = authenticate(serverIp);
            if (jwtToken.isEmpty()) {
                Log.w(TAG, "No JWT token obtained from " + serverIp + ", WebSocket handshake will be rejected");
                notifyError("Authentication failed on " + serverIp);
            } else {
                registerPushToken(serverIp, jwtToken);
            }
            // Connect anyway: on failure the client keeps retrying and re-authenticates each attempt.
            webSocketClient.connectAsync(serverIp, jwtToken);
        });
    }

    /**
     * Logs in (registering the account first if it does not exist yet) and returns the JWT,
     * or "" on failure. Blocking - call from a background thread.
     */
    public String authenticate(String serverIp) {
        String httpUrl = formatHttpUrl(serverIp);
        String username = accountStore.getUsername();
        String password = accountStore.getPassword();

        String token = login(httpUrl, username, password);
        // Unknown user / failed login -> register and take the token from the response.
        if (token.isEmpty()) {
            String pubKey = cryptoManager.getPublicKeyBase64();
            if (pubKey == null) pubKey = "dummyPublicKeyBase64StringForE2EE";
            token = register(httpUrl, username, password, pubKey);
        }
        if (!token.isEmpty()) {
            accountStore.addAuthenticatedServer(serverIp);
        }
        return token;
    }

    /**
     * Uploads this device's FCM registration token to {@code /api/users/fcm-token} so the
     * server can push new-message notifications even when the app is not running.
     * Blocking - call from a background thread.
     */
    public void registerPushToken(String serverIp, String jwt) {
        try {
            String fcmToken = Tasks.await(FirebaseMessaging.getInstance().getToken(), 20, TimeUnit.SECONDS);
            uploadPushToken(serverIp, jwt, fcmToken);
        } catch (Exception e) {
            // e.g. missing google-services.json, no Google Play services
            Log.w(TAG, "Could not obtain FCM token: " + e.getMessage());
        }
    }

    /**
     * Called when Firebase issues a new registration token: re-uploads it to every known server.
     */
    public void onNewPushToken(String fcmToken) {
        executor.execute(() -> {
            for (String serverIp : accountStore.getAuthenticatedServers()) {
                String jwt = authenticate(serverIp);
                if (!jwt.isEmpty()) {
                    uploadPushToken(serverIp, jwt, fcmToken);
                }
            }
        });
    }

    private void uploadPushToken(String serverIp, String jwt, String fcmToken) {
        try {
            JSONObject json = new JSONObject();
            json.put("fcmToken", fcmToken);

            Request request = new Request.Builder()
                    .url(formatHttpUrl(serverIp) + "/api/users/fcm-token")
                    .header("Authorization", "Bearer " + jwt)
                    .post(RequestBody.create(json.toString(), JSON))
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful()) {
                    Log.d(TAG, "FCM token registered on " + serverIp);
                } else {
                    Log.w(TAG, "FCM token upload to " + serverIp + " failed with HTTP " + response.code());
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "FCM token upload to " + serverIp + " failed: " + e.getMessage());
        }
    }

    /**
     * Posts credentials to {@code /api/auth/login} and returns the JWT token, or "" on any failure.
     */
    private String login(String httpUrl, String username, String password) {
        try {
            JSONObject loginJson = new JSONObject();
            loginJson.put("username", username);
            loginJson.put("password", password);

            Request loginRequest = new Request.Builder()
                    .url(httpUrl + "/api/auth/login")
                    .post(RequestBody.create(loginJson.toString(), JSON))
                    .build();

            try (Response response = httpClient.newCall(loginRequest).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    String token = new JSONObject(response.body().string()).optString("token", "");
                    Log.d(TAG, "Authentication successful as " + username);
                    return token;
                }
                Log.w(TAG, "Login failed with HTTP " + response.code());
            }
        } catch (Exception e) {
            Log.w(TAG, "Login request failed: " + e.getMessage());
        }
        return "";
    }

    /**
     * Registers a new user on {@code /api/auth/register} and returns the JWT token, or "" on any failure.
     */
    private String register(String httpUrl, String username, String password, String publicKey) {
        try {
            JSONObject regJson = new JSONObject();
            regJson.put("username", username);
            regJson.put("password", password);
            regJson.put("publicKey", publicKey);

            Request regRequest = new Request.Builder()
                    .url(httpUrl + "/api/auth/register")
                    .post(RequestBody.create(regJson.toString(), JSON))
                    .build();

            try (Response regResponse = httpClient.newCall(regRequest).execute()) {
                if (regResponse.isSuccessful() && regResponse.body() != null) {
                    String token = new JSONObject(regResponse.body().string()).optString("token", "");
                    Log.d(TAG, "Registered new user " + username + ", obtained token: " + !token.isEmpty());
                    return token;
                }
                Log.w(TAG, "Registration failed with HTTP " + regResponse.code());
            }
        } catch (Exception e) {
            Log.w(TAG, "Registration request failed: " + e.getMessage());
        }
        return "";
    }

    /**
     * Loads the channels this user can access on the given backend
     * ({@code /api/servers/my} + {@code /api/servers/{id}/channels/my}). Result is delivered on the main thread.
     */
    public void fetchChannels(String serverIp, Callback<List<Room>> callback) {
        executor.execute(() -> {
            try {
                String jwt = serverIp.equalsIgnoreCase(currentServerIp) && !jwtToken.isEmpty()
                        ? jwtToken
                        : authenticate(serverIp);
                if (jwt.isEmpty()) {
                    throw new IllegalStateException("Authentication failed");
                }

                String httpUrl = formatHttpUrl(serverIp);
                List<Room> rooms = new ArrayList<>();
                JSONArray servers = new JSONArray(getJson(httpUrl + "/api/servers/my", jwt));
                for (int i = 0; i < servers.length(); i++) {
                    JSONObject server = servers.getJSONObject(i);
                    long serverId = server.getLong("id");
                    String serverName = server.optString("name", "Server " + serverId);

                    JSONArray channels = new JSONArray(
                            getJson(httpUrl + "/api/servers/" + serverId + "/channels/my", jwt));
                    for (int j = 0; j < channels.length(); j++) {
                        JSONObject channel = channels.getJSONObject(j);
                        long channelId = channel.getLong("id");
                        String name = servers.length() > 1
                                ? serverName + " / " + channel.optString("name")
                                : channel.optString("name");
                        rooms.add(new Room(String.valueOf(channelId), name, defaultChannelSecret(channelId)));
                    }
                }
                mainHandler.post(() -> callback.onResult(rooms));
            } catch (Exception e) {
                Log.w(TAG, "Failed to fetch channels from " + serverIp + ": " + e.getMessage());
                mainHandler.post(() -> callback.onFailure(e.getMessage()));
            }
        });
    }

    private String getJson(String url, String jwt) throws Exception {
        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + jwt)
                .get()
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IllegalStateException("GET " + url + " failed with HTTP " + response.code());
            }
            return response.body().string();
        }
    }

    /**
     * Shared secret a channel's AES key is derived from.
     * <p>
     * TODO: the server only stores channel key hashes and has no key distribution yet, so every
     * member derives the same key from the channel id. Replace once channel keys are exchanged
     * via the members' public keys.
     */
    public static String defaultChannelSecret(long channelId) {
        return "ems-channel-" + channelId;
    }

    /**
     * Joins a channel: sends CHANNEL_JOIN and remembers the channel key used for encrypting / decrypting
     * its messages. The server falls back to the channel's active key id, so only the channel id is sent.
     */
    public void joinChannel(String roomId, String encryptionKey) {
        long channelId = parseChannelId(roomId);
        this.currentChannelId = channelId;
        this.currentChannelKey = deriveChannelKey(encryptionKey);

        if (!webSocketClient.isConnected()) {
            Log.d(TAG, "Not connected yet, channel " + channelId + " will be joined after connecting");
            return;
        }
        sendChannelJoin();
    }

    public long getCurrentChannelId() {
        return currentChannelId;
    }

    private void sendChannelJoin() {
        long channelId = currentChannelId;
        if (channelId < 0) return;

        ByteBuffer buffer = ByteBuffer.allocate(8);
        buffer.putLong(channelId);
        webSocketClient.sendPacketAsync(new Packet(Packet.TYPE_CHANNEL_JOIN, buffer.array()));
        Log.d(TAG, "Sent CHANNEL_JOIN for channel " + channelId);
    }

    /**
     * Encrypts and transmits a chat message to the current channel.
     *
     * @param clientMessageId id of the locally displayed message, used to drop the server's echo of it
     */
    public void sendChatMessage(String clientMessageId, String text) {
        if (!webSocketClient.isConnected()) {
            Log.w(TAG, "Cannot send message: Not connected to WebSocket server");
            notifyError("Not connected to server");
            return;
        }
        byte[] key = currentChannelKey;
        if (currentChannelId < 0 || key == null) {
            notifyError("No channel joined");
            return;
        }

        try {
            JSONObject envelope = new JSONObject();
            envelope.put("id", clientMessageId);
            envelope.put("sender", accountStore.getUsername());
            envelope.put("text", text);
            envelope.put("ts", System.currentTimeMillis());

            byte[] ciphertext = cryptoManager.encryptPayload(
                    envelope.toString().getBytes(StandardCharsets.UTF_8), key);

            webSocketClient.sendPacketAsync(new Packet(Packet.TYPE_CHAT_MESSAGE_ENC, ciphertext));
            Log.d(TAG, "Sent CHAT_MESSAGE_ENC (" + ciphertext.length + " bytes) to channel " + currentChannelId);
        } catch (Exception e) {
            Log.e(TAG, "Failed to send chat message packet", e);
            notifyError("Failed to send message: " + e.getMessage());
        }
    }

    /**
     * Sends a MESSAGE_RECEIPT for a server-side message id.
     */
    public void sendReceipt(long messageId, byte action) {
        ByteBuffer buffer = ByteBuffer.allocate(9);
        buffer.putLong(messageId);
        buffer.put(action);
        webSocketClient.sendPacketAsync(new Packet(Packet.TYPE_MESSAGE_RECEIPT, buffer.array()));
    }

    public void disconnect() {
        webSocketClient.disconnect();
    }

    // --- WebSocketClientListener callbacks ---

    @Override
    public void onConnected(String url) {
        Log.d(TAG, "WebSocket connected to " + currentServerIp);
        notifyStateChanged(true, currentServerIp);
        // Channel subscriptions live in the server session, so re-join after every (re)connect.
        sendChannelJoin();
    }

    @Override
    public void onPacketReceived(Packet packet) {
        byte[] payload = packet.getPayload();

        switch (packet.getType()) {
            case Packet.TYPE_CHAT_MESSAGE_ENC:
                handleChatMessage(payload);
                break;

            case Packet.TYPE_MESSAGE_RECEIPT:
                if (payload.length >= 9) {
                    ByteBuffer buffer = ByteBuffer.wrap(payload);
                    long messageId = buffer.getLong();
                    byte action = buffer.get();
                    Log.d(TAG, "Receipt for message " + messageId + ": "
                            + (action == Packet.RECEIPT_READ ? "READ" : "DELIVERED"));
                }
                break;

            case Packet.TYPE_ROTATE_KEY:
                // TODO: answer with [requestId][current key hash][new key] once channel key distribution exists.
                Log.w(TAG, "Server requested key rotation ("
                        + new String(payload, StandardCharsets.UTF_8) + "), not supported by this client yet");
                break;

            case Packet.TYPE_MULTICAST_ROTATED_KEY:
                Log.d(TAG, "Channel key was rotated (" + payload.length + " bytes)");
                break;

            case Packet.TYPE_UNKNOWN:
                String errorMsg = new String(payload, StandardCharsets.UTF_8);
                Log.e(TAG, "Server returned error packet: " + errorMsg);
                notifyError(errorMsg);
                break;

            default:
                Log.d(TAG, "Unhandled packet " + Packet.typeName(packet.getType()));
                break;
        }
    }

    private void handleChatMessage(byte[] ciphertext) {
        byte[] key = currentChannelKey;
        try {
            if (key == null) {
                throw new IllegalStateException("no channel key");
            }
            byte[] plain = cryptoManager.decryptPayload(ciphertext, key);
            JSONObject envelope = new JSONObject(new String(plain, StandardCharsets.UTF_8));
            notifyMessageReceived(new Message(
                    envelope.optString("id", String.valueOf(System.nanoTime())),
                    envelope.optString("text", ""),
                    envelope.optString("sender", "Unknown"),
                    envelope.optLong("ts", System.currentTimeMillis())
            ));
        } catch (Exception e) {
            Log.w(TAG, "Could not decrypt chat message: " + e.getMessage());
            notifyMessageReceived(new Message(
                    String.valueOf(System.nanoTime()),
                    "🔒 Encrypted message (cannot decrypt)",
                    "Unknown",
                    System.currentTimeMillis()
            ));
        }
    }

    @Override
    public void onDisconnected() {
        Log.d(TAG, "WebSocket disconnected from " + currentServerIp);
        notifyStateChanged(false, currentServerIp);
    }

    @Override
    public void onError(Throwable t) {
        Log.e(TAG, "WebSocket error", t);
        notifyError(t != null ? t.getMessage() : "Unknown connection error");
    }

    // --- Helper notification methods on Main UI thread ---

    private void notifyStateChanged(boolean connected, String serverIp) {
        mainHandler.post(() -> {
            for (ConnectionListener l : listeners) {
                l.onConnectionStateChanged(connected, serverIp);
            }
        });
    }

    private void notifyMessageReceived(Message message) {
        mainHandler.post(() -> {
            for (ConnectionListener l : listeners) {
                l.onMessageReceived(message);
            }
        });
    }

    private void notifyError(String error) {
        mainHandler.post(() -> {
            for (ConnectionListener l : listeners) {
                l.onError(error);
            }
        });
    }

    // --- Helpers ---

    /**
     * Room ids are server channel ids ("42"); legacy placeholder ids ("r1") map to their digits.
     */
    public static long parseChannelId(String roomId) {
        if (roomId == null) return -1L;
        String numericOnly = roomId.replaceAll("\\D+", "");
        if (numericOnly.isEmpty()) return -1L;
        try {
            return Long.parseLong(numericOnly);
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    /**
     * AES-256 key = SHA-256(channel secret), so any secret string yields a valid key length.
     */
    private static byte[] deriveChannelKey(String secret) {
        if (secret == null || secret.isEmpty()) return null;
        try {
            return MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static String formatHttpUrl(String input) {
        if (input == null || input.isEmpty()) {
            return "http://10.0.2.2:8080";
        }
        String clean = input.trim();
        if (clean.startsWith("ws://")) clean = "http://" + clean.substring(5);
        if (clean.startsWith("wss://")) clean = "https://" + clean.substring(6);
        if (!clean.startsWith("http://") && !clean.startsWith("https://")) clean = "http://" + clean;

        if (clean.contains("/ws")) {
            clean = clean.substring(0, clean.indexOf("/ws"));
        }
        if (clean.endsWith("/")) {
            clean = clean.substring(0, clean.length() - 1);
        }
        return clean;
    }
}
