package cz.mkdaniel.enterprisemessenger.net;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import cz.mkdaniel.enterprisemessenger.crypto.CryptoManager;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Verifies the WebSocket client against a local server, including its upgrade request and callback.
 */
public class WebSocketClientIntegrationTest {
    private static final long TIMEOUT_SECONDS = 5L;
    private static final String WEBSOCKET_GUID =
            "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    @Test
    public void connectsWithBearerTokenAndReportsTheOpenedSocket() throws Exception {
        CountDownLatch requestReceived = new CountDownLatch(1);
        CountDownLatch allowServerClose = new CountDownLatch(1);
        CountDownLatch packetExchangeComplete = new CountDownLatch(1);
        AtomicReference<String> requestLine = new AtomicReference<>();
        AtomicReference<Map<String, String>> requestHeaders = new AtomicReference<>();
        AtomicReference<byte[]> receivedPacket = new AtomicReference<>();
        AtomicReference<Throwable> serverFailure = new AtomicReference<>();
        CountDownLatch connected = new CountDownLatch(1);
        CountDownLatch packetReceived = new CountDownLatch(1);
        AtomicReference<String> connectedUrl = new AtomicReference<>();
        AtomicReference<Packet> incomingPacket = new AtomicReference<>();
        AtomicReference<Throwable> clientFailure = new AtomicReference<>();

        try (ServerSocket server = new ServerSocket(0)) {
            Thread serverThread = new Thread(() -> serveHandshake(
                    server, requestReceived, allowServerClose, requestLine,
                    requestHeaders, receivedPacket, packetExchangeComplete,
                    serverFailure), "websocket-test-server");
            serverThread.setDaemon(true);
            serverThread.start();

            String url = "ws://127.0.0.1:" + server.getLocalPort() + "/messages";
            WebSocketClient client =
                    new WebSocketClient(CryptoManager.getInstance(null), url);
            client.setListener(new WebSocketClient.WebSocketClientListener() {
                @Override
                public void onConnected(String connectedEndpoint) {
                    connectedUrl.set(connectedEndpoint);
                    connected.countDown();
                }

                @Override
                public void onPacketReceived(Packet packet) {
                    incomingPacket.set(packet);
                    packetReceived.countDown();
                }

                @Override
                public void onDisconnected() {
                }

                @Override
                public void onError(Throwable failure) {
                    clientFailure.set(failure);
                }
            });

            try {
                client.connectAsync(url, "integration-token");

                assertTrue("server did not receive the WebSocket request",
                        requestReceived.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
                assertNull("server handshake failed", serverFailure.get());
                assertTrue("client did not report the completed WebSocket handshake",
                        connected.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
                assertEquals("GET /messages HTTP/1.1", requestLine.get());
                assertEquals("Bearer integration-token",
                        requestHeaders.get().get("authorization"));
                assertEquals(url, connectedUrl.get());
                assertTrue(client.isConnected());
                assertNull(clientFailure.get());

                byte[] outgoingPayload = {12, 34, 56};
                Packet outgoingPacket = new Packet(Packet.TYPE_CHAT_MESSAGE, outgoingPayload);
                client.sendPacketAsync(outgoingPacket);

                assertTrue("server did not receive the binary packet",
                        packetExchangeComplete.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
                assertNull("server packet exchange failed", serverFailure.get());
                assertArrayEquals(outgoingPacket.serialize(), receivedPacket.get());
                assertTrue("client did not deliver the server packet",
                        packetReceived.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
                assertEquals(Packet.TYPE_ACK, incomingPacket.get().getType());
                assertArrayEquals(new byte[]{7, 8}, incomingPacket.get().getPayload());
            } finally {
                client.disconnect();
                allowServerClose.countDown();
                serverThread.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
            }
        }
    }

    private static void serveHandshake(
            ServerSocket server,
            CountDownLatch requestReceived,
            CountDownLatch allowServerClose,
            AtomicReference<String> requestLine,
            AtomicReference<Map<String, String>> requestHeaders,
            AtomicReference<byte[]> receivedPacket,
            CountDownLatch packetExchangeComplete,
            AtomicReference<Throwable> serverFailure) {
        try (Socket socket = server.accept()) {
            socket.setSoTimeout((int) TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    socket.getInputStream(), StandardCharsets.ISO_8859_1));
            requestLine.set(reader.readLine());

            Map<String, String> headers = new HashMap<>();
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                int separator = line.indexOf(':');
                if (separator > 0) {
                    headers.put(line.substring(0, separator).trim().toLowerCase(Locale.ROOT),
                            line.substring(separator + 1).trim());
                }
            }
            requestHeaders.set(headers);

            String websocketKey = headers.get("sec-websocket-key");
            if (websocketKey == null) {
                throw new IllegalStateException("missing Sec-WebSocket-Key header");
            }
            String accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                    .digest((websocketKey + WEBSOCKET_GUID)
                            .getBytes(StandardCharsets.ISO_8859_1)));
            OutputStream output = socket.getOutputStream();
            output.write(("HTTP/1.1 101 Switching Protocols\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Accept: " + accept + "\r\n"
                    + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
            output.flush();
            requestReceived.countDown();

            byte[] clientPacket = readClientBinaryFrame(socket.getInputStream());
            receivedPacket.set(clientPacket);
            output.write(new byte[]{(byte) 0x82, 3, Packet.TYPE_ACK, 7, 8});
            output.flush();
            packetExchangeComplete.countDown();

            allowServerClose.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception failure) {
            serverFailure.set(failure);
            requestReceived.countDown();
            packetExchangeComplete.countDown();
        }
    }

    private static byte[] readClientBinaryFrame(InputStream input) throws IOException {
        DataInputStream dataInput = new DataInputStream(input);
        int first = dataInput.readUnsignedByte();
        int second = dataInput.readUnsignedByte();
        if ((first & 0x0f) != 0x2 || (second & 0x80) == 0) {
            throw new IOException("expected a masked WebSocket binary frame");
        }

        long length = second & 0x7f;
        if (length == 126) {
            length = dataInput.readUnsignedShort();
        } else if (length == 127) {
            length = dataInput.readLong();
        }
        if (length > Integer.MAX_VALUE) {
            throw new IOException("WebSocket test frame is too large");
        }

        byte[] mask = new byte[4];
        dataInput.readFully(mask);
        byte[] payload = new byte[(int) length];
        dataInput.readFully(payload);
        for (int i = 0; i < payload.length; i++) {
            payload[i] ^= mask[i % mask.length];
        }

        return payload;
    }
}
