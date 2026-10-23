package cz.mkdaniel.enterprisemessenger.net;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import cz.mkdaniel.enterprisemessenger.crypto.CryptoManager;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * Manages WebSocket connections for binary packet transport and messaging.
 * <p>
 * Handles asynchronous connection via OkHttp WebSocket, continuous handling of incoming
 * binary/text frames, and packet transmission over WebSockets.
 */
public class WebSocketClient {
    private static final String TAG = "WebSocketClient";
    private static final int DEFAULT_TIMEOUT_SECONDS = 10;
    /**
     * Path of the binary chat WebSocket endpoint on the server (see server's {@code WebSocketConfig}).
     */
    //public static final String WS_PROTOCOL = "wss"; 
    //public static final String WS_HOST = "172.18.0.1";
    //public static final Short WS_PORT = 32769/*8443*/;
    //public static final Integer WS_PORT = 32778/*8443*/;
    //public static final String WS_ENDPOINT_PATH = "wss";

    private final CryptoManager cryptoManager;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean isConnected = new AtomicBoolean(false);
    private final OkHttpClient okHttpClient;
    //private final String currentUrl = String.format("%s://%s:%d/%s", WS_PROTOCOL, WS_HOST, WS_PORT, WS_ENDPOINT_PATH);
    private final currentUrl;

    private WebSocket webSocket;
    private WebSocketClientListener listener;
    private byte[] channelEncryptionKey;
    private String token = "Invalid";

    public interface WebSocketClientListener {
        void onConnected(String url);
        void onPacketReceived(Packet packet);
        void onDisconnected();
        void onError(Throwable t);
    }

    public WebSocketClient(final CryptoManager cryptoManager, final String currentServerIp) {
	currentUrl = currentServerIp;
        this.cryptoManager = cryptoManager;
        this.okHttpClient = new OkHttpClient.Builder()
                .connectTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build();
    }

    public void setListener(WebSocketClientListener listener) {
        this.listener = listener;
    }

    public void setChannelEncryptionKey(byte[] encryptionKey) {
        this.channelEncryptionKey = encryptionKey;
    }

    public boolean isConnected() {
        return isConnected.get() && webSocket != null;
    }

    public String getCurrentUrl() {
        return currentUrl;
    }

    /**
     * Asynchronously connects to the remote WebSocket URL.
     *
     * Close existing connection, if any.
     */
    public void connectAsync(final String url, final String token) {
        executor.execute(() -> {
            try {
                disconnectInternal(false);
                Log.d(TAG, "Connecting to WebSocket URL: " + currentUrl + " with token: " + token);

                final var request = new Request
		    .Builder()
		    .url(currentUrl)
		    .addHeader("Authorization", String.format("Bearer %s", token))
		    .addHeader("Upgrade", "websocket")
		    .addHeader("Connection", "Upgrade")
		    .build();

                webSocket = okHttpClient.newWebSocket(request, createWebSocketListener());
            } catch (Exception e) {
                Log.e(TAG, "Failed to initiate WebSocket connection to " + url, e);
                isConnected.set(false);

                if (listener != null) {
                    listener.onError(e);
                }
            }
        });
    }

    private WebSocketListener createWebSocketListener() {
        return new WebSocketListener() {
            @Override
            public void onOpen(@NonNull WebSocket webSocket, @NonNull Response response) {
                Log.d(TAG, "WebSocket connected: " + currentUrl);
                isConnected.set(true);
                if (listener != null) {
                    listener.onConnected(currentUrl);
                }
            }

            @Override
            public void onMessage(@NonNull WebSocket webSocket, @NonNull ByteString bytes) {
                try {
                    byte[] rawBytes = bytes.toByteArray();
                    Packet packet = Packet.readFromByteArray(rawBytes, cryptoManager, channelEncryptionKey);
                    Log.d(TAG, "Received binary packet type 0x" + Integer.toHexString(packet.getType())
                            + ", payload length: " + packet.getPayload().length);

                    if (listener != null) {
                        listener.onPacketReceived(packet);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error processing incoming binary WebSocket frame", e);
                    if (listener != null) {
                        listener.onError(e);
                    }
                }
            }

            @Override
            public void onMessage(@NonNull WebSocket webSocket, @NonNull String text) {
                Log.d(TAG, "Received text frame: " + text);
                if (listener != null) {
                    listener.onTextMessageReceived(text);
                }
            }

            @Override
            public void onClosing(@NonNull WebSocket webSocket, int code, @NonNull String reason) {
                Log.d(TAG, "WebSocket closing: " + code + " / " + reason);
                webSocket.close(1000, null);
            }

            @Override
            public void onClosed(@NonNull WebSocket webSocket, int code, @NonNull String reason) {
                Log.d(TAG, "WebSocket closed: " + code + " / " + reason);
                boolean wasConnected = isConnected.getAndSet(false);
                if (wasConnected && listener != null) {
                    listener.onDisconnected();
                }
            }

            @Override
            public void onFailure(@NonNull WebSocket webSocket, @NonNull Throwable t, @Nullable Response response) {
                Log.e(TAG, "WebSocket failure", t);
                boolean wasConnected = isConnected.getAndSet(false);
                if (listener != null) {
                    listener.onError(t);
                    if (wasConnected) {
                        listener.onDisconnected();
                    }
                }
            }
        };
    }

    /**
     * Sends a packet asynchronously over the WebSocket connection as a binary frame.
     */
    public void sendPacketAsync(Packet packet) {
        if (!isConnected()) {
            Log.w(TAG, "Cannot send packet: WebSocket client is not connected");
            return;
        }

        executor.execute(() -> {
            try {
                byte[] rawPacketBytes = packet.serialize(cryptoManager, channelEncryptionKey);
                if (webSocket != null) {
                    boolean success = webSocket.send(ByteString.of(rawPacketBytes));
                    if (success) {
                        Log.d(TAG, "Sent packet type 0x" + Integer.toHexString(packet.getType())
                                + " (" + rawPacketBytes.length + " bytes)");
                    } else {
                        Log.w(TAG, "Failed to enqueue packet type 0x" + Integer.toHexString(packet.getType()));
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error sending packet type 0x" + Integer.toHexString(packet.getType()), e);
                if (listener != null) {
                    listener.onError(e);
                }
            }
        });
    }

    /**
     * Closes the WebSocket connection.
     */
    public synchronized void disconnect() {
        disconnectInternal(true);
    }

    private synchronized void disconnectInternal(boolean notifyListener) {
        boolean wasConnected = isConnected.getAndSet(false);

        if (webSocket != null) {
            try {
                webSocket.close(1000, "Client disconnect");
            } catch (Exception e) {
                Log.e(TAG, "Error closing WebSocket", e);
            }
            webSocket = null;
        }

        if (wasConnected && notifyListener && listener != null) {
            listener.onDisconnected();
        }
    }
}
