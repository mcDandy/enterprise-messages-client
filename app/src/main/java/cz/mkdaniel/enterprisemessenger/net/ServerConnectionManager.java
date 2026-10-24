package cz.mkdaniel.enterprisemessenger.net;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;


import org.json.JSONObject;
import org.json.JSONArray;

import java.nio.charset.StandardCharsets;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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
 * Flow: REST login/register -> FCM token upload -> WebSocket {@code /ws?token=JWT} -> CHANNEL_JOIN ->
 * end-to-end encrypted CHAT_MESSAGE_ENC packets.
 */
public class ServerConnectionManager implements WebSocketClient.WebSocketClientListener {
    public static final String HTTPS_PROTOCOL = "https";
    public static final String WS_PROTOCOL = "wss";
    public static final String WS_HOST = "172.18.0.1";
    public static final Integer WS_PORT = 32779;
    public static final String WS_ENDPOINT_PATH = "wss";

    private static final String TAG = "ServerConnectionManager";

    private static volatile ServerConnectionManager instance;

    private final CryptoManager cryptoManager;
    private final WebSocketClient webSocketClient;
    private final OkHttpClient httpClient;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Set<ConnectionListener> listeners = new CopyOnWriteArraySet<>();
    private final String currentServerHTTPIp = String.format("%s://%s:%d", HTTPS_PROTOCOL, WS_HOST, WS_PORT);
    private final String currentServerWSIp = String.format("%s://%s:%d/%s", WS_PROTOCOL, WS_HOST, WS_PORT, WS_ENDPOINT_PATH);

    private String currentRoomId = "";
    private String jwtToken = "";
    private long visibleChannelId;

    public interface ConnectionListener {
	void onConnectionStateChanged(boolean connected, String serverIp);
	void onMessageReceived(Message message);
	void onError(String errorMessage);
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

    private ServerConnectionManager(Context context) {
	this.cryptoManager = CryptoManager.getInstance(context);
	this.webSocketClient = new WebSocketClient(cryptoManager, currentServerWSIp);
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
	return currentServerHTTPIp;
    }

    /**
     * Authenticates via REST API (if needed) and connects to remote WebSocket server.
     */
    public void connectToServer(String serverIp) {
	if (webSocketClient.isConnected() && serverIp.equalsIgnoreCase(currentServerHTTPIp)) {
	    Log.d(TAG, "Already connected to " + serverIp);
	    notifyStateChanged(true, serverIp);

	    return;
	}

	Log.d(TAG, "Initiating authentication & connection to: " + currentServerHTTPIp);
	executor.execute(() -> fetchJwtTokenAndConnect(currentServerHTTPIp));
    }

    private void fetchJwtTokenAndConnect(String serverIp) {
	try {
	final var username = "android_user";
	final var password = "GuestPassword123";
	var pubKey = cryptoManager.getPublicKeyBase64();

	if (pubKey == null) pubKey = "dummyPublicKeyBase64StringForE2EE";

	jwtToken = login("", username, password);

	if (jwtToken.isEmpty()) {
	    jwtToken = register("", username, password, pubKey);
	}

	if (jwtToken.isEmpty()) {
	    Log.w(TAG, "No JWT token obtained from " + currentServerHTTPIp + ", server will reject unauthenticated packets");
	}

	webSocketClient.connectAsync(serverIp, jwtToken);

	Log.d(TAG, "retrieving servers");
//makePOSTRequest("/api/servers", new JSONObject().put("name", "test").put("type", "ORGANIZATION"));
	final var servers = makeGETRequestCollection("/api/servers");

	Log.d(TAG, servers.toString());
//makePOSTRequest("/api/servers/1/channels", new JSONObject().put("name", "test"));

	final var channels = makeGETRequestCollection("/api/servers/1/channels");

	Log.d(TAG, channels.toString());

//makePOSTRequest("/api/servers/1/channels/1/members", new JSONObject().put("username", "android_user"));
	final var memberships = makeGETRequestCollection("/api/servers/1/channels/1/members");

	Log.d(TAG, memberships.toString());

	for (var i = 0; i < memberships.length(); ++i) {
	    Log.d(TAG, makeGETRequest("/api/users/" + memberships.getJSONObject(i).get("userId") +  "/key").toString());
	
	    // update key map/store
	}
	}
	catch (final Exception exception) {
	    Log.e(TAG, exception.toString());
	}
    }

