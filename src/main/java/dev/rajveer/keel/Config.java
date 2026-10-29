package dev.rajveer.keel;

import java.nio.file.Path;
import java.util.Locale;

public record Config(
        int port,
        Path dir,
        boolean appendOnly,
        boolean fsync,
        Path aofFile,
        Path snapshotFile
) {
    public static final String USAGE = """
            keel - a small Redis-compatible server

            usage: keel [--port 6379] [--dir data]
                        [--appendonly yes|no] [--appendfsync always|no]
                        [--appendfilename keel.aof] [--dbfilename keel.rdb]
            """;

    public static Config parse(String[] args) {
        int port = 6379;
        Path dir = Path.of("data");
        boolean appendOnly = true;
        boolean fsync = true;
        String aofName = "keel.aof";
        String snapName = "keel.rdb";
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--port" -> port = parsePort(next(args, ++i, arg));
                case "--dir" -> dir = Path.of(next(args, ++i, arg));
                case "--appendonly" -> appendOnly = yesNo(next(args, ++i, arg));
                case "--appendfsync" -> fsync = fsync(next(args, ++i, arg));
                case "--appendfilename" -> aofName = next(args, ++i, arg);
                case "--dbfilename" -> snapName = next(args, ++i, arg);
                case "--help", "-h" -> {
                    System.out.print(USAGE);
                    System.exit(0);
                }
                default -> throw new IllegalArgumentException("unknown argument: " + arg);
            }
        }
        return new Config(port, dir, appendOnly, fsync, dir.resolve(aofName), dir.resolve(snapName));
    }

    private static String next(String[] args, int i, String flag) {
        if (i >= args.length) {
            throw new IllegalArgumentException("missing value for " + flag);
        }
        return args[i];
    }

    private static int parsePort(String text) {
        try {
            int port = Integer.parseInt(text);
            if (port < 0 || port > 65535) {
                throw new IllegalArgumentException("port out of range");
            }
            return port;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("bad port: " + text);
        }
    }

    private static boolean yesNo(String text) {
        return switch (text.toLowerCase(Locale.ROOT)) {
            case "yes", "true", "on", "1" -> true;
            case "no", "false", "off", "0" -> false;
            default -> throw new IllegalArgumentException("expected yes or no, got " + text);
        };
    }

    private static boolean fsync(String text) {
        return switch (text.toLowerCase(Locale.ROOT)) {
            case "always", "yes" -> true;
            case "no" -> false;
            default -> throw new IllegalArgumentException("expected always or no, got " + text);
        };
    }
}
