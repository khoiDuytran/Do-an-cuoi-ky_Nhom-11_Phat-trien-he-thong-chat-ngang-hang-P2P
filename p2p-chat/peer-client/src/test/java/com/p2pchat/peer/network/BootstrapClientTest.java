package com.p2pchat.peer.network;

import com.p2pchat.peer.model.PeerInfo;
import com.p2pchat.peer.protocol.Message;
import com.p2pchat.peer.protocol.MessageType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for BootstrapClient reconnect logic.
 * Uses a local TCP server to simulate the bootstrap server.
 */
class BootstrapClientTest {

    private static final int MAX_ATTEMPTS = 3;
    private static final long RETRY_DELAY_MS = 500; // short delay for tests

    private ServerSocket serverSocket;
    private int serverPort;

    /** Accepts one connection, sends PEER_LIST, then optionally drops. */
    private volatile boolean dropOnNextRead = false;
    private final List<Thread> serverThreads = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startServer() throws Exception {
        serverSocket = new ServerSocket(0);
        serverPort = serverSocket.getLocalPort();
        dropOnNextRead = false;
    }

    @AfterEach
    void stopServer() throws Exception {
        dropOnNextRead = true;
        for (Thread t : serverThreads) {
            t.interrupt();
        }
        serverThreads.clear();
        if (serverSocket != null) serverSocket.close();
    }

    // ─── Test helpers ─────────────────────────────────────────────────────────

    private Message makePeerListMsg(List<PeerInfo> peers) {
        Message msg = new Message(MessageType.PEER_LIST, "bootstrap", "peer list");
        msg.putMeta("peers", new java.util.ArrayList<>(peers));
        return msg;
    }

    private void startServerHandler(Runnable onFirstMessage) {
        Thread t = new Thread(() -> {
            try {
                Socket client = serverSocket.accept();
                ObjectInputStream in = new ObjectInputStream(client.getInputStream());
                ObjectOutputStream out = new ObjectOutputStream(client.getOutputStream());
                out.flush();

                // First message: REGISTER from client → respond with PEER_LIST
                Message reg = (Message) in.readObject();
                onFirstMessage.run();
                Message response = makePeerListMsg(List.of());
                out.writeObject(response);
                out.flush();

                // After that: read and optionally drop
                while (!dropOnNextRead) {
                    try {
                        in.readObject();
                    } catch (EOFException | java.net.SocketException expected) {
                        break;
                    }
                }
                client.close();
            } catch (Exception e) {
                // server closed or client disconnected — expected
            }
        }, "test-server");
        t.setDaemon(true);
        t.start();
        serverThreads.add(t);
    }

    private BootstrapClient makeClient(List<Message> received) throws Exception {
        BootstrapClient client = new BootstrapClient(
                "localhost", serverPort,
                "peer-1", "Alice", 9001,
                received::add
        );
        client.setOnReconnectAttempt((a, m) -> {
            /* consume silently */
        });
        return client;
    }

    // ─── Tests ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("connectAndRegister returns peers and sets running=true on success")
    void connectAndRegister_success() throws Exception {
        List<Message> received = new CopyOnWriteArrayList<>();
        startServerHandler(() -> {});

        BootstrapClient client = makeClient(received);
        List<PeerInfo> peers = client.connectAndRegister();

        assertNotNull(peers);
        assertTrue(client.isConnected());
    }

    @Test
    @DisplayName("connectAndRegister throws IOException when server sends wrong message type")
    void connectAndRegister_wrongResponse() throws Exception {
        Thread t = new Thread(() -> {
            try {
                Socket client = serverSocket.accept();
                ObjectInputStream in = new ObjectInputStream(client.getInputStream());
                ObjectOutputStream out = new ObjectOutputStream(client.getOutputStream());
                out.flush();
                in.readObject(); // consume REGISTER
                out.writeObject(new Message(MessageType.PEER_JOINED, "x", "y")); // wrong type
                out.flush();
                client.close();
            } catch (Exception ignored) {}
        }, "wrong-response-server");
        t.setDaemon(true);
        t.start();

        BootstrapClient client = makeClient(List.of());
        assertThrows(IOException.class, client::connectAndRegister);
        assertFalse(client.isConnected());
    }

