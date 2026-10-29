package dev.rajveer.keel;

import dev.rajveer.keel.commands.Dispatcher;
import dev.rajveer.keel.persistence.AofLog;
import dev.rajveer.keel.protocol.Resp;
import dev.rajveer.keel.store.Database;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class Check {
    public final Database db;
    public final Dispatcher dispatcher;
    public final long[] now = {1_000_000L};

    public Check() {
        this(Path.of("build", "unused.rdb"));
    }

    public Check(Path snapshot) {
        db = new Database(() -> now[0]);
        db.attach(AofLog.disabled());
        dispatcher = new Dispatcher(db, snapshot);
    }

    public Check(Database db, Dispatcher dispatcher) {
        this.db = db;
        this.dispatcher = dispatcher;
    }

    public Resp call(String... args) {
        List<byte[]> argv = new ArrayList<>();
        for (String arg : args) {
            argv.add(arg.getBytes(StandardCharsets.UTF_8));
        }
        return dispatcher.dispatch(argv);
    }

    public Resp callBytes(byte[]... args) {
        List<byte[]> argv = new ArrayList<>();
        for (byte[] arg : args) {
            argv.add(arg);
        }
        return dispatcher.dispatch(argv);
    }

    public static void ok(Resp reply) {
        assertEquals("OK", assertInstanceOf(Resp.Simple.class, reply).text());
    }

    public static void pong(Resp reply) {
        assertEquals("PONG", assertInstanceOf(Resp.Simple.class, reply).text());
    }

    public static void bulk(Resp reply, String text) {
        assertArrayEquals(text.getBytes(StandardCharsets.UTF_8), assertInstanceOf(Resp.Bulk.class, reply).data());
    }

    public static void nil(Resp reply) {
        assertNull(assertInstanceOf(Resp.Bulk.class, reply).data());
    }

    public static void integer(Resp reply, long expected) {
        assertEquals(expected, assertInstanceOf(Resp.Int.class, reply).value());
    }

    public static void error(Resp reply, String part) {
        assertTrue(assertInstanceOf(Resp.Error.class, reply).text().contains(part),
                () -> assertInstanceOf(Resp.Error.class, reply).text());
    }

    public static List<String> strings(Resp reply) {
        Resp.Array array = assertInstanceOf(Resp.Array.class, reply);
        List<String> out = new ArrayList<>();
        for (Resp item : array.items()) {
            byte[] data = assertInstanceOf(Resp.Bulk.class, item).data();
            out.add(new String(data, StandardCharsets.UTF_8));
        }
        return out;
    }
}
