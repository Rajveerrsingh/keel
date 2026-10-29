package dev.rajveer.keel.commands;

import dev.rajveer.keel.protocol.Resp;
import dev.rajveer.keel.store.Database;
import dev.rajveer.keel.store.Longs;
import dev.rajveer.keel.store.StoreException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class KeyCommands {
    private KeyCommands() {}

    public static void register(Map<String, Command> commands) {
        commands.put("EXPIRE", KeyCommands::expire);
        commands.put("PEXPIREAT", KeyCommands::pexpireat);
        commands.put("TTL", KeyCommands::ttl);
        commands.put("KEYS", KeyCommands::keys);
    }

    static Resp expire(Database db, List<byte[]> args) {
        if (args.size() != 2) {
            return Resp.error(Errors.arity("expire"));
        }
        long seconds;
        try {
            seconds = Longs.parse(args.get(1));
        } catch (StoreException e) {
            return Resp.error(e.getMessage());
        }
        long when;
        if (seconds <= 0) {
            when = db.now();
        } else {
            try {
                when = Math.addExact(db.now(), Math.multiplyExact(seconds, 1000L));
            } catch (ArithmeticException e) {
                return Resp.error("ERR invalid expire time in 'expire' command");
            }
        }
        return Resp.integer(db.expireAt(args.get(0), when) ? 1 : 0);
    }

    static Resp pexpireat(Database db, List<byte[]> args) {
        if (args.size() != 2) {
            return Resp.error(Errors.arity("pexpireat"));
        }
        long when;
        try {
            when = Longs.parse(args.get(1));
        } catch (StoreException e) {
            return Resp.error(e.getMessage());
        }
        return Resp.integer(db.expireAt(args.get(0), when) ? 1 : 0);
    }

    static Resp ttl(Database db, List<byte[]> args) {
        if (args.size() != 1) {
            return Resp.error(Errors.arity("ttl"));
        }
        return Resp.integer(db.ttl(args.get(0)));
    }

    static Resp keys(Database db, List<byte[]> args) {
        if (args.size() != 1) {
            return Resp.error(Errors.arity("keys"));
        }
        if (args.get(0).length > 1024) {
            return Resp.error("ERR pattern is too long");
        }
        List<byte[]> found = db.keys(args.get(0));
        List<Resp> items = new ArrayList<>(found.size());
        for (byte[] key : found) {
            items.add(Resp.bulk(key));
        }
        return Resp.array(items);
    }
}
