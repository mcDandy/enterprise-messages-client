package cz.mkdaniel.enterprisemessenger.net;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import cz.mkdaniel.enterprisemessenger.crypto.CryptoManager;
import cz.mkdaniel.enterprisemessenger.ui.message.Message;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Singleton manager handling WebSocket protocol exchanges and HTTP Auth with Enterprise Messenger servers.
 * Implements Auth login/register, Handshake, Key Exchange, Channel Join, and Encrypted Chat Message transmission.
 */
public class ServerConnectionManager implements WebSocketClient.WebSocketClientListener {

    private static final String TAG = "ServerConnectionManager";
    private static volatile ServerConnectionManager instance;

    private final CryptoManager cryptoManager;
    private final WebSocketClient webSocketClient;
    private final OkHttpClient httpClient;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Set<ConnectionListener> listeners = new CopyOnWriteArraySet<>();

    private String currentServerIp = "";
    private String currentRoomId = "";
    private String jwtToken = "";

    public interface ConnectionListener {
        void onConnectionStateChanged(boolean connected, String serverIp);
        void onMessageReceived(Message message);
        void onError(String errorMessage);
    }

    private ServerConnectionManager(Context context) {
        this.cryptoManager = CryptoManager.getInstance(context);
        this.webSocketClient = new WebSocketClient(cryptoManager);
        this.webSocketClient.setListener(this);
        this.httpClient = new OkHttpClient();
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

    /**
     * Authenticates via REST API (if needed) and connects to remote WebSocket server.
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
            // Attempt to fetch a valid JWT token via REST API /api/auth/login or /api/auth/register
            fetchJwtTokenAndConnect(serverIp);
        });
    }

    private void fetchJwtTokenAndConnect(String serverIp) {
        String httpUrl = formatHttpUrl(serverIp);
        String username = "android_user";
        String password = "GuestPassword123";
        String pubKey = cryptoManager.getPublicKeyBase64();
        if (pubKey == null) pubKey = "dummyPublicKeyBase64StringForE2EE";

        try {
            // 1. Try login
            JSONObject loginJson = new JSONObject();
            loginJson.put("username", username);
            loginJson.put("password", password);

            RequestBody body = RequestBody.create(
                    loginJson.toString(),
                    MediaType.parse("application/json; charset=utf-8")
            );

            Request loginRequest = new Request.Builder()
                    .url(httpUrl + "/api/auth/login")
                    .post(body)
                    .build();

            try (Response response = httpClient.newCall(loginRequest).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    String resStr = response.body().string();
                    JSONObject resObj = new JSONObject(resStr);
                    jwtToken = resObj.optString("token", "");
                    Log.d(TAG, "Authentication successful, obtained JWT token");
                }
            }

            // 2. If login failed/unauthorized, attempt registration
            if (jwtToken.isEmpty()) {
                JSONObject regJson = new JSONObject();
                regJson.put("username", username);
                regJson.put("password", password);
                regJson.put("publicKey", pubKey);

                RequestBody regBody = RequestBody.create(
                        regJson.toString(),
                        MediaType.parse("application/json; charset=utf-8")
                );

                Request regRequest = new Request.Builder()
                        .url(httpUrl + "/api/auth/register")
                        .post(regBody)
                        .build();

                try (Response regResponse = httpClient.newCall(regRequest).execute()) {
                    if (regResponse.body() != null) {
                        String resStr = regResponse.body().string();
                        JSONObject resObj = new JSONObject(resStr);
                        jwtToken = resObj.optString("token", "");
                        Log.d(TAG, "Registration response, obtained token: " + !jwtToken.isEmpty());
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "HTTP Auth request failed or skipped: " + e.getMessage());
        }

        // Connect WebSocket regardless (will send token in Handshake if present)
        webSocketClient.connectAsync(serverIp);
    }

    /**
     * Sends ROOM_JOIN packet for a specific channel ID/room.
     */
    public void joinRoom(String roomId) {
        this.currentRoomId = roomId;
        if (!webSocketClient.isConnected()) {
            Log.w(TAG, "Cannot join room " + roomId + ": Not connected to server");
            return;
        }

        try {
            long channelLongId = 1L;
            if (roomId != null) {
                String numericOnly = roomId.replaceAll("\\D+", "");
                if (!numericOnly.isEmpty()) {
                    channelLongId = Long.parseLong(numericOnly);
                } else {
                    channelLongId = Math.abs((long) roomId.hashCode());
                }
            }

            ByteBuffer buffer = ByteBuffer.allocate(8);
            buffer.putLong(channelLongId);

            Packet joinPacket = new Packet(Packet.TYPE_ROOM_JOIN, buffer.array());
            webSocketClient.sendPacketAsync(joinPacket);
            Log.d(TAG, "Sent ROOM_JOIN for channel: " + channelLongId + " (" + roomId + ")");
        } catch (Exception e) {
            Log.e(TAG, "Error sending ROOM_JOIN packet", e);
        }
    }

    /**
     * Encrypts and transmits a chat message packet to the server.
     */
    public void sendChatMessage(String text, String senderUsername, byte[] encryptionKey) {
        if (!webSocketClient.isConnected()) {
            Log.w(TAG, "Cannot send message: Not connected to WebSocket server");
            notifyError("Not connected to server");
            return;
        }

        try {
            webSocketClient.setChannelEncryptionKey(encryptionKey);
            byte[] textBytes = text.getBytes(StandardCharsets.UTF_8);

            // Create Packet of type TYPE_CHAT_MESSAGE (0x0002)
            Packet messagePacket = new Packet(Packet.TYPE_CHAT_MESSAGE, textBytes);
            webSocketClient.sendPacketAsync(messagePacket);

            Log.d(TAG, "Sent CHAT_MESSAGE (" + textBytes.length + " bytes)");
        } catch (Exception e) {
            Log.e(TAG, "Failed to send chat message packet", e);
            notifyError("Failed to send message: " + e.getMessage());
        }
    }

    public void disconnect() {
        webSocketClient.disconnect();
    }

    // --- WebSocketClientListener callbacks ---

    @Override
    public void onConnected(String url) {
        Log.d(TAG, "WebSocket connected successfully: " + url);
        notifyStateChanged(true, currentServerIp);

        // 1. Perform Handshake with server using the JWT token obtained from HTTP Auth
        if (!jwtToken.isEmpty()) {
            try {
                Packet handshakePacket = new Packet(Packet.TYPE_HANDSHAKE, jwtToken.getBytes(StandardCharsets.UTF_8));
                webSocketClient.sendPacketAsync(handshakePacket);
                Log.d(TAG, "Sent HANDSHAKE packet with JWT token");
            } catch (Exception e) {
                Log.e(TAG, "Error sending HANDSHAKE", e);
            }
        }

        // 2. Perform Key Exchange REQ with client's Public Key
        try {
            if (cryptoManager.getPublicKey() != null) {
                byte[] pubKeyBytes = cryptoManager.getPublicKey().getEncoded();
                Packet keyExchangePacket = new Packet(Packet.TYPE_KEY_EXCHANGE_REQ, pubKeyBytes);
                webSocketClient.sendPacketAsync(keyExchangePacket);
                Log.d(TAG, "Sent KEY_EXCHANGE_REQ packet");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error sending KEY_EXCHANGE_REQ", e);
        }

        // 3. Join current room if set
        if (!currentRoomId.isEmpty()) {
            joinRoom(currentRoomId);
        }
    }

    @Override
    public void onPacketReceived(Packet packet) {
        short type = packet.getType();
        Log.d(TAG, "Incoming packet type: 0x" + Integer.toHexString(type) + ", payload size: " + packet.getPayload().length);

        switch (type) {
            case Packet.TYPE_HANDSHAKE:
                String responseText = new String(packet.getPayload(), StandardCharsets.UTF_8);
                Log.d(TAG, "Handshake response from server: " + responseText);
                break;

            case Packet.TYPE_CHAT_MESSAGE:
                String messageText = new String(packet.getPayload(), StandardCharsets.UTF_8);
                Message msg = new Message(
                        String.valueOf(System.currentTimeMillis()),
                        messageText,
                        "Server User",
                        System.currentTimeMillis()
                );
                notifyMessageReceived(msg);
                break;

            case Packet.TYPE_PONG:
                Log.d(TAG, "Received PONG from server");
                break;

            case Packet.TYPE_UNKNOWN:
                String errorMsg = new String(packet.getPayload(), StandardCharsets.UTF_8);
                Log.e(TAG, "Server returned error packet: " + errorMsg);
                notifyError(errorMsg);
                break;

            default:
                Log.d(TAG, "Unhandled packet type: 0x" + Integer.toHexString(type));
                break;
        }
    }

    @Override
    public void onTextMessageReceived(String text) {
        Log.d(TAG, "Received text frame: " + text);
        Message msg = new Message(
                String.valueOf(System.currentTimeMillis()),
                text,
                "Server",
                System.currentTimeMillis()
        );
        notifyMessageReceived(msg);
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

    private static String formatHttpUrl(String input) {
        if (input == null || input.isEmpty()) {
            return "http://10.0.2.2:8080";
        }
        String clean = input;
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
