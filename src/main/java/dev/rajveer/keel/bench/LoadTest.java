package dev.rajveer.keel.bench;

import dev.rajveer.keel.protocol.RespReader;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Tiny TCP load test. It talks RESP, same as redis-cli, and prints one line of numbers.
 *
 * <pre>
 *   java -cp build/libs/keel.jar dev.rajveer.keel.bench.LoadTest --port 6397 --mode set
 * </pre>
 */
public final class LoadTest {
    private LoadTest() {}

    public static void main(String[] args) throws Exception {
        int port = 6379;
        String host = "127.0.0.1";
        int clients = 32;
        int pipeline = 16;
        int seconds = 4;
        int warmup = 1;
        String mode = "set";
        int keys = 1000;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--host" -> host = args[++i];
                case "--clients" -> clients = Integer.parseInt(args[++i]);
                case "--pipeline" -> pipeline = Integer.parseInt(args[++i]);
                case "--seconds" -> seconds = Integer.parseInt(args[++i]);
                case "--warmup" -> warmup = Integer.parseInt(args[++i]);
                case "--mode" -> mode = args[++i];
                case "--keys" -> keys = Integer.parseInt(args[++i]);
                case "--help", "-h" -> {
                    usage();
                    return;
                }
                default -> {
                    System.err.println("unknown argument: " + args[i]);
                    usage();
                    System.exit(2);
                    return;
                }
            }
        }
        byte[][] payloads = payloads(mode, keys);
        if ("get".equals(mode) || "mixed".equals(mode)) {
            preload(host, port, keys);
        }
        Result result = run(host, port, clients, pipeline, seconds, warmup, payloads);
        System.out.printf(
                "mode=%s clients=%d pipeline=%d seconds=%d warmup=%d ops=%d errors=%d ops_per_sec=%.0f%n",
                mode, clients, pipeline, seconds, warmup, result.ops, result.errors, result.ops / (double) seconds);
    }

    private static void preload(String host, int port, int keys) throws Exception {
        try (Socket socket = open(host, port)) {
            BufferedOutputStream out = new BufferedOutputStream(socket.getOutputStream());
            RespReader reader = new RespReader(new BufferedInputStream(socket.getInputStream()));
            byte[][] sets = new byte[keys][];
            for (int i = 0; i < keys; i++) {
                sets[i] = command("SET", key(i), "x");
            }
            int pipeline = 32;
            for (int i = 0; i < keys; ) {
                int n = Math.min(pipeline, keys - i);
                for (int j = 0; j < n; j++) {
                    out.write(sets[i + j]);
                }
                out.flush();
                for (int j = 0; j < n; j++) {
                    if (reader.read() == null) {
                        throw new IllegalStateException("server closed during preload");
                    }
                }
                i += n;
            }
        }
    }

    private static Result run(String host, int port, int clients, int pipeline, int seconds, int warmup, byte[][] payloads)
            throws Exception {
        CountDownLatch ready = new CountDownLatch(clients);
        CountDownLatch done = new CountDownLatch(clients);
        LongAdder ops = new LongAdder();
        LongAdder errors = new LongAdder();
        AtomicLong windowStart = new AtomicLong();
        for (int c = 0; c < clients; c++) {
            int id = c;
            Thread.ofVirtual().start(() -> {
                try (Socket socket = open(host, port)) {
                    BufferedOutputStream out = new BufferedOutputStream(socket.getOutputStream());
                    RespReader reader = new RespReader(new BufferedInputStream(socket.getInputStream()));
                    ready.countDown();
                    ready.await();
                    long start = windowStart.get();
                    while (start == 0) {
                        Thread.onSpinWait();
                        start = windowStart.get();
                    }
                    long warmupEnd = start + warmup * 1_000_000_000L;
                    long measureEnd = warmupEnd + seconds * 1_000_000_000L;
                    int cursor = id;
                    while (System.nanoTime() < measureEnd) {
                        boolean count = System.nanoTime() >= warmupEnd;
                        for (int i = 0; i < pipeline; i++) {
                            out.write(payloads[cursor % payloads.length]);
                            cursor++;
                        }
                        out.flush();
                        for (int i = 0; i < pipeline; i++) {
                            var reply = reader.read();
                            if (count) {
                                if (reply instanceof dev.rajveer.keel.protocol.Resp.Error) {
                                    errors.increment();
                                } else if (reply != null) {
                                    ops.increment();
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    System.err.println("client: " + e.getMessage());
                    errors.increment();
                } finally {
                    done.countDown();
                }
            });
        }
        ready.await();
        windowStart.set(System.nanoTime());
        done.await();
        return new Result(ops.sum(), errors.sum());
    }

    private static byte[][] payloads(String mode, int keys) {
        byte[][] out = new byte[keys][];
        for (int i = 0; i < keys; i++) {
            out[i] = switch (mode) {
                case "ping" -> command("PING");
                case "set" -> command("SET", key(i), "x");
                case "get" -> command("GET", key(i));
                case "mixed" -> (i % 2 == 0) ? command("SET", key(i), "x") : command("GET", key(i));
                default -> throw new IllegalArgumentException("mode must be ping, set, get, or mixed");
            };
        }
        if ("ping".equals(mode)) {
            return new byte[][] {command("PING")};
        }
        return out;
    }

    private static byte[] command(String name, String... args) {
        StringBuilder sb = new StringBuilder();
        sb.append('*').append(args.length + 1).append("\r\n");
        writeBulk(sb, name);
        for (String arg : args) {
            writeBulk(sb, arg);
        }
        return sb.toString().getBytes(StandardCharsets.US_ASCII);
    }

    private static void writeBulk(StringBuilder sb, String text) {
        sb.append('$').append(text.length()).append("\r\n").append(text).append("\r\n");
    }

    private static String key(int i) {
        return "k" + i;
    }

    private static Socket open(String host, int port) throws Exception {
        Socket socket = new Socket();
        socket.setTcpNoDelay(true);
        socket.connect(new InetSocketAddress(host, port), 2000);
        socket.setSoTimeout(30000);
        return socket;
    }

    private static void usage() {
        System.out.println("""
                usage: LoadTest [--host 127.0.0.1] [--port 6379]
                                [--clients 32] [--pipeline 16]
                                [--seconds 4] [--warmup 1]
                                [--mode ping|set|get|mixed] [--keys 1000]
                """);
    }

    private record Result(long ops, long errors) {}
}
