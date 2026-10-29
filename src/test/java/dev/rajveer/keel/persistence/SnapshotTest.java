package dev.rajveer.keel.persistence;

import dev.rajveer.keel.Check;
import dev.rajveer.keel.commands.Dispatcher;
import dev.rajveer.keel.store.Database;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class SnapshotTest {
    @Test
    void saveAndLoad(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("keel.rdb");
        Check c = new Check(file);
        Check.ok(c.call("SET", "city", "Toronto", "EX", "30"));
        Check.integer(c.call("RPUSH", "jobs", "a", "b"), 2);
        Check.integer(c.call("HSET", "dev", "name", "Rajveer"), 1);
        byte[] blob = new byte[] {0, 1, '\r', '\n', (byte) 0xff};
        Check.ok(c.callBytes("SET".getBytes(StandardCharsets.US_ASCII), "bin".getBytes(StandardCharsets.US_ASCII), blob));
        Check.ok(c.call("SAVE"));

        Database loaded = new Database(() -> c.now[0]);
        assertEquals(4, Snapshot.load(loaded, file));
        Check view = new Check(loaded, new Dispatcher(loaded, file));
        Check.bulk(view.call("GET", "city"), "Toronto");
        Check.integer(view.call("TTL", "city"), 30);
        assertEquals(List.of("a", "b"), Check.strings(view.call("LRANGE", "jobs", "0", "-1")));
        Check.bulk(view.call("HGET", "dev", "name"), "Rajveer");
        var got = view.call("GET", "bin");
        assertArrayEquals(blob, assertInstanceOf(dev.rajveer.keel.protocol.Resp.Bulk.class, got).data());
    }

    @Test
    void expiredKeyIsLeftOut(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("keel.rdb");
        Check c = new Check(file);
        Check.ok(c.call("SET", "temp", "x", "PX", "5"));
        Check.ok(c.call("SET", "keep", "y"));
        c.now[0] += 5;
        Check.ok(c.call("SAVE"));
        Database loaded = new Database(() -> c.now[0]);
        assertEquals(1, Snapshot.load(loaded, file));
        Check.bulk(new Check(loaded, new Dispatcher(loaded, file)).call("GET", "keep"), "y");
    }
}
