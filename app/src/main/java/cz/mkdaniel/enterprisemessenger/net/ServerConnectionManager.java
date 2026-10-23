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
    public static final String WS_PROTOCOL = "wss"; 
    public static final String WS_HOST = "172.18.0.1";
    public static final Integer WS_PORT = 32778;
    public static final String WS_ENDPOINT_PATH = "wss";

    private static final String TAG = "ServerConnectionManager";

    private static volatile ServerConnectionManager instance;

    private final CryptoManager cryptoManager;
    private final WebSocketClient webSocketClient;
    private final OkHttpClient httpClient;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Set<ConnectionListener> listeners = new CopyOnWriteArraySet<>();
    private final String currentServerIp = String.format("%s://%s:%d/%s", WS_PROTOCOL, WS_HOST, WS_PORT, WS_ENDPOINT_PATH);

    //private String currentServerIp = "";
    private String currentRoomId = "";
    private String jwtToken = "";

    public interface ConnectionListener {
	void onConnectionStateChanged(boolean connected, String serverIp);
	void onMessageReceived(Message message);
	void onError(String errorMessage);
    }

    private ServerConnectionManager(Context context) {
	this.cryptoManager = CryptoManager.getInstance(context);
	this.webSocketClient = new WebSocketClient(cryptoManager, currentServerIp);
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
	executor.execute(() -> fetchJwtTokenAndConnect(serverIp));
    }

    private void fetchJwtTokenAndConnect(String serverIp) {
	String httpUrl = "https://172.18.0.1:32778"/*formatHttpUrl(serverIp)*/;
	String username = "android_user";
	String password = "GuestPassword123";
	String pubKey = cryptoManager.getPublicKeyBase64();

	if (pubKey == null) pubKey = "dummyPublicKeyBase64StringForE2EE";

	jwtToken = login(httpUrl, username, password);

	if (jwtToken.isEmpty()) {
	    jwtToken = register(httpUrl, username, password, pubKey);
	}

	if (jwtToken.isEmpty()) {
	    Log.w(TAG, "No JWT token obtained from " + httpUrl + ", server will reject unauthenticated packets");
	}

	webSocketClient.connectAsync(serverIp, jwtToken);
    }

    /**
     * Posts credentials to {@code /api/auth/login} and returns the JWT token, or "" on any failure.
     */
    private String login(String httpUrl, String username, String password) {
	try {
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
		    String token = resObj.optString("token", "");
		    Log.d(TAG, "Authentication successful, obtained JWT token");

		    return token;
		}
		Log.w(TAG, "Login failed with HTTP " + response.code());
	    }
	} catch (Exception e) {
	    Log.w(TAG, "Login request failed: " + e.getMessage());
	}
	return "";
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

	    Packet joinPacket = new Packet();
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
	try {
	    if (!webSocketClient.isConnected()) {
		Log.w(TAG, "Cannot send message: Not connected to WebSocket server");
		notifyError("Not connected to server");
		return;
	    }

	    webSocketClient.setChannelEncryptionKey(encryptionKey);
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
	notifyStateChanged(true, currentServerIp);

	if (!jwtToken.isEmpty()) {
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
	    joinRoom(currentRoomId);
	}
    }

    @Override
    public void onPacketReceived(Packet packet) {
	int type = packet.getType();
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
		break;

	    case Packet.TYPE_UNKNOWN:
		String errorMsg = new String(packet.getPayload(), StandardCharsets.UTF_8);
		Log.e(TAG, "Server returned error packet: " + errorMsg);
		notifyError(errorMsg);
		break;

	    case Packet.TYPE_ROTATE_KEY:
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

		    final var multicastRequest = new Packet();

		    webSocketClient.sendPacketAsync(multicastRequest);
		}
		break;

	    case Packet.TYPE_UNICAST_ROTATED_KEY:
		{
		    // update current private shared key hash
		    //cryptoManager.updateCurrentKeyHash();
		}
		break;
	    case Packet.TYPE_SOLICITE_PUBLIC_KEY:
		{
		    // create public key message
		    // send to server
		}
		break;
	    case Packet.TYPE_ADVERTISE_PUBLIC_KEY:
		{
		    // extract public key from message
		    // create new keypair
		    // store public key with new keypair
		}
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

    //public void generateNewKeyRotation() {
    //final var temp = multicastNewRotationToChannelMembers();
    //}

    /*public void multicastNewRotationToChannelMembers() {
      for (var i = 0; i < 0; ++i) {
      webSocketClient.sendPacketAsync(null);
      }
      }*/
}
