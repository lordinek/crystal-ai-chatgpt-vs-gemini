package com.crystalai.plugin.bridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import javax.annotation.Nullable;

/**
 * Localhost TCP endpoint for the trainer. A background thread only accepts connections; all
 * reading and writing happens on the server thread (the decision step is synchronous).
 * One trainer at a time; newline-delimited JSON.
 */
public final class BridgeServer {

    private final Logger logger;
    private final AtomicReference<Socket> pending = new AtomicReference<>();
    private @Nullable ServerSocket serverSocket;
    private @Nullable Thread acceptThread;
    private @Nullable Socket client;
    private @Nullable BufferedReader in;
    private @Nullable BufferedWriter out;

    public BridgeServer(Logger logger) {
        this.logger = logger;
    }

    public synchronized void start(int port) throws IOException {
        if (serverSocket != null) {
            return;
        }
        ServerSocket socket = new ServerSocket();
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
        serverSocket = socket;
        acceptThread = new Thread(this::acceptLoop, "CrystalAI-bridge-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        logger.info("Bridge listening on 127.0.0.1:" + port);
    }

    private void acceptLoop() {
        ServerSocket socket = serverSocket;
        while (socket != null && !socket.isClosed()) {
            try {
                Socket incoming = socket.accept();
                incoming.setTcpNoDelay(true);
                Socket previous = pending.getAndSet(incoming);
                if (previous != null) {
                    previous.close();
                }
            } catch (IOException e) {
                if (!socket.isClosed()) {
                    logger.warning("Bridge accept failed: " + e.getMessage());
                }
            }
        }
    }

    /** Server thread: adopts a newly accepted trainer (dropping the current one). */
    public boolean adoptNewClient(int timeoutMillis) throws IOException {
        Socket incoming = pending.getAndSet(null);
        if (incoming == null) {
            return false;
        }
        disconnect("replaced by a new trainer");
        incoming.setSoTimeout(timeoutMillis);
        client = incoming;
        in = new BufferedReader(new InputStreamReader(incoming.getInputStream(), StandardCharsets.UTF_8), 1 << 16);
        out = new BufferedWriter(new OutputStreamWriter(incoming.getOutputStream(), StandardCharsets.UTF_8), 1 << 16);
        logger.info("Trainer connected from " + incoming.getRemoteSocketAddress());
        return true;
    }

    public boolean connected() {
        return client != null && !client.isClosed();
    }

    public void send(String json) throws IOException {
        if (out == null) {
            throw new IOException("no trainer connected");
        }
        out.write(json);
        out.write('\n');
        out.flush();
    }

    /** Blocks until one message arrives (or the socket timeout hits). */
    public JsonObject receive() throws IOException {
        if (in == null) {
            throw new IOException("no trainer connected");
        }
        String line;
        try {
            line = in.readLine();
        } catch (SocketTimeoutException e) {
            throw new IOException("trainer did not answer in time", e);
        }
        if (line == null) {
            throw new IOException("trainer closed the connection");
        }
        return JsonParser.parseString(line).getAsJsonObject();
    }

    public void disconnect(String reason) {
        if (client == null) {
            return;
        }
        try {
            client.close();
        } catch (IOException ignored) {
            // closing anyway
        }
        logger.info("Trainer disconnected: " + reason);
        client = null;
        in = null;
        out = null;
    }

    public synchronized void stop() {
        disconnect("bridge stopped");
        Socket waiting = pending.getAndSet(null);
        try {
            if (waiting != null) {
                waiting.close();
            }
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
            // shutting down
        }
        serverSocket = null;
        acceptThread = null;
    }
}
