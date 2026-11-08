package cz.mkdaniel.enterprisemessenger.net;

import android.content.Context;

import android.os.Handler;
import android.os.Looper;

import android.util.Log;
import android.util.Base64;

import java.nio.ByteBuffer;

import org.json.JSONObject;
import org.json.JSONArray;

import java.nio.charset.StandardCharsets;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import cz.mkdaniel.enterprisemessenger.crypto.CryptoManager;
import cz.mkdaniel.enterprisemessenger.notification.NotificationHelper;
import cz.mkdaniel.enterprisemessenger.ui.message.Message;
import cz.mkdaniel.enterprisemessenger.ui.transform.Room;

/**
 * Singleton manager handling WebSocket protocol exchanges and HTTP Auth with Enterprise Messenger servers.
 * <p>
 * Flow: REST login/register -> WebSocket {@code /wss} with {@code Authorization: Bearer <jwt>} ->
 * end-to-end encrypted {@code CHAT_MESSAGE_ENC} packets.
 * <p>
 * The deployment address is a single point of truth in {@code @string/server_ip} /
 * {@code @integer/server_port}; only the scheme and the {@code /wss} path are code constants.
 */
public class ServerConnectionManager implements WebSocketClient.WebSocketClientListener {
    public static final String HTTPS_PROTOCOL = "https";
    public static final String WS_PROTOCOL = "wss";

    /**
     * Default host, the value of {@code @string/server_ip}. It is deliberately empty: the resource
     * is the single point of truth for the deployment address and is read through
     * {@link #applyDeploymentDefault} when the singleton is created, so no address may ever be
     * duplicated in code.
     */
    public static String WS_HOST = "";

    /** Default port, the value of {@code @integer/server_port}; read with the host above. */
    public static Integer WS_PORT = 0;

    /** Binary WebSocket endpoint of the server, see its {@code WebSocketConfig}. */
    public static final String WS_ENDPOINT_PATH = "wss";

    private static final String TAG = "ServerConnectionManager";

    private static volatile ServerConnectionManager instance;

    private final Context context;
    private final CryptoManager cryptoManager;
    private final WebSocketClient webSocketClient;
    private final OkHttpClient httpClient;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Set<ConnectionListener> listeners = new CopyOnWriteArraySet<>();
    private String currentServerHTTPIp;
    private String currentServerWSIp;

    private String jwtToken = "";

    /**
     * HTTP base URL of the server that issued {@link #jwtToken}. A JWT only authenticates on the
     * server that signed it, so offering a token from another address always comes back as 401,
     * which clears the session and looks to the user like a spontaneous log-out.
     */
    private String jwtTokenIssuer = "";

    /**
     * Set when the server rejected our credentials with 401/403. It distinguishes a genuinely
     * invalid session (clear it) from a server that is merely unreachable (keep it).
     */
    private boolean lastLoginRejected = false;

    private long visibleChannelId;

    /**
     * Symmetric key of the channel the user is currently chatting in, derived from
     * {@link #defaultChannelSecret(long)}. Chat payloads are AES-GCM ciphertext of a small JSON
     * envelope, so the server can store and echo them without being able to read them.
     */
    private volatile byte[] activeChannelKey;
    private volatile long activeChannelId = -1L;

    public interface ConnectionListener {
	void onConnectionStateChanged(boolean connected, String serverIp);
	void onMessageReceived(Message message);
	void onError(String errorMessage);
	default void onAuthRequired() {}
    }

    public interface Callback<T> {
	void onResult(T result);
	void onFailure(String error);
    }

