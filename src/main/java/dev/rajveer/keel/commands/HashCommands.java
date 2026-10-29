package dev.rajveer.keel.commands;

import dev.rajveer.keel.protocol.Resp;
import dev.rajveer.keel.store.Database;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class HashCommands {
    private HashCommands() {}

    public static void register(Map<String, Command> commands) {
        commands.put("HSET", HashCommands::hset);
        commands.put("HGET", HashCommands::hget);
        commands.put("HDEL", HashCommands::hdel);
        commands.put("HGETALL", HashCommands::hgetall);
    }

    static Resp hset(Database db, List<byte[]> args) {
        if (args.size() < 3 || args.size() % 2 == 0) {
            return Resp.error(Errors.arity("hset"));
        }
        return Resp.integer(db.hset(args.get(0), args.subList(1, args.size())));
    }

    static Resp hget(Database db, List<byte[]> args) {
        if (args.size() != 2) {
            return Resp.error(Errors.arity("hget"));
        }
        byte[] value = db.hget(args.get(0), args.get(1));
        return value == null ? Resp.nil() : Resp.bulk(value);
    }

    static Resp hdel(Database db, List<byte[]> args) {
        if (args.size() < 2) {
            return Resp.error(Errors.arity("hdel"));
        }
        return Resp.integer(db.hdel(args.get(0), args.subList(1, args.size())));
    }

    static Resp hgetall(Database db, List<byte[]> args) {
        if (args.size() != 1) {
            return Resp.error(Errors.arity("hgetall"));
        }
        List<byte[]> flat = db.hgetall(args.get(0));
        List<Resp> items = new ArrayList<>(flat.size());
        for (byte[] part : flat) {
            items.add(Resp.bulk(part));
        }
        return Resp.array(items);
    }
}
