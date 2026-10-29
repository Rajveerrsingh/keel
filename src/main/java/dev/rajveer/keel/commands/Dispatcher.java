package dev.rajveer.keel.commands;

import dev.rajveer.keel.protocol.Resp;
import dev.rajveer.keel.store.Bytes;
import dev.rajveer.keel.store.Database;
import dev.rajveer.keel.store.StoreException;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class Dispatcher {
    private final Database db;
    private final Map<String, Command> commands = new HashMap<>();

    public Dispatcher(Database db, Path snapshotFile) {
        this.db = db;
        StringCommands.register(commands);
        KeyCommands.register(commands);
        ListCommands.register(commands);
        HashCommands.register(commands);
        ServerCommands.register(commands, snapshotFile);
    }

    public Resp dispatch(List<byte[]> argv) {
        if (argv.isEmpty()) {
            return Resp.error("ERR protocol error");
        }
        String name = Bytes.str(argv.get(0)).toUpperCase(Locale.ROOT);
        Command command = commands.get(name);
        if (command == null) {
            String shown = Bytes.str(argv.get(0)).toLowerCase(Locale.ROOT);
            return Resp.error("ERR unknown command '" + shown + "'");
        }
        try {
            return command.execute(db, argv.subList(1, argv.size()));
        } catch (StoreException e) {
            return Resp.error(e.getMessage());
        }
    }
}
