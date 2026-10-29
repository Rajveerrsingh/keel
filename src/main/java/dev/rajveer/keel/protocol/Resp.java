package dev.rajveer.keel.protocol;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * One RESP2 value. Null bulk strings and null arrays use a null payload,
 * which is how Redis writes {@code $-1} and {@code *-1}.
 */
public sealed interface Resp {

    record Simple(String text) implements Resp {}

    record Error(String text) implements Resp {}

    record Int(long value) implements Resp {}

    record Bulk(byte[] data) implements Resp {}

    record Array(List<Resp> items) implements Resp {}

    static Resp ok() {
        return new Simple("OK");
    }

    static Resp error(String text) {
        return new Error(text);
    }

    static Resp nil() {
        return new Bulk(null);
    }

    static Resp integer(long value) {
        return new Int(value);
    }

    static Resp bulk(byte[] data) {
        return new Bulk(data);
    }

    static Resp bulk(String text) {
        return new Bulk(text.getBytes(StandardCharsets.UTF_8));
    }

    static Resp array(List<Resp> items) {
        return new Array(items);
    }

    /** A command, the shape redis-cli sends and the shape the AOF stores. */
    static Array command(String name, byte[]... args) {
        List<Resp> items = new ArrayList<>(args.length + 1);
        items.add(new Bulk(name.getBytes(StandardCharsets.US_ASCII)));
        for (byte[] arg : args) {
            items.add(new Bulk(arg));
        }
        return new Array(items);
    }
}