    /**
     * Posts credentials to {@code /api/auth/login} and returns the JWT token, or "" on any failure.
     */
    private String login(String httpUrl, String username, String password) {
	try {
	    final var loginJson = new JSONObject();

	    loginJson.put("username", username);
	    loginJson.put("password", password);

	    final var resObj = makePOSTRequest("/api/auth/login", loginJson);
	    final var token = resObj.optString("token", "");

	    Log.d(TAG, "Authentication successful, obtained JWT token");

	    return token;
	} catch (Exception e) {
	    Log.w(TAG, "Login request failed: " + e.getMessage());
	
	    return "";
	}
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

		return null;
	    }
	    catch (final Exception exception) {
		Log.e(TAG, exception.toString());
		exception.printStackTrace();

		return null;
	    }
    }

    private JSONArray makeGETRequestCollection(final String endpoint) {
	    final var loginRequest = new Request
		.Builder()
		.url(String.format("%s%s", currentServerHTTPIp, endpoint))
		.addHeader("Authorization", String.format("Bearer %s", jwtToken))
		.get()
		.build();

	    try (final var response = httpClient.newCall(loginRequest).execute()) {
		Log.w(TAG, String.format("GET %s%s", currentServerHTTPIp, endpoint));

		if (response.isSuccessful() && response.body() != null) {
		    final var resStr = response.body().string();

		    return new JSONArray(resStr);
		}

		Log.w(TAG, String.format("GET failed with HTTP status code: %s, and response: %s", response.code(), response));

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
		.post(requestBody)
		.build();

	    try (final var response = httpClient.newCall(loginRequest).execute()) {
		if (response.isSuccessful() && response.body() != null) {
		    final var resStr = response.body().string();
		    final var resObj = new JSONObject(resStr);
		    
		    return resObj;
		}
		
		Log.w(TAG, "Login failed with HTTP " + response.code());
		return null;
	    }
	    catch (final Exception exception) {
		Log.e(TAG, "Login failed with HTTP ");

		return null;
	    }
    }

    private JSONObject makeDELETERequest(final String endpoint) {
	    final var loginRequest = new Request
		.Builder()
		.url(String.format("%s%s", currentServerHTTPIp, endpoint))
		.addHeader("Authorization", String.format("Bearer %s", jwtToken))
		.build();

	    try (final var response = httpClient.newCall(loginRequest).execute()) {
		if (response.isSuccessful() && response.body() != null) {
		    final var resStr = response.body().string();
		    final var resObj = new JSONObject(resStr);
		    
		    return resObj;
		}

		Log.w(TAG, "Login failed with HTTP " + response.code());

		return null;
	    }
	    catch (final Exception exception) {
		Log.e(TAG, "Login failed with HTTP ");

		return null;
	    }
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
		    String token = resObj.optString("token", "");
		    Log.d(TAG, "Registration response, obtained token: " + !token.isEmpty());
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
     * Send REST request to join channel with the supplied ID.
     */
    public void joinChannel(final String iD) {
	try {
	    /*this.currentRoomId = roomId;

	    if (!webSocketClient.isConnected()) {
		Log.w(TAG, "Cannot join room " + roomId + ": Not connected to server");
		return;
	    }

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

	    Packet joinPacket = new Packet();
	    webSocketClient.sendPacketAsync(joinPacket);
	    Log.d(TAG, "Sent ROOM_JOIN for channel: " + channelLongId + " (" + roomId + ")");*/
	} catch (Exception e) {
	    Log.e(TAG, "Error sending ROOM_JOIN packet", e);
	}
    }

    /**
     * Encrypts and transmits a chat message packet to the server.
     */
    public void sendChatMessage(String text, String senderUsername, byte[] encryptionKey) {
	try {
	    if (!webSocketClient.isConnected()) {
		Log.w(TAG, "Cannot send message: Not connected to WebSocket server");
		notifyError("Not connected to server");

		return;
	    }

//	    webSocketClient.setChannelEncryptionKey(encryptionKey);
	    byte[] textBytes = text.getBytes(StandardCharsets.UTF_8);

	    Packet messagePacket = new Packet(Packet.TYPE_CHAT_MESSAGE, textBytes);
	    webSocketClient.sendPacketAsync(messagePacket);

	    Log.d(TAG, "Sent CHAT_MESSAGE (" + textBytes.length + " bytes)");
	} catch (final Exception e) {
	    Log.e(TAG, "Failed to send chat message packet", e);
	    notifyError("Failed to send message: " + e.getMessage());
	}
    }

    public void disconnect() {
	webSocketClient.disconnect();
    }

    @Override
    public void onConnected(String url) {
	Log.d(TAG, "WebSocket connected successfully: " + url);
	notifyStateChanged(true, currentServerHTTPIp);

	/*if (!jwtToken.isEmpty()) {
	    try {
		Packet handshakePacket = new Packet(Packet.TYPE_UNKNOWN, jwtToken.getBytes(StandardCharsets.UTF_8));
		webSocketClient.sendPacketAsync(handshakePacket);
		Log.d(TAG, "Sent HANDSHAKE packet with JWT token");
	    } catch (Exception e) {
		Log.e(TAG, "Error sending HANDSHAKE", e);
	    }
	}

	try {
	    if (cryptoManager.getPublicKey() != null) {
		byte[] pubKeyBytes = cryptoManager.getPublicKey().getEncoded();
		Packet keyExchangePacket = new Packet();
		webSocketClient.sendPacketAsync(keyExchangePacket);
		Log.d(TAG, "Sent KEY_EXCHANGE_REQ packet");
	    }
	} catch (Exception e) {
	    Log.e(TAG, "Error sending KEY_EXCHANGE_REQ", e);
	}

	if (!currentRoomId.isEmpty()) {
	    joinChannel(currentRoomId);
	}*/
    }

    @Override
    public void onPacketReceived(Packet packet) {
	final var type = packet.getType();

	Log.d(TAG, "Incoming packet type: 0x" + Integer.toHexString(type) + ", payload size: " + packet.getPayload().length);

	switch (type) {
	    case Packet.TYPE_CHAT_MESSAGE:
		String messageText = new String(packet.getPayload(), StandardCharsets.UTF_8);
		Message msg = new Message(
			String.valueOf(System.currentTimeMillis()),
			messageText,
			"Server User",
			System.currentTimeMillis()
			);
		notifyMessageReceived(msg);

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
		    // get number of neighbours from payload
		    // generate new private key
		    // generate hash of new private key
		    // add entry to key hash mapping
		    // cryptoManager.addNewHashKeyMapping();
		    // create rotate key message with current key hash
		    // send rotate key message to server
		    // create N public key unicast messages, where n is the number of client in this chat
		    // send N unicast messages to server

		    //final var newKey = generateNewKeyRotation();
		    //final var newKeyHash = cryptoManager.hashKey(newKey);

		    //for (peer : peers) {
			// send n Packet.TYPE_UNICAST_ROTATED_KEY
			//webSocketClient.sendPacketAsync(multicastRequest);
		    //}
		}
		break;

	    default:
		Log.d(TAG, "Unhandled packet type: 0x" + Integer.toHexString(type));
		break;
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