    @Test
    @DisplayName("run() exits when stopReconnectLoop() is called")
    void run_exitsOnStop() throws Exception {
        List<Message> received = new CopyOnWriteArrayList<>();
        CountDownLatch started = new CountDownLatch(1);

        Thread t = new Thread(() -> {
            try {
                Socket client = serverSocket.accept();
                ObjectInputStream in = new ObjectInputStream(client.getInputStream());
                ObjectOutputStream out = new ObjectOutputStream(client.getOutputStream());
                out.flush();
                in.readObject();
                out.writeObject(makePeerListMsg(List.of()));
                out.flush();
                started.countDown();
                // Don't send anything else — run() will block on readObject
                Thread.sleep(500);
                client.close();
            } catch (Exception ignored) {}
        }, "blocking-server");
        t.setDaemon(true);
        t.start();

        BootstrapClient client = makeClient(received);
        client.connectAndRegister();

        Thread listener = new Thread(client, "bootstrap-listener");
        listener.setDaemon(true);
        listener.start();

        assertTrue(started.await(2, TimeUnit.SECONDS));
        client.stopReconnectLoop();
        listener.join(2000);
        assertFalse(listener.isAlive());
    }

    @Test
    @DisplayName("disconnect() sets running=false and closes socket")
    void disconnect_works() throws Exception {
        List<Message> received = new CopyOnWriteArrayList<>();
        CountDownLatch serverGotUnreg = new CountDownLatch(1);

        // Single-threaded server: handles REGISTER, PEER_LIST, then UNREGISTER on same connection
        Thread server = new Thread(() -> {
            try {
                Socket s = serverSocket.accept();
                ObjectInputStream in = new ObjectInputStream(s.getInputStream());
                ObjectOutputStream out = new ObjectOutputStream(s.getOutputStream());
                out.flush();
                in.readObject(); // REGISTER
                out.writeObject(makePeerListMsg(List.of()));
                out.flush();
                in.readObject(); // UNREGISTER
                s.close();
                serverGotUnreg.countDown();
            } catch (Exception ignored) {}
        }, "disconnect-server");
        server.setDaemon(true);
        server.start();
        serverThreads.add(server);

        BootstrapClient client = makeClient(received);
        client.connectAndRegister();
        assertTrue(client.isConnected());

        client.disconnect();

        assertFalse(client.isConnected());
        assertTrue(serverGotUnreg.await(2, TimeUnit.SECONDS),
                "Server should have received UNREGISTER before socket close");
    }

    @Test
    @DisplayName("connectAndRegister uses fresh socket, does not leak the old one")
    void connectAndRegister_noSocketLeak() throws Exception {
        List<Message> received = new CopyOnWriteArrayList<>();
        AtomicInteger connections = new AtomicInteger(0);

        Thread t = new Thread(() -> {
            try {
                for (int i = 0; i < 2; i++) {
                    Socket client = serverSocket.accept();
                    connections.incrementAndGet();
                    ObjectInputStream in = new ObjectInputStream(client.getInputStream());
                    ObjectOutputStream out = new ObjectOutputStream(client.getOutputStream());
                    out.flush();
                    in.readObject();
                    out.writeObject(makePeerListMsg(List.of()));
                    out.flush();
                    // Simulate a brief disconnect
                    Thread.sleep(100);
                    client.close();
                }
            } catch (Exception ignored) {}
        }, "multi-connect-server");
        t.setDaemon(true);
        t.start();

        BootstrapClient client = makeClient(received);

        // First connection
        client.connectAndRegister();
        assertEquals(1, connections.get());
        assertTrue(client.isConnected());

        // Simulate a second connectAndRegister (old socket should be closed before new one opens)
        client.connectAndRegister();
        assertEquals(2, connections.get());
        assertTrue(client.isConnected());
    }

    @Test
    @DisplayName("Reconnect: calls onStatusChange(false) once after all attempts exhausted")
    void reconnect_firesStatusChangeOnceOnFailure() throws Exception {
        CountDownLatch disconnected = new CountDownLatch(1);

        // Server immediately closes each connection (mimics unreachable bootstrap)
        Thread refusor = new Thread(() -> {
            try {
                for (int i = 0; i < MAX_ATTEMPTS + 1; i++) {
                    Socket s = serverSocket.accept();
                    s.close(); // client will get SocketException
                }
            } catch (Exception ignored) {}
        }, "close-on-accept-server");
        refusor.setDaemon(true);
        refusor.start();
        serverThreads.add(refusor);

        BootstrapClient client = new BootstrapClient(
                "localhost", serverPort, "peer-y", "Carol", 9003, _ -> {}
        );
        client.setOnStatusChange(s -> {
            if (s != null && s.state() == BootstrapState.DISCONNECTED) disconnected.countDown();
        });

        // Call reconnect() directly — it loops MAX_ATTEMPTS times then fires onStatusChange(false)
        new Thread(() -> client.reconnect(), "direct-reconnect").start();

        assertTrue(disconnected.await(MAX_ATTEMPTS * 8000 + 3000, TimeUnit.MILLISECONDS),
                "Disconnect callback should fire after all retry attempts");
    }
}
