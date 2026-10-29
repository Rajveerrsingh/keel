package dev.rajveer.keel.commands;

import dev.rajveer.keel.persistence.Snapshot;
import dev.rajveer.keel.protocol.Resp;
import dev.rajveer.keel.store.Bytes;
import dev.rajveer.keel.store.Database;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ServerCommands {
    private ServerCommands() {}

    public static void register(Map<String, Command> commands, Path snapshotFile) {
        commands.put("SAVE", (db, args) -> save(db, args, snapshotFile));
        commands.put("QUIT", ServerCommands::quit);
        commands.put("CLIENT", ServerCommands::client);
    }

    static Resp save(Database db, List<byte[]> args, Path snapshotFile) {
        if (!args.isEmpty()) {
            return Resp.error(Errors.arity("save"));
        }
        try {
            Snapshot.save(db, snapshotFile);
            return Resp.ok();
        } catch (IOException e) {
            return Resp.error("ERR save failed: " + e.getMessage());
        }
    }

    static Resp quit(Database db, List<byte[]> args) {
        throw new CloseConnection();
    }

    /** redis-cli 7.2 sends CLIENT SETINFO on connect and ignores older servers. */
    static Resp client(Database db, List<byte[]> args) {
        if (args.isEmpty()) {
            return Resp.error(Errors.arity("client"));
        }
        String sub = Bytes.str(args.get(0)).toUpperCase(Locale.ROOT);
        if ("SETINFO".equals(sub)) {
            if (args.size() != 3) {
                return Resp.error(Errors.arity("client|setinfo"));
            }
            return Resp.ok();
        }
        return Resp.error("ERR unknown subcommand '" + Bytes.str(args.get(0)) + "'");
    }
}
