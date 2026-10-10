package cz.mkdaniel.enterprisemessenger.net;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import cz.mkdaniel.enterprisemessenger.crypto.CryptoManager;
import cz.mkdaniel.enterprisemessenger.ui.message.Message;

/**
 * Singleton manager handling WebSocket protocol exchanges with Enterprise Messenger servers.
 * Implements Handshake, Key Exchange, Channel Join, and Encrypted Chat Message transmission.
 */
public class ServerConnectionManager implements WebSocketClient.WebSocketClientListener {

    private static final String TAG = "ServerConnectionManager";
    private static volatile ServerConnectionManager instance;

    private final CryptoManager cryptoManager;
    private final WebSocketClient webSocketClient;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Set<ConnectionListener> listeners = new CopyOnWriteArraySet<>();

    private String currentServerIp = "";
    private String currentRoomId = "";
    private byte[] activeChannelKey = null;

    public interface ConnectionListener {
        void onConnectionStateChanged(boolean connected, String serverIp);
        void onMessageReceived(Message message);
        void onError(String errorMessage);
    }

    private ServerConnectionManager(Context context) {
        this.cryptoManager = CryptoManager.getInstance(context);
        this.webSocketClient = new WebSocketClient(cryptoManager);
        this.webSocketClient.setListener(this);
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
     * Connects to a remote WebSocket server IP address or host.
     */
    public void connectToServer(String serverIp) {
        if (webSocketClient.isConnected() && serverIp.equalsIgnoreCase(currentServerIp)) {
            Log.d(TAG, "Already connected to " + serverIp);
            notifyStateChanged(true, serverIp);
            return;
        }

        this.currentServerIp = serverIp;
        Log.d(TAG, "Connecting to server: " + serverIp);
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
            // Encode numeric channel ID if possible or hash string to Long for 8-byte server payload
            long channelLongId = 1L;
            if (roomId != null) {
                String numericOnly = roomId.replaceAll("\\D+", "");
                if (!numericOnly.isEmpty()) {
                    channelLongId = Long.parseLong(numericOnly);
                } else {
                    channelLongId = Math.abs(roomId.hashCode());
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

        // 1. Perform automatic Handshake with server (JWT or Auth token)
        try {
            String token = "eyJhbGciOiJIUzI1NiJ9.demoToken"; // Default JWT demo token
            Packet handshakePacket = new Packet(Packet.TYPE_HANDSHAKE, token.getBytes(StandardCharsets.UTF_8));
            webSocketClient.sendPacketAsync(handshakePacket);
            Log.d(TAG, "Sent HANDSHAKE packet");
        } catch (Exception e) {
            Log.e(TAG, "Error sending HANDSHAKE", e);
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
}
