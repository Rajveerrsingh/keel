package dev.rajveer.keel;

import dev.rajveer.keel.commands.Dispatcher;
import dev.rajveer.keel.persistence.AofLog;
import dev.rajveer.keel.protocol.Resp;
import dev.rajveer.keel.protocol.RespReader;
import dev.rajveer.keel.protocol.RespWriter;
import dev.rajveer.keel.server.KeelServer;
import dev.rajveer.keel.store.Database;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerTest {
    @Test
    void commandsOverARealSocket() throws Exception {
        try (RunningServer server = new RunningServer()) {
            try (Client client = server.connect()) {
                Check.pong(client.call("PING"));
                Check.bulk(client.call("ECHO", "hi"), "hi");
                Check.ok(client.call("SET", "city", "Toronto"));
                Check.bulk(client.call("GET", "city"), "Toronto");
                Check.integer(client.call("INCR", "visits"), 1);
                Check.integer(client.call("LPUSH", "stack", "a", "b"), 2);
                assertEquals(List.of("b", "a"), Check.strings(client.call("LRANGE", "stack", "0", "-1")));
                Check.integer(client.call("HSET", "dev", "name", "Rajveer"), 1);
                Check.bulk(client.call("HGET", "dev", "name"), "Rajveer");
                Check.error(client.call("NOPE"), "unknown command");
                Check.pong(client.call("PING"));
                byte[] blob = new byte[] {0, '\r', '\n', (byte) 0xff};
                Check.ok(client.callBytes("SET".getBytes(StandardCharsets.US_ASCII),
                        "bin".getBytes(StandardCharsets.US_ASCII), blob));
                var got = client.callBytes("GET".getBytes(StandardCharsets.US_ASCII),
                        "bin".getBytes(StandardCharsets.US_ASCII));
                org.junit.jupiter.api.Assertions.assertArrayEquals(
                        blob,
                        org.junit.jupiter.api.Assertions.assertInstanceOf(Resp.Bulk.class, got).data());
            }
        }
    }

    @Test
    void inlinePing() throws Exception {
        try (RunningServer server = new RunningServer()) {
            try (Socket socket = new Socket("127.0.0.1", server.port)) {
                socket.setSoTimeout(2000);
                socket.getOutputStream().write("PING\r\nECHO hi\r\n".getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
                byte[] buf = socket.getInputStream().readNBytes(15);
                assertEquals("+PONG\r\n$2\r\nhi\r\n", new String(buf, StandardCharsets.US_ASCII));
            }
        }
    }

    @Test
    void pipelineAndQuit() throws Exception {
        try (RunningServer server = new RunningServer()) {
            try (Client client = server.connect()) {
                client.writer.write(Resp.command("PING"));
                client.writer.write(Resp.command("SET", b("k"), b("v")));
                client.writer.write(Resp.command("GET", b("k")));
                Check.pong(client.reader.read());
                Check.ok(client.reader.read());
                Check.bulk(client.reader.read(), "v");
                client.writer.write(Resp.command("QUIT"));
                Check.ok(client.reader.read());
                assertNull(client.reader.read());
            }
        }
    }

    @Test
    void twoClientsIncr() throws Exception {
        try (RunningServer server = new RunningServer()) {
            int threads = 4;
            int each = 100;
            List<Thread> workers = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                workers.add(Thread.startVirtualThread(() -> {
                    try (Client client = server.connect()) {
                        for (int j = 0; j < each; j++) {
                            if (!(client.call("INCR", "n") instanceof Resp.Int)) {
                                throw new AssertionError("incr did not return an integer");
                            }
                        }
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }));
            }
            for (Thread worker : workers) {
                worker.join();
            }
            try (Client client = server.connect()) {
                Check.bulk(client.call("GET", "n"), Integer.toString(threads * each));
            }
        }
    }

    @Test
    void redisCliWhenInstalled() throws Exception {
        Assumptions.assumeTrue(redisCliWorks());
        try (RunningServer server = new RunningServer()) {
            assertEquals("PONG\n", cli(server.port, "PING"));
            assertEquals("OK\n", cli(server.port, "SET", "user", "Rajveer", "EX", "60"));
            assertEquals("Rajveer\n", cli(server.port, "GET", "user"));
            assertEquals("1\n", cli(server.port, "INCR", "visits"));
            assertEquals("2\n", cli(server.port, "RPUSH", "jobs", "keel", "java"));
            String jobs = cli(server.port, "LRANGE", "jobs", "0", "-1");
            assertTrue(jobs.contains("keel"));
            assertTrue(jobs.contains("java"));
            assertEquals("1\n", cli(server.port, "HSET", "profile", "city", "Toronto"));
            String all = cli(server.port, "HGETALL", "profile");
            assertTrue(all.contains("city"));
            assertTrue(all.contains("Toronto"));
            String ttl = cli(server.port, "TTL", "user").trim();
            int seconds = Integer.parseInt(ttl);
            assertTrue(seconds > 0 && seconds <= 60, ttl);
        }
    }

    private static boolean redisCliWorks() throws Exception {
        Process process = new ProcessBuilder("redis-cli", "--version").redirectErrorStream(true).start();
        return process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0;
    }

    private static String cli(int port, String... args) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add("redis-cli");
        cmd.add("-p");
        cmd.add(Integer.toString(port));
        for (String arg : args) {
            cmd.add(arg);
        }
        Process process = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        assertTrue(process.waitFor(5, TimeUnit.SECONDS));
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), out);
        return out;
    }

    private static byte[] b(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static final class RunningServer implements AutoCloseable {
        final KeelServer server;
        final int port;

        RunningServer() throws Exception {
            Database db = new Database();
            db.attach(AofLog.disabled());
            Dispatcher dispatcher = new Dispatcher(db, Path.of("build", "it.rdb"));
            server = new KeelServer(0, db, dispatcher);
            port = server.port();
            Thread.ofVirtual().start(() -> {
                try {
                    server.serve();
                } catch (Exception e) {
                    if (server.isRunning()) {
                        e.printStackTrace();
                    }
                }
            });
        }

        Client connect() throws Exception {
            java.io.IOException last = null;
            for (int i = 0; i < 50; i++) {
                try {
                    Socket socket = new Socket();
                    socket.connect(new InetSocketAddress("127.0.0.1", port), 200);
                    socket.setTcpNoDelay(true);
                    socket.setSoTimeout(2000);
                    return new Client(socket);
                } catch (java.io.IOException e) {
                    last = e;
                    Thread.sleep(20);
                }
            }
            throw last;
        }

        @Override
        public void close() throws Exception {
            server.close();
        }
    }

    private static final class Client implements AutoCloseable {
        final Socket socket;
        final RespReader reader;
        final RespWriter writer;

        Client(Socket socket) throws Exception {
            this.socket = socket;
            this.reader = new RespReader(new BufferedInputStream(socket.getInputStream()));
            this.writer = new RespWriter(new BufferedOutputStream(socket.getOutputStream()));
        }

        Resp call(String... args) throws Exception {
            byte[][] raw = new byte[args.length][];
            for (int i = 0; i < args.length; i++) {
                raw[i] = args[i].getBytes(StandardCharsets.UTF_8);
            }
            return callBytes(raw);
        }

        Resp callBytes(byte[]... args) throws Exception {
            writer.write(command(args));
            return reader.read();
        }

        @Override
        public void close() throws Exception {
            socket.close();
        }

    }

    private static Resp.Array command(byte[]... args) {
        List<Resp> items = new ArrayList<>();
        for (byte[] arg : args) {
            items.add(Resp.bulk(arg));
        }
        return new Resp.Array(items);
    }
}
