package dev.rajveer.keel.store;

import dev.rajveer.keel.persistence.AofLog;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * The keyspace. Updates to one key happen inside {@link ConcurrentHashMap#compute},
 * so that key is atomic and other keys can move at the same time.
 * A key is dropped when something touches it after its deadline (lazy expiry),
 * and {@link #activeExpireCycle()} goes looking for the ones nobody touches.
 */
public final class Database {
    public record Stored(String key, Value value, long expiresAt) {}

    private static final class Entry {
        private Value value;
        private long expiresAt;
    }

    private final ConcurrentHashMap<String, Entry> keys = new ConcurrentHashMap<>();
    private final LongSupplier clock;
    private AofLog aof = AofLog.disabled();
    private volatile boolean replaying;

    public Database() {
        this(System::currentTimeMillis);
    }

    public Database(LongSupplier clock) {
        this.clock = clock;
    }

    public void attach(AofLog log) {
        this.aof = log == null ? AofLog.disabled() : log;
    }

    public long now() {
        return clock.getAsLong();
    }

    public int replay(Path path, Consumer<List<byte[]>> apply) throws IOException {
        boolean previous = replaying;
        replaying = true;
        try {
            return AofLog.replay(path, apply);
        } finally {
            replaying = previous;
        }
    }

    public boolean set(byte[] rawKey, byte[] value, boolean nx, boolean xx, long expiresAt) {
        boolean[] wrote = {false};
        keys.compute(Bytes.str(rawKey), (k, e) -> {
            boolean live = e != null && !isExpired(e);
            if (nx && live) {
                return e;
            }
            if (xx && !live) {
                return null;
            }
            Entry next = entry(new Value.Str(value), expiresAt);
            log("SET", rawKey, value);
            if (expiresAt != 0) {
                log("PEXPIREAT", rawKey, Longs.bytes(expiresAt));
            }
            wrote[0] = true;
            return next;
        });
        return wrote[0];
    }

    public byte[] get(byte[] rawKey) {
        byte[][] out = {null};
        keys.compute(Bytes.str(rawKey), (k, e) -> {
            if (e == null || isExpired(e)) {
                return null;
            }
            if (!(e.value instanceof Value.Str str)) {
                throw StoreException.wrongType();
            }
            out[0] = str.data();
            return e;
        });
        return out[0];
    }

    public int del(List<byte[]> rawKeys) {
        int removed = 0;
        for (byte[] rawKey : rawKeys) {
            boolean[] gone = {false};
            keys.compute(Bytes.str(rawKey), (k, e) -> {
                if (e == null || isExpired(e)) {
                    return null;
                }
                log("DEL", rawKey);
                gone[0] = true;
                return null;
            });
            if (gone[0]) {
                removed++;
            }
        }
        return removed;
    }

    public int exists(List<byte[]> rawKeys) {
        int n = 0;
        for (byte[] rawKey : rawKeys) {
            if (live(rawKey)) {
                n++;
            }
        }
        return n;
    }

    public long incr(byte[] rawKey, long delta) {
        long[] result = {0};
        keys.compute(Bytes.str(rawKey), (k, e) -> {
            long current = 0;
            long exp = 0;
            if (e != null && isExpired(e)) {
                e = null;
            }
            if (e != null) {
                if (!(e.value instanceof Value.Str str)) {
                    throw StoreException.wrongType();
                }
                current = Longs.parse(str.data());
                exp = e.expiresAt;
            }
            long next;
            try {
                next = Math.addExact(current, delta);
            } catch (ArithmeticException ex) {
                throw StoreException.overflow();
            }
            log(delta < 0 ? "DECR" : "INCR", rawKey);
            result[0] = next;
            return entry(new Value.Str(Longs.bytes(next)), exp);
        });
        return result[0];
    }

    /** @return true when the key existed and the deadline was applied */
    public boolean expireAt(byte[] rawKey, long when) {
        boolean[] ok = {false};
        keys.compute(Bytes.str(rawKey), (k, e) -> {
            if (e == null || isExpired(e)) {
                return null;
            }
            ok[0] = true;
            log("PEXPIREAT", rawKey, Longs.bytes(when));
            if (when <= now()) {
                return null;
            }
            return entry(e.value, when);
        });
        return ok[0];
    }

    /** Seconds left, {@code -1} if the key lives forever, {@code -2} if it is missing. */
    public long ttl(byte[] rawKey) {
        long[] result = {-2};
        keys.compute(Bytes.str(rawKey), (k, e) -> {
            if (e == null || isExpired(e)) {
                return null;
            }
            if (e.expiresAt == 0) {
                result[0] = -1;
                return e;
            }
            long left = e.expiresAt - now();
            result[0] = Math.max(left, 0) / 1000;
            return e;
        });
        return result[0];
    }

    public List<byte[]> keys(byte[] pattern) {
        String pat = Bytes.str(pattern);
        List<byte[]> found = new ArrayList<>();
        for (String key : List.copyOf(keys.keySet())) {
            if (live(Bytes.bytes(key)) && Glob.matches(pat, key)) {
                found.add(Bytes.bytes(key));
            }
        }
        return found;
    }

    public int push(byte[] rawKey, List<byte[]> elements, boolean left) {
        int[] len = {0};
        keys.compute(Bytes.str(rawKey), (k, e) -> {
            long exp = 0;
            ArrayDeque<byte[]> items;
            if (e != null && isExpired(e)) {
                e = null;
            }
            if (e == null) {
                items = new ArrayDeque<>();
            } else if (e.value instanceof Value.ListVal list) {
                items = new ArrayDeque<>(list.items());
                exp = e.expiresAt;
            } else {
                throw StoreException.wrongType();
            }
            for (byte[] element : elements) {
                if (left) {
                    items.addFirst(element);
                } else {
                    items.addLast(element);
                }
            }
            log(left ? "LPUSH" : "RPUSH", withKey(rawKey, elements));
            len[0] = items.size();
            return entry(new Value.ListVal(items), exp);
        });
        return len[0];
    }

    public byte[] pop(byte[] rawKey, boolean left) {
        byte[][] out = {null};
        keys.compute(Bytes.str(rawKey), (k, e) -> {
            if (e == null || isExpired(e)) {
                return null;
            }
            if (!(e.value instanceof Value.ListVal list)) {
                throw StoreException.wrongType();
            }
            if (list.items().isEmpty()) {
                return null;
            }
            ArrayDeque<byte[]> items = new ArrayDeque<>(list.items());
            out[0] = left ? items.removeFirst() : items.removeLast();
            log(left ? "LPOP" : "RPOP", rawKey);
            if (items.isEmpty()) {
                return null;
            }
            return entry(new Value.ListVal(items), e.expiresAt);
        });
        return out[0];
    }

    public List<byte[]> lrange(byte[] rawKey, long startRaw, long stopRaw) {
        List<byte[]> out = new ArrayList<>();
        keys.compute(Bytes.str(rawKey), (k, e) -> {
            if (e == null || isExpired(e)) {
                return null;
            }
            if (!(e.value instanceof Value.ListVal list)) {
                throw StoreException.wrongType();
            }
            int len = list.items().size();
            int start = (int) startRaw;
            int stop = (int) stopRaw;
            if (startRaw < 0) {
                start = (int) Math.max(startRaw + len, Integer.MIN_VALUE);
            }
            if (stopRaw < 0) {
                stop = (int) Math.max(stopRaw + len, Integer.MIN_VALUE);
            }
            if (start < 0) {
                start = 0;
            }
            if (stop >= len) {
                stop = len - 1;
            }
            if (start > stop || start >= len) {
                return e;
            }
            int i = 0;
            for (byte[] item : list.items()) {
                if (i > stop) {
                    break;
                }
                if (i >= start) {
                    out.add(item);
                }
                i++;
            }
            return e;
        });
        return out;
    }

    public int hset(byte[] rawKey, List<byte[]> pairs) {
        int[] added = {0};
        keys.compute(Bytes.str(rawKey), (k, e) -> {
            long exp = 0;
            LinkedHashMap<String, byte[]> fields;
            if (e != null && isExpired(e)) {
                e = null;
            }
            if (e == null) {
                fields = new LinkedHashMap<>();
            } else if (e.value instanceof Value.HashVal hash) {
                fields = new LinkedHashMap<>(hash.fields());
                exp = e.expiresAt;
            } else {
                throw StoreException.wrongType();
            }
            for (int i = 0; i < pairs.size(); i += 2) {
                if (fields.put(Bytes.str(pairs.get(i)), pairs.get(i + 1)) == null) {
                    added[0]++;
                }
            }
            log("HSET", withKey(rawKey, pairs));
            return entry(new Value.HashVal(fields), exp);
        });
        return added[0];
    }

    public byte[] hget(byte[] rawKey, byte[] field) {
        byte[][] out = {null};
        keys.compute(Bytes.str(rawKey), (k, e) -> {
            if (e == null || isExpired(e)) {
                return null;
            }
            if (!(e.value instanceof Value.HashVal hash)) {
                throw StoreException.wrongType();
            }
            out[0] = hash.fields().get(Bytes.str(field));
            return e;
        });
        return out[0];
    }

    public int hdel(byte[] rawKey, List<byte[]> fields) {
        int[] removed = {0};
        keys.compute(Bytes.str(rawKey), (k, e) -> {
            if (e == null || isExpired(e)) {
                return null;
            }
            if (!(e.value instanceof Value.HashVal hash)) {
                throw StoreException.wrongType();
            }
            LinkedHashMap<String, byte[]> copy = new LinkedHashMap<>(hash.fields());
            for (byte[] field : fields) {
                if (copy.remove(Bytes.str(field)) != null) {
                    removed[0]++;
                }
            }
            if (removed[0] == 0) {
                return e;
            }
            log("HDEL", withKey(rawKey, fields));
            if (copy.isEmpty()) {
                return null;
            }
            return entry(new Value.HashVal(copy), e.expiresAt);
        });
        return removed[0];
    }

    public List<byte[]> hgetall(byte[] rawKey) {
        List<byte[]> out = new ArrayList<>();
        keys.compute(Bytes.str(rawKey), (k, e) -> {
            if (e == null || isExpired(e)) {
                return null;
            }
            if (!(e.value instanceof Value.HashVal hash)) {
                throw StoreException.wrongType();
            }
            for (var field : hash.fields().entrySet()) {
                out.add(Bytes.bytes(field.getKey()));
                out.add(field.getValue());
            }
            return e;
        });
        return out;
    }

    /**
     * Drop expired keys. A small keyspace is scanned whole. A bigger one checks 20
     * random keys, and if more than a quarter of them were dead it takes another pass.
     * Copying the key set is fine here; a larger server would walk the table in place.
     */
    public int activeExpireCycle() {
        int removedTotal = 0;
        for (int pass = 0; pass < 16; pass++) {
            List<String> all = new ArrayList<>(keys.keySet());
            if (all.isEmpty()) {
                break;
            }
            List<String> sample;
            if (all.size() <= 20) {
                sample = all;
            } else {
                Collections.shuffle(all, ThreadLocalRandom.current());
                sample = all.subList(0, 20);
            }
            int removed = 0;
            for (String key : sample) {
                if (expireIfDue(key)) {
                    removed++;
                }
            }
            removedTotal += removed;
            if (removed * 4 <= sample.size()) {
                break;
            }
        }
        return removedTotal;
    }

    public List<Stored> exportLive() {
        List<Stored> out = new ArrayList<>();
        for (String key : List.copyOf(keys.keySet())) {
            keys.compute(key, (k, e) -> {
                if (e == null || isExpired(e)) {
                    return null;
                }
                out.add(new Stored(k, copyValue(e.value), e.expiresAt));
                return e;
            });
        }
        return out;
    }

    public int importAll(List<Stored> entries) {
        keys.clear();
        int n = 0;
        for (Stored stored : entries) {
            if (stored.expiresAt() != 0 && stored.expiresAt() <= now()) {
                continue;
            }
            keys.put(stored.key(), entry(stored.value(), stored.expiresAt()));
            n++;
        }
        return n;
    }

    public int liveSize() {
        int n = 0;
        for (String key : List.copyOf(keys.keySet())) {
            if (live(Bytes.bytes(key))) {
                n++;
            }
        }
        return n;
    }

    private boolean live(byte[] rawKey) {
        boolean[] ok = {false};
        keys.compute(Bytes.str(rawKey), (k, e) -> {
            if (e == null || isExpired(e)) {
                return null;
            }
            ok[0] = true;
            return e;
        });
        return ok[0];
    }

    private boolean expireIfDue(String key) {
        boolean[] removed = {false};
        keys.compute(key, (k, e) -> {
            if (e == null || !isExpired(e)) {
                return e;
            }
            removed[0] = true;
            return null;
        });
        return removed[0];
    }

    private boolean isExpired(Entry e) {
        return e.expiresAt != 0 && e.expiresAt <= now();
    }

    private void log(String command, byte[]... args) {
        if (replaying || !aof.enabled()) {
            return;
        }
        try {
            aof.append(command, args);
        } catch (UncheckedIOException e) {
            throw new StoreException("ERR aof write failed");
        }
    }

    private static byte[][] withKey(byte[] key, List<byte[]> rest) {
        byte[][] all = new byte[rest.size() + 1][];
        all[0] = key;
        for (int i = 0; i < rest.size(); i++) {
            all[i + 1] = rest.get(i);
        }
        return all;
    }

    private static Entry entry(Value value, long expiresAt) {
        Entry e = new Entry();
        e.value = value;
        e.expiresAt = expiresAt;
        return e;
    }

    private static Value copyValue(Value value) {
        return switch (value) {
            case Value.Str str -> str;
            case Value.ListVal list -> new Value.ListVal(new ArrayDeque<>(list.items()));
            case Value.HashVal hash -> new Value.HashVal(new LinkedHashMap<>(hash.fields()));
        };
    }
}
