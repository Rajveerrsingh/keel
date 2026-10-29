package dev.rajveer.keel.server;

import dev.rajveer.keel.commands.CloseConnection;
import dev.rajveer.keel.commands.Dispatcher;
import dev.rajveer.keel.protocol.Resp;
import dev.rajveer.keel.protocol.RespException;
import dev.rajveer.keel.protocol.RespReader;
import dev.rajveer.keel.protocol.RespWriter;
import dev.rajveer.keel.store.Database;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * One virtual thread per client. The read loop looks blocking, and the JVM
 * parks the thread when the socket has nothing to read.
 */
public final class KeelServer implements Closeable {
    private final ServerSocket serverSocket;
    private final Database db;
    private final Dispatcher dispatcher;
    private volatile boolean running = true;

    public KeelServer(int port, Database db, Dispatcher dispatcher) throws IOException {
        this.db = db;
        this.dispatcher = dispatcher;
        this.serverSocket = new ServerSocket();
        this.serverSocket.setReuseAddress(true);
        this.serverSocket.bind(new InetSocketAddress(port));
    }

    public int port() {
        return serverSocket.getLocalPort();
    }

    public boolean isRunning() {
        return running;
    }

    public void serve() throws IOException {
        Thread.ofVirtual().name("keel-expiry").start(this::expireLoop);
        while (running) {
            Socket socket;
            try {
                socket = serverSocket.accept();
            } catch (SocketException e) {
                if (!running) {
                    return;
                }
                throw e;
            }
            Thread.ofVirtual().name("keel-client").start(() -> handle(socket));
        }
    }

    private void expireLoop() {
        while (running) {
            try {
                db.activeExpireCycle();
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                System.err.println("expiry: " + e.getMessage());
            }
        }
    }

    private void handle(Socket socket) {
        try (socket) {
            socket.setTcpNoDelay(true);
            BufferedInputStream in = new BufferedInputStream(socket.getInputStream());
            BufferedOutputStream out = new BufferedOutputStream(socket.getOutputStream());
            RespReader reader = new RespReader(in);
            RespWriter writer = new RespWriter(out);
            while (running) {
                in.mark(1);
                int peek = in.read();
                if (peek < 0) {
                    return;
                }
                in.reset();
                try {
                    Resp response;
                    if (isRespPrefix(peek)) {
                        Resp value;
                        try {
                            value = reader.read();
                        } catch (EOFException e) {
                            return;
                        } catch (RespException e) {
                            writer.write(Resp.error("ERR protocol error"));
                            return;
                        }
                        if (value == null) {
                            return;
                        }
                        response = reply(value);
                    } else {
                        // redis-benchmark's PING_INLINE test, and a telnet session,
                        // send "PING\r\n" instead of a RESP array.
                        response = inline(in);
                        if (response == null) {
                            return;
                        }
                    }
                    writer.write(response);
                } catch (CloseConnection e) {
                    writer.write(Resp.ok());
                    return;
                }
            }
        } catch (IOException e) {
            // the client went away
        } catch (RuntimeException e) {
            System.err.println("client: " + e);
        }
    }

    private static boolean isRespPrefix(int b) {
        return b == '+' || b == '-' || b == ':' || b == '$' || b == '*';
    }

    /** One space-separated line. Quotes are not handled; redis-cli does not need them. */
    private Resp inline(BufferedInputStream in) throws IOException {
        StringBuilder line = new StringBuilder();
        while (true) {
            int b = in.read();
            if (b < 0) {
                if (line.isEmpty()) {
                    return null;
                }
                throw new EOFException("truncated line");
            }
            if (b == '\n') {
                break;
            }
            if (b != '\r') {
                line.append((char) b);
            }
        }
        String text = line.toString().trim();
        if (text.isEmpty()) {
            return Resp.error("ERR protocol error");
        }
        String[] parts = text.split("\\s+");
        List<byte[]> argv = new ArrayList<>(parts.length);
        for (String part : parts) {
            argv.add(part.getBytes(StandardCharsets.UTF_8));
        }
        return dispatcher.dispatch(argv);
    }

    private Resp reply(Resp value) {
        if (!(value instanceof Resp.Array array) || array.items() == null) {
            return Resp.error("ERR protocol error");
        }
        List<byte[]> argv = new ArrayList<>(array.items().size());
        for (Resp item : array.items()) {
            if (!(item instanceof Resp.Bulk bulk) || bulk.data() == null) {
                return Resp.error("ERR protocol error");
            }
            argv.add(bulk.data());
        }
        if (argv.isEmpty()) {
            return Resp.error("ERR protocol error");
        }
        return dispatcher.dispatch(argv);
    }

    @Override
    public void close() throws IOException {
        running = false;
        serverSocket.close();
    }
}
