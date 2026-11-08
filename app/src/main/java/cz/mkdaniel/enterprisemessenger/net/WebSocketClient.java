package cz.mkdaniel.enterprisemessenger.net;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import java.util.Arrays;

import java.nio.ByteBuffer;

import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

import okio.ByteString;

import cz.mkdaniel.enterprisemessenger.crypto.CryptoManager;

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
    private static final int DEFAULT_TIMEOUT_SECONDS = 10;
    private static final int CONNECT_TIMEOUT_SECONDS = 10;
    private static final int INITIAL_RECONNECT_DELAY_MS = 1000;
    private static final int MAX_RECONNECT_DELAY_MS = 1000;
    private static final int PING_INTERVAL_SECONDS = 15;

    /**
     * Path of the binary WebSocket endpoint on the server (see server's {@code WebSocketConfig}).
     * The JWT is sent as the {@code token} query parameter (server's {@code JwtHandshakeInterceptor}).
     */
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    private final AtomicBoolean isConnected = new AtomicBoolean(false);
    private final AtomicBoolean shouldConnect = new AtomicBoolean(false);

    private final OkHttpClient okHttpClient;
    private String currentUrl;
    private final CryptoManager cryptoManager;

    private WebSocket webSocket;
    private WebSocketClientListener listener;
    private byte[] channelEncryptionKey;
    private String token = "Invalid";
    private TokenProvider tokenProvider;
    private String currentAddress;

    private long reconnectDelayMs = 1000;
    private ScheduledFuture<?> reconnectTask;

    public interface WebSocketClientListener {
        void onConnected(String url);
        void onPacketReceived(Packet packet);
        void onDisconnected();
        void onError(Throwable t);
        default void onAuthError(int statusCode, String message) {}
    }

    /**
     * Supplies a fresh JWT before every automatic reconnect (the old one may have expired).
     * Called on a background thread, may block.
     */
    public interface TokenProvider {
        String fetchToken();
    }

    public WebSocketClient(final CryptoManager cryptoManager, final String currentServerIp) {
	currentUrl = currentServerIp;
        this.cryptoManager = cryptoManager;
        this.okHttpClient = createOkHttpClient();
    }

    private static OkHttpClient createOkHttpClient() {
        try {
            final javax.net.ssl.TrustManager[] trustAllCerts = new javax.net.ssl.TrustManager[]{
                new javax.net.ssl.X509TrustManager() {
                    @Override
                    public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) {}
                    @Override
                    public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) {}
                    @Override
                    public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                        return new java.security.cert.X509Certificate[]{};
                    }
                }
            };

            final javax.net.ssl.SSLContext sslContext = javax.net.ssl.SSLContext.getInstance("SSL");
            sslContext.init(null, trustAllCerts, new java.security.SecureRandom());
            final javax.net.ssl.SSLSocketFactory sslSocketFactory = sslContext.getSocketFactory();

            return new OkHttpClient.Builder()
                    .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .writeTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .readTimeout(0, TimeUnit.MILLISECONDS)
                    .pingInterval(PING_INTERVAL_SECONDS, TimeUnit.SECONDS)
                    .sslSocketFactory(sslSocketFactory, (javax.net.ssl.X509TrustManager) trustAllCerts[0])
                    .hostnameVerifier((hostname, session) -> true)
                    .build();
        } catch (Exception e) {
            return new OkHttpClient.Builder()
                    .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .writeTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .readTimeout(0, TimeUnit.MILLISECONDS)
                    .pingInterval(PING_INTERVAL_SECONDS, TimeUnit.SECONDS)
                    .build();
        }
    }

    public void setListener(WebSocketClientListener listener) {
        this.listener = listener;
    }

    public void setTokenProvider(TokenProvider tokenProvider) {
        this.tokenProvider = tokenProvider;
    }

    public boolean isConnected() {
        return isConnected.get() && webSocket != null;
    }

    public String getCurrentUrl() {
        return currentUrl;
    }

    public boolean isTokenValid(String tokenToCheck) {
        String t = (tokenToCheck != null && !tokenToCheck.isEmpty()) ? tokenToCheck : this.token;
        if (t == null || t.isEmpty() || t.equalsIgnoreCase("Invalid")) {
            if (tokenProvider != null) {
                String fresh = tokenProvider.fetchToken();
                if (fresh != null && !fresh.isEmpty() && !fresh.equalsIgnoreCase("Invalid")) {
                    this.token = fresh;
                    return true;
                }
            }
            return false;
        }
        return true;
    }

    /**
     * Asynchronously connects to the remote WebSocket URL.
     *
     * Close existing connection, if any.
     */
    public void connectAsync(final String url, final String token) {
        if (!isTokenValid(token)) {
            Log.w(TAG, "Cannot connect to WebSocket: token is missing or invalid");
            shouldConnect.set(false);
            if (listener != null) {
                listener.onAuthError(401, "Token is missing or invalid");
            }
            return;
        }

        if (url != null && !url.isEmpty()) {
            this.currentUrl = url;
        }
        this.token = token;
        shouldConnect.set(true);
        cancelScheduledReconnect();

        executor.execute(() -> {
            try {
                disconnectInternal(false);
                Log.d(TAG, "Connecting to WebSocket URL: " + currentUrl + " with token: " + redactToken(this.token));

                final var request = new Request
		    .Builder()
		    .url(currentUrl)
		    .addHeader("Authorization", String.format("Bearer %s", this.token))
		    .addHeader("Upgrade", "websocket")
		    .addHeader("Connection", "Upgrade")
		    .build();

                webSocket = okHttpClient.newWebSocket(request, createWebSocketListener());
            } catch (Exception e) {
                Log.e(TAG, "Failed to initiate WebSocket connection to " + currentUrl, e);
                isConnected.set(false);

                if (listener != null) {
                    listener.onError(e);
                }

                if (shouldConnect.get() && isTokenValid(this.token)) {
                    scheduleReconnect();
                }
            }
        });
    }

    private WebSocketListener createWebSocketListener() {
        return new WebSocketListener() {
            @Override
            public void onOpen(@NonNull WebSocket webSocket, @NonNull Response response) {
                Log.d(TAG, "WebSocket connected: " + redactToken(currentUrl));
                isConnected.set(true);
                reconnectDelayMs = INITIAL_RECONNECT_DELAY_MS; // Reset reconnect delay on success
                if (listener != null) {
                    listener.onConnected(currentUrl);
                }
            }

            @Override
            public void onMessage(@NonNull WebSocket webSocket, @NonNull ByteString bytes) {
                try {
                    // Server's PacketCodec frame: 1 type byte + payload, the WS frame is the framing.
                    byte[] raw = bytes.toByteArray();
                    if (raw.length == 0) return;

                    final var packet = new Packet(raw[0], Arrays.copyOfRange(raw, 1, raw.length));

                    Log.d(TAG, "Received packet " + Packet.typeName(packet.getType()) + ", payload length: " + packet.getPayload().length);

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
                if (shouldConnect.get() && isTokenValid(token)) {
                    scheduleReconnect();
                }
            }

            @Override
            public void onFailure(@NonNull WebSocket webSocket, @NonNull Throwable t, @Nullable Response response) {
                Log.w(TAG, "WebSocket failure: " + t.getMessage() + (response != null ? " HTTP " + response.code() : ""));
                boolean wasConnected = isConnected.getAndSet(false);

                boolean isAuthError = false;
                int statusCode = 0;
                String errorMsg = t.getMessage();

                if (response != null && (response.code() == 401 || response.code() == 403)) {
                    isAuthError = true;
                    statusCode = response.code();
                    errorMsg = response.message() != null && !response.message().isEmpty() ? response.message() : "Unauthorized";
                    Log.w(TAG, "WebSocket connection rejected with HTTP " + statusCode);
                    shouldConnect.set(false);
                }

                if (listener != null) {
                    if (isAuthError) {
                        listener.onAuthError(statusCode, errorMsg);
                    } else {
                        listener.onError(t);
                    }
                    if (wasConnected) {
                        listener.onDisconnected();
                    }
                }

                if (shouldConnect.get() && !isAuthError && isTokenValid(token)) {
                    scheduleReconnect();
                }
            }
        };
    }

    private synchronized void scheduleReconnect() {
        if (!shouldConnect.get()) {
            Log.d(TAG, "Skipping WebSocket reconnect: shouldConnect is false");
            return;
        }

        String currentToken = token;
        if (tokenProvider != null) {
            String freshToken = tokenProvider.fetchToken();
            if (freshToken != null && !freshToken.isEmpty()) {
                currentToken = freshToken;
                this.token = freshToken;
            }
        }

        if (!isTokenValid(currentToken)) {
            Log.w(TAG, "Skipping WebSocket reconnect: Token is empty or invalid");
            shouldConnect.set(false);
            if (listener != null) {
                listener.onAuthError(401, "Invalid token for reconnection");
            }
            return;
        }

        cancelScheduledReconnect();

        long delay = reconnectDelayMs;
        Log.d(TAG, "Scheduling WebSocket reconnect in " + delay + " ms");
        reconnectDelayMs = Math.min(reconnectDelayMs * 2, MAX_RECONNECT_DELAY_MS);

        final String tokenToUse = currentToken;
        reconnectTask = executor.schedule(() -> {
            if (shouldConnect.get() && currentUrl != null && isTokenValid(tokenToUse)) {
                Log.d(TAG, "Attempting automatic WebSocket reconnection...");
                try {
                    disconnectInternal(false);
                    Request request = new Request.Builder()
                            .url(currentUrl)
                            .addHeader("Authorization", String.format("Bearer %s", tokenToUse))
                            .addHeader("Upgrade", "websocket")
                            .addHeader("Connection", "Upgrade")
                            .build();
                    webSocket = okHttpClient.newWebSocket(request, createWebSocketListener());
                } catch (Exception e) {
                    Log.e(TAG, "Error during WebSocket reconnect attempt", e);
                    if (shouldConnect.get() && isTokenValid(tokenToUse)) {
                        scheduleReconnect();
                    }
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
                byte[] rawPacketBytes = packet.serialize();

		Log.d(TAG, "Sending packet " + Packet.typeName(packet.getType()) + ": " + Arrays.toString(rawPacketBytes));

                if (webSocket != null) {
                    boolean success = webSocket.send(ByteString.of(rawPacketBytes));

                    if (success) {
                        Log.d(TAG, "Sent packet " + Packet.typeName(packet.getType()) + " (" + rawPacketBytes.length + " bytes)");
                    } else {
                        Log.w(TAG, "Failed to enqueue packet " + Packet.typeName(packet.getType()));
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error sending packet " + Packet.typeName(packet.getType()), e);
                if (listener != null) {
                    listener.onError(e);
                }
            }
        });
    }

    /**
     * Closes the WebSocket connection.
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

    private static String redactToken(String url) {
        return url == null ? null : url.replaceAll("token=[^&]*", "token=***");
    }
}
