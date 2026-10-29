package dev.rajveer.keel.commands;

import dev.rajveer.keel.protocol.Resp;
import dev.rajveer.keel.store.Bytes;
import dev.rajveer.keel.store.Database;
import dev.rajveer.keel.store.Longs;
import dev.rajveer.keel.store.StoreException;

import java.util.List;
import java.util.Locale;

public final class StringCommands {
    private StringCommands() {}

    public static void register(java.util.Map<String, Command> commands) {
        commands.put("PING", StringCommands::ping);
        commands.put("ECHO", StringCommands::echo);
        commands.put("SET", StringCommands::set);
        commands.put("GET", StringCommands::get);
        commands.put("DEL", StringCommands::del);
        commands.put("EXISTS", StringCommands::exists);
        commands.put("INCR", StringCommands::incr);
        commands.put("DECR", StringCommands::decr);
    }

    static Resp ping(Database db, List<byte[]> args) {
        if (args.isEmpty()) {
            return new Resp.Simple("PONG");
        }
        if (args.size() == 1) {
            return Resp.bulk(args.get(0));
        }
        return Resp.error(Errors.arity("ping"));
    }

    static Resp echo(Database db, List<byte[]> args) {
        if (args.size() != 1) {
            return Resp.error(Errors.arity("echo"));
        }
        return Resp.bulk(args.get(0));
    }

    static Resp get(Database db, List<byte[]> args) {
        if (args.size() != 1) {
            return Resp.error(Errors.arity("get"));
        }
        byte[] value = db.get(args.get(0));
        return value == null ? Resp.nil() : Resp.bulk(value);
    }

    static Resp del(Database db, List<byte[]> args) {
        if (args.isEmpty()) {
            return Resp.error(Errors.arity("del"));
        }
        return Resp.integer(db.del(args));
    }

    static Resp exists(Database db, List<byte[]> args) {
        if (args.isEmpty()) {
            return Resp.error(Errors.arity("exists"));
        }
        return Resp.integer(db.exists(args));
    }

    static Resp incr(Database db, List<byte[]> args) {
        return incrBy(db, args, 1, "incr");
    }

    static Resp decr(Database db, List<byte[]> args) {
        return incrBy(db, args, -1, "decr");
    }

    private static Resp incrBy(Database db, List<byte[]> args, long delta, String name) {
        if (args.size() != 1) {
            return Resp.error(Errors.arity(name));
        }
        return Resp.integer(db.incr(args.get(0), delta));
    }

    static Resp set(Database db, List<byte[]> args) {
        if (args.size() < 2) {
            return Resp.error(Errors.arity("set"));
        }
        boolean nx = false;
        boolean xx = false;
        Long ttlMillis = null;
        for (int i = 2; i < args.size(); i++) {
            String opt = Bytes.str(args.get(i)).toUpperCase(Locale.ROOT);
            switch (opt) {
                case "NX" -> {
                    if (xx) {
                        return Resp.error(StoreException.SYNTAX);
                    }
                    nx = true;
                }
                case "XX" -> {
                    if (nx) {
                        return Resp.error(StoreException.SYNTAX);
                    }
                    xx = true;
                }
                case "EX", "PX" -> {
                    if (ttlMillis != null || i + 1 >= args.size()) {
                        return Resp.error(StoreException.SYNTAX);
                    }
                    long n;
                    try {
                        n = Longs.parse(args.get(++i));
                    } catch (StoreException e) {
                        return Resp.error(e.getMessage());
                    }
                    if (n <= 0) {
                        return Resp.error("ERR invalid expire time in 'set' command");
                    }
                    try {
                        ttlMillis = "EX".equals(opt) ? Math.multiplyExact(n, 1000L) : n;
                    } catch (ArithmeticException e) {
                        return Resp.error("ERR invalid expire time in 'set' command");
                    }
                }
                default -> {
                    return Resp.error(StoreException.SYNTAX);
                }
            }
        }
        long expiresAt = 0;
        if (ttlMillis != null) {
            try {
                expiresAt = Math.addExact(db.now(), ttlMillis);
            } catch (ArithmeticException e) {
                return Resp.error("ERR invalid expire time in 'set' command");
            }
        }
        boolean wrote = db.set(args.get(0), args.get(1), nx, xx, expiresAt);
        return wrote ? Resp.ok() : Resp.nil();
    }
}
