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
import java.util.Locale;
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
import cz.mkdaniel.enterprisemessenger.ui.transform.ServerViewItem;

/**
 * Singleton manager handling the REST and WebSocket traffic of the app.
 * <p>
 * <b>Physical vs. virtual servers.</b> There is exactly one <i>physical</i> server and its address
 * is compile-time configuration only: {@code @string/server_ip} plus {@code @integer/server_port}.
 * The address is never a parameter of a method here and never stored, so no code path can dial a
 * host the operator did not build into the APK. Everything living behind that address is a
 * <i>virtual</i> server (the server's {@code MessageServer} entity) fetched over REST:
 * <pre>
 *   physical server (@string/server_ip)
 *     - virtual server (MessageServer)  GET /api/servers
 *       - channel (Room)                GET /api/servers/{id}/channels/my
 *         - messages                    WebSocket /wss packets
 * </pre>
 * Flow: REST login/register -> WebSocket {@code /wss} with {@code Authorization: Bearer <jwt>} ->
 * announce the channel with {@code CHANNEL_JOIN} -> end-to-end encrypted {@code CHAT_MESSAGE}
 * packets.
 * <p>
 * Only the scheme and the {@code /wss} path are code constants; {@link #deployBaseUrl()} is the one
 * place the configured address becomes a URL.
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
    /** Normalized {@code https://host:port} of the physical server, from {@code @string/server_ip}. */
    private final String currentServerHTTPIp;
    /** Normalized {@code wss://host:port/wss} of the same server, see {@link #WS_ENDPOINT_PATH}. */
    private final String currentServerWSIp;

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
	/**
	 * @param serverBaseUrl {@link #deployBaseUrl()} of the physical server, i.e. which host the
	 *                      state change happened on. It is never an address the user picked.
	 */
	void onConnectionStateChanged(boolean connected, String serverBaseUrl);
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
            return HTTPS_PROTOCOL + "://" + ip;
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

    /**
     * {@code type} a virtual server is created with. The server accepts {@code ORGANIZATION} and
     * {@code DIRECT} only; the app has no use for the one-to-one {@code DIRECT} servers yet, so
     * every virtual server it creates is an organization.
     */
    public static final String SERVER_TYPE_ORGANIZATION = "ORGANIZATION";

    /** Reported when the server answers 403 to a CRUD call: the session is fine, the role is not. */
    private static final String ERROR_PERMISSION_DENIED = "You do not have permission to do that on this server";

    /*
     * Virtual-server and channel CRUD against the one physical server. Every method below runs its
     * request on the single background {@link #executor} and delivers the answer on the main
     * thread, so a fragment can call it straight from a click listener.
     */

    /**
     * Reads the numeric id the server assigned to the signed-in account and caches it in
     * {@link AccountStore}. Everything that has to recognise "me" inside a member list uses it.
     */
    public void fetchUserId(final Callback<Long> callback) {
	executor.execute(() -> {
	    if (ensureJwt().isEmpty()) {
		postFailure(callback, "Not signed in");

		return;
	    }

	    final long userId = resolveUserId();
	    if (userId < 0) {
		postFailure(callback, "Failed to read your account from the server");

		return;
	    }

	    mainHandler.post(() -> callback.onResult(userId));
	});
    }

    /**
     * Lists the virtual servers the user is a member of ({@code GET /api/servers}) together with
     * whether he administers each of them, which is what the UI gates its mutations on.
     * <p>
     * The admin-ness comes from each server's member list, mirroring the server-side check; the
     * fan-out is deliberately sequential because it already runs inside the one background task and
     * the list holds a handful of entries.
     */
    public void fetchServers(final Callback<List<ServerViewItem>> callback) {
	executor.execute(() -> {
	    try {
		if (ensureJwt().isEmpty()) {
		    postFailure(callback, "Not signed in");

		    return;
		}

		final long myUserId = resolveUserId();
		if (myUserId < 0) {
		    postFailure(callback, "Failed to read your account from the server");

		    return;
		}

		final JSONArray serversArray = executeGet("/api/servers").asJsonArray();
		if (serversArray == null) {
		    postFailure(callback, "Failed to fetch virtual servers from the server");

		    return;
		}

		final List<ServerViewItem> servers = new ArrayList<>();

		for (int i = 0; i < serversArray.length(); i++) {
		    final JSONObject obj = serversArray.optJSONObject(i);
		    if (obj == null) {
			continue;
		    }

		    final long serverId = obj.optLong("id", -1L);
		    if (serverId < 0) {
			continue;
		    }

		    final String name = obj.optString("name", String.format(Locale.US, "Server %d", serverId));
		    final String type = obj.optString("type", "");
		    final boolean isAdmin = isUserAdminOfServer(serverId, myUserId);

		    servers.add(new ServerViewItem(serverId, name, type, isAdmin,
			    ServerViewItem.avatarDrawableForIndex(i)));
		}

		mainHandler.post(() -> callback.onResult(servers));
	    } catch (final Exception e) {
		postFailure(callback, e.getMessage());
	    }
	});
    }

    /**
     * Creates a virtual server ({@code POST /api/servers}). Any authenticated user may do it and
     * becomes its {@code ADMINISTRATOR}, which is why the returned item says {@code isAdmin = true}.
     */
    public void createServer(final String name, final Callback<ServerViewItem> callback) {
	final String trimmedName = name == null ? "" : name.trim();
	if (trimmedName.isEmpty()) {
	    mainHandler.post(() -> callback.onFailure("Enter a server name"));

	    return;
	}

	executor.execute(() -> {
	    try {
		if (ensureJwt().isEmpty()) {
		    postFailure(callback, "Not signed in");

		    return;
		}

		final JSONObject body = new JSONObject();
		body.put("name", trimmedName);
		body.put("type", SERVER_TYPE_ORGANIZATION);

		final RestResponse response = executePost("/api/servers", body);
		final JSONObject created = response.asJson();

		if (!response.isSuccessful() || created == null) {
		    failRest(response, callback, "Creating the server");

		    return;
		}

		final long serverId = created.optLong("id", -1L);
		if (serverId < 0) {
		    postFailure(callback, "The server did not return the new server id");

		    return;
		}

		final String serverName = created.optString("name", trimmedName);
		final String serverType = created.optString("type", SERVER_TYPE_ORGANIZATION);

		mainHandler.post(() -> callback.onResult(new ServerViewItem(serverId, serverName, serverType,
			true, ServerViewItem.avatarDrawableForIndex((int) serverId))));
	    } catch (final Exception e) {
		postFailure(callback, e.getMessage());
	    }
	});
    }

    /**
     * Renames a virtual server ({@code PUT /api/servers/{id}}). Server-administrator only, so a
     * member reaches the {@code onFailure} branch with a permission message instead of being logged out.
     */
    public void renameServer(final long serverId, final String name, final Callback<Void> callback) {
	final String trimmedName = name == null ? "" : name.trim();
	if (trimmedName.isEmpty()) {
	    mainHandler.post(() -> callback.onFailure("Enter a server name"));

	    return;
	}

	executor.execute(() -> {
	    try {
		if (ensureJwt().isEmpty()) {
		    postFailure(callback, "Not signed in");

		    return;
		}

		final JSONObject body = new JSONObject();
		body.put("name", trimmedName);

		final RestResponse response = executePut(String.format(Locale.US, "/api/servers/%d", serverId), body);
		if (!response.isSuccessful()) {
		    failRest(response, callback, "Renaming the server");

		    return;
		}

		mainHandler.post(() -> callback.onResult(null));
	    } catch (final Exception e) {
		postFailure(callback, e.getMessage());
	    }
	});
    }

    /**
     * Deletes a virtual server ({@code DELETE /api/servers/{id}}), which the server answers 204 with
     * no body - hence the success check looks at the status alone.
     */
    public void deleteServer(final long serverId, final Callback<Void> callback) {
	executor.execute(() -> {
	    if (ensureJwt().isEmpty()) {
		postFailure(callback, "Not signed in");

		return;
	    }

	    final RestResponse response = executeDelete(String.format(Locale.US, "/api/servers/%d", serverId));
	    if (!response.isSuccessful()) {
		failRest(response, callback, "Deleting the server");

		return;
	    }

	    mainHandler.post(() -> callback.onResult(null));
	});
    }

    /**
     * Lists the channels of a virtual server the user is a member of
     * ({@code GET /api/servers/{id}/channels/my}); every room carries the channel key the members
     * agree on, see {@link #defaultChannelSecret(long)}.
     */
    public void fetchChannels(final long serverId, Callback<List<Room>> callback) {
	executor.execute(() -> {
	    try {
		if (ensureJwt().isEmpty()) {
		    postFailure(callback, "Not signed in");

		    return;
		}

		final JSONArray channelsArray = executeGet(String.format(Locale.US, "/api/servers/%d/channels/my", serverId)).asJsonArray();
		if (channelsArray == null) {
		    postFailure(callback, "Failed to fetch channels from server");

		    return;
		}

		List<Room> rooms = new ArrayList<>();
		for (int i = 0; i < channelsArray.length(); i++) {
		    final JSONObject obj = channelsArray.optJSONObject(i);
		    if (obj == null) {
			continue;
		    }

		    final long channelId = obj.optLong("id", -1L);
		    if (channelId < 0) {
			continue;
		    }

		    final String id = String.format(Locale.US, "%d", channelId);
		    final String name = obj.optString("name", "channel-" + id);

		    rooms.add(new Room(id, name, defaultChannelSecret(channelId), false));
		}
		mainHandler.post(() -> callback.onResult(rooms));
	    } catch (Exception e) {
		postFailure(callback, e.getMessage());
	    }
	});
    }

    /**
     * Creates a channel in a virtual server ({@code POST /api/servers/{id}/channels}). The server
     * restricts it to the virtual server's administrators and rejects a duplicate name.
     */
    public void createChannel(final long serverId, final String name, final Callback<Room> callback) {
	final String trimmedName = name == null ? "" : name.trim();
	if (trimmedName.isEmpty()) {
	    mainHandler.post(() -> callback.onFailure("Enter a channel name"));

	    return;
	}

	executor.execute(() -> {
	    try {
		if (ensureJwt().isEmpty()) {
		    postFailure(callback, "Not signed in");

		    return;
		}

		final JSONObject body = new JSONObject();
		body.put("name", trimmedName);

		final RestResponse response = executePost(String.format(Locale.US, "/api/servers/%d/channels", serverId), body);
		final JSONObject created = response.asJson();

		if (!response.isSuccessful() || created == null) {
		    failRest(response, callback, "Creating the channel");

		    return;
		}

		final long channelId = created.optLong("id", -1L);
		if (channelId < 0) {
		    postFailure(callback, "The server did not return the new channel id");

		    return;
		}

		final String channelIdText = String.format(Locale.US, "%d", channelId);
		final String channelName = created.optString("name", trimmedName);

		mainHandler.post(() -> callback.onResult(new Room(channelIdText, channelName,
			defaultChannelSecret(channelId), false)));
	    } catch (final Exception e) {
		postFailure(callback, e.getMessage());
	    }
	});
    }

    /**
     * Renames a channel ({@code PUT /api/channels/{id}}); the server accepts it from the virtual
     * server's administrator only.
     */
    public void renameChannel(final long channelId, final String name, final Callback<Void> callback) {
	final String trimmedName = name == null ? "" : name.trim();
	if (trimmedName.isEmpty()) {
	    mainHandler.post(() -> callback.onFailure("Enter a channel name"));

	    return;
	}

	executor.execute(() -> {
	    try {
		if (ensureJwt().isEmpty()) {
		    postFailure(callback, "Not signed in");

		    return;
		}

		final JSONObject body = new JSONObject();
		body.put("name", trimmedName);

		final RestResponse response = executePut(String.format(Locale.US, "/api/channels/%d", channelId), body);
		if (!response.isSuccessful()) {
		    failRest(response, callback, "Renaming the channel");

		    return;
		}

		mainHandler.post(() -> callback.onResult(null));
	    } catch (final Exception e) {
		postFailure(callback, e.getMessage());
	    }
	});
    }

    /**
     * Deletes a channel ({@code DELETE /api/channels/{id}}), answered with 204 and no body.
     */
    public void deleteChannel(final long channelId, final Callback<Void> callback) {
	executor.execute(() -> {
	    if (ensureJwt().isEmpty()) {
		postFailure(callback, "Not signed in");

		return;
	    }

	    final RestResponse response = executeDelete(String.format(Locale.US, "/api/channels/%d", channelId));
	    if (!response.isSuccessful()) {
		failRest(response, callback, "Deleting the channel");

		return;
	    }

	    mainHandler.post(() -> callback.onResult(null));
	});
    }

    /**
     * Makes sure {@link #jwtToken} holds a token for the deployment address, restoring it from the
     * account store and otherwise signing in with the stored credentials. Call it at the top of
     * every background task: a screen can be opened before the startup {@link #connect()} has
     * finished, and a REST call without a token comes back as 401, which clears the session.
     *
     * @return the token to send, or "" when the user is signed out or the server rejected him
     */
    private String ensureJwt() {
	final String baseUrl = deployBaseUrl();

	if (jwtToken == null || jwtToken.isEmpty()) {
	    final AccountStore accountStore = new AccountStore(context);
	    final String storedToken = accountStore.getJwtToken();

	    // A token only authenticates on the server that signed it, and after a deployment address
	    // change the stored one belongs to a host this build no longer dials.
	    if (storedToken != null && !storedToken.isEmpty()
		    && baseUrl.equalsIgnoreCase(accountStore.getTokenIssuer())) {
		jwtToken = storedToken;
		jwtTokenIssuer = baseUrl;
	    } else if (accountStore.isLoggedIn()) {
		jwtToken = login(accountStore.getUsername(), accountStore.getPassword());
	    }
	}

	return jwtToken == null ? "" : jwtToken;
    }

    /**
     * Id of the signed-in user, cached in {@link AccountStore} after the first read. Must be called
     * on the background executor.
     *
     * @return the user id, or -1 when it could not be read
     */
    private long resolveUserId() {
	final AccountStore accountStore = new AccountStore(context);
	final long cachedUserId = accountStore.getUserId();

	if (cachedUserId > 0) {
	    return cachedUserId;
	}

	final JSONObject me = makeGETRequest("/api/users/me");
	if (me == null) {
	    return -1L;
	}

	final long userId = me.optLong("id", -1L);
	if (userId > 0) {
	    accountStore.saveUserId(userId);
	}

	return userId;
    }

    /**
     * Mirrors the server's {@code isUserAdmin}: the user administers a virtual server when that
     * server's member list contains his id with the {@code ADMINISTRATOR} role. Reading the list is
     * allowed for any authenticated user, so a plain member can learn that he is one.
     */
    private boolean isUserAdminOfServer(final long serverId, final long myUserId) {
	final JSONArray members = executeGet(String.format(Locale.US, "/api/servers/%d/members", serverId)).asJsonArray();
	if (members == null) {
	    return false;
	}

	for (int i = 0; i < members.length(); i++) {
	    final JSONObject member = members.optJSONObject(i);
	    if (member == null || member.optLong("userId", -1L) != myUserId) {
		continue;
	    }

	    return "ADMINISTRATOR".equalsIgnoreCase(member.optString("role", ""));
	}

	return false;
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

	// @string/server_ip is the only source of the address; nothing picked in the UI can override it,
	// because what the UI lists are virtual servers living behind this one address.
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

    /**
     * Normalized {@code https://host:port} base URL of the one physical server, built from
     * {@code @string/server_ip} and {@code @integer/server_port}. Every REST call goes here; the
     * address is not a parameter of anything and cannot be pointed elsewhere at runtime.
     */
    public String deployBaseUrl() {
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
     * Authenticates via the REST API (if needed) and opens the WebSocket to
     * {@link #deployBaseUrl()}. There is no address parameter on purpose: the app talks to the one
     * physical server from {@code @string/server_ip}, and screens only ever select a virtual server
     * or a channel, both of which are data behind that address.
     */
    public void connect() {
	final String httpUrl = deployBaseUrl();
	final String wsUrl = currentServerWSIp;

	// A token signed by another server cannot authenticate here (the address can only change by
	// rebuilding the APK, which leaves the stored token of the previous deployment behind). Dropping
	// it makes the code below log in again instead of being rejected and clearing the session.
	if (!jwtToken.isEmpty() && !httpUrl.equalsIgnoreCase(jwtTokenIssuer)) {
	    Log.d(TAG, "Cached JWT was issued by " + jwtTokenIssuer + ", re-authenticating against " + httpUrl);
	    jwtToken = "";
	    jwtTokenIssuer = "";
	}

	if (webSocketClient.isConnected()) {
	    Log.d(TAG, "Already connected to " + httpUrl);
	    notifyStateChanged(true, httpUrl);
	    return;
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
		jwtToken = login(username, password);
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

	    // Peer public keys are deliberately not fetched here: they belong to a channel, and the
	    // channel the user chats in is only known once joinChannel() picks one. The key-rotation
	    // handler reads /api/channels/{channelId}/keys when it actually needs the members' keys.
	}
	catch (final Exception exception) {
	    Log.e(TAG, exception.toString());
	}
    }

    /**
     * Posts credentials to {@code /api/auth/login} of {@link #deployBaseUrl()} and returns the JWT
     * token, or "" on any failure. There is no address parameter: the only server that can hand out
     * a token this app accepts is the one built in through {@code @string/server_ip}.
     *
     * <p>Must be called off the main thread.
     */
    public String login(String username, String password) {
	lastLoginRejected = false;

	try {
	    final var loginJson = new JSONObject();

	    loginJson.put("username", username);
	    loginJson.put("password", password);

	    final var requestBody = RequestBody.create(loginJson.toString(), MediaType.parse("application/json; charset=utf-8"));
	    final var baseUrl = deployBaseUrl();
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

	// The channel's members are known only now, so their public keys - and with them the ECDH
	// secrets that key-rotation packets are opened with - are established here rather than at
	// login, when no channel had been chosen yet.
	executor.execute(() -> establishChannelPeerKeys(id));
    }

    /**
     * Derives and caches an ECDH secret with every member of {@code channelId}.
     * <p>
     * Without those secrets {@code TYPE_KEY_ROTATION} packets cannot be opened - the handler drops
     * them with "No shared ECDH secret with peer N yet" - so this has to run before the first
     * rotated message of a channel arrives. Peers whose secret is already known are skipped.
     */
    private void establishChannelPeerKeys(final long channelId) {
	try {
	    final var peers = executeGet(String.format(Locale.US, "/api/channels/%d/keys", channelId));
	    if (!peers.isSuccessful()) {
		Log.w(TAG, "No peer keys available for channel " + channelId);
		return;
	    }

	    final var peerKeys = peers.asJsonArray();
	    if (peerKeys == null) {
		Log.w(TAG, "Peer key list for channel " + channelId + " is not a JSON array");
		return;
	    }

	    for (var i = 0; i < peerKeys.length(); ++i) {
		final var peer = peerKeys.optJSONObject(i);
		if (peer == null) {
		    continue;
		}

		final var peerId = peer.optLong("userId", -1L);
		final var peerPublicKey = peer.optString("publicKey", "");
		if (peerId < 0 || peerPublicKey.isEmpty()
			|| cryptoManager.retrieveSecretSymmetricKey(peerId) != null) {
		    // Unknown shape, or a secret already derived while visiting another channel.
		    continue;
		}

		cryptoManager.putNewPeerKeyEntry(peerId,
			cryptoManager.deriveSharedSecret(Base64.decode(peerPublicKey, Base64.NO_WRAP)));

		Log.d(TAG, "Established ECDH secret with peer " + peerId + " for channel " + channelId);
	    }
	} catch (final Exception exception) {
	    Log.w(TAG, "Failed to establish peer keys for channel " + channelId, exception);
	}
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

    /**
     * Outcome of one REST call: the status code plus the body as text. Keeping the status code is
     * the point - a bare {@code null} used to blur 401 (the session is dead, log out) and 403 (the
     * session is fine, the role is not) into the same "it failed".
     */
    private static final class RestResponse {
	private final int statusCode;
	private final String body;

	RestResponse(final int statusCode, final String body) {
	    this.statusCode = statusCode;
	    this.body = body;
	}

	boolean isSuccessful() {
	    return statusCode >= 200 && statusCode < 300;
	}

	int statusCode() {
	    return statusCode;
	}

	JSONObject asJson() {
	    if (body == null || body.isEmpty()) {
		return null;
	    }

	    try {
		return new JSONObject(body);
	    } catch (final Exception exception) {
		Log.w(TAG, "Response body is not a JSON object: " + body);

		return null;
	    }
	}

	JSONArray asJsonArray() {
	    if (body == null || body.isEmpty()) {
		return null;
	    }

	    try {
		return new JSONArray(body);
	    } catch (final Exception exception) {
		Log.w(TAG, "Response body is not a JSON array: " + body);

		return null;
	    }
	}
    }

    /**
     * Runs one authenticated REST call against {@link #deployBaseUrl()} on the calling (background)
     * thread. An unreachable server comes back as status {@code 0}, which is deliberately not
     * treated as an auth problem so a network hiccup cannot clear the session.
     */
    private RestResponse executeRequest(final String method, final String endpoint, final JSONObject body) {
	final RequestBody requestBody = body == null ? null
	    : RequestBody.create(body.toString(), MediaType.parse("application/json; charset=utf-8"));

	final Request request = new Request
	    .Builder()
	    .url(String.format(Locale.US, "%s%s", deployBaseUrl(), endpoint))
	    .addHeader("Authorization", String.format(Locale.US, "Bearer %s", jwtToken))
	    .method(method, requestBody)
	    .build();

	try (final var response = httpClient.newCall(request).execute()) {
	    final String resStr = response.body() != null ? response.body().string() : "";

	    if (!response.isSuccessful()) {
		Log.w(TAG, String.format(Locale.US, "%s %s failed with HTTP %d: %s", method, endpoint,
			response.code(), resStr));
	    }

	    return new RestResponse(response.code(), resStr);
	}
	catch (final Exception exception) {
	    Log.e(TAG, String.format(Locale.US, "%s %s failed", method, endpoint), exception);

	    return new RestResponse(0, "");
	}
    }

    private RestResponse executeGet(final String endpoint) {
	return executeRequest("GET", endpoint, null);
    }

    private RestResponse executePost(final String endpoint, final JSONObject body) {
	return executeRequest("POST", endpoint, body);
    }

    private RestResponse executePut(final String endpoint, final JSONObject body) {
	return executeRequest("PUT", endpoint, body);
    }

    private RestResponse executeDelete(final String endpoint) {
	return executeRequest("DELETE", endpoint, null);
    }

    /**
     * GET returning a JSON object, where 401/403 really does mean "this session is not accepted".
     * Only the endpoint that describes the signed-in account itself uses it; the CRUD endpoints go
     * through {@link #executeGet(String)} so that a 403 stays a permission problem.
     */
    private JSONObject makeGETRequest(final String endpoint) {
	final RestResponse response = executeGet(endpoint);

	if (response.isSuccessful()) {
	    return response.asJson();
	}

	if (response.statusCode() == 401 || response.statusCode() == 403) {
	    handleAuthError(response.statusCode(), String.format(Locale.US, "GET %s was rejected", endpoint));
	}

	return null;
    }

    /**
     * {@link #makeGETRequest(String)} for the collection endpoints, such as a channel's member keys.
     */
    private JSONArray makeGETRequestCollection(final String endpoint) {
	final RestResponse response = executeGet(endpoint);

	if (response.isSuccessful()) {
	    return response.asJsonArray();
	}

	if (response.statusCode() == 401 || response.statusCode() == 403) {
	    handleAuthError(response.statusCode(), String.format(Locale.US, "GET %s was rejected", endpoint));
	}

	return null;
    }

    /**
     * Delivers a failure on the main thread; a request that fails without a message (an unreachable
     * server, for instance) still needs to say something to the caller.
     */
    private <T> void postFailure(final Callback<T> callback, final String error) {
	final String message = error == null || error.isEmpty() ? "The server request failed" : error;

	mainHandler.post(() -> callback.onFailure(message));
    }

    /**
     * Turns a failed CRUD response into a {@link Callback} failure.
     * <p>
     * 401 means the session really is gone, so it goes through {@link #handleAuthError}. 403 on the
     * other hand only says the signed-in user is not the administrator of that virtual server or
     * channel - the server enforces the same rule the UI gates on - so the session must survive it
     * and the user merely gets a permission message.
     *
     * @param action what was being attempted, e.g. "Renaming the server"
     */
    private <T> void failRest(final RestResponse response, final Callback<T> callback, final String action) {
	if (response.statusCode() == 401) {
	    postFailure(callback, action + " failed: your session has expired");
	    handleAuthError(response.statusCode(), action + " was rejected by the server");

	    return;
	}

	if (response.statusCode() == 403) {
	    Log.w(TAG, action + " rejected with 403 - the user is not an administrator, keeping the session");
	    postFailure(callback, ERROR_PERMISSION_DENIED);

	    return;
	}

	postFailure(callback, action + " failed with HTTP " + response.statusCode());
    }

    /**
     * Registers a new user on {@code /api/auth/register} of {@link #deployBaseUrl()} and returns the
     * JWT token, or "" on any failure. Like {@link #login(String, String)} it takes no address.
     *
     * <p>Must be called off the main thread.
     */
    public String register(String username, String password, String publicKey) {
	try {
	    JSONObject regJson = new JSONObject();
	    regJson.put("username", username);
	    regJson.put("password", password);
	    regJson.put("publicKey", (publicKey != null && !publicKey.isEmpty()) ? publicKey : cryptoManager.getPublicKeyBase64());

	    RequestBody regBody = RequestBody.create(
		    regJson.toString(),
		    MediaType.parse("application/json; charset=utf-8")
		    );

	    String baseUrl = deployBaseUrl();
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
			new AccountStore(context).saveJwtToken(token, baseUrl);
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

		    final var peers = makeGETRequestCollection(String.format(Locale.US, "/api/channels/%d/keys", channelId));
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

    private void notifyStateChanged(boolean connected, String serverBaseUrl) {
	mainHandler.post(() -> {
	    for (ConnectionListener l : listeners) {
		l.onConnectionStateChanged(connected, serverBaseUrl);
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