    public static String normalizeHttpUrl(String rawIp) {
        // No implicit fallback to another host: an empty address is a caller error, and silently
        // dialling @string/server_ip instead would send the JWT to a server the user never picked.
        if (rawIp == null || rawIp.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "Server address is empty; @string/server_ip is the only default source");
        }
        if (WS_PORT == null || WS_PORT <= 0) {
            // @integer/server_port has not been read yet, which would build a "host:0" URL.
            throw new IllegalStateException(
                    "Deployment port not loaded; obtain the singleton with getInstance(context) first");
        }
        String ip = rawIp.trim();
        if (ip.startsWith("http://") || ip.startsWith("https://")) {
            return ip.endsWith("/") ? ip.substring(0, ip.length() - 1) : ip;
        }
        if (ip.contains(":")) {
            return "https://" + ip;
        } else {
            return HTTPS_PROTOCOL + "://" + ip + ":" + WS_PORT;
        }
    }

    public static String normalizeWsUrl(String rawIp) {
        String httpUrl = normalizeHttpUrl(rawIp);
        String wsUrl = httpUrl.replaceFirst("^https://", "wss://").replaceFirst("^http://", "ws://");
        if (!wsUrl.endsWith("/" + WS_ENDPOINT_PATH)) {
            wsUrl = wsUrl + "/" + WS_ENDPOINT_PATH;
        }
        return wsUrl;
    }

    public void fetchChannels(String serverIp, Callback<List<Room>> callback) {
	executor.execute(() -> {
	    try {
		if (channelsArray == null) {
		    mainHandler.post(() -> callback.onFailure("Failed to fetch channels from server"));

		    return;
		}
		List<Room> rooms = new ArrayList<>();
		for (int i = 0; i < channelsArray.length(); i++) {
		    JSONObject obj = channelsArray.getJSONObject(i);
		    String id = String.valueOf(obj.optLong("id", i + 1));
		    String name = obj.optString("name", "channel-" + id);
		    long channelId = parseChannelId(id);
		    String encKey = defaultChannelSecret(channelId);
		    rooms.add(new Room(id, name, encKey, false));
		}
		mainHandler.post(() -> callback.onResult(rooms));
	    } catch (Exception e) {
		mainHandler.post(() -> callback.onFailure(e.getMessage()));
	    }
	});
    }

    public void setVisibleChannel(long channelId) {
	visibleChannelId = channelId;
    }

    public boolean isChannelVisible(long channelId) {
	return channelId >= 0 && channelId == visibleChannelId;
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
        return new OkHttpClient();
    }

    /**
     * Reads the deployment address from {@code @string/server_ip} and {@code @integer/server_port},
     * the only place it is configured. Called before the first URL is built.
     */
    private static void applyDeploymentDefault(Context context) {
	final Context appContext = context.getApplicationContext();
	WS_HOST = appContext.getString(cz.mkdaniel.enterprisemessenger.R.string.server_ip);
	WS_PORT = appContext.getResources().getInteger(cz.mkdaniel.enterprisemessenger.R.integer.server_port);
    }

    private ServerConnectionManager(Context context) {
	this.context = context.getApplicationContext();
	this.cryptoManager = CryptoManager.getInstance(context);

	// The address in @string/server_ip is the default target; a server entry selected in the UI
	// can still override it through connectToServer().
	applyDeploymentDefault(this.context);

	this.currentServerHTTPIp = normalizeHttpUrl(WS_HOST + ":" + WS_PORT);
	this.currentServerWSIp = normalizeWsUrl(WS_HOST + ":" + WS_PORT);
	this.webSocketClient = new WebSocketClient(cryptoManager, currentServerWSIp);
	this.webSocketClient.setListener(this);
	this.webSocketClient.setTokenProvider(() -> {
	    if (jwtToken != null && !jwtToken.isEmpty()) {
		return jwtToken;
	    }
	    AccountStore accountStore = new AccountStore(context);
	    String savedToken = accountStore.getJwtToken();
	    // Only reuse the persisted token while it was issued by the server the socket is dialling;
	    // otherwise the reconnect loop would keep offering a foreign token and be rejected forever.
	    if (savedToken != null && !savedToken.isEmpty()
		    && currentServerHTTPIp.equalsIgnoreCase(accountStore.getTokenIssuer())) {
		jwtToken = savedToken;
		jwtTokenIssuer = currentServerHTTPIp;
		return savedToken;
	    }
	    return "";
	});
	this.httpClient = createOkHttpClient();
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
	return currentServerHTTPIp;
    }

    public void handleAuthError(int statusCode, String message) {
	Log.w(TAG, "Authentication error (" + statusCode + "): " + message + ". Clearing session and disconnecting.");
	this.jwtToken = "";
	this.jwtTokenIssuer = "";
	AccountStore accountStore = new AccountStore(context);
	accountStore.clearSession();
	webSocketClient.disconnect();
	notifyAuthRequired();
    }

    /**
     * Authenticates via REST API (if needed) and connects to remote WebSocket server.
     */
    public void connectToServer(String serverIp) {
	// Never fall back to another address here: dialling the deployment default when the caller
	// knows no address is exactly what makes the app reconnect to a host the user never entered.
	if (serverIp == null || serverIp.trim().isEmpty()) {
	    Log.w(TAG, "connectToServer: no address given, keeping " + currentServerHTTPIp);
	    return;
	}

	final String httpUrl = normalizeHttpUrl(serverIp);
	final String wsUrl = normalizeWsUrl(serverIp);

	final boolean targetChanged = !wsUrl.equalsIgnoreCase(webSocketClient.getCurrentUrl());

	this.currentServerHTTPIp = httpUrl;
	this.currentServerWSIp = wsUrl;

	// A token signed by another server cannot authenticate here. Dropping it makes the code below
	// log in again with the stored credentials instead of being rejected and clearing the session.
	if (!jwtToken.isEmpty() && !httpUrl.equalsIgnoreCase(jwtTokenIssuer)) {
	    Log.d(TAG, "Cached JWT was issued by " + jwtTokenIssuer + ", re-authenticating against " + httpUrl);
	    jwtToken = "";
	    jwtTokenIssuer = "";
	}

	if (webSocketClient.isConnected() && !targetChanged) {
	    Log.d(TAG, "Already connected to " + httpUrl);
	    notifyStateChanged(true, httpUrl);
	    return;
	}

	// The socket keeps the URL of its last handshake and reconnects to that URL verbatim. Without
	// this redial an already running (or retrying) connection to another address would keep its
	// old URL while every REST call went to the newly selected server.
	if (targetChanged) {
	    Log.d(TAG, "Target address changed, reconnecting the WebSocket to " + httpUrl);
	    webSocketClient.disconnect();
	}

	Log.d(TAG, "Initiating authentication & connection to: " + httpUrl);
	executor.execute(() -> fetchJwtTokenAndConnect(httpUrl, wsUrl));
    }

    private void fetchJwtTokenAndConnect(String httpUrl, String wsUrl) {
	try {
	    var pubKey = cryptoManager.getPublicKeyBase64();
	    AccountStore accountStore = new AccountStore(context);

	    // A token restored from the previous launch belongs to the server that stored it; using it
	    // against another address only produces a 401 and clears the session.
	    if (jwtToken == null || jwtToken.isEmpty()) {
		final String storedToken = accountStore.getJwtToken();
		final String storedIssuer = accountStore.getTokenIssuer();

		if (storedToken != null && !storedToken.isEmpty()
			&& httpUrl.equalsIgnoreCase(storedIssuer)) {
		    jwtToken = storedToken;
		    jwtTokenIssuer = storedIssuer;
		} else if (storedToken != null && !storedToken.isEmpty()) {
		    Log.d(TAG, "Stored JWT was issued by " + storedIssuer
			    + ", not " + httpUrl + ", signing in again");
		}
	    }

	    if (jwtToken.isEmpty() && accountStore.isLoggedIn()) {
		String username = accountStore.getUsername();
		String password = accountStore.getPassword();
		jwtToken = login(httpUrl, username, password);
	    }

	    if (jwtToken == null || jwtToken.isEmpty()) {
		Log.w(TAG, "No valid JWT token obtained from " + httpUrl + ", aborting WebSocket connection");
		if (lastLoginRejected) {
		    // The server answered 401/403 to our credentials: the session really is invalid.
		    handleAuthError(401, "No valid JWT token available");
		} else {
		    // Unreachable server or a failed request is not a rejected login, so the stored
		    // session must survive it; the socket keeps retrying on its own.
			notifyError("Cannot reach " + httpUrl);
			notifyStateChanged(false, httpUrl);
		}
		return;
	    }

	    webSocketClient.connectAsync(wsUrl, jwtToken);

	    final var peerKeys = makeGETRequestCollection(String.format("/api/channels/2/keys"));
	    if (peerKeys == null) {
		return;
	    }

	    for (var i = 0; i < peerKeys.length(); ++i) {
		final var peer = peerKeys.getJSONObject(i);
		final var peerId = peer.getLong("userId");
		final var peerPublicKey = peer.getString("publicKey");
	    
		Log.d(TAG, String.format("Establishing public key %s with peer %d", peerPublicKey, peerId));

		final var peerKey = cryptoManager.deriveSharedSecret(Base64.decode(peerPublicKey, Base64.NO_WRAP));
	
		Log.d(TAG, String.format("PeerKey: %s", Arrays.toString(peerKey)));

		cryptoManager.putNewPeerKeyEntry(peerId, peerKey);
	    }
	}
	catch (final Exception exception) {
	    Log.e(TAG, exception.toString());
	}
    }

    /**
     * Posts credentials to {@code /api/auth/login} and returns the JWT token, or "" on any failure.
     */
    public String login(String httpUrl, String username, String password) {
	lastLoginRejected = false;

	try {
	    final var loginJson = new JSONObject();

	    loginJson.put("username", username);
	    loginJson.put("password", password);

	    final var requestBody = RequestBody.create(loginJson.toString(), MediaType.parse("application/json; charset=utf-8"));
	    final var baseUrl = (httpUrl != null && !httpUrl.isEmpty()) ? httpUrl : currentServerHTTPIp;
	    final var loginRequest = new Request.Builder()
		.url(baseUrl + "/api/auth/login")
		.post(requestBody)
		.build();

	    try (final var response = httpClient.newCall(loginRequest).execute()) {
		if (response.isSuccessful() && response.body() != null) {
		    final var resStr = response.body().string();
		    final var resObj = new JSONObject(resStr);
		    final var token = resObj.optString("token", "");

		    Log.d(TAG, "Authentication successful, obtained JWT token");

		    if (!token.isEmpty()) {
			this.jwtToken = token;
			this.jwtTokenIssuer = baseUrl;
			new AccountStore(context).saveJwtToken(token, baseUrl);
		    }

		    return token;
		}

		lastLoginRejected = response.code() == 401 || response.code() == 403;
		Log.w(TAG, "Login failed with HTTP " + response.code());
		return "";
	    }
	} catch (Exception e) {
	    Log.w(TAG, "Login request failed: " + e.getMessage());

	    return "";
	}
    }

    /**
     * Selects the channel whose messages are sent and received on the shared WebSocket session.
     * <p>
     * The server derives the target channel from its own session state, so there is nothing to
     * transmit here yet; the client only needs the channel id (for notifications) and the channel
     * key (for end-to-end encryption).
     */
    public void joinChannel(final String channelId) {
	final long id = parseChannelId(channelId);
	if (id < 0) {
	    Log.w(TAG, "joinChannel: cannot parse channel id from '" + channelId + "'");
	    return;
	}

	this.activeChannelId = id;
	this.activeChannelKey = channelKey(id);

	// While the socket is still dialling, onConnected() sends the join for us.
	sendChannelJoin();
    }

    /**
     * Tells the server which channel our packets belong to. The server keeps the active channel
     * per WebSocket session, so this has to be repeated after every (re)connection.
     */
    private void sendChannelJoin() {
	final long channelId = activeChannelId;
	if (channelId < 0) {
	    return;
	}

	if (!webSocketClient.isConnected()) {
	    Log.d(TAG, "WebSocket not connected yet, channel " + channelId + " join deferred until open");
	    return;
	}

	final var joinPayload = ByteBuffer.allocate(Long.BYTES).putLong(channelId).array();
	webSocketClient.sendPacketAsync(new Packet(Packet.TYPE_CHANNEL_JOIN, joinPayload));

	Log.d(TAG, "Sent CHANNEL_JOIN for channel " + channelId);
    }

    /**
     * AES key a channel's messages are encrypted with. Every member derives the same key, which
     * is why it must never be sent over the wire.
     */
    public static byte[] channelKey(long channelId) {
	try {
	    return java.security.MessageDigest
		.getInstance("SHA-256")
		.digest(defaultChannelSecret(channelId).getBytes(StandardCharsets.UTF_8));
	} catch (final Exception exception) {
	    Log.e(TAG, "Failed to derive channel key", exception);
	    return null;
	}
    }

    public long getActiveChannelId() {
	return activeChannelId;
    }

    private JSONObject makeGETRequest(final String endpoint) {
	final var loginRequest = new Request
	    .Builder()
	    .url(String.format("%s%s", currentServerHTTPIp, endpoint))
	    .addHeader("Authorization", String.format("Bearer %s", jwtToken))
	    .get()
	    .build();

	try (final var response = httpClient.newCall(loginRequest).execute()) {
	    Log.w(TAG, String.format("GET %s%s", currentServerHTTPIp, endpoint));

	    if (response.isSuccessful() && response.body() != null) {
		return new JSONObject(response.body().string());
	    }

	    Log.w(TAG, String.format("GET failed with HTTP status code: %s, and response: %s", response.code(), response));

	    if (response.code() == 401 || response.code() == 403) {
		handleAuthError(response.code(), response.message());
	    }

	    return null;
	}
	catch (final Exception exception) {
	    Log.e(TAG, exception.toString());
	    exception.printStackTrace();

	    return null;
	}
    }

    private JSONArray makeGETRequestCollection(final String endpoint) {
	return makeGETRequestCollection(currentServerHTTPIp, endpoint);
    }

    private JSONArray makeGETRequestCollection(final String baseUrl, final String endpoint) {
	final var loginRequest = new Request
	    .Builder()
	    .url(String.format("%s%s", baseUrl, endpoint))
	    .addHeader("Authorization", String.format("Bearer %s", jwtToken))
	    .get()
	    .build();

	try (final var response = httpClient.newCall(loginRequest).execute()) {
	    Log.w(TAG, String.format("GET %s%s", baseUrl, endpoint));

	    if (response.isSuccessful() && response.body() != null) {
		final var resStr = response.body().string();

		return new JSONArray(resStr);
	    }

	    Log.w(TAG, String.format("GET failed with HTTP status code: %s, and response: %s", response.code(), response));

	    if (response.code() == 401 || response.code() == 403) {
		handleAuthError(response.code(), response.message());
	    }

	    return null;
	}
	catch (final Exception exception) {
	    Log.e(TAG, exception.toString());
	    exception.printStackTrace();

	    return null;
	}
    }

    private JSONObject makePOSTRequest(final String endpoint, final JSONObject body) {
	final var requestBody = RequestBody.create(body.toString(), MediaType.parse("application/json; charset=utf-8"));
	final var loginRequest = new Request
	    .Builder()
	    .url(String.format("%s%s", currentServerHTTPIp, endpoint))
	    .addHeader("Authorization", String.format("Bearer %s", jwtToken))
	    .post(requestBody)
	    .build();

	try (final var response = httpClient.newCall(loginRequest).execute()) {
	    if (response.isSuccessful() && response.body() != null) {
		final var resStr = response.body().string();
		final var resObj = new JSONObject(resStr);

		return resObj;
	    }

	    Log.w(TAG, "POST failed with HTTP " + response.code());

	    if (response.code() == 401 || response.code() == 403) {
		handleAuthError(response.code(), response.message());
	    }

	    return null;
	}
	catch (final Exception exception) {
	    Log.e(TAG, exception.toString());

	    return null;
	}
    }

    private JSONObject makePUTRequest(final String endpoint, final JSONObject body) {
	final var requestBody = RequestBody.create(body.toString(), MediaType.parse("application/json; charset=utf-8"));
	final var loginRequest = new Request
	    .Builder()
	    .url(String.format("%s%s", currentServerHTTPIp, endpoint))
	    .addHeader("Authorization", String.format("Bearer %s", jwtToken))
	    .put(requestBody)
	    .build();

	try (final var response = httpClient.newCall(loginRequest).execute()) {
	    if (response.isSuccessful() && response.body() != null) {
		final var resStr = response.body().string();
		final var resObj = new JSONObject(resStr);

		return resObj;
	    }

	    Log.w(TAG, "PUT failed with HTTP " + response.code());

	    if (response.code() == 401 || response.code() == 403) {
		handleAuthError(response.code(), response.message());
	    }

	    return null;
	}
	catch (final Exception exception) {
	    Log.e(TAG, "PUT failed with HTTP ");

	    return null;
	}
    }

    private JSONObject makeDELETERequest(final String endpoint) {
	final var loginRequest = new Request
	    .Builder()
	    .url(String.format("%s%s", currentServerHTTPIp, endpoint))
	    .addHeader("Authorization", String.format("Bearer %s", jwtToken))
	    .delete()
	    .build();

	try (final var response = httpClient.newCall(loginRequest).execute()) {
	    if (response.isSuccessful() && response.body() != null) {
		final var resStr = response.body().string();
		final var resObj = new JSONObject(resStr);

		return resObj;
	    }

	    Log.w(TAG, "DELETE failed with HTTP " + response.code());

	    if (response.code() == 401 || response.code() == 403) {
		handleAuthError(response.code(), response.message());
	    }

	    return null;
	}
	catch (final Exception exception) {
	    Log.e(TAG, "DELETE failed with HTTP ");

	    return null;
	}
    }

    /**
     * Registers a new user on {@code /api/auth/register} and returns the JWT token, or "" on any failure.
     */
    public String register(String httpUrl, String username, String password, String publicKey) {
	try {
	    JSONObject regJson = new JSONObject();
	    regJson.put("username", username);
	    regJson.put("password", password);
	    regJson.put("publicKey", (publicKey != null && !publicKey.isEmpty()) ? publicKey : cryptoManager.getPublicKeyBase64());

	    RequestBody regBody = RequestBody.create(
		    regJson.toString(),
		    MediaType.parse("application/json; charset=utf-8")
		    );

	    String baseUrl = (httpUrl != null && !httpUrl.isEmpty()) ? httpUrl : currentServerHTTPIp;
	    Request regRequest = new Request.Builder()
		.url(baseUrl + "/api/auth/register")
		.post(regBody)
		.build();

	    try (Response regResponse = httpClient.newCall(regRequest).execute()) {
		if (regResponse.body() != null) {
		    String resStr = regResponse.body().string();
		    JSONObject resObj = new JSONObject(resStr);
		    String token = resObj.optString("token", "");
		    Log.d(TAG, "Registration response, obtained token: " + !token.isEmpty());
		    if (!token.isEmpty()) {
			this.jwtToken = token;
			this.jwtTokenIssuer = baseUrl;
		    }
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
     * Encrypts and transmits a chat message packet to the server.
     * <p>
     * The payload is the AES-GCM ciphertext ({@code [12-byte IV][ciphertext+tag]}) of a JSON
     * envelope carrying the message id, sender and text. The server treats it as opaque bytes and
     * broadcasts it verbatim to every session in the channel (including ours), which is how the
     * sender's own bubble is reconciled by {@code messageId} on the way back.
     *
     * @param messageId stable id used to deduplicate our own echo
     */
    public void sendChatMessage(String messageId, String text, String senderUsername) {
	try {
	    if (!webSocketClient.isConnected()) {
		Log.w(TAG, "Cannot send message: Not connected to WebSocket server");
		notifyError("Not connected to server");

		return;
	    }

	    final var envelope = new JSONObject();
	    envelope.put("id", messageId);
	    envelope.put("sender", senderUsername == null || senderUsername.isEmpty() ? "You" : senderUsername);
	    envelope.put("text", text);
	    envelope.put("timestamp", System.currentTimeMillis());

	    final byte[] key = activeChannelKey != null ? activeChannelKey : channelKey(activeChannelId);
	    if (key == null) {
		notifyError("No channel key available - open a channel before sending");
		return;
	    }

	    final var payloadBytes = cryptoManager.encryptPayload(envelope.toString().getBytes(StandardCharsets.UTF_8), key);
	    final var messagePacket = new Packet(Packet.TYPE_CHAT_MESSAGE, payloadBytes);
	    webSocketClient.sendPacketAsync(messagePacket);

	    Log.d(TAG, "Sent CHAT_MESSAGE to channel " + activeChannelId + " (" + payloadBytes.length + " bytes)");
	} catch (final Exception e) {
	    Log.e(TAG, "Failed to send chat message packet", e);
	    notifyError("Failed to send message: " + e.getMessage());
	}
    }

    public void disconnect() {
	webSocketClient.disconnect();
    }

    /**
     * Decrypts a broadcast chat payload. A message can arrive before the user opened that
     * channel (or while browsing another one), so the active channel key is tried first and the
     * currently visible channel key second; a wrong key only fails the GCM tag check.
     *
     * @return the plaintext envelope, or {@code null} when no known key opens the payload
     */
    private byte[] decryptChatPayload(final byte[] ciphertext) {
	final byte[][] candidates = {activeChannelKey, channelKey(visibleChannelId)};

	for (final byte[] key : candidates) {
	    if (key == null) {
		continue;
	    }

	    try {
		return cryptoManager.decryptPayload(ciphertext, key);
	    } catch (final Exception ignored) {
		// Wrong channel key - try the next one.
	    }
	}

	return null;
    }

    @Override
    public void onConnected(String url) {
	Log.d(TAG, "WebSocket connected successfully: " + url);

	// Re-announce the channel: the server's active-channel state lives on the dead session.
	sendChannelJoin();

	notifyStateChanged(true, currentServerHTTPIp);
    }

    /**
     *  encrypt the new key rotation with shared ECDH key
     */
    @Override
    public void onPacketReceived(final Packet packet) {
	try {
	final var type = packet.getType();
	final var wrappedPayload = ByteBuffer.wrap(packet.getPayload());

	Log.d(TAG, "Incoming packet type: 0x" + Integer.toHexString(type) + ", payload size: " + packet.getPayload().length);

	switch (type) {
	    case Packet.TYPE_CHAT_MESSAGE:
		// Payload is exactly what the sender put on the wire: AES-GCM ciphertext of the envelope.
		byte[] payload = packet.getPayload();
		if (payload == null || payload.length == 0) {
		    Log.w(TAG, "Received empty CHAT_MESSAGE payload");
		    break;
		}

		String messageText;
		String senderName = "Server User";
		String messageId = String.valueOf(System.currentTimeMillis());
		long sentAt = System.currentTimeMillis();

		byte[] plaintext = decryptChatPayload(payload);
		if (plaintext == null) {
		    Log.w(TAG, "CHAT_MESSAGE could not be decrypted with the active channel key");
		    break;
		}

		messageText = new String(plaintext, StandardCharsets.UTF_8);

		if (messageText.startsWith("{")) {
		    try {
			JSONObject jsonMsg = new JSONObject(messageText);
			messageText = jsonMsg.optString("text", messageText);
			senderName = jsonMsg.optString("sender", senderName);
			messageId = jsonMsg.optString("id", messageId);
			sentAt = jsonMsg.optLong("timestamp", sentAt);
		    } catch (Exception ignored) {}
		}

		notifyMessageReceived(new Message(messageId, messageText, senderName, sentAt));

		if (!isChannelVisible(visibleChannelId)) {
		    NotificationHelper.showChannelMessageNotification(context, visibleChannelId, null);
		}

		break;

	    case Packet.TYPE_UNKNOWN:
		String errorMsg = new String(packet.getPayload(), StandardCharsets.UTF_8);
		Log.e(TAG, "Server returned error packet: " + errorMsg);
		notifyError(errorMsg);

		break;

	    case Packet.TYPE_KEY_ROTATION:
		{
		    Log.d(TAG, "Key rotation initiated by the server");

		    // Payload is [version MSB][version LSB][channelId]; the type byte is not part of it.
		    var offset = 0;

		    if (wrappedPayload.remaining() < 3 * Long.BYTES) {
			Log.e(TAG, "ROTATE_KEY payload too short: " + wrappedPayload.remaining() + " bytes");
			break;
		    }

		    final var mostSignificant = wrappedPayload.getLong(offset);
		    
		    offset += Long.SIZE/8;

		    final var leastSignigicant = wrappedPayload.getLong(offset);
		    final var rotationUUID = new UUID(mostSignificant, leastSignigicant);

		    offset += Long.SIZE/8;

		    Log.d(TAG, "New key rotation UUID is set to: " + rotationUUID);

		    final var channelId = wrappedPayload.getLong(offset);

		    offset += Long.SIZE/8;

		    Log.d(TAG, "New key rotation channelId is set to: " + channelId);

		    final var newRotation = cryptoManager.generateSenderSymmetricSecretRotatedKey(rotationUUID);
		    if (newRotation == null) {
			Log.e(TAG, "Failed to generate rotated sender key for " + rotationUUID);
			break;
		    }

		    final var peers = makeGETRequestCollection(String.format("/api/channels/%d/keys", channelId));
		    if (peers == null) {
			Log.w(TAG, "No peer keys available for channel " + channelId + ", cannot unicast the rotated key");
			break;
		    }

		    Log.d(TAG, peers.toString());
 
		    for (var i = 0; i < peers.length(); ++i) { // make collection request return Collection<JSONObject>
			final var peer = peers.getJSONObject(i);
			final var peerId = peers.getJSONObject(i).getLong("userId");

			Log.d(TAG, String.format("Unicasting to %d", peerId));

			var peerSecretSymmetricKey = cryptoManager.retrieveSecretSymmetricKey(peerId); // do checked cast


			if (peerSecretSymmetricKey == null) {
			    Log.d(TAG, String.format("Did not communicate with %d yet, will create the key on the spot", peerId));
		
			    final var peerPublicKey = peer.getString("publicKey");
	    
			    Log.d(TAG, String.format("Establishing public key %s with peer %d", peerPublicKey, peerId));

			    peerSecretSymmetricKey = cryptoManager.deriveSharedSecret(Base64.decode(peerPublicKey, Base64.NO_WRAP));

			    cryptoManager.putNewPeerKeyEntry(peerId, peerSecretSymmetricKey);
			}

			final var encryptedSenderKey = cryptoManager.encryptPayload(newRotation.getBytes(), peerSecretSymmetricKey);
			final var unicastBuffer = ByteBuffer.allocate(2*Long.BYTES+encryptedSenderKey.length);
	
			unicastBuffer.putLong(peerId);
			unicastBuffer.putLong(channelId);
			unicastBuffer.put(encryptedSenderKey);

			final var rotationUnicast = new Packet(Packet.TYPE_UNICAST_ROTATED_KEY, unicastBuffer.array());

			webSocketClient.sendPacketAsync(rotationUnicast);
		    }
		}
 
		break;

	    case Packet.TYPE_UNICAST_ROTATED_KEY:
		{	
		    Log.d(TAG, "Key rotation unicast received from the server");

		    // Header is [senderUserId][version MSB][version LSB] followed by the ciphertext.
		    // The server's offline queue replays queued unicasts without that header, so the
		    // payload must be long enough before any of it is read as a long.
		    if (wrappedPayload.remaining() < 3 * Long.BYTES) {
			Log.w(TAG, "UNICAST_ROTATED_KEY payload has no rotation header (" + wrappedPayload.remaining() + " bytes), ignoring");
			break;
		    }

		    final var peerId = wrappedPayload.getLong();
		    final var mostSignificant = wrappedPayload.getLong();
		    final var leastSignigicant = wrappedPayload.getLong();
		    final var rotationUUID = new UUID(mostSignificant, leastSignigicant);
		    final var newRotation = new byte[wrappedPayload.remaining()];
		    
		    wrappedPayload.get(newRotation);
		    
		    Log.d(TAG, String.format("Encrypted key is %s", Arrays.toString(newRotation)));

		    final var sharedKey = cryptoManager.retrieveSecretSymmetricKey(peerId);
		    if (sharedKey == null) {
			Log.w(TAG, "No shared ECDH secret with peer " + peerId + " yet, dropping rotated key");
			break;
		    }

		    final var newRotationDecrypted = new String(cryptoManager.decryptPayload(newRotation, sharedKey));
		    
		    cryptoManager.putNewUUIDRotationEntry(rotationUUID, Base64.decode(newRotationDecrypted, Base64.NO_WRAP));
		    Log.d(TAG, String.format("Rotated to %s from %d", rotationUUID, peerId));
		}

		break;

	    default:
		Log.d(TAG, "Unhandled packet type: 0x" + Integer.toHexString(type));

		break;
	}
	}
	catch (final Exception exception) {
	    Log.e(TAG, exception.toString());
	}
    }

    @Override
    public void onDisconnected() {
	Log.d(TAG, "WebSocket disconnected from " + currentServerHTTPIp);
	notifyStateChanged(false, currentServerHTTPIp);
    }

    @Override
    public void onError(Throwable t) {
	Log.e(TAG, "WebSocket error", t);
	notifyError(t != null ? t.getMessage() : "Unknown connection error");
    }

    @Override
    public void onAuthError(int statusCode, String message) {
	Log.e(TAG, "WebSocket auth error (" + statusCode + "): " + message);
	handleAuthError(statusCode, message);
    }

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

    private void notifyAuthRequired() {
	mainHandler.post(() -> {
	    for (ConnectionListener l : listeners) {
		l.onAuthRequired();
	    }
	});
    }
}
