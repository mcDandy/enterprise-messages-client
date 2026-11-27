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
import cz.mkdaniel.enterprisemessenger.crypto.AppSecrets;
import cz.mkdaniel.enterprisemessenger.crypto.ChannelKeyRegistry;
import cz.mkdaniel.enterprisemessenger.crypto.CryptoManager;
import cz.mkdaniel.enterprisemessenger.crypto.PeerSecretRegistry;
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
 * select a channel through {@link #joinChannel(String)} -> end-to-end encrypted {@code CHAT_MESSAGE}
 * packets.
 * <p>
 * Only the scheme and the {@code /wss} path are code constants; {@link #deployBaseUrl()} is the one
 * place the configured address becomes a URL.
 */
public class ServerConnectionManager implements WebSocketClient.WebSocketClientListener {
    public static final String HTTPS_PROTOCOL = "https";
    public static final String WS_PROTOCOL = "wss";
    /**
     * Binary WebSocket endpoint of the server, see its {@code WebSocketConfig}.
     */
    public static final String WS_ENDPOINT_PATH = "wss";
    /**
     * {@code type} a virtual server is created with. The server accepts {@code ORGANIZATION} and
     * {@code DIRECT} only; the app has no use for the one-to-one {@code DIRECT} servers yet, so
     * every virtual server it creates is an organization.
     */
    public static final String SERVER_TYPE_ORGANIZATION = "ORGANIZATION";
    private static final int INITIAL_RECONNECT_DELAY_MS = 1000;
    private static final int MAX_RECONNECT_DELAY_MS = 1000;
    private static final int PING_INTERVAL_SECONDS = 15;
    private static final String TAG = "ServerConnectionManager";
    /**
     * Reported when the server answers 403 to a CRUD call: the session is fine, the role is not.
     */
    private static final String ERROR_PERMISSION_DENIED = "You do not have permission to do that on this server";
    /**
     * Default host, the value of {@code @string/server_ip}. It is deliberately empty: the resource
     * is the single point of truth for the deployment address and is read through
     * {@link #applyDeploymentDefault} when the singleton is created, so no address may ever be
     * duplicated in code.
     */
    public static String WS_HOST = "";
    /**
     * Default port, the value of {@code @integer/server_port}; read with the host above.
     */
    public static Integer WS_PORT = 0;
    private static volatile ServerConnectionManager instance;
    private final Context context;
    private final CryptoManager cryptoManager;
    /**
     * Per-channel AES keys and the peer ECDH secrets, sealed in the Android KeyStore.
     */
    private final ChannelKeyRegistry channelKeys;
    private final PeerSecretRegistry peerSecrets;
    private final WebSocketClient webSocketClient;
    private final OkHttpClient httpClient;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Set<ConnectionListener> listeners = new CopyOnWriteArraySet<>();
    /**
     * Normalized {@code https://host:port} of the physical server, from {@code @string/server_ip}.
     */
    private final String currentServerHTTPIp;
    /**
     * Normalized {@code wss://host:port/wss} of the same server, see {@link #WS_ENDPOINT_PATH}.
     */
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
    private String lastRegisterError = "";
    private long visibleChannelId;
    /**
     * Symmetric key of the channel the user is currently chatting in, read from
     * {@link ChannelKeyRegistry}. Chat payloads are AES-GCM ciphertext of the raw UTF-8 message
     * text, so the server can store and queue them without being able to read them.
     */
    private volatile byte[] activeChannelKey;
    private volatile long activeChannelId = -1L;

    private ServerConnectionManager(Context context) {
        this.context = context.getApplicationContext();
        this.cryptoManager = CryptoManager.getInstance(context);

        final AppSecrets secrets = AppSecrets.getInstance(this.context);
        this.channelKeys = secrets.channelKeys();
        this.peerSecrets = secrets.peerSecrets();

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
     * Key of the scheme this app used before channel keys were random and distributed: a hash of a
     * constant string plus the channel id, i.e. a value anyone who knows the (sequential, publicly
     * listed) channel id can recompute.
     * <p>
     * It survives as a read-only candidate so messages written by an older build stay readable after
     * the update. It is never used to encrypt anything: doing so would put the message back within
     * reach of the server and of every stranger who can enumerate channel ids.
     */
    private static byte[] legacyReadKey(long channelId) {
        if (channelId < 0) {
            return null;
        }

        try {
            return CryptoManager.sha256(("ems-channel-" + channelId)
                    .getBytes(StandardCharsets.UTF_8));
        } catch (final Exception exception) {
            Log.w(TAG, "Legacy channel key could not be derived for channel " + channelId);

            return null;
        }
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

    /*
     * Virtual-server and channel CRUD against the one physical server. Every method below runs its
     * request on the single background {@link #executor} and delivers the answer on the main
     * thread, so a fragment can call it straight from a click listener.
     */

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

    public String getLastRegisterError() {
        return lastRegisterError;
    }

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
     * What {@code GET /api/users/me} says about the signed-in account: the identity the About-you
     * card shows. Immutable, and every field is what the server reported - nothing here is guessed.
     * <p>
     * {@code userId} is the numeric id used to recognise "me" in member lists; {@code username} is the
     * handle the account signs in with; {@code displayName} is the name it chose to be shown under and
     * is {@code ""} when unset; {@code registeredAt} is the server's ISO-8601 registration instant and
     * is {@code ""} when absent.
     */
    public static final class Profile {
        public final long userId;
        public final String username;
        public final String displayName;
        public final String registeredAt;

        Profile(long userId, String username, String displayName, String registeredAt) {
            this.userId = userId;
            this.username = username == null ? "" : username;
            this.displayName = displayName == null ? "" : displayName;
            this.registeredAt = registeredAt == null ? "" : registeredAt;
        }

        /**
         * The name to show a human: the chosen display name, falling back to the login handle for an
         * account that never set one. The card uses this rather than picking between the two itself.
         */
        public String preferredName() {
            return displayName.isEmpty() ? username : displayName;
        }
    }

    /**
     * Reads the signed-in account's profile ({@code GET /api/users/me}) and caches what it learns in
     * {@link AccountStore}, so the About-you card can render from the cache on the next launch and
     * still show something while this request is in flight or the network is away.
     * <p>
     * Unlike {@link #fetchUserId}, which only needs the id, this keeps the display name and the
     * registration instant the server already returns and that were previously discarded.
     */
    public void fetchProfile(final Callback<Profile> callback) {
        executor.execute(() -> {
            if (ensureJwt().isEmpty()) {
                postFailure(callback, "Not signed in");

                return;
            }

            final AccountStore accountStore = new AccountStore(context);
            final String username = accountStore.getUsername();
            final JSONObject me = makeGETRequest("/api/users/me");

            if (me == null) {
                postFailure(callback, "Could not read your account from the server");

                return;
            }

            // The server sends JSON null for an account with no display name, and optString maps a
            // missing key to the default but a present null to "null", hence the explicit check.
            final long userId = me.optLong("id", -1L);
            final String displayName = me.isNull("displayName") ? "" : me.optString("displayName", "");
            final String registeredAt = me.isNull("createdAt") ? "" : me.optString("createdAt", "");
            final Profile profile = new Profile(userId, username, displayName, registeredAt);

            if (userId > 0) {
                accountStore.saveUserId(userId);
            }
            accountStore.saveProfileDetails(displayName, registeredAt);

            mainHandler.post(() -> callback.onResult(profile));
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

                final RestResponse serversResponse = executeGet("/api/servers");
                final JSONArray serversArray = serversResponse.asJsonArray();
                if (!serversResponse.isSuccessful() || serversArray == null) {
                    failRest(serversResponse, callback, "Fetching virtual servers");

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
        if (trimmedName.length() > 100) {
            mainHandler.post(() -> callback.onFailure("Server name can contain at most 100 characters"));
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
        if (trimmedName.length() > 100) {
            mainHandler.post(() -> callback.onFailure("Server name can contain at most 100 characters"));
            return;
        }

        executor.execute(() -> {
            try {
                if (ensureJwt().isEmpty()) {
                    postFailure(callback, "Not signed in");

                    return;
                }
                if (!verifyServerAdmin(serverId, callback)) {
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
            if (!verifyServerAdmin(serverId, callback)) {
                return;
            }

            final RestResponse channelsResponse = executeGet(String.format(
                    Locale.US, "/api/servers/%d/channels/my", serverId));
            final RestResponse response = executeDelete(String.format(Locale.US, "/api/servers/%d", serverId));
            if (!response.isSuccessful()) {
                failRest(response, callback, "Deleting the server");

                return;
            }

            // The server deleted its channel set; remove only the matching local key material.
            final JSONArray channels = channelsResponse.asJsonArray();
            if (channelsResponse.isSuccessful() && channels != null) {
                for (int i = 0; i < channels.length(); i++) {
                    final JSONObject channel = channels.optJSONObject(i);
                    if (channel == null) {
                        continue;
                    }
                    final long channelId = channel.optLong("id", -1L);
                    if (channelId >= 0) {
                        if (channelId == activeChannelId) {
                            activeChannelId = -1L;
                            activeChannelKey = null;
                        }
                        if (channelId == visibleChannelId) {
                            visibleChannelId = -1L;
                        }
                        channelKeys.forget(channelId);
                    }
                }
            }

            mainHandler.post(() -> callback.onResult(null));
        });
    }

    /**
     * Adds a registered user to a virtual server as a regular member.
     * The server requires {@code ADMINISTRATOR} for this operation and reports 403 otherwise.
     */
    public void addServerMember(final long serverId, final long userId, final Callback<Void> callback) {
        if (serverId < 0 || userId <= 0) {
            mainHandler.post(() -> callback.onFailure("Invalid server or user id"));
            return;
        }
        executor.execute(() -> {
            if (ensureJwt().isEmpty()) {
                postFailure(callback, "Not signed in");

                return;
            }
            if (!verifyServerAdmin(serverId, callback)) {
                return;
            }

            try {
                final JSONObject body = new JSONObject();
                body.put("userId", userId);
                body.put("role", "MEMBER");

                final RestResponse response = executePost(
                        String.format(Locale.US, "/api/servers/%d/members", serverId), body);
                if (!response.isSuccessful()) {
                    failRest(response, callback, "Adding the server member");

                    return;
                }

                mainHandler.post(() -> callback.onResult(null));
            } catch (final Exception e) {
                postFailure(callback, "Could not add server member: " + e.getMessage());
            }
        });
    }

    public void fetchServerMembers(final long serverId, final Callback<JSONArray> callback) {
        if (serverId < 0) {
            postFailure(callback, "Invalid server id");
            return;
        }
        executor.execute(() -> {
            if (ensureJwt().isEmpty()) {
                postFailure(callback, "Not signed in");
                return;
            }
            final RestResponse response = executeGet(String.format(Locale.US, "/api/servers/%d/members", serverId));
            final JSONArray array = response.asJsonArray();
            if (!response.isSuccessful() || array == null) {
                failRest(response, callback, "Fetching server members");
                return;
            }
            mainHandler.post(() -> callback.onResult(array));
        });
    }

    public void removeServerMember(final long serverId, final long userId, final Callback<Void> callback) {
        if (serverId < 0 || userId <= 0) {
            postFailure(callback, "Invalid server or user id");
            return;
        }
        executor.execute(() -> {
            if (ensureJwt().isEmpty()) {
                postFailure(callback, "Not signed in");
                return;
            }
            if (!verifyServerAdmin(serverId, callback)) {
                return;
            }
            final RestResponse response = executeDelete(String.format(Locale.US, "/api/servers/%d/members/%d", serverId, userId));
            if (!response.isSuccessful()) {
                failRest(response, callback, "Removing server member");
                return;
            }
            mainHandler.post(() -> callback.onResult(null));
        });
    }

    public void banUser(final long userId, final Callback<Void> callback) {
        if (userId <= 0) {
            postFailure(callback, "Invalid user id");
            return;
        }
        executor.execute(() -> {
            if (ensureJwt().isEmpty()) {
                postFailure(callback, "Not signed in");
                return;
            }
            final RestResponse response = executePost(String.format(Locale.US, "/api/admin/users/%d/ban", userId), null);
            if (!response.isSuccessful()) {
                failRest(response, callback, "Banning user");
                return;
            }
            mainHandler.post(() -> callback.onResult(null));
        });
    }

    public void unbanUser(final long userId, final Callback<Void> callback) {
        if (userId <= 0) {
            postFailure(callback, "Invalid user id");
            return;
        }
        executor.execute(() -> {
            if (ensureJwt().isEmpty()) {
                postFailure(callback, "Not signed in");
                return;
            }
            final RestResponse response = executePost(String.format(Locale.US, "/api/admin/users/%d/unban", userId), null);
            if (!response.isSuccessful()) {
                failRest(response, callback, "Unbanning user");
                return;
            }
            mainHandler.post(() -> callback.onResult(null));
        });
    }

    /**
     * Lists the channels of a virtual server the user is a member of
     * ({@code GET /api/servers/{id}/channels/my}); every room carries the channel key the members
     * agree on, see {@link ChannelKeyRegistry}.
     */
    public void fetchChannels(final long serverId, Callback<List<Room>> callback) {
        executor.execute(() -> {
            try {
                if (ensureJwt().isEmpty()) {
                    postFailure(callback, "Not signed in");

                    return;
                }

                final RestResponse channelsResponse = executeGet(String.format(
                        Locale.US, "/api/servers/%d/channels/my", serverId));
                final JSONArray channelsArray = channelsResponse.asJsonArray();
                if (!channelsResponse.isSuccessful() || channelsArray == null) {
                    failRest(channelsResponse, callback, "Fetching channels");

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

                    // A room carries no key on purpose: the key lives in ChannelKeyRegistry, sealed, and is
                    // never a value that travels through navigation arguments or a log line.
                    rooms.add(new Room(id, name));
                }
                mainHandler.post(() -> callback.onResult(rooms));
            } catch (Exception e) {
                postFailure(callback, e.getMessage());
            }
        });
    }

    /**
     * Loads one older page of channel history. The REST endpoint paginates newest-first, so the
     * returned messages are reversed before they are delivered to the chat adapter.
     */
    public void fetchMessagePage(final long channelId, final int page, final int size,
                                 final Callback<List<Message>> callback) {
        executor.execute(() -> {
            try {
                if (ensureJwt().isEmpty()) {
                    postFailure(callback, "Not signed in");
                    return;
                }

                final String endpoint = String.format(Locale.US,
                        "/api/channels/%d/messages?page=%d&size=%d&sort=sentAt,desc",
                        channelId, page, size);
                final RestResponse response = executeGet(endpoint);
                final JSONObject result = response.asJson();
                if (!response.isSuccessful() || result == null) {
                    failRest(response, callback, "Fetching messages");
                    return;
                }

                final JSONArray messages = result.optJSONArray("content");
                if (messages == null) {
                    postFailure(callback, "Failed to fetch older messages");
                    return;
                }

                final long currentUserId = resolveUserId();
                if (currentUserId < 0) {
                    postFailure(callback, "Could not identify your account while loading messages");
                    return;
                }

                final List<Message> decrypted = new ArrayList<>();
                for (int i = 0; i < messages.length(); i++) {
                    final JSONObject item = messages.optJSONObject(i);
                    if (item == null) {
                        continue;
                    }

                    final byte[] ciphertext;
                    try {
                        ciphertext = Base64.decode(item.optString("ciphertext", ""), Base64.NO_WRAP);
                    } catch (final Exception e) {
                        continue;
                    }
                    if (ciphertext.length == 0) {
                        continue;
                    }

                    final UUID keyVersion = parseUuid(item.optString("keyVersion", ""));
                    final byte[] plaintext =
                            decryptChatPayloadForVersion(channelId, ciphertext, keyVersion);
                    if (plaintext == null) {
                        continue;
                    }

                    final String fallbackId =
                            String.valueOf(item.optLong("id", System.currentTimeMillis()));
                    final String text = new String(plaintext, StandardCharsets.UTF_8);
                    final String sender = item.optLong("senderId", -1L) == currentUserId
                            ? "You" : "Server User";
                    final String messageId = fallbackId;
                    long timestamp = parseOffsetDateTime(item.optString("sentAt", ""));

                    decrypted.add(new Message(messageId, text, normalizeSenderName(sender), timestamp));
                }

                java.util.Collections.reverse(decrypted);
                mainHandler.post(() -> callback.onResult(decrypted));
            } catch (final Exception e) {
                postFailure(callback, e.getMessage());
            }
        });
    }

    private UUID parseUuid(final String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (final Exception e) {
            return null;
        }
    }

    private long parseOffsetDateTime(final String value) {
        if (value == null || value.isEmpty()) {
            return System.currentTimeMillis();
        }
        try {
            return java.time.OffsetDateTime.parse(value).toInstant().toEpochMilli();
        } catch (final Exception e) {
            return System.currentTimeMillis();
        }
    }

    private String normalizeSenderName(final String senderName) {
        if (senderName == null) {
            return "Server User";
        }

        final String username = new AccountStore(context).getUsername();
        if (username != null && !username.isEmpty() && senderName.equals(username)) {
            return "You";
        }

        return senderName;
    }

    private byte[] decryptChatPayloadForVersion(final long channelId, final byte[] ciphertext,
                                                 final UUID keyVersion) {
        if (keyVersion != null) {
            for (final ChannelKeyRegistry.KeyVersion candidate : channelKeys.decryptCandidates(channelId)) {
                if (candidate.version().equals(keyVersion)) {
                    try {
                        byte[] plaintext = cryptoManager.decryptPayload(ciphertext, candidate.key());
                        Log.d(TAG, "decryptChatPayloadForVersion: Successfully decrypted with key version " + keyVersion);
                        return plaintext;
                    } catch (final Exception e) {
                        Log.w(TAG, "decryptChatPayloadForVersion: Failed to decrypt with key version " + keyVersion + ": " + e.getMessage());
                        return null;
                    }
                }
            }
            Log.w(TAG, "decryptChatPayloadForVersion: Key version " + keyVersion + " not found in candidates for channel " + channelId + ". Triggering peer key re-establishment.");
            executor.execute(() -> establishChannelPeerKeys(channelId, true));
        }
        return decryptChatPayload(channelId, ciphertext);
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
        if (trimmedName.length() > 100) {
            mainHandler.post(() -> callback.onFailure("Channel name can contain at most 100 characters"));
            return;
        }

        executor.execute(() -> {
            try {
                if (ensureJwt().isEmpty()) {
                    postFailure(callback, "Not signed in");

                    return;
                }
                if (!verifyServerAdmin(serverId, callback)) {
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

                mainHandler.post(() -> callback.onResult(new Room(channelIdText, channelName)));
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
        if (trimmedName.length() > 100) {
            mainHandler.post(() -> callback.onFailure("Channel name can contain at most 100 characters"));
            return;
        }

        executor.execute(() -> {
            try {
                if (ensureJwt().isEmpty()) {
                    postFailure(callback, "Not signed in");

                    return;
                }
                if (!verifyChannelAdmin(channelId, callback)) {
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
            if (!verifyChannelAdmin(channelId, callback)) {
                return;
            }

            final RestResponse response = executeDelete(String.format(Locale.US, "/api/channels/%d", channelId));
            if (!response.isSuccessful()) {
                failRest(response, callback, "Deleting the channel");

                return;
            }

            // The channel is gone for good, so is its key: keeping it would only grow the sealed store.
            if (channelId == activeChannelId) {
                this.activeChannelId = -1L;
                this.activeChannelKey = null;
            }
            if (channelId == visibleChannelId) {
                visibleChannelId = -1L;
            }

            channelKeys.forget(channelId);

            mainHandler.post(() -> callback.onResult(null));
        });
    }

    /**
     * Adds a registered user to a channel. The server requires the signed-in user to be that
     * channel's server administrator and adds both channel and server membership as needed.
     */
    public void addChannelMember(final long channelId, final long userId, final Callback<Void> callback) {
        if (channelId < 0 || userId <= 0) {
            mainHandler.post(() -> callback.onFailure("Invalid channel or user id"));
            return;
        }
        executor.execute(() -> {
            if (ensureJwt().isEmpty()) {
                postFailure(callback, "Not signed in");

                return;
            }
            if (!verifyChannelAdmin(channelId, callback)) {
                return;
            }

            try {
                final JSONObject body = new JSONObject();
                body.put("userId", userId);

                final RestResponse response = executePost(
                        String.format(Locale.US, "/api/channels/%d/members", channelId), body);
                if (!response.isSuccessful()) {
                    failRest(response, callback, "Adding the channel member");

                    return;
                }

                mainHandler.post(() -> callback.onResult(null));
            } catch (final Exception e) {
                postFailure(callback, "Could not add channel member: " + e.getMessage());
            }
        });
    }

    /**
     * Updates the display name of the signed-in account.
     */
    public void updateDisplayName(final String displayName, final Callback<Void> callback) {
        final String trimmedName = displayName == null ? "" : displayName.trim();
        if (trimmedName.isEmpty()) {
            mainHandler.post(() -> callback.onFailure("Display name must not be empty"));
            return;
        }

        executor.execute(() -> {
            if (ensureJwt().isEmpty()) {
                postFailure(callback, "Not signed in");
                return;
            }

            try {
                final JSONObject body = new JSONObject();
                body.put("displayName", trimmedName);

                final RestResponse response = executePut("/api/users/me/display-name", body);
                if (!response.isSuccessful()) {
                    failRest(response, callback, "Updating display name");
                    return;
                }

                mainHandler.post(() -> callback.onResult(null));
            } catch (final Exception e) {
                postFailure(callback, "Could not update display name: " + e.getMessage());
            }
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
        final AccountStore accountStore = new AccountStore(context);

        if (jwtToken == null || jwtToken.isEmpty()) {
            final String storedToken = accountStore.getJwtToken();

            // A token only authenticates on the server that signed it, and after a deployment address
            // change the stored one belongs to a host this build no longer dials.
            if (storedToken != null && !storedToken.isEmpty()
                    && baseUrl.equalsIgnoreCase(accountStore.getTokenIssuer())) {
                jwtToken = storedToken;
                jwtTokenIssuer = baseUrl;
            }
        }

        // A token about to expire is exchanged while it still works. Re-logging in is not an option any
        // more: the password is deliberately not stored.
        if (!jwtToken.isEmpty() && baseUrl.equalsIgnoreCase(jwtTokenIssuer)
                && accountStore.shouldRefreshToken()) {
            refreshToken();
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
        final RestResponse response = executeGet(String.format(
                Locale.US, "/api/servers/%d/members", serverId));
        final JSONArray members = response.asJsonArray();
        if (!response.isSuccessful() || members == null) {
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

    private <T> boolean verifyServerAdmin(final long serverId, final Callback<T> callback) {
        if (serverId < 0) {
            postFailure(callback, "Invalid server id");
            return false;
        }

        final long myUserId = resolveUserId();
        if (myUserId < 0) {
            postFailure(callback, "Failed to verify your server role");
            return false;
        }

        if (!isUserAdminOfServer(serverId, myUserId)) {
            postFailure(callback, ERROR_PERMISSION_DENIED);
            return false;
        }

        return true;
    }

    private <T> boolean verifyChannelAdmin(final long channelId, final Callback<T> callback) {
        if (channelId < 0) {
            postFailure(callback, "Invalid channel id");
            return false;
        }

        final RestResponse channelResponse = executeGet(String.format(
                Locale.US, "/api/channels/%d", channelId));
        final JSONObject channel = channelResponse.asJson();
        if (!channelResponse.isSuccessful() || channel == null) {
            failRest(channelResponse, callback, "Checking the channel");
            return false;
        }

        final long serverId = channel.optLong("serverId", -1L);
        return verifyServerAdmin(serverId, callback);
    }

    public void setVisibleChannel(long channelId) {
        visibleChannelId = channelId;
    }

    public boolean isChannelVisible(long channelId) {
        return channelId >= 0 && channelId == visibleChannelId;
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
        this.activeChannelKey = null;
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

            // The password is never stored, so an expired token is exchanged rather than replaced by a
            // re-login. A refresh failure means the session is over and the user has to sign in again.
            if (jwtToken.isEmpty() && accountStore.isLoggedIn()) {
                final String storedToken = accountStore.getJwtToken();

                if (storedToken != null && !storedToken.isEmpty()) {
                    refreshToken();
                }
            }

            if (jwtToken == null || jwtToken.isEmpty()) {
                Log.w(TAG, "No valid JWT token obtained from " + httpUrl + ", aborting WebSocket connection");

                // Without a sealed token there is nothing left to refresh - the password is deliberately
                // not stored - so the session really is over and the user has to sign in again. A refusal of
                // the refresh on an otherwise reachable server says the same thing.
                if (lastLoginRejected || accountStore.getJwtToken().isEmpty()) {
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
        } catch (final Exception exception) {
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
                        new AccountStore(context).saveJwtToken(token, baseUrl, resObj.optLong("expiresAt", 0L));
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
     * Exchanges the stored token for a fresh one at {@code /api/auth/refresh} of
     * {@link #deployBaseUrl()} and returns it, or "" when the refresh was refused.
     * <p>
     * The server's refresh endpoint accepts the current access token and answers with a new one, so
     * no password is needed - which matters, because the app no longer stores one. A refusal is not
     * fatal here; the caller keeps the old token and the next 401 ends the session properly.
     *
     * <p>Must be called off the main thread.
     */
    public String refreshToken() {
        final AccountStore accountStore = new AccountStore(context);
        final String currentToken = !jwtToken.isEmpty() ? jwtToken : accountStore.getJwtToken();

        if (currentToken == null || currentToken.isEmpty()) {
            return "";
        }

        final String baseUrl = deployBaseUrl();

        // A token of another deployment can only be refused; trying it would also leak it to that host.
        if (!baseUrl.equalsIgnoreCase(jwtTokenIssuer.isEmpty() ? accountStore.getTokenIssuer() : jwtTokenIssuer)) {
            return "";
        }

        try {
            final var body = new JSONObject();
            body.put("refreshToken", currentToken);

            final var request = new Request.Builder()
                    .url(baseUrl + "/api/auth/refresh")
                    .post(RequestBody.create(body.toString(), MediaType.parse("application/json; charset=utf-8")))
                    .build();

            try (final var response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    Log.w(TAG, "Token refresh refused with HTTP " + response.code());
                    lastLoginRejected = response.code() == 401 || response.code() == 403;

                    return "";
                }

                final var resObj = new JSONObject(response.body().string());
                final var token = resObj.optString("token", "");

                if (token.isEmpty()) {
                    return "";
                }

                this.jwtToken = token;
                this.jwtTokenIssuer = baseUrl;
                accountStore.saveJwtToken(token, baseUrl, resObj.optLong("expiresAt", 0L));

                Log.d(TAG, "Session token refreshed");

                return token;
            }
        } catch (final Exception exception) {
            Log.w(TAG, "Token refresh request failed: " + exception.getMessage());

            return "";
        }
    }

    /**
     * Selects the channel whose messages are sent and received on the shared WebSocket session.
     * <p>
     * The client needs the channel id (for notifications) and the channel key (for end-to-end
     * encryption). The key is this device's own random key for that channel; if the channel has
     * never been opened before, the key is created now and handed to the other members over their
     * ECDH secrets, see {@link #establishChannelPeerKeys}.
     */
    public void joinChannel(final String channelId) {
        final long id = parseChannelId(channelId);
        if (id < 0) {
            Log.w(TAG, "joinChannel: cannot parse channel id from '" + channelId + "'");
            return;
        }

        this.activeChannelId = id;

        try {
            final ChannelKeyRegistry.GenerationResult key = channelKeys.ensureChannelKey(id);

            this.activeChannelKey = key.key();

            if (key.freshlyCreated()) {
                Log.d(TAG, "Channel " + id + " has a brand new key, distributing it");
            }

            executor.execute(() -> {
                if (!ensureJwt().isEmpty()) {
                    Log.d(TAG, "Selected channel " + id + "; membership is provided by the server");
                }

                final RestResponse membership = executeGet(
                        String.format(Locale.US, "/api/channels/%d/members", id));
                final JSONArray members = membership.asJsonArray();
                if (!membership.isSuccessful() || members == null) {
                    notifyError("Could not verify channel membership");
                    return;
                }

                final long myUserId = resolveUserId();
                boolean isMember = false;
                for (int i = 0; i < members.length(); i++) {
                    final JSONObject member = members.optJSONObject(i);
                    if (member != null && member.optLong("userId", -1L) == myUserId) {
                        isMember = true;
                        break;
                    }
                }
                if (!isMember) {
                    notifyError("You do not have permission to send messages in this channel");
                    return;
                }

                establishChannelPeerKeys(id, key.freshlyCreated());
            });
        } catch (final Exception exception) {
            // No usable KeyStore means no channel key can be persisted, so chat cannot be secured here.
            // The old (publicly recomputable) key is used for reading only; nothing is sent.
            Log.e(TAG, "No channel key available for channel " + id, exception);
            this.activeChannelKey = null;
            notifyError("Encryption keys are unavailable - open a channel after restarting the app");
        }
    }

    /**
     * Derives and seals an ECDH secret with every member of {@code channelId} and hands this
     * device's channel key to each of them.
     * <p>
     * Without those secrets nothing can be unicasted - the rotation handler used to drop packets
     * with "No shared ECDH secret with peer N yet" - so this has to run before the first rotated
     * message of a channel arrives. Peers whose secret is already sealed are skipped.
     * <p>
     * The key is offered when the channel key is freshly created or when a new peer appears,
     * avoiding redundant network unicasts on every join / room open.
     */
    private void establishChannelPeerKeys(final long channelId, final boolean freshlyCreated) {
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
                if (peerId < 0 || peerPublicKey.isEmpty()) {
                    continue;
                }

                final boolean isNewPeer = !peerSecrets.knows(peerId);
                if (isNewPeer) {
                    final byte[] decodedPeerKey = CryptoManager.decodePeerKey(peerPublicKey);
                    if (decodedPeerKey == null) {
                        continue;
                    }

                    peerSecrets.store(peerId, cryptoManager.deriveSharedSecret(decodedPeerKey));

                    Log.d(TAG, "Established an ECDH secret with peer " + peerId);
                }

                // Always offer our key on join/peer check as designed (re-offering is a no-op for receivers who already have it)
                unicastsChannelKeyTo(channelId, peerId);
            }
        } catch (final Exception exception) {
            Log.w(TAG, "Failed to establish peer keys for channel " + channelId, exception);
        }
    }

    /**
     * Wraps this device's channel key in the ECDH secret shared with {@code peerId} and sends it as
     * {@code TYPE_UNICAST_ROTATED_KEY}, which the server relays to that one member (or queues).
     * <p>
     * The payload the server expects is {@code [recipientUserId][channelId][ciphertext]}. The
     * version UUID travels <i>inside</i> the ciphertext rather than in the header the server
     * rewrites: {@code KeyRotationService} stamps the header with the {@code channel_key} row of its
     * own table, which describes a different rotation than ours, so trusting it would adopt keys
     * under a mismatched version.
     */
    private void unicastsChannelKeyTo(final long channelId, final long peerId) {
        unicastsChannelKeyTo(channelId, peerId, activeChannelKey, channelKeys.currentVersion(channelId));
    }

    /**
     * Worker of {@link #unicastsChannelKeyTo(long, long)} with the key given explicitly, so the
     * rotation path can hand out the key it just generated instead of the channel's active one.
     */
    private void unicastsChannelKeyTo(final long channelId, final long peerId,
                                      final byte[] key, final UUID version) {
        try {
            final byte[] peerSecret = peerSecrets.secret(peerId);

            if (peerSecret == null || key == null || version == null) {
                Log.w(TAG, "Cannot hand channel key " + channelId + " to peer " + peerId + ": missing material");

                return;
            }

            final var plainBuffer = ByteBuffer.allocate(Long.BYTES + 2 * Long.BYTES + key.length);
            plainBuffer.putLong(channelId);
            plainBuffer.putLong(version.getMostSignificantBits());
            plainBuffer.putLong(version.getLeastSignificantBits());
            plainBuffer.put(key);

            final byte[] ciphertext = cryptoManager.encryptPayload(plainBuffer.array(), peerSecret);

            final var buffer = ByteBuffer.allocate(2 * Long.BYTES + ciphertext.length);
            buffer.putLong(peerId);
            buffer.putLong(channelId);
            buffer.put(ciphertext);

            webSocketClient.sendPacketAsync(new Packet(Packet.TYPE_UNICAST_ROTATED_KEY, buffer.array()));

            Log.d(TAG, "Handed channel key " + version + " to peer " + peerId);
        } catch (final Exception exception) {
            Log.w(TAG, "Failed to hand channel key of channel " + channelId + " to peer " + peerId, exception);
        }
    }

    /**
     * Answers the server's request to re-key {@code channelId}: a fresh random key replaces the old
     * one (which stays readable through the registry's history) and is handed to every member.
     *
     * <p>Runs on the background executor; it performs REST calls and sealing.
     */
    private void rotateAndDistributeChannelKey(final long channelId) {
        try {
            final ChannelKeyRegistry.GenerationResult rotated = channelKeys.rotateChannelKey(channelId);

            if (channelId == activeChannelId) {
                this.activeChannelKey = rotated.key();
            }

            for (final JSONObject peer : channelMembers(channelId)) {
                final long peerId = peer.optLong("userId", -1L);
                if (peerId < 0) {
                    continue;
                }

                ensurePeerSecret(peerId, peer.optString("publicKey", ""));
                unicastsChannelKeyTo(channelId, peerId, rotated.key(), rotated.version());
            }
        } catch (final Exception exception) {
            Log.e(TAG, "Rotation of channel " + channelId + " failed", exception);
            notifyError("Could not rotate the key of this channel");
        }
    }

    /**
     * Opens a {@code TYPE_UNICAST_ROTATED_KEY} envelope with the ECDH secret shared with
     * {@code senderUserId} and adopts the channel key inside it. The {@code channelId} and the key
     * version are read from the <i>decrypted</i> payload, so neither can be forged by whoever the
     * server claims sent the packet.
     */
    private void adoptUnicastChannelKey(final long senderUserId, final byte[] cipher_text) {
        try {
            byte[] peerSecret = peerSecrets.secret(senderUserId);

            if (peerSecret == null) {
                // The server's offline queue replays queued unicasts after a restart, when the secret has
                // to be re-derived from the sender's public key first.
                final RestResponse senderKeyResponse = executeGet(
                        String.format(Locale.US, "/api/users/%d/key", senderUserId));
                final JSONObject senderKeyJson = senderKeyResponse.isSuccessful()
                        ? senderKeyResponse.asJson()
                        : null;
                final byte[] senderKey = senderKeyJson == null
                        ? null
                        : CryptoManager.decodePeerKey(senderKeyJson.optString("publicKey", ""));

                if (senderKey == null) {
                    Log.w(TAG, "No shared ECDH secret with peer " + senderUserId + " yet, dropping rotated key");

                    return;
                }

                peerSecrets.store(senderUserId, cryptoManager.deriveSharedSecret(senderKey));
                peerSecret = peerSecrets.secret(senderUserId);

                if (peerSecret == null) {
                    return;
                }
            }

            final byte[] plain = cryptoManager.decryptPayload(cipher_text, peerSecret);
            if (plain == null || plain.length < Long.BYTES + 2 * Long.BYTES + 32) {
                Log.w(TAG, "A key rotation unicast carried an incomplete envelope, ignoring it");

                return;
            }

            final ByteBuffer plainBuffer = ByteBuffer.wrap(plain);
            final long channelId = plainBuffer.getLong();
            final long versionMsb = plainBuffer.getLong();
            final long versionLsb = plainBuffer.getLong();
            final UUID version = new UUID(versionMsb, versionLsb);
            final byte[] key = new byte[32];
            plainBuffer.get(key);

            final boolean adopted = channelKeys.adoptChannelKey(channelId, version, key);
            if (!adopted) {
                // Already this device's key (its own unicast echoed back, or a duplicate delivery).
                return;
            }

            if (channelId == activeChannelId || channelId == visibleChannelId) {
                this.activeChannelKey = key;
            }

            Log.d(TAG, "Adopted key " + version + " of channel " + channelId + " from peer " + senderUserId);

            // Convergence: a member who learns the key also offers it to the members that were offline
            // when the sender unicasted. Those that already have it answer with a no-op, so this stops
            // after a single round.
            for (final JSONObject peer : channelMembers(channelId)) {
                final long peerId = peer.optLong("userId", -1L);
                if (peerId < 0 || peerId == senderUserId) {
                    continue;
                }

                ensurePeerSecret(peerId, peer.optString("publicKey", ""));
                unicastsChannelKeyTo(channelId, peerId, key, version);
            }
        } catch (final Exception exception) {
            Log.w(TAG, "A key rotation unicast from peer " + senderUserId + " could not be opened", exception);
        }
    }

    /**
     * Members of {@code channelId} with their public keys
     * ({@code GET /api/channels/{id}/keys}); empty when the server has nothing to offer.
     */
    private List<JSONObject> channelMembers(final long channelId) {
        final List<JSONObject> members = new ArrayList<>();

        try {
            final RestResponse response = executeGet(String.format(
                    Locale.US, "/api/channels/%d/keys", channelId));
            final JSONArray peers = response.asJsonArray();

            if (!response.isSuccessful() || peers == null) {
                Log.w(TAG, "Could not fetch channel keys: HTTP " + response.statusCode());
                return members;
            }

            for (var i = 0; i < peers.length(); ++i) {
                final JSONObject peer = peers.optJSONObject(i);
                if (peer != null) {
                    members.add(peer);
                }
            }
        } catch (final Exception exception) {
            Log.w(TAG, "Member list of channel " + channelId + " could not be read", exception);
        }

        return members;
    }

    /**
     * Derives and seals the ECDH secret with {@code peerId} unless it is sealed already.
     */
    private void ensurePeerSecret(final long peerId, final String peerPublicKey) {
        if (peerSecrets.knows(peerId)) {
            return;
        }

        final byte[] decodedPeerKey = CryptoManager.decodePeerKey(peerPublicKey);
        if (decodedPeerKey == null) {
            return;
        }

        try {
            peerSecrets.store(peerId, cryptoManager.deriveSharedSecret(decodedPeerKey));

            Log.d(TAG, "Established an ECDH secret with peer " + peerId);
        } catch (final Exception exception) {
            Log.w(TAG, "ECDH secret with peer " + peerId + " could not be derived", exception);
        }
    }

    public long getActiveChannelId() {
        return activeChannelId;
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
        } catch (final Exception exception) {
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

        if (response.statusCode() == 404) {
            postFailure(callback, action + " failed: not found");
            return;
        }

        if (response.statusCode() == 409) {
            postFailure(callback, action + " failed: the resource already exists or conflicts");
            return;
        }

        if (response.statusCode() == 400) {
            postFailure(callback, action + " failed: invalid request");
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
        lastRegisterError = "";
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
                String resStr = regResponse.body() != null ? regResponse.body().string() : "";
                JSONObject resObj = null;
                if (!resStr.isEmpty()) {
                    try {
                        resObj = new JSONObject(resStr);
                    } catch (Exception ignored) {
                    }
                }

                if (regResponse.isSuccessful()) {
                    String token = resObj != null ? resObj.optString("token", "") : "";
                    Log.d(TAG, "Registration response, obtained token: " + !token.isEmpty());
                    if (!token.isEmpty()) {
                        this.jwtToken = token;
                        this.jwtTokenIssuer = baseUrl;
                        new AccountStore(context).saveJwtToken(token, baseUrl, resObj.optLong("expiresAt", 0L));
                        return token;
                    }

                    Log.d(TAG, "Registration succeeded without token in response, attempting login fallback");
                    String loginToken = login(username, password);
                    if (!loginToken.isEmpty()) {
                        return loginToken;
                    }
                }

                int statusCode = regResponse.code();
                String serverMsg = "";
                if (resObj != null) {
                    serverMsg = resObj.optString("message", resObj.optString("error", ""));
                }

                Log.w(TAG, "Registration failed with HTTP " + statusCode + ", body: " + resStr + (serverMsg.isEmpty() ? "" : ": " + serverMsg));

                boolean isAlreadyExists = statusCode == 409 ||
                        serverMsg.toLowerCase(Locale.ROOT).contains("exist") ||
                        serverMsg.toLowerCase(Locale.ROOT).contains("taken") ||
                        serverMsg.toLowerCase(Locale.ROOT).contains("already");

                if (isAlreadyExists) {
                    Log.d(TAG, "Username already exists, attempting login fallback with provided credentials");
                    String loginToken = login(username, password);
                    if (!loginToken.isEmpty()) {
                        Log.d(TAG, "Login fallback succeeded for existing username");
                        return loginToken;
                    }
                }

                if (!serverMsg.isEmpty()) {
                    lastRegisterError = serverMsg;
                } else if (isAlreadyExists) {
                    lastRegisterError = "Username already exists. Please check your password or log in.";
                } else {
                    lastRegisterError = "Registration failed (HTTP " + statusCode + (resStr.isEmpty() ? "" : ": " + resStr) + ")";
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Registration request failed: " + e.getMessage());
            lastRegisterError = e.getMessage() != null ? e.getMessage() : "Registration request failed";
        }
        return "";
    }

    /**
     * Encrypts and transmits a chat message packet to the server.
     * <p>
     * The payload is the AES-GCM ciphertext ({@code [12-byte IV][ciphertext+tag]}) of the raw
     * UTF-8 message text. The server treats it as opaque bytes and queues it verbatim for other
     * channel members.
     */
    public void sendChatMessage(String text) {
        try {
            if (!webSocketClient.isConnected()) {
                Log.w(TAG, "Cannot send message: Not connected to WebSocket server");
                notifyError("Not connected to server");

                return;
            }

            // Only this device's sealed channel key can seal an outgoing message. The legacy
            // id-derived key is deliberately not offered here: it is recomputable by anyone who knows
            // the channel id, including the server.
            final byte[] key = activeChannelKey != null
                    ? activeChannelKey
                    : (activeChannelId >= 0 ? channelKeys.currentKey(activeChannelId) : null);

            if (key == null) {
                notifyError("No channel key available - open a channel before sending");
                return;
            }

            this.activeChannelKey = key;

            final var payloadBytes = cryptoManager.encryptPayload(text.getBytes(StandardCharsets.UTF_8), key);
            if (activeChannelId < 0 || activeChannelId > 0xffff_ffffL) {
                notifyError("Invalid channel selected");
                return;
            }

            final var messagePayload = ByteBuffer.allocate(Integer.BYTES + payloadBytes.length);
            messagePayload.putInt((int) activeChannelId);
            messagePayload.put(payloadBytes);

            final var messagePacket = new Packet(Packet.TYPE_CHAT_MESSAGE, messagePayload.array());
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
     * Drops every trace of the session this process still holds: the in-memory token, the active
     * channel key and the socket. {@link #disconnect()} alone is not enough when the device identity
     * is deleted underneath the session (Settings - reset encryption identity): the socket would
     * stay closed but the token would still be handed to the next connect(), and the channel key of
     * an account that no longer exists would still be offered to {@code sendChatMessage}.
     */
    public void clearSessionState() {
        this.jwtToken = "";
        this.jwtTokenIssuer = "";
        this.activeChannelKey = null;
        this.activeChannelId = -1L;
        webSocketClient.disconnect();
    }

    /**
     * Decrypts a broadcast chat payload. A message can arrive before the user opened that
     * channel (or while browsing another one), so the active channel key is tried first and the
     * currently visible channel key second; a wrong key only fails the GCM tag check.
     *
     * @return the plaintext envelope, or {@code null} when no known key opens the payload
     */
    private byte[] decryptChatPayload(final long channelId, final byte[] ciphertext) {
        final java.util.List<byte[]> candidates = new ArrayList<>();

        if (activeChannelKey != null) {
            candidates.add(activeChannelKey);
        }

        for (final long chId : new long[]{channelId, activeChannelId, visibleChannelId}) {
            if (chId < 0) {
                continue;
            }

            for (final ChannelKeyRegistry.KeyVersion version : channelKeys.decryptCandidates(chId)) {
                boolean alreadyPresent = false;
                for (byte[] existing : candidates) {
                    if (java.util.Arrays.equals(existing, version.key())) {
                        alreadyPresent = true;
                        break;
                    }
                }
                if (!alreadyPresent) {
                    candidates.add(version.key());
                }
            }
        }

        // Last resort: the key of the scheme used before channel keys were random, so messages an
        // older build wrote remain readable. Read-only by construction.
        for (final long chId : new long[]{channelId, activeChannelId, visibleChannelId}) {
            final byte[] legacyKey = legacyReadKey(chId);
            if (legacyKey != null) {
                boolean alreadyPresent = false;
                for (byte[] existing : candidates) {
                    if (java.util.Arrays.equals(existing, legacyKey)) {
                        alreadyPresent = true;
                        break;
                    }
                }
                if (!alreadyPresent) {
                    candidates.add(legacyKey);
                }
            }
        }

        int index = 0;
        for (final byte[] key : candidates) {
            try {
                byte[] plaintext = cryptoManager.decryptPayload(ciphertext, key);
                Log.d(TAG, "decryptChatPayload: Successfully decrypted message for channel " + channelId + " using candidate key #" + index);
                return plaintext;
            } catch (final Exception e) {
                Log.d(TAG, "decryptChatPayload: candidate key #" + index + " failed: " + e.getMessage());
            }
            index++;
        }

        Log.w(TAG, "decryptChatPayload: Failed to decrypt message for channel " + channelId + " with " + candidates.size() + " candidate keys");
        return null;
    }

    @Override
    public void onConnected(String url) {
        Log.d(TAG, "WebSocket connected successfully: " + url);

        notifyStateChanged(true, currentServerHTTPIp);
    }

    /**
     * encrypt the new key rotation with shared ECDH key
     */
    @Override
    public void onPacketReceived(final Packet packet) {
        final byte type = packet.getType();
        boolean acknowledgementDeferred = false;
        try {
            final var wrappedPayload = ByteBuffer.wrap(packet.getPayload());

            Log.d(TAG, "Incoming packet type: 0x" + Integer.toHexString(type) + ", payload size: " + packet.getPayload().length);

            // ACK packets confirm delivery and must never generate another ACK. Acknowleding an ACK
            // would make the peer send it back repeatedly.
            if (type == Packet.TYPE_ACK) {
                Log.d(TAG, "Received acknowledgement for packet type 0x"
                        + (packet.getPayload().length > 0
                        ? Integer.toHexString(packet.getPayload()[0] & 0xff)
                        : "unknown"));
                return;
            }

            switch (type) {
                case Packet.TYPE_CHAT_MESSAGE:
                    // Payload is [4-byte channel id][AES-GCM ciphertext].
                    byte[] payload = packet.getPayload();
                    if (payload == null || payload.length <= Integer.BYTES) {
                        Log.w(TAG, "Received empty CHAT_MESSAGE payload");
                        break;
                    }

                    final long receivedAt = System.currentTimeMillis();
                    String messageId = String.valueOf(receivedAt);
                    final String senderName = "Server User";

                    final ByteBuffer chatPayload = ByteBuffer.wrap(payload);
                    final long packetChannelId = chatPayload.getInt() & 0xffff_ffffL;
                    if (packetChannelId != activeChannelId && packetChannelId != visibleChannelId) {
                        Log.d(TAG, "Ignoring CHAT_MESSAGE for channel " + packetChannelId
                                + " while active=" + activeChannelId + ", visible=" + visibleChannelId);
                        break;
                    }
                    final byte[] ciphertext = new byte[chatPayload.remaining()];
                    chatPayload.get(ciphertext);

                    byte[] plaintext = decryptChatPayload(packetChannelId, ciphertext);
                    if (plaintext == null) {
                        Log.w(TAG, "CHAT_MESSAGE could not be decrypted with the active channel key");
                        break;
                    }

                    final String messageText = new String(plaintext, StandardCharsets.UTF_8);

                    notifyMessageReceived(new Message(
                            messageId,
                            messageText,
                            normalizeSenderName(senderName),
                            receivedAt));

                    if (!isChannelVisible(visibleChannelId)) {
                        NotificationHelper.showChannelMessageNotification(context, visibleChannelId, null);
                    }

                    break;

                case Packet.TYPE_UNKNOWN:
                    String errorMsg = new String(packet.getPayload(), StandardCharsets.UTF_8);
                    Log.e(TAG, "Server returned error packet: " + errorMsg);
                    notifyError(errorMsg);

                    break;

                case Packet.TYPE_KEY_ROTATION: {
                    Log.d(TAG, "Key rotation initiated by the server");

                    // Payload is [version MSB][version LSB][channelId]; the type byte is not part of it.
                    var offset = 0;

                    if (wrappedPayload.remaining() < 3 * Long.BYTES) {
                        Log.e(TAG, "ROTATE_KEY payload too short: " + wrappedPayload.remaining() + " bytes");
                        break;
                    }

                    final var serverVersion = new UUID(wrappedPayload.getLong(offset),
                            wrappedPayload.getLong(offset + Long.BYTES));

                    offset += 2 * Long.BYTES;

                    final var channelId = wrappedPayload.getLong(offset);

                    Log.d(TAG, "Server asked for a key rotation of channel " + channelId
                            + " under its version " + serverVersion);

                    // The server's version identifies a row of its own channel_key table and says nothing
                    // about the key we are about to invent, so it is logged and ignored; the version that
                    // travels to the other members is ours, inside the encrypted envelope.
                    executor.execute(() -> {
                        try {
                            rotateAndDistributeChannelKey(channelId);
                        } finally {
                            sendAcknowledgement();
                        }
                    });
                    acknowledgementDeferred = true;
                }

                break;

                case Packet.TYPE_UNICAST_ROTATED_KEY: {
                    Log.d(TAG, "Key rotation unicast received from the server");

                    // Header is [senderUserId][version MSB][version LSB] followed by the ciphertext.
                    // The server's offline queue replays queued unicasts without that header, so the
                    // payload must be long enough before any of it is read as a long.
                    if (wrappedPayload.remaining() < 3 * Long.BYTES) {
                        Log.w(TAG, "UNICAST_ROTATED_KEY payload has no rotation header (" + wrappedPayload.remaining() + " bytes), ignoring");
                        break;
                    }

                    final var peerId = wrappedPayload.getLong();
                    wrappedPayload.getLong(); // skip server version MSB
                    wrappedPayload.getLong(); // skip server version LSB

                    final var ciphertextrcv = new byte[wrappedPayload.remaining()];
                    wrappedPayload.get(ciphertextrcv);

                    executor.execute(() -> {
                        try {
                            adoptUnicastChannelKey(peerId, ciphertextrcv);
                        } finally {
                            sendAcknowledgement();
                        }
                    });
                    acknowledgementDeferred = true;
                }

                break;

                default:
                    Log.d(TAG, "Unhandled packet type: 0x" + Integer.toHexString(type));

                    break;
            }

        } catch (final Exception exception) {
            Log.e(TAG, exception.toString());
        } finally {
            if (!acknowledgementDeferred && isQueuedDeliveryPacket(type)) {
                sendAcknowledgement();
            }
        }
    }

    private boolean isQueuedDeliveryPacket(final byte type) {
        return type == Packet.TYPE_CHAT_MESSAGE
                || type == Packet.TYPE_KEY_ROTATION
                || type == Packet.TYPE_UNICAST_ROTATED_KEY;
    }

    private void sendAcknowledgement() {
        webSocketClient.sendPacketAsync(new Packet(Packet.TYPE_ACK, new byte[0]));
    }

    @Override
    public void onDisconnected() {
        Log.d(TAG, "WebSocket disconnected from " + currentServerHTTPIp);
        notifyStateChanged(false, currentServerHTTPIp);
    }

    /**
     * A close the peer announced with a code. A server error (1011) means the peer killed the session
     * while handling it - typically a moment after the handshake - and redialling immediately
     * reproduces exactly that, so the reason is reported instead of the generic unreachable message.
     */
    @Override
    public void onDisconnected(final int code, final String reason) {
        Log.d(TAG, "WebSocket closed by the peer with code " + code
                + (reason == null || reason.isEmpty() ? "" : " (" + reason + ")"));

        if (code == WebSocketClient.CLOSE_CODE_SERVER_ERROR) {
            notifyError(context.getString(cz.mkdaniel.enterprisemessenger.R.string.error_connection_closed, code));
        }

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

    public interface ConnectionListener {
        /**
         * @param serverBaseUrl {@link #deployBaseUrl()} of the physical server, i.e. which host the
         *                      state change happened on. It is never an address the user picked.
         */
        void onConnectionStateChanged(boolean connected, String serverBaseUrl);

        void onMessageReceived(Message message);

        void onError(String errorMessage);

        default void onAuthRequired() {
        }
    }

    public interface Callback<T> {
        void onResult(T result);

        void onFailure(String error);
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
}
