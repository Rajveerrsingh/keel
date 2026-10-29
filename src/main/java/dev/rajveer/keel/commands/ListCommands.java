package dev.rajveer.keel.commands;

import dev.rajveer.keel.protocol.Resp;
import dev.rajveer.keel.store.Database;
import dev.rajveer.keel.store.Longs;
import dev.rajveer.keel.store.StoreException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class ListCommands {
    private ListCommands() {}

    public static void register(Map<String, Command> commands) {
        commands.put("LPUSH", ListCommands::lpush);
        commands.put("RPUSH", ListCommands::rpush);
        commands.put("LPOP", ListCommands::lpop);
        commands.put("RPOP", ListCommands::rpop);
        commands.put("LRANGE", ListCommands::lrange);
    }

    static Resp lpush(Database db, List<byte[]> args) {
        return push(db, args, true, "lpush");
    }

    static Resp rpush(Database db, List<byte[]> args) {
        return push(db, args, false, "rpush");
    }

    private static Resp push(Database db, List<byte[]> args, boolean left, String name) {
        if (args.size() < 2) {
            return Resp.error(Errors.arity(name));
        }
        int len = db.push(args.get(0), args.subList(1, args.size()), left);
        return Resp.integer(len);
    }

    static Resp lpop(Database db, List<byte[]> args) {
        return pop(db, args, true, "lpop");
    }

    static Resp rpop(Database db, List<byte[]> args) {
        return pop(db, args, false, "rpop");
    }

    private static Resp pop(Database db, List<byte[]> args, boolean left, String name) {
        if (args.size() != 1) {
            return Resp.error(Errors.arity(name));
        }
        byte[] value = db.pop(args.get(0), left);
        return value == null ? Resp.nil() : Resp.bulk(value);
    }

    static Resp lrange(Database db, List<byte[]> args) {
        if (args.size() != 3) {
            return Resp.error(Errors.arity("lrange"));
        }
        long start;
        long stop;
        try {
            start = Longs.parse(args.get(1));
            stop = Longs.parse(args.get(2));
        } catch (StoreException e) {
            return Resp.error(e.getMessage());
        }
        List<byte[]> items = db.lrange(args.get(0), start, stop);
        List<Resp> bulks = new ArrayList<>(items.size());
        for (byte[] item : items) {
            bulks.add(Resp.bulk(item));
        }
        return Resp.array(bulks);
    }
}
