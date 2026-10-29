package dev.rajveer.keel;

import dev.rajveer.keel.commands.Dispatcher;
import dev.rajveer.keel.persistence.AofLog;
import dev.rajveer.keel.persistence.Snapshot;
import dev.rajveer.keel.protocol.Resp;
import dev.rajveer.keel.server.KeelServer;
import dev.rajveer.keel.store.Database;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;

public final class Keel {
    private Keel() {}

    public static void main(String[] args) {
        Config cfg;
        try {
            cfg = Config.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.err.println();
            System.err.print(Config.USAGE);
            System.exit(2);
            return;
        }
        try {
            run(cfg);
        } catch (UncheckedIOException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            System.err.println("keel: " + cause.getMessage());
            System.exit(1);
        } catch (IOException e) {
            System.err.println("keel: " + e.getMessage());
            System.exit(1);
        }
    }

    static void run(Config cfg) throws IOException {
        Files.createDirectories(cfg.dir());
        Database db = new Database();
        Dispatcher dispatcher = new Dispatcher(db, cfg.snapshotFile());
        if (cfg.appendOnly() && Files.isRegularFile(cfg.aofFile())) {
            int n = db.replay(cfg.aofFile(), argv -> {
                Resp reply = dispatcher.dispatch(argv);
                if (reply instanceof Resp.Error err) {
                    throw new UncheckedIOException(new IOException(err.text()));
                }
            });
            if (n > 0) {
                System.out.println("replayed " + n + " commands from " + cfg.aofFile());
            }
        } else if (!cfg.appendOnly() && Files.isRegularFile(cfg.snapshotFile())) {
            int n = Snapshot.load(db, cfg.snapshotFile());
            System.out.println("loaded " + n + " keys from " + cfg.snapshotFile());
        }
        AofLog aof = cfg.appendOnly() ? AofLog.open(cfg.aofFile(), cfg.fsync()) : AofLog.disabled();
        db.attach(aof);
        KeelServer server = new KeelServer(cfg.port(), db, dispatcher);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                server.close();
                aof.close();
            } catch (IOException ignored) {
                // already going down
            }
        }, "keel-shutdown"));
        System.out.println("keel listening on " + server.port());
        server.serve();
    }
}
