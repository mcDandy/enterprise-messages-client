package cz.mkdaniel.enterprisemessenger.net;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;
import android.util.Base64;

import java.nio.ByteBuffer;

import org.json.JSONObject;
import org.json.JSONArray;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
     * How many history messages keep their decrypted text, see {@link #historyPlaintext}. One page
     * of chat history is 50 messages, so this covers a screen of scrollback several times over while
     * staying far below what the chat adapter itself holds.
     */
    private static final int HISTORY_CACHE_MAX_ENTRIES = 512;
    /**
     * How many deliveries of one key envelope this device tolerates before it acknowledges the
     * envelope and lets it go, see {@link #answerKeyEnvelopeFailure(long, byte[])}.
     */
    private static final int MAX_KEY_ENVELOPE_ATTEMPTS = 3;
    /**
     * How often one channel may ask its members for missing keys during a session, and how far apart
     * those asks are. See {@link #requestMissingKeys(long, List)}.
     */
    private static final int MAX_KEY_REQUESTS_PER_SESSION = 3;
    private static final long KEY_REQUEST_COOLDOWN_MS = 10 * 60 * 1000L;
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
     * Decrypted text of history messages, keyed by the server's message id.
     * <p>
     * The chat screen polls page 0 every few seconds, and each poll used to walk the whole page
     * through the candidate-key sweep again - fifty messages, each trying every key the channel has
     * ever had, for a page that is nearly always identical to the one before it. A message id is
     * immutable and its ciphertext never changes, so a payload that opened once never has to be
     * opened again.
     * <p>
     * Successes only: a failure is recorded in {@link #historyUndecryptableAtEpoch}, because a key
     * can reach this device later and make a message readable that was not readable before.
     */
    private final LruCache<Long, String> historyPlaintext = new LruCache<>(HISTORY_CACHE_MAX_ENTRIES);
    /**
     * Message ids no key of this device could open, mapped to the
     * {@link ChannelKeyRegistry#keyEpoch()} of that attempt. The verdict is reused only while the
     * epoch has not moved, so adopting a key retries every message this device could not read.
     */
    private final LruCache<Long, Long> historyUndecryptableAtEpoch =
            new LruCache<>(HISTORY_CACHE_MAX_ENTRIES);
    /**
     * Messages this device sent live but that had no server id when they were handed to the UI,
     * oldest first. The {@code CHAT_MESSAGE} frame carries no id, so the position only shows up in
     * the {@code CHANNEL_MEMBER_INFO} that follows, and the two are matched up by reading that
     * cursor against the last position this device knew.
     * <p>
     * Kept only while the id is unknown, which is a fraction of a second in practice. A message
     * whose id never arrives is dropped once it is older than {@link #PENDING_SERVER_ID_TTL},
     * because guessing at it would report the wrong position in a read receipt.
     */
    private final ArrayDeque<UnassignedMessage> awaitingServerIds = new ArrayDeque<>();
    /**
     * Newest message position this device has seen, from history or from a read cursor. It is the
     * baseline the next {@code CHANNEL_MEMBER_INFO} is compared against to tell how many messages
     * arrived since.
     */
    private volatile long highestKnownMessageId = -1L;
    /**
     * Furthest message each <i>other</i> member of the channel has had their client acknowledge,
     * keyed by user id. The server broadcasts one member's cursors to every online member of the
     * channel, and the {@code userId} in the frame names the member the cursors belong to, not the
     * recipient - so a frame about anyone but this device is a delivery confirmation for messages
     * this device sent.
     * <p>
     * Only the maximum across members is kept, because the UI has one delivered state rather than
     * one per member. This device's own id is never stored here: acknowledging a message the
     * device itself wrote would mark it delivered the instant it was sent.
     */
    private final Map<Long, Long> peerDeliveredCursors = new ConcurrentHashMap<>();
    /**
     * How many other members the channel had when the cursors were last listed. The server only
     * reports a member's cursor when it moves, so a peer who has acknowledged nothing is absent
     * from every frame and from the snapshot; this is what tells the UI a count of two out of an
     * unknown total is really two out of five.
     */
    private volatile int peerCount = 0;

    /**
     * A sent message still waiting for its server id, with the instant it was sent so a stale one
     * can be discarded rather than matched to a message it has nothing to do with.
     */
    private static final class UnassignedMessage {
        final Message message;
        final long sentAt;

        UnassignedMessage(final Message message, final long sentAt) {
            this.message = message;
            this.sentAt = sentAt;
        }
    }
    /**
     * Deliveries of a key envelope this device failed to open, counted per envelope fingerprint and
     * only for the lifetime of this process. See {@link #answerKeyEnvelopeFailure(long, byte[])}.
     */
    private final Map<String, Integer> undeliverableKeyEnvelopes = new ConcurrentHashMap<>();
    /**
     * When this device last asked the other members of a channel for keys it is missing, and how often
     * it did during this session. A request is an ordinary message stored in the channel's history, so
     * it is rate-limited and capped: asking repeatedly for a key nobody in the channel holds would only
     * fill the channel with handshake traffic.
     */
    private final Map<Long, Long> keyRequestSentAt = new ConcurrentHashMap<>();
    private final Map<Long, Integer> keyRequestCount = new ConcurrentHashMap<>();
    /**
     * Offers already applied, by fingerprint, so a page of history that is re-read every few seconds
     * does not reopen the same key blob each time.
     */
    private final Set<String> appliedKeyOffers = ConcurrentHashMap.newKeySet();
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
     * Channel the user is currently chatting in; chat is sent to it and received from it.
     * <p>
     * Its key is deliberately not cached next to the id. A key held in a field of its own outlives
     * the channel it belongs to and gets overwritten by whichever adoption happens to arrive last,
     * so a key adopted for the channel on screen could seal a message of the channel selected here -
     * producing a message no member of that channel can open. {@link ChannelKeyRegistry} is the only
     * place a key is read from, always through this id.
     */
    private volatile long activeChannelId = -1L;
    /**
     * Identity public key this device last saw on the server, so {@link #publishIdentityKeyIfStale()}
     * costs one request per session instead of one per call.
     */
    private volatile String publishedPublicKey = "";

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
                        }
                        if (channelId == visibleChannelId) {
                            visibleChannelId = -1L;
                        }
                        channelKeys.forget(channelId);
                    }
                }
            }

            // The server dropped its whole channel set, so none of these message ids will ever be
            // served again: keep no plaintext of them in memory.
            clearHistoryCaches();

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

    /**
     * Lists the members of one channel ({@code GET /api/channels/{id}/members}). Used by the channel
     * menu to offer a member for removal; the ids are what
     * {@link #removeChannelMember(long, long, Callback)} needs.
     */
    public void fetchChannelMembers(final long channelId, final Callback<JSONArray> callback) {
        if (channelId < 0) {
            postFailure(callback, "Invalid channel id");
            return;
        }
        executor.execute(() -> {
            if (ensureJwt().isEmpty()) {
                postFailure(callback, "Not signed in");
                return;
            }
            final RestResponse response = executeGet(String.format(Locale.US, "/api/channels/%d/members", channelId));
            final JSONArray array = response.asJsonArray();
            if (!response.isSuccessful() || array == null) {
                failRest(response, callback, "Fetching channel members");
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
                // Snapshot of the key state: a message written off as unreadable is retried as soon
                // as this device gains a key, and not one poll before.
                final long keyEpoch = channelKeys.keyEpoch();
                final List<UUID> missingRows = new ArrayList<>();
                int reused = 0;
                int unreadable = 0;

                for (int i = 0; i < messages.length(); i++) {
                    final JSONObject item = messages.optJSONObject(i);
                    if (item == null) {
                        continue;
                    }

                    // The server's message id is the cache key: it is immutable and the ciphertext
                    // behind it never changes. An item served without one is opened on the spot and
                    // simply not cached.
                    final long serverMessageId = item.optLong("id", -1L);

                    String text = serverMessageId < 0
                            ? null
                            : historyPlaintext.get(serverMessageId);

                    if (text == null) {
                        final Long unreadableSince = serverMessageId < 0
                                ? null
                                : historyUndecryptableAtEpoch.get(serverMessageId);
                        if (unreadableSince != null && unreadableSince == keyEpoch) {
                            // No key of this device opened it, and the key set has not moved since
                            // that attempt, so sweeping the same candidates cannot end differently.
                            unreadable++;
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
                            if (serverMessageId >= 0) {
                                historyUndecryptableAtEpoch.put(serverMessageId, keyEpoch);
                            }

                            // The server's key row names the rotation this payload needs. A member
                            // that took part in it can map that row to a key and offer it back.
                            final UUID missingRow = parseUuid(item.optString("keyVersion", ""));
                            if (missingRow != null && !missingRows.contains(missingRow)) {
                                missingRows.add(missingRow);
                            }

                            unreadable++;
                            continue;
                        }

                        text = new String(plaintext, StandardCharsets.UTF_8);
                        if (serverMessageId >= 0) {
                            historyPlaintext.put(serverMessageId, text);
                            historyUndecryptableAtEpoch.remove(serverMessageId);
                        }
                    } else {
                        ++reused;
                    }

                    if (KeyRecoveryMessage.isControl(text)) {
                        // Handshake traffic stored as an ordinary message: never shown. An offer for
                        // this device is still consumed, which is what recovers keys that arrived while
                        // it was offline; requests are answered only when they arrive live, so a page
                        // of history cannot make a channel answer itself.
                        applyKeyRecoveryOffer(channelId, text);
                        continue;
                    }

                    final String fallbackId = serverMessageId >= 0
                            ? String.valueOf(serverMessageId)
                            : String.valueOf(System.currentTimeMillis());
                    final String sender = item.optLong("senderId", -1L) == currentUserId
                            ? "You" : "Server User";
                    final String messageId = fallbackId;
                    long timestamp = parseOffsetDateTime(item.optString("sentAt", ""));

                    decrypted.add(new Message(
                        messageId,
                        serverMessageId,
                        text,
                        normalizeSenderName(sender),
                        timestamp,
                        false));
                if (serverMessageId > highestKnownMessageId) {
                    highestKnownMessageId = serverMessageId;
                }
                }

                if (!missingRows.isEmpty()) {
                    requestMissingKeys(channelId, missingRows);
                }

                if (reused > 0 || unreadable > 0) {
                    // One line per page instead of one per candidate key: the chat screen asks for
                    // this page every few seconds.
                    Log.d(TAG, "fetchMessagePage: channel " + channelId + " page " + page + " - "
                            + decrypted.size() + " messages delivered, " + reused
                            + " reused from cache, " + unreadable
                            + " sealed with a key this device does not have");
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
                                                 final UUID serverKeyVersion) {
        if (serverKeyVersion != null) {
            // The message names the server's key row, which is a different namespace from this
            // device's key versions. The pairing was recorded when that rotation's envelope was
            // opened, so the exact key can be picked instead of trying every one the channel has.
            final UUID clientVersion = channelKeys.clientVersionFor(channelId, serverKeyVersion);

            if (clientVersion != null) {
                for (final ChannelKeyRegistry.KeyVersion candidate : channelKeys.decryptCandidates(channelId)) {
                    if (candidate.version().equals(clientVersion)) {
                        try {
                            return cryptoManager.decryptPayload(ciphertext, candidate.key());
                        } catch (final Exception e) {
                            // The right key and a failed tag check can only mean the payload itself.
                            Log.w(TAG, "decryptChatPayloadForVersion: key " + clientVersion
                                    + " of server row " + serverKeyVersion + " did not open the payload of "
                                    + ciphertext.length + " bytes: " + e.getMessage());

                            return null;
                        }
                    }
                }
            }

            // Not a warning: a device that never took part in that rotation legitimately has no
            // pairing, and the sweep below still opens anything it can read.
            Log.v(TAG, "decryptChatPayloadForVersion: no key of channel " + channelId + " is paired with"
                    + " server row " + serverKeyVersion + ", sweeping candidates instead");
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
            }
            if (channelId == visibleChannelId) {
                visibleChannelId = -1L;
            }

            channelKeys.forget(channelId);
            clearHistoryCaches();

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
            try {
                if (ensureJwt().isEmpty()) {
                    postFailure(callback, "Not signed in");
                    return;
                }
                if (!verifyChannelAdmin(channelId, callback)) {
                    return;
                }

                final RestResponse membersResponse = executeGet(
                        String.format(Locale.US, "/api/channels/%d/members", channelId));
                final JSONArray members = membersResponse.asJsonArray();
                if (!membersResponse.isSuccessful() || members == null) {
                    failRest(membersResponse, callback, "Fetching channel members");
                    return;
                }

                final Set<Long> memberIds = new java.util.HashSet<>();
                for (int i = 0; i < members.length(); i++) {
                    JSONObject m = members.optJSONObject(i);
                    if (m != null) {
                        memberIds.add(m.optLong("userId", -1L));
                    }
                }
                memberIds.add(userId);

                final RestResponse keysResponse = executeGet(
                        String.format(Locale.US, "/api/channels/%d/keys", channelId));
                final JSONArray peerKeys = keysResponse.isSuccessful() ? keysResponse.asJsonArray() : null;

                if (peerKeys != null) {
                    for (int i = 0; i < peerKeys.length(); i++) {
                        JSONObject peer = peerKeys.optJSONObject(i);
                        if (peer != null) {
                            long peerId = peer.optLong("userId", -1L);
                            if (memberIds.contains(peerId)) {
                                ensurePeerSecret(peerId, peer.optString("publicKey", ""));
                            }
                        }
                    }
                }
                if (!peerSecrets.knows(userId)) {
                    final RestResponse userKeyResponse = executeGet(
                            String.format(Locale.US, "/api/users/%d/key", userId));
                    final JSONObject userKeyJson = userKeyResponse.isSuccessful() ? userKeyResponse.asJson() : null;
                    if (userKeyJson != null) {
                        ensurePeerSecret(userId, userKeyJson.optString("publicKey", ""));
                    }
                }

                // The key is generated but deliberately not stored yet: only a server that accepted
                // the envelopes may make it this device's key. Rotating first used to leave this
                // device sealing messages with a key no other member ever received, which made the
                // channel unreadable for good after a refused or unreachable request.
                final ChannelKeyRegistry.KeyVersion candidate = channelKeys.newUnstoredKey();

                // This device is one of the recipients of its own envelopes, so the server has to
                // hold this device's current identity key as well before anything is sealed.
                publishIdentityKeyIfStale();

                final JSONArray envelopes = new JSONArray();
                for (long peerId : memberIds) {
                    // Always derived from the peer's current public key, never from the cached
                    // secret: a peer that replaced its identity key pair cannot open an envelope
                    // sealed with the old one, and nothing announces that it did.
                    final byte[] peerSecret = refreshPeerSecret(peerId);
                    if (peerSecret == null) {
                        postFailure(callback, "Could not establish encryption secret for member " + peerId);
                        return;
                    }

                    final var plainBuffer = ByteBuffer.allocate(Long.BYTES + 2 * Long.BYTES + candidate.key().length);
                    plainBuffer.putLong(channelId);
                    plainBuffer.putLong(candidate.version().getMostSignificantBits());
                    plainBuffer.putLong(candidate.version().getLeastSignificantBits());
                    plainBuffer.put(candidate.key());

                    final byte[] ciphertext = cryptoManager.encryptPayload(plainBuffer.array(), peerSecret);
                    final String ciphertextBase64 = Base64.encodeToString(ciphertext, Base64.NO_WRAP);

                    final JSONObject envelope = new JSONObject();
                    envelope.put("userId", peerId);
                    envelope.put("ciphertext", ciphertextBase64);
                    envelopes.put(envelope);
                }

                final RestResponse response = executePostArray(
                        String.format(Locale.US, "/api/channels/%d/members", channelId), envelopes);
                if (!response.isSuccessful()) {
                    failRest(response, callback, "Adding the channel member");
                    return;
                }

                adoptDistributedKey(channelId, candidate);
                mainHandler.post(() -> callback.onResult(null));
            } catch (final Exception e) {
                postFailure(callback, "Could not add channel member: " + e.getMessage());
            }
        });
    }

    public void removeChannelMember(final long channelId, final long userId, final Callback<Void> callback) {
        if (channelId < 0 || userId <= 0) {
            mainHandler.post(() -> callback.onFailure("Invalid channel or user id"));
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

                final RestResponse membersResponse = executeGet(
                        String.format(Locale.US, "/api/channels/%d/members", channelId));
                final JSONArray members = membersResponse.asJsonArray();
                if (!membersResponse.isSuccessful() || members == null) {
                    failRest(membersResponse, callback, "Fetching channel members");
                    return;
                }

                final Set<Long> memberIds = new java.util.HashSet<>();
                for (int i = 0; i < members.length(); i++) {
                    JSONObject m = members.optJSONObject(i);
                    if (m != null) {
                        memberIds.add(m.optLong("userId", -1L));
                    }
                }
                memberIds.remove(userId);

                final RestResponse keysResponse = executeGet(
                        String.format(Locale.US, "/api/channels/%d/keys", channelId));
                final JSONArray peerKeys = keysResponse.isSuccessful() ? keysResponse.asJsonArray() : null;

                if (peerKeys != null) {
                    for (int i = 0; i < peerKeys.length(); i++) {
                        JSONObject peer = peerKeys.optJSONObject(i);
                        if (peer != null) {
                            long peerId = peer.optLong("userId", -1L);
                            if (memberIds.contains(peerId)) {
                                ensurePeerSecret(peerId, peer.optString("publicKey", ""));
                            }
                        }
                    }
                }

                // The key is generated but deliberately not stored yet: only a server that accepted
                // the envelopes may make it this device's key. Rotating first used to leave this
                // device sealing messages with a key no other member ever received, which made the
                // channel unreadable for good after a refused or unreachable request.
                final ChannelKeyRegistry.KeyVersion candidate = channelKeys.newUnstoredKey();

                // This device is one of the recipients of its own envelopes, so the server has to
                // hold this device's current identity key as well before anything is sealed.
                publishIdentityKeyIfStale();

                final JSONArray envelopes = new JSONArray();
                for (long peerId : memberIds) {
                    // Always derived from the peer's current public key, never from the cached
                    // secret: a peer that replaced its identity key pair cannot open an envelope
                    // sealed with the old one, and nothing announces that it did.
                    final byte[] peerSecret = refreshPeerSecret(peerId);
                    if (peerSecret == null) {
                        postFailure(callback, "Could not establish encryption secret for member " + peerId);
                        return;
                    }

                    final var plainBuffer = ByteBuffer.allocate(Long.BYTES + 2 * Long.BYTES + candidate.key().length);
                    plainBuffer.putLong(channelId);
                    plainBuffer.putLong(candidate.version().getMostSignificantBits());
                    plainBuffer.putLong(candidate.version().getLeastSignificantBits());
                    plainBuffer.put(candidate.key());

                    final byte[] ciphertext = cryptoManager.encryptPayload(plainBuffer.array(), peerSecret);
                    final String ciphertextBase64 = Base64.encodeToString(ciphertext, Base64.NO_WRAP);

                    final JSONObject envelope = new JSONObject();
                    envelope.put("userId", peerId);
                    envelope.put("ciphertext", ciphertextBase64);
                    envelopes.put(envelope);
                }

                final RestResponse response = executePostArray(
                        String.format(Locale.US, "/api/channels/%d/members/%d/remove", channelId, userId), envelopes);
                if (!response.isSuccessful()) {
                    failRest(response, callback, "Removing the channel member");
                    return;
                }

                adoptDistributedKey(channelId, candidate);
                mainHandler.post(() -> callback.onResult(null));
            } catch (final Exception e) {
                postFailure(callback, "Could not remove channel member: " + e.getMessage());
            }
        });
    }

    /**
     * Makes the key {@link #addChannelMember} or {@link #removeChannelMember} just handed out this
     * device's key for {@code channelId}. It runs only after the server accepted the envelopes, so a
     * refused request cannot leave this device sealing messages with a key that exists nowhere else.
     * The superseded key moves into the registry's history and stays readable.
     */
    private void adoptDistributedKey(final long channelId, final ChannelKeyRegistry.KeyVersion key) {
        try {
            channelKeys.adoptChannelKey(channelId, key.version(), key.key());

            // Nothing else to update: the key is read back from the registry through the channel id,
            // so an adoption takes effect for the next message of that channel immediately.
            Log.d(TAG, "Adopted the key the server just distributed for channel " + channelId);
        } catch (final Exception exception) {
            Log.e(TAG, "The server accepted the new key of channel " + channelId
                    + " but this device could not store it", exception);
        }
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
     * encryption). The key is only ever the one this device already holds: it arrives from another
     * member as {@code TYPE_UNICAST_ROTATED_KEY}, which the server queues whenever an administrator
     * adds or removes a member. Opening a channel deliberately does <b>not</b> invent a key, because
     * a locally invented key reaches nobody and every message sealed with it would stay unreadable
     * for the rest of the channel.
     */
    public void joinChannel(final String channelId) {
        final long id = parseChannelId(channelId);
        if (id < 0) {
            Log.w(TAG, "joinChannel: cannot parse channel id from '" + channelId + "'");
            return;
        }

        this.activeChannelId = id;

        try {
            final byte[] key = channelKeys.currentKey(id);

            if (key == null) {
                // Not a failure to report here: a channel the administrator just created legitimately
                // has no key until a second member is added, and the user is told when he is actually
                // blocked, in sendChatMessage.
                Log.w(TAG, "Channel " + id + " has no key on this device; nothing here can be read or sent until a member hands one over");
            }

            executor.execute(() -> {
                if (!ensureJwt().isEmpty()) {
                    Log.d(TAG, "Selected channel " + id + "; membership is provided by the server");
                }

                // Before anyone is asked to hand a key over: peers seal envelopes for the public key
                // the server holds, so a stale copy of this device's key leaves this channel
                // permanently unreadable here.
                publishIdentityKeyIfStale();

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

                establishChannelPeerKeys(id);
            });
        } catch (final Exception exception) {
            // No usable KeyStore means no channel key can be persisted, so chat cannot be secured here.
            // The old (publicly recomputable) key is used for reading only; nothing is sent.
            Log.e(TAG, "No channel key available for channel " + id, exception);
            notifyError("Encryption keys are unavailable - open a channel after restarting the app");
        }
    }

    /**
     * Derives and seals the ECDH secret with every member of {@code channelId}.
     * <p>
     * Those secrets are what the channel-key envelopes are sealed with here and in
     * {@link #addChannelMember} / {@link #removeChannelMember}, and what opens an incoming
     * {@code TYPE_UNICAST_ROTATED_KEY}. Peers whose secret is already sealed are skipped.
     * <p>
     * This method deliberately hands out no channel key of its own: the server has no handler for an
     * inbound {@code TYPE_UNICAST_ROTATED_KEY} and drops it, so keys travel only inside the REST
     * envelopes of a membership change.
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

            }
        } catch (final Exception exception) {
            Log.w(TAG, "Failed to establish peer keys for channel " + channelId, exception);
        }
    }

    /**
     * Opens a {@code TYPE_UNICAST_ROTATED_KEY} envelope with the ECDH secret shared with
     * {@code senderUserId} and adopts the channel key inside it. The {@code channelId} and the key
     * version are read from the <i>decrypted</i> payload, so neither can be forged by whoever the
     * server claims sent the packet.
     *
     * @return {@code true} when the queue may move past this envelope - the key inside it is now this
     * device's key, it already was, or the envelope is too malformed for any retry to open it.
     * {@code false} when only a transient reason stopped it, in which case the caller withholds the
     * acknowledgement so that the server keeps the envelope queued instead of deleting the key.
     */
    private boolean adoptUnicastChannelKey(final long senderUserId, final UUID serverKeyVersion,
                                           final byte[] cipher_text) {
        final String fingerprint = envelopeFingerprint(cipher_text);

        try {
            byte[] peerSecret = peerSecrets.secret(senderUserId);
            byte[] plain = null;

            for (int attempt = 0; attempt < 2; attempt++) {
                if (peerSecret == null) {
                    // The server's offline queue replays queued unicasts after a restart, or when the peer
                    // reinstalled/rotated their identity key, requiring re-deriving the secret from the server.
                    final RestResponse senderKeyResponse = executeGet(
                            String.format(Locale.US, "/api/users/%d/key", senderUserId));
                    final JSONObject senderKeyJson = senderKeyResponse.isSuccessful()
                            ? senderKeyResponse.asJson()
                            : null;
                    final byte[] senderKey = senderKeyJson == null
                            ? null
                            : CryptoManager.decodePeerKey(senderKeyJson.optString("publicKey", ""));

                    if (senderKey == null) {
                        // The peer's public key is one REST call away and may come back at any time, so
                        // this is worth another delivery rather than throwing the key away.
                        Log.w(TAG, "No shared ECDH secret with peer " + senderUserId
                                + " and no public key to derive one (envelope " + fingerprint + ")");

                        return false;
                    }

                    peerSecrets.store(senderUserId, cryptoManager.deriveSharedSecret(senderKey));
                    peerSecret = peerSecrets.secret(senderUserId);

                    if (peerSecret == null) {
                        Log.w(TAG, "The secret derived with peer " + senderUserId
                                + " could not be sealed (envelope " + fingerprint + ")");

                        return false;
                    }
                }

                try {
                    plain = cryptoManager.decryptPayload(cipher_text, peerSecret);
                    break;
                } catch (final Exception e) {
                    if (attempt == 0) {
                        Log.w(TAG, "Failed to decrypt unicast from peer " + senderUserId + " with cached secret (peer may have rotated identity key). Retrying with fresh public key from server.");
                        peerSecret = null; // force fetch on next iteration
                    } else {
                        // Either the envelope was sealed for an identity key this device no longer has,
                        // or it is not an envelope at all. A wrong secret is not cleared by retrying,
                        // but the attempt counter still has to see it before it is dropped.
                        Log.w(TAG, "Failed to decrypt unicast from peer " + senderUserId
                                + " even after fetching fresh public key (envelope " + fingerprint
                                + "): " + e.getMessage());

                        return false;
                    }
                }
            }

            if (plain == null || plain.length < Long.BYTES + 2 * Long.BYTES + 32) {
                // Structurally incomplete: no delivery of this packet can open it, so the queue is
                // told to move on rather than stall behind it forever.
                Log.e(TAG, "A key rotation unicast from peer " + senderUserId + " carried an incomplete"
                        + " envelope of " + (plain == null ? 0 : plain.length) + " bytes (envelope "
                        + fingerprint + "), dropping it");

                return true;
            }

            final ByteBuffer plainBuffer = ByteBuffer.wrap(plain);
            final long channelId = plainBuffer.getLong();
            final long versionMsb = plainBuffer.getLong();
            final long versionLsb = plainBuffer.getLong();
            final UUID version = new UUID(versionMsb, versionLsb);
            final byte[] key = new byte[32];
            plainBuffer.get(key);

            // Pair the two namespaces while both are in hand: a history message names the server's
            // key row, and without this record the reader cannot tell which of its keys sealed it.
            try {
                channelKeys.rememberServerVersion(channelId, serverKeyVersion, version);
            } catch (final Exception e) {
                // The mapping is an optimisation and a diagnostic; losing it must not cost the key.
                Log.w(TAG, "Could not record that server row " + serverKeyVersion + " of channel "
                        + channelId + " is key " + version + ": " + e.getMessage());
            }

            final boolean adopted = channelKeys.adoptChannelKey(channelId, version, key);
            if (!adopted) {
                // Already this device's key - its own unicast echoed back, a duplicate delivery, or a
                // version it superseded earlier. Nothing to learn from it, so the queue advances.
                Log.d(TAG, "Key envelope " + fingerprint + " from peer " + senderUserId + " carried key "
                        + version + ", which channel " + channelId + " already knows");

                return true;
            }

            // The adopted key is left in the registry under its own channel and is not remembered
            // here: a unicast for one channel must never become the key another channel seals with.
            undeliverableKeyEnvelopes.remove(fingerprint);
            Log.d(TAG, "Adopted key " + version + " of channel " + channelId + " from peer " + senderUserId);

            return true;
        } catch (final Exception exception) {
            // A KeyStore or REST hiccup is exactly the transient failure a retry exists for.
            Log.w(TAG, "A key rotation unicast from peer " + senderUserId + " (envelope " + fingerprint
                    + ") could not be opened", exception);

            return false;
        }
    }

    /**
     * Answers a key envelope this device could not open. The server deletes a queued packet only when
     * the recipient acknowledges it, so staying silent keeps the envelope at the head of the queue
     * and the key is offered again on the next reconnect instead of being destroyed.
     * <p>
     * Silence is not free: the server sends only the head, so an envelope that never opens would
     * stall every message queued behind it. After a few attempts in one session it is acknowledged
     * and dropped, which costs the key of one rotation and keeps the channel usable.
     */
    private void answerKeyEnvelopeFailure(final long senderUserId, final byte[] envelope) {
        final String fingerprint = envelopeFingerprint(envelope);
        final int attempts = undeliverableKeyEnvelopes.merge(fingerprint, 1, Integer::sum);

        if (attempts < MAX_KEY_ENVELOPE_ATTEMPTS) {
            Log.w(TAG, "Key envelope " + fingerprint + " from peer " + senderUserId
                    + " stayed sealed (attempt " + attempts + " of " + MAX_KEY_ENVELOPE_ATTEMPTS
                    + "); leaving it queued so the server offers it again");

            return;
        }

        undeliverableKeyEnvelopes.remove(fingerprint);
        Log.e(TAG, "Key envelope " + fingerprint + " from peer " + senderUserId + " did not open after "
                + attempts + " deliveries; acknowledging it so the rest of the queue can arrive. Messages"
                + " sealed with that key stay unreadable until a member re-keys the channel");

        sendAcknowledgement();
    }

    /**
     * Short label identifying one key envelope, used only to count its deliveries. A digest of the
     * ciphertext is safe to log and tells two envelopes apart without saying anything about the key.
     */
    private static String envelopeFingerprint(final byte[] envelope) {
        try {
            final byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(envelope);
            final StringBuilder hex = new StringBuilder(16);

            for (int i = 0; i < 8; ++i) {
                hex.append(String.format(Locale.US, "%02x", digest[i]));
            }

            return hex.toString();
        } catch (final Exception e) {
            return Integer.toHexString(Arrays.hashCode(envelope));
        }
    }

    /**
     * Handles a {@link KeyRecoveryMessage} that arrived live. Must run off the WebSocket thread: an
     * answer needs the requester's public key, which is a REST call.
     */
    private void handleKeyRecoveryMessage(final long channelId, final String text) {
        final KeyRecoveryMessage.Request request = KeyRecoveryMessage.parseRequest(text);

        if (request != null) {
            answerKeyRecoveryRequest(channelId, request);
            return;
        }

        applyKeyRecoveryOffer(channelId, text);
    }

    /**
     * Answers a member's request for keys this device holds. The answer is sealed with the ECDH secret
     * shared with the requester rather than with the channel key, so a member who joins later and is
     * not meant to read that history cannot pick the keys up off the channel.
     */
    private void answerKeyRecoveryRequest(final long channelId, final KeyRecoveryMessage.Request request) {
        final long myUserId = new AccountStore(context).getUserId();

        if (myUserId <= 0 || request.requesterUserId() == myUserId || !webSocketClient.isConnected()) {
            return;
        }

        final Set<UUID> seen = new java.util.HashSet<>();
        final List<ChannelKeyRegistry.KeyVersion> held = new ArrayList<>();

        for (final UUID row : request.serverRows()) {
            final UUID clientVersion = channelKeys.clientVersionFor(channelId, row);

            if (clientVersion == null || !seen.add(clientVersion)) {
                continue;
            }

            for (final ChannelKeyRegistry.KeyVersion candidate : channelKeys.decryptCandidates(channelId)) {
                if (candidate.version().equals(clientVersion)) {
                    held.add(candidate);
                }
            }
        }

        if (held.isEmpty()) {
            Log.d(TAG, "Member " + request.requesterUserId() + " asks for keys of channel " + channelId
                    + " that this device does not hold either");

            return;
        }

        final byte[] secret = refreshPeerSecret(request.requesterUserId());

        if (secret == null) {
            Log.w(TAG, "Could not derive a secret with member " + request.requesterUserId()
                    + ", so the keys of channel " + channelId + " could not be offered");

            return;
        }

        try {
            final byte[] sealed = cryptoManager.encryptPayload(KeyRecoveryMessage.packKeys(held), secret);

            sendChatPayload(channelId, KeyRecoveryMessage
                    .encodeOffer(request.requesterUserId(), myUserId, sealed)
                    .getBytes(StandardCharsets.UTF_8));

            Log.i(TAG, "Offered " + held.size() + " key(s) of channel " + channelId + " to member "
                    + request.requesterUserId());
        } catch (final Exception e) {
            Log.w(TAG, "Could not offer the keys of channel " + channelId + ": " + e.getMessage());
        }
    }

    /**
     * Adopts the keys an offer carries, if it is addressed to this device and opens with the secret it
     * shares with the member who sent it. Recovered keys become readable without becoming the current
     * one, so history opens again while new messages keep going out under the shared key.
     */
    private void applyKeyRecoveryOffer(final long channelId, final String text) {
        final KeyRecoveryMessage.Offer offer = KeyRecoveryMessage.parseOffer(text);

        if (offer == null) {
            // A request sitting in the history is not answered: only live requests are, or every poll
            // of every member would answer the same page again.
            return;
        }

        final long myUserId = new AccountStore(context).getUserId();
        final String fingerprint = envelopeFingerprint(text.getBytes(StandardCharsets.UTF_8));

        if (myUserId <= 0 || offer.targetUserId() != myUserId || !appliedKeyOffers.add(fingerprint)) {
            return;
        }

        final byte[] secret = refreshPeerSecret(offer.responderUserId());

        if (secret == null) {
            appliedKeyOffers.remove(fingerprint);

            return;
        }

        try {
            final byte[] blob = cryptoManager.decryptPayload(offer.sealedKeys(), secret);
            int adopted = 0;

            for (final ChannelKeyRegistry.KeyVersion key : KeyRecoveryMessage.unpackKeys(blob)) {
                if (channelKeys.addReadableKey(channelId, key.version(), key.key())) {
                    adopted++;
                }
            }

            Log.i(TAG, "Recovered " + adopted + " key(s) of channel " + channelId + " from member "
                    + offer.responderUserId() + "; unreadable history is retried on the next load");
        } catch (final Exception e) {
            // Let a later delivery of the same offer try again.
            appliedKeyOffers.remove(fingerprint);
            Log.w(TAG, "A key offer for channel " + channelId + " could not be opened: " + e.getMessage());
        }
    }

    /**
     * Asks the other members of {@code channelId} for the keys behind the server's key rows this device
     * could not open. Rate-limited and capped per session: the request is an ordinary message the
     * server stores in the channel, so repeating it forever would fill the channel with handshake.
     */
    private void requestMissingKeys(final long channelId, final List<UUID> rows) {
        final long now = System.currentTimeMillis();
        final Long sentAt = keyRequestSentAt.get(channelId);
        final Integer sentBefore = keyRequestCount.get(channelId);
        final int sent = sentBefore == null ? 0 : sentBefore;

        if (sent >= MAX_KEY_REQUESTS_PER_SESSION
                || (sentAt != null && now - sentAt < KEY_REQUEST_COOLDOWN_MS)
                || !webSocketClient.isConnected()) {
            return;
        }

        final long myUserId = new AccountStore(context).getUserId();

        if (myUserId <= 0) {
            return;
        }

        keyRequestSentAt.put(channelId, now);
        keyRequestCount.put(channelId, sent + 1);

        try {
            sendChatPayload(channelId,
                    KeyRecoveryMessage.encodeRequest(myUserId, rows).getBytes(StandardCharsets.UTF_8));

            Log.i(TAG, "Asked the members of channel " + channelId + " for " + rows.size()
                    + " key(s) this device is missing (attempt " + (sent + 1)
                    + " of " + MAX_KEY_REQUESTS_PER_SESSION + ")");
        } catch (final Exception e) {
            Log.w(TAG, "Could not ask for the missing keys of channel " + channelId + ": " + e.getMessage());
        }
    }

    /**
     * Makes sure the server holds this device's current identity public key, publishing it when the
     * server still carries an older one.
     * <p>
     * The key pair lives in the Android KeyStore and is regenerated whenever that entry is lost - a
     * reset of the encryption identity, cleared app storage, a reinstall on an emulator that keeps
     * the account. The server keeps offering whatever key was sent at registration, so every peer
     * then seals channel-key envelopes for a key pair this device no longer owns. Nothing the
     * recipient can do opens such an envelope - the wrong copy is the sender's - and the channel stays
     * unreadable here forever, which is what "Failed to decrypt unicast from peer N even after
     * fetching fresh public key" means in the log.
     * <p>
     * The server accepts a replacement at {@code POST /api/users/me/keys}; comparing the two keys
     * first keeps the normal case at a single request.
     */
    private void publishIdentityKeyIfStale() {
        try {
            final String localKey = cryptoManager.getPublicKeyBase64();

            if (localKey == null || localKey.isEmpty() || localKey.equals(publishedPublicKey)) {
                return;
            }

            final long myUserId = resolveUserId();
            if (myUserId < 0) {
                return;
            }

            final RestResponse registered = executeGet(
                    String.format(Locale.US, "/api/users/%d/key", myUserId));
            final JSONObject registeredJson = registered.isSuccessful() ? registered.asJson() : null;
            final String serverKey = registeredJson == null
                    ? ""
                    : registeredJson.optString("publicKey", "");

            if (localKey.equals(serverKey)) {
                publishedPublicKey = localKey;

                return;
            }

            final JSONObject body = new JSONObject();
            body.put("publicKey", localKey);

            final RestResponse published = executePost("/api/users/me/keys", body);
            if (!published.isSuccessful()) {
                Log.e(TAG, "Could not publish this device's identity public key: HTTP "
                        + published.statusCode() + ". Peers will keep sealing keys for a key pair"
                        + " this device may no longer own");

                return;
            }

            publishedPublicKey = localKey;

            // Every secret derived with the old private key is now worthless, and keeping it would
            // only produce envelopes nobody can open.
            peerSecrets.forgetAll();

            Log.i(TAG, "Published this device's identity public key; the server had held a different"
                    + " one, so every cached peer secret was dropped");
        } catch (final Exception e) {
            Log.w(TAG, "The identity public key check failed: " + e.getMessage());
        }
    }

    /**
     * Re-derives and re-seals the ECDH secret with {@code peerId} from the public key the server holds
     * for it, overwriting whatever this device cached.
     * <p>
     * A cached secret stays valid only while the peer keeps its identity key pair, and the server
     * gives no signal when a peer replaces it. An envelope sealed with a stale secret is unopenable by
     * its recipient no matter what the recipient does, so the paths that hand keys out always derive
     * from the server's current copy instead of trusting the cache.
     *
     * @return the shared secret, or {@code null} when the peer has no usable public key on the server
     */
    private byte[] refreshPeerSecret(final long peerId) {
        final RestResponse keyResponse = executeGet(
                String.format(Locale.US, "/api/users/%d/key", peerId));
        final JSONObject keyJson = keyResponse.isSuccessful() ? keyResponse.asJson() : null;
        final byte[] decodedPeerKey = keyJson == null
                ? null
                : CryptoManager.decodePeerKey(keyJson.optString("publicKey", ""));

        if (decodedPeerKey == null) {
            Log.w(TAG, "Peer " + peerId + " has no usable public key on the server");

            return null;
        }

        try {
            final byte[] secret = cryptoManager.deriveSharedSecret(decodedPeerKey);
            peerSecrets.store(peerId, secret);

            return secret;
        } catch (final Exception e) {
            Log.w(TAG, "Could not derive a secret with peer " + peerId + ": " + e.getMessage());

            return null;
        }
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

    private RestResponse executePostArray(final String endpoint, final JSONArray body) {
        final RequestBody requestBody = body == null ? null
                : RequestBody.create(body.toString(), MediaType.parse("application/json; charset=utf-8"));

        final Request request = new Request
                .Builder()
                .url(String.format(Locale.US, "%s%s", deployBaseUrl(), endpoint))
                .addHeader("Authorization", String.format(Locale.US, "Bearer %s", jwtToken))
                .method("POST", requestBody)
                .build();

        try (final var response = httpClient.newCall(request).execute()) {
            final String resStr = response.body() != null ? response.body().string() : "";

            if (!response.isSuccessful()) {
                Log.w(TAG, String.format(Locale.US, "POST %s failed with HTTP %d: %s", endpoint,
                        response.code(), resStr));
            }

            return new RestResponse(response.code(), resStr);
        } catch (final Exception exception) {
            Log.e(TAG, String.format(Locale.US, "POST %s failed", endpoint), exception);

            return new RestResponse(0, "");
        }
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

            // The id is read once: the user can select another channel while this runs, and a payload
            // sealed with one channel's key must never be stamped with another channel's id.
            final long channelId = activeChannelId;
            if (channelId < 0 || channelId > 0xffff_ffffL) {
                notifyError("Invalid channel selected");
                return;
            }

            // The key of that channel and of no other, taken from the registry. The legacy id-derived
            // key is deliberately not offered either: it is recomputable by anyone who knows the
            // channel id, including the server.
            if (channelKeys.currentKey(channelId) == null) {
                notifyError("This channel has no key on this device yet - an administrator has to add a member for the key to be distributed");
                return;
            }

            sendChatPayload(channelId, text.getBytes(StandardCharsets.UTF_8));

            Log.d(TAG, "Sent CHAT_MESSAGE to channel " + channelId);
        } catch (final Exception e) {
            Log.e(TAG, "Failed to send chat message packet", e);
            notifyError("Failed to send message: " + e.getMessage());
        }
    }

    /**
     * Seals {@code plaintext} with the current key of {@code channelId} and sends it as a
     * {@code CHAT_MESSAGE}: {@code [4-byte channel id][AES-GCM ciphertext]}. The server stores the
     * bytes and queues them for the other members verbatim, never seeing the plaintext.
     * <p>
     * The key-recovery handshake rides on this same path, because a chat message is the only frame the
     * server delivers to every member of a channel.
     */
    private void sendChatPayload(final long channelId, final byte[] plaintext) throws Exception {
        final byte[] key = channelKeys.currentKey(channelId);

        if (key == null) {
            throw new IllegalStateException("Channel " + channelId + " has no key on this device");
        }

        final var payloadBytes = cryptoManager.encryptPayload(plaintext, key);

        final var messagePayload = ByteBuffer.allocate(Integer.BYTES + payloadBytes.length);
        messagePayload.putInt((int) channelId);
        messagePayload.put(payloadBytes);

        webSocketClient.sendPacketAsync(new Packet(Packet.TYPE_CHAT_MESSAGE, messagePayload.array()));
    }

    /**
     * How long a sent message may wait for the read cursor to name its position. Past this the
     * link to the cursor is too uncertain to guess from, and the message keeps no id.
     */
    private static final long PENDING_SERVER_ID_TTL = 30_000L;

    public void sendReadReceipt(long channelId, long messageId) {
        if (!webSocketClient.isConnected()) {
            return;
        }
        try {
            ByteBuffer payload = ByteBuffer.allocate(Long.BYTES);
            payload.putLong(messageId);
            webSocketClient.sendPacketAsync(new Packet(Packet.TYPE_READ, payload.array()));
            Log.d(TAG, "Sent READ receipt for message " + messageId + " in channel " + channelId);
        } catch (Exception e) {
            Log.e(TAG, "Failed to send read receipt", e);
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
        this.activeChannelId = -1L;
        clearHistoryCaches();
        undeliverableKeyEnvelopes.clear();
        webSocketClient.disconnect();
    }

    /**
     * Drops the decrypted history of the session: the plaintext already opened and the verdicts on
     * messages that stayed sealed. Called when the session ends and when a channel is deleted, so no
     * plaintext of an account that is no longer signed in is left behind in this process.
     */
    private void clearHistoryCaches() {
        historyPlaintext.evictAll();
        historyUndecryptableAtEpoch.evictAll();
    }

    /**
     * Decrypts a broadcast chat payload. A message can arrive before the user opened that
     * channel (or while browsing another one), so the keys of the message's channel, of the selected
     * channel and of the channel on screen are all tried, each current version first. A wrong key
     * only fails the GCM tag check, so trying them in order is safe.
     *
     * @return the plaintext envelope, or {@code null} when no known key opens the payload
     */
    private byte[] decryptChatPayload(final long channelId, final byte[] ciphertext) {
        // Keyed by key material, so a key reachable through several of the ids below is still tried
        // once. ByteBuffer hashes and compares the bytes it wraps without copying them, which keeps
        // this linear now that a channel can carry a long key history.
        final LinkedHashMap<ByteBuffer, byte[]> candidates = new LinkedHashMap<>();

        for (final long chId : new long[]{channelId, activeChannelId, visibleChannelId}) {
            if (chId < 0) {
                continue;
            }

            for (final ChannelKeyRegistry.KeyVersion version : channelKeys.decryptCandidates(chId)) {
                candidates.putIfAbsent(ByteBuffer.wrap(version.key()), version.key());
            }
        }

        // Last resort: the key of the scheme used before channel keys were random, so messages an
        // older build wrote remain readable. Read-only by construction.
        for (final long chId : new long[]{channelId, activeChannelId, visibleChannelId}) {
            final byte[] legacyKey = legacyReadKey(chId);
            if (legacyKey != null) {
                candidates.putIfAbsent(ByteBuffer.wrap(legacyKey), legacyKey);
            }
        }

        int index = 0;
        for (final byte[] key : candidates.values()) {
            try {
                final byte[] plaintext = cryptoManager.decryptPayload(ciphertext, key);
                Log.d(TAG, "decryptChatPayload: opened a payload of channel " + channelId
                        + " with candidate key #" + index + " of " + candidates.size());
                return plaintext;
            } catch (final Exception e) {
                // Missing on all but the right key is the normal case once a channel has a history,
                // so a miss is not worth a line: one unreadable message used to print hundreds.
                index++;
            }
        }

        final StringBuilder held = new StringBuilder();
        for (final ChannelKeyRegistry.KeyVersion version : channelKeys.decryptCandidates(channelId)) {
            if (held.length() > 0) {
                held.append(", ");
            }
            held.append(version.version());
        }

        Log.w(TAG, "decryptChatPayload: no key of channel " + channelId + " opens this payload of "
                + ciphertext.length + " bytes; this device holds " + candidates.size()
                + " candidate keys, versions [" + held + "]");
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

                    // The frame carries no id of its own, so the position the server reports in
                    // the next CHANNEL_MEMBER_INFO is the only one this message will ever have.
                    // Until then it is identified by a random id: two messages can arrive in the
                    // same millisecond, and an id built from the clock would name them alike.
                    final long receivedAt = System.currentTimeMillis();
                    final String messageId = UUID.randomUUID().toString();
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

                    if (KeyRecoveryMessage.isControl(messageText)) {
                        // Handshake traffic that rides on the chat channel: it never reaches the
                        // message list, and answering it may call REST, so it is not done here.
                        executor.execute(() -> handleKeyRecoveryMessage(packetChannelId, messageText));
                        break;
                    }

                    // Only a message of the channel on screen can be matched to a read cursor,
                    // which is a per-member position and therefore only meaningful for the
                    // channel the user is actually looking at.
                    final boolean trackServerId = packetChannelId == visibleChannelId;

                    final Message liveMessage = new Message(
                            messageId,
                            Message.NO_SERVER_ID,
                            messageText,
                            normalizeSenderName(senderName),
                            receivedAt,
                            false);
                    notifyMessageReceived(liveMessage);
                    if (trackServerId) {
                        trackForServerId(liveMessage, receivedAt);
                    }

                    if (!isChannelVisible(visibleChannelId)) {
                        NotificationHelper.showChannelMessageNotification(context, visibleChannelId, null);
                    }

                    break;

                case Packet.TYPE_UNKNOWN:
                    String errorMsg = new String(packet.getPayload(), StandardCharsets.UTF_8);
                    Log.e(TAG, "Server returned error packet: " + errorMsg);
                    notifyError(errorMsg);

                    break;

                case Packet.TYPE_CHANNEL_MEMBER_INFO: {
                    byte[] infoPayload = packet.getPayload();
                    if (infoPayload == null || infoPayload.length < 4 * Long.BYTES) {
                        Log.w(TAG, "Received invalid CHANNEL_MEMBER_INFO payload");
                        break;
                    }
                    ByteBuffer infoBuffer = ByteBuffer.wrap(infoPayload);
                    long channelId = infoBuffer.getLong();
                    long userId = infoBuffer.getLong();
                    long lastDelivered = infoBuffer.getLong();
                    long lastRead = infoBuffer.getLong();

                    Log.d(TAG, "Received CHANNEL_MEMBER_INFO: channel=" + channelId + ", user=" + userId
                            + ", delivered=" + lastDelivered + ", read=" + lastRead);

                    final long myUserId = resolveUserId();
                    Log.d(TAG, "CHANNEL_MEMBER_INFO describes member " + userId
                            + (userId == myUserId ? " (this device)" : " (a peer)"));

                    if (userId == myUserId) {
                        // This device's own row: it says what this device has received, which is
                        // how a message this device wrote gets its id. It says nothing about any
                        // peer, and treating it as one would mark a message delivered the moment
                        // it was sent.
                        if (lastDelivered > highestKnownMessageId) {
                            highestKnownMessageId = lastDelivered;
                        }
                        if (channelId == visibleChannelId) {
                            assignServerIds(lastDelivered);
                        }
                    } else if (channelId == visibleChannelId) {
                        recordPeerDelivery(userId, lastDelivered);
                    }

                    mainHandler.post(() -> {
                        for (ConnectionListener l : listeners) {
                            l.onMemberInfoReceived(channelId, userId, lastDelivered, lastRead);
                        }
                    });
                    break;
                }

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
                    // The header carries the server's own key-row uuid. It is not the version of the
                    // key inside the envelope - the two namespaces are unrelated - but this packet is
                    // the only place they ever appear together, so the pair is worth keeping.
                    final UUID serverKeyVersion =
                            new UUID(wrappedPayload.getLong(), wrappedPayload.getLong());

                    final var ciphertextrcv = new byte[wrappedPayload.remaining()];
                    wrappedPayload.get(ciphertextrcv);

                    executor.execute(() -> {
                        // The acknowledgement is what deletes the envelope from the server's queue, so
                        // it is sent only once the key inside it is safely stored. A failure leaves
                        // the envelope queued for another delivery instead of destroying the key.
                        if (adoptUnicastChannelKey(peerId, serverKeyVersion, ciphertextrcv)) {
                            sendAcknowledgement();
                        } else {
                            answerKeyEnvelopeFailure(peerId, ciphertextrcv);
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
            if (!acknowledgementDeferred && Packet.isQueuedDelivery(type)) {
                sendAcknowledgement();
            }
        }
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

    /**
     * Hands the listeners a copy of {@code message} carrying the position the server assigned it.
     * <p>
     * A live {@code CHAT_MESSAGE} arrives before {@code CHANNEL_MEMBER_INFO} names its position,
     * so it enters the list with no server id and cannot take part in read receipts. The receipt
     * reports the newest position the device knows, so leaving that message anonymous would hold
     * the cursor back for every message behind it. The id is filled in as soon as it is known.
     */
    private void replaceMessage(final Message message) {
        mainHandler.post(() -> {
            for (ConnectionListener l : listeners) {
                l.onMessageReplaced(message);
            }
        });
    }

    /**
     * Remembers a message that reached the UI without a server id, so the next read cursor can
     * supply one. Must run on the socket thread, which is the only writer of the deque.
     */
    private void trackForServerId(final Message message, final long sentAt) {
        synchronized (awaitingServerIds) {
            awaitingServerIds.addLast(new UnassignedMessage(message, sentAt));
        }
    }

    /**
     * Gives the messages that arrived since {@link #highestKnownMessageId} the positions the
     * cursor now reports, and hands the UI a copy of each carrying its own.
     * <p>
     * The cursor is a single high-water mark, so it only proves that the {@code n} positions
     * ending at {@code lastDelivered} are the newest ones, where {@code n} is how many messages
     * are still unnamed. That is an assumption about the server handing out ids one per message
     * with no gaps; it is not something the frame confirms. Were it ever false, the worst the
     * damage reaches is a receipt for a neighbouring message, which is what a cursor already
     * reports for everything behind it.
     */
    private void assignServerIds(final long lastDelivered) {
        final List<Message> resolved = new ArrayList<>();
        synchronized (awaitingServerIds) {
            final long now = System.currentTimeMillis();
            UnassignedMessage oldest;
            while ((oldest = awaitingServerIds.peekFirst()) != null
                    && now - oldest.sentAt > PENDING_SERVER_ID_TTL) {
                awaitingServerIds.pollFirst();
            }
            final int unnamed = awaitingServerIds.size();
            if (unnamed == 0 || lastDelivered - unnamed + 1 <= 0) {
                return;
            }
            long id = lastDelivered - unnamed + 1;
            for (final UnassignedMessage pending : awaitingServerIds) {
                resolved.add(withServerId(pending.message, id++));
            }
            awaitingServerIds.clear();
        }
        for (final Message message : resolved) {
            replaceMessage(message);
        }
    }

    /**
     * Copy of {@code message} carrying {@code serverId}; every other field is carried over, and
     * {@link Message#getId()} is deliberately kept so the UI can find the row to replace.
     */
    private static Message withServerId(final Message message, final long serverId) {
        return new Message(message.getId(), serverId, message.getText(), message.getSender(),
                message.getTimestamp(), message.isPending());
    }

    /**
     * Records that {@code userId}'s client has acknowledged everything up to {@code lastDelivered},
     * and tells the UI so messages at or below it can be marked delivered.
     * <p>
     * Monotonic per member: the server's own update is monotonic, but frames can arrive out of
     * order across a reconnect, and a stale frame must not un-deliver a message already shown as
     * delivered.
     */
    private void recordPeerDelivery(final long userId, final long lastDelivered) {
        final long previous = peerDeliveredCursors.merge(userId, lastDelivered, Math::max);
        if (previous >= lastDelivered) {
            return;
        }
        mainHandler.post(this::notifyPeerDelivery);
    }

    /**
     * Publishes the whole peer cursor map, so the UI can count how many recipients acknowledged
     * any given message. The map is sent rather than a single high-water mark because the number
     * of recipients is what the UI shows: one member having read up to 90 and five members having
     * read up to 90 are different states.
     */
    private void notifyPeerDelivery() {
        if (listeners.isEmpty()) {
            return;
        }
        final Map<Long, Long> snapshot = new HashMap<>(peerDeliveredCursors);
        final int peers = peerCount;
        mainHandler.post(() -> {
            for (ConnectionListener l : listeners) {
                l.onPeerDeliveryProgress(snapshot, peers);
            }
        });
    }

    /**
     * Loads each member's delivered cursor once ({@code GET /api/channels/{id}/members}) so
     * messages already delivered before this device opened the channel are marked as such rather
     * than waiting for a frame that will not come. Live updates arrive afterwards over
     * {@code CHANNEL_MEMBER_INFO}.
     */
    public void fetchPeerDeliveryCursors(final long channelId) {
        if (channelId < 0) {
            return;
        }
        executor.execute(() -> {
            if (ensureJwt().isEmpty()) {
                return;
            }
            final long myUserId = resolveUserId();
            final RestResponse response = executeGet(
                    String.format(Locale.US, "/api/channels/%d/members", channelId));
            final JSONArray members = response.asJsonArray();
            if (!response.isSuccessful() || members == null) {
                // Not worth surfacing: delivery marks are an enhancement, and the live frames
                // still fill them in for anything sent from now on.
                Log.d(TAG, "Could not load member delivery cursors for channel " + channelId);
                return;
            }
            int peers = 0;
            for (int i = 0; i < members.length(); i++) {
                final JSONObject member = members.optJSONObject(i);
                if (member == null) {
                    continue;
                }
                final long memberId = member.optLong("userId", -1L);
                if (memberId < 0 || memberId == myUserId) {
                    continue;
                }
                peers++;
                recordPeerDelivery(memberId, member.optLong("lastDeliveredMessageId", 0L));
            }
            // This listing is the only place the channel's size is knowable: a member who has
            // acknowledged nothing has no cursor to broadcast, so without a total the UI cannot
            // tell one recipient out of two from one recipient out of twenty.
            peerCount = peers;
            mainHandler.post(this::notifyPeerDelivery);
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

        /**
         * Replaces the message of the same {@link Message#getId()} with {@code message}, which
         * carries information the live frame did not have. The default does nothing, so a listener
         * that only shows notifications need not care.
         */
        default void onMessageReplaced(Message message) {
        }

        /**
         * Peer delivery cursors changed. {@code cursors} maps each other member's user id to the
         * highest message id their client has acknowledged, so a message is delivered to that many
         * recipients when its own id is at or below the entry. {@code peerCount} is how many other
         * members the channel has, which is not derivable from {@code cursors} because a member
         * who has acknowledged nothing has no entry. Called on the main thread; the default does
         * nothing.
         */
        default void onPeerDeliveryProgress(Map<Long, Long> cursors, int peerCount) {
        }

        void onError(String errorMessage);

        default void onAuthRequired() {
        }

        default void onMemberInfoReceived(long channelId, long userId, long lastDeliveredMessageId, long lastReadMessageId) {
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
