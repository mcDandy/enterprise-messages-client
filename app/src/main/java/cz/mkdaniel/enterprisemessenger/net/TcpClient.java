package cz.mkdaniel.enterprisemessenger.net;

import android.util.Log;

import java.io.DataOutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import cz.mkdaniel.enterprisemessenger.crypto.CryptoManager;

/**
 * Manages TCP socket connections for binary packet transport.
 * <p>
 * Handles asynchronous background connection, continuous reading of incoming
 * binary packets, and packet transmission over output streams.
 */
public class TcpClient {

    private static final String TAG = "TcpClient";
    private static final int DEFAULT_TIMEOUT_MS = 10000; // 10 seconds

    private final CryptoManager cryptoManager;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final AtomicBoolean isConnected = new AtomicBoolean(false);

    private Socket socket;
    private DataOutputStream outputStream;
    private InputStream inputStream;
    private TcpClientListener listener;
    private byte[] channelEncryptionKey;

    public interface TcpClientListener {
        void onConnected(String host, int port);
        void onPacketReceived(Packet packet);
        void onDisconnected();
        void onError(Exception e);
    }

    public TcpClient(CryptoManager cryptoManager) {
        this.cryptoManager = cryptoManager;
    }

    public void setListener(TcpClientListener listener) {
        this.listener = listener;
    }

    public void setChannelEncryptionKey(byte[] encryptionKey) {
        this.channelEncryptionKey = encryptionKey;
    }

    public boolean isConnected() {
        return isConnected.get() && socket != null && !socket.isClosed();
    }

    /**
     * Asynchronously connects to the remote TCP server.
     */
    public void connectAsync(String host, int port) {
        executor.execute(() -> {
            try {
                disconnect(); // Close existing connection if any

                Log.d(TAG, "Connecting to TCP server " + host + ":" + port + "...");
                socket = new Socket();
                socket.connect(new InetSocketAddress(host, port), DEFAULT_TIMEOUT_MS);

                outputStream = new DataOutputStream(socket.getOutputStream());
                inputStream = socket.getInputStream();
                isConnected.set(true);

                Log.d(TAG, "Connected to " + host + ":" + port);
                if (listener != null) {
                    listener.onConnected(host, port);
                }

                // Start background reader loop
                startListening();

            } catch (Exception e) {
                Log.e(TAG, "Failed to connect to " + host + ":" + port, e);
                isConnected.set(false);
                if (listener != null) {
                    listener.onError(e);
                }
            }
        });
    }

    /**
     * Sends a packet asynchronously over the TCP socket connection.
     */
    public void sendPacketAsync(Packet packet) {
        if (!isConnected()) {
            Log.w(TAG, "Cannot send packet: TCP client is not connected");
            return;
        }

        executor.execute(() -> {
            try {
                byte[] rawPacketBytes = packet.serialize(cryptoManager, channelEncryptionKey);
                synchronized (this) {
                    if (outputStream != null) {
                        outputStream.write(rawPacketBytes);
                        outputStream.flush();
                        Log.d(TAG, "Sent packet type 0x" + Integer.toHexString(packet.getType())
                                + " (" + rawPacketBytes.length + " bytes)");
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

    private void startListening() {
        executor.execute(() -> {
            try {
                while (isConnected.get() && inputStream != null) {
                    Packet incomingPacket = Packet.readFromStream(inputStream, cryptoManager, channelEncryptionKey);
                    Log.d(TAG, "Received packet type 0x" + Integer.toHexString(incomingPacket.getType())
                            + ", payload length: " + incomingPacket.getPayload().length);

                    if (listener != null) {
                        listener.onPacketReceived(incomingPacket);
                    }
                }
            } catch (Exception e) {
                if (isConnected.get()) {
                    Log.e(TAG, "Error reading from TCP socket stream", e);
                    if (listener != null) {
                        listener.onError(e);
                    }
                }
            } finally {
                disconnect();
            }
        });
    }

    /**
     * Closes the TCP socket connection and cleans up streams.
     */
    public synchronized void disconnect() {
        boolean wasConnected = isConnected.getAndSet(false);

        try {
            if (outputStream != null) {
                outputStream.close();
                outputStream = null;
            }
            if (inputStream != null) {
                inputStream.close();
                inputStream = null;
            }
            if (socket != null && !socket.isClosed()) {
                socket.close();
                socket = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error closing TCP client streams", e);
        }

        if (wasConnected && listener != null) {
            listener.onDisconnected();
        }
    }
}
