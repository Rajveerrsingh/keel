package dev.rajveer.keel.persistence;

import dev.rajveer.keel.Check;
import dev.rajveer.keel.commands.Dispatcher;
import dev.rajveer.keel.protocol.Resp;
import dev.rajveer.keel.store.Database;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AofTest {
    @Test
    void replayRestoresStringsListsHashesAndExpiry(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("keel.aof");
        Check writer = new Check();
        AofLog log = AofLog.open(file, false);
        writer.db.attach(log);
        Check.ok(writer.call("SET", "city", "Toronto", "EX", "10"));
        Check.integer(writer.call("INCR", "visits"), 1);
        Check.integer(writer.call("LPUSH", "stack", "a", "b", "c"), 3);
        Check.integer(writer.call("HSET", "dev", "name", "Rajveer", "city", "Toronto"), 2);
        Check.integer(writer.call("HDEL", "dev", "city"), 1);
        log.close();

        long[] clock = {writer.now[0] + 4_000};
        Database restored = new Database(() -> clock[0]);
        restored.attach(AofLog.disabled());
        Dispatcher dispatcher = new Dispatcher(restored, dir.resolve("unused.rdb"));
        int applied = restored.replay(file, argv -> {
            Resp reply = dispatcher.dispatch(argv);
            if (reply instanceof Resp.Error err) {
                throw new AssertionError(err.text());
            }
        });
        assertEquals(6, applied);

        Check view = new Check(restored, dispatcher);
        Check.bulk(view.call("GET", "city"), "Toronto");
        Check.integer(view.call("TTL", "city"), 6);
        Check.bulk(view.call("GET", "visits"), "1");
        assertEquals(List.of("c", "b", "a"), Check.strings(view.call("LRANGE", "stack", "0", "-1")));
        assertEquals(List.of("name", "Rajveer"), Check.strings(view.call("HGETALL", "dev")));

        clock[0] = writer.now[0] + 10_000;
        Check.nil(view.call("GET", "city"));
        long sizeAfterReplay = Files.size(file);
        view.call("SET", "other", "x");
        assertEquals(sizeAfterReplay, Files.size(file));
    }

    @Test
    void tornTailIsCut(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("keel.aof");
        Check writer = new Check();
        AofLog log = AofLog.open(file, false);
        writer.db.attach(log);
        Check.ok(writer.call("SET", "k", "v"));
        log.close();
        long complete = Files.size(file);
        Files.writeString(file, "*1\r\n$3\r\nSE", StandardCharsets.US_ASCII, StandardOpenOption.APPEND);

        Database restored = new Database();
        restored.attach(AofLog.disabled());
        Dispatcher dispatcher = new Dispatcher(restored, dir.resolve("unused.rdb"));
        int applied = restored.replay(file, dispatcher::dispatch);
        assertEquals(1, applied);
        Check.bulk(new Check(restored, dispatcher).call("GET", "k"), "v");
        assertEquals(complete, Files.size(file));
        String text = Files.readString(file);
        assertTrue(text.contains("SET"));
        assertFalse(text.endsWith("SE"));
    }
}
