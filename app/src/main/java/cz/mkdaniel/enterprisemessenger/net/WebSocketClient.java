package cz.mkdaniel.enterprisemessenger.net;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
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
 * <p>
 * Includes keep-alive ping interval (to prevent socket read timeouts / connection aborts)
 * and automatic reconnection logic.
 */
public class WebSocketClient {

    private static final String TAG = "WebSocketClient";
    private static final int CONNECT_TIMEOUT_SECONDS = 10;
    private static final int PING_INTERVAL_SECONDS = 15;

    private static final long INITIAL_RECONNECT_DELAY_MS = 2_000L;
    private static final long MAX_RECONNECT_DELAY_MS = 30_000L;

    /**
     * Path of the binary chat WebSocket endpoint on the server (see server's {@code WebSocketConfig}).
     */
    public static final String WS_ENDPOINT_PATH = "/ws/chat";

    private final CryptoManager cryptoManager;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    private final AtomicBoolean isConnected = new AtomicBoolean(false);
    private final AtomicBoolean shouldConnect = new AtomicBoolean(false);

    private final OkHttpClient okHttpClient;
    private WebSocket webSocket;
    private WebSocketClientListener listener;
    private byte[] channelEncryptionKey;
    private String currentUrl;

    private long reconnectDelayMs = INITIAL_RECONNECT_DELAY_MS;
    private ScheduledFuture<?> reconnectTask;

    public interface WebSocketClientListener {
        void onConnected(String url);
        void onPacketReceived(Packet packet);
        default void onTextMessageReceived(String text) {}
        void onDisconnected();
        void onError(Throwable t);
    }

    public WebSocketClient(CryptoManager cryptoManager) {
        this.cryptoManager = cryptoManager;
        this.okHttpClient = new OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                // Long-lived WebSocket connection: readTimeout MUST be 0 (no timeout)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                // Send PING frames every 15s to keep the TCP socket alive against NAT/router timeouts
                .pingInterval(PING_INTERVAL_SECONDS, TimeUnit.SECONDS)
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
     * Asynchronously connects to the remote WebSocket server using host and port.
     * E.g. host="127.0.0.1", port=8080 -> ws://127.0.0.1:8080/ws/chat
     */
    public void connectAsync(String host, int port) {
        if (host == null || host.isEmpty()) {
            Log.w(TAG, "Cannot connect: host is empty");
            return;
        }
        boolean alreadyAbsolute = host.startsWith("ws://") || host.startsWith("wss://");
        connectAsync(alreadyAbsolute ? host : host + ":" + port);
    }

    /**
     * Asynchronously connects to the remote WebSocket URL (e.g., ws://10.0.2.2:8080/ws/chat or wss://...).
     */
    public void connectAsync(String url) {
        shouldConnect.set(true);
        cancelScheduledReconnect();

        executor.execute(() -> {
            try {
                disconnectInternal(false); // Close existing connection if any

                this.currentUrl = formatWebSocketUrl(url);
                Log.d(TAG, "Connecting to WebSocket URL: " + currentUrl);

                Request request = new Request.Builder()
                        .url(currentUrl)
                        .build();

                webSocket = okHttpClient.newWebSocket(request, createWebSocketListener());

            } catch (Exception e) {
                Log.e(TAG, "Failed to initiate WebSocket connection to " + url, e);
                isConnected.set(false);
                if (listener != null) {
                    listener.onError(e);
                }
                scheduleReconnect();
            }
        });
    }

    private WebSocketListener createWebSocketListener() {
        return new WebSocketListener() {
            @Override
            public void onOpen(@NonNull WebSocket webSocket, @NonNull Response response) {
                Log.d(TAG, "WebSocket connected: " + currentUrl);
                isConnected.set(true);
                reconnectDelayMs = INITIAL_RECONNECT_DELAY_MS; // Reset reconnect delay on success
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
                if (shouldConnect.get()) {
                    scheduleReconnect();
                }
            }

            @Override
            public void onFailure(@NonNull WebSocket webSocket, @NonNull Throwable t, @Nullable Response response) {
                Log.w(TAG, "WebSocket failure: " + t.getMessage());
                boolean wasConnected = isConnected.getAndSet(false);
                if (listener != null) {
                    listener.onError(t);
                    if (wasConnected) {
                        listener.onDisconnected();
                    }
                }
                if (shouldConnect.get()) {
                    scheduleReconnect();
                }
            }
        };
    }

    private synchronized void scheduleReconnect() {
        if (!shouldConnect.get()) {
            return;
        }
        cancelScheduledReconnect();

        long delay = reconnectDelayMs;
        Log.d(TAG, "Scheduling WebSocket reconnect in " + delay + " ms");
        reconnectDelayMs = Math.min(reconnectDelayMs * 2, MAX_RECONNECT_DELAY_MS);

        reconnectTask = executor.schedule(() -> {
            if (shouldConnect.get() && currentUrl != null) {
                Log.d(TAG, "Attempting automatic WebSocket reconnection...");
                try {
                    disconnectInternal(false);
                    Request request = new Request.Builder()
                            .url(currentUrl)
                            .build();
                    webSocket = okHttpClient.newWebSocket(request, createWebSocketListener());
                } catch (Exception e) {
                    Log.e(TAG, "Error during WebSocket reconnect attempt", e);
                    scheduleReconnect();
                }
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    private synchronized void cancelScheduledReconnect() {
        if (reconnectTask != null && !reconnectTask.isDone()) {
            reconnectTask.cancel(true);
            reconnectTask = null;
        }
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
     * Sends a text frame asynchronously over the WebSocket connection.
     */
    public void sendTextAsync(String text) {
        if (!isConnected()) {
            Log.w(TAG, "Cannot send text: WebSocket client is not connected");
            return;
        }

        executor.execute(() -> {
            try {
                if (webSocket != null) {
                    webSocket.send(text);
                    Log.d(TAG, "Sent text frame: " + text);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error sending text frame", e);
                if (listener != null) {
                    listener.onError(e);
                }
            }
        });
    }

    /**
     * Closes the WebSocket connection and disables automatic reconnection.
     */
    public synchronized void disconnect() {
        shouldConnect.set(false);
        cancelScheduledReconnect();
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

    /**
     * Normalizes whatever is stored for a server (bare {@code ip:port}, {@code http(s)://…} or
     * {@code ws(s)://…}) into an absolute WebSocket URL pointing at the server's chat endpoint.
     */
    private static String formatWebSocketUrl(String input) {
        if (input == null || input.isEmpty()) {
            return "ws://127.0.0.1:8080" + WS_ENDPOINT_PATH;
        }

        String url = input.trim();
        if (url.startsWith("http://")) {
            url = "ws://" + url.substring(7);
        } else if (url.startsWith("https://")) {
            url = "wss://" + url.substring(8);
        } else if (!url.startsWith("ws://") && !url.startsWith("wss://")) {
            url = "ws://" + url;
        }

        int schemeEnd = url.indexOf("//") + 2;
        int pathStart = url.indexOf('/', schemeEnd);
        String authority = pathStart < 0 ? url : url.substring(0, pathStart);
        String path = pathStart < 0 ? "" : url.substring(pathStart);

        // Empty path or the old "/ws" placeholder -> point at the real endpoint.
        if (path.isEmpty() || path.equals("/") || path.equals("/ws") || path.equals("/ws/")) {
            return authority + WS_ENDPOINT_PATH;
        }
        return url;
    }
}
