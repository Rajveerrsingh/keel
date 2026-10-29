package dev.rajveer.keel.persistence;

import dev.rajveer.keel.store.Bytes;
import dev.rajveer.keel.store.Database;
import dev.rajveer.keel.store.Value;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * A small snapshot, not Redis RDB. Magic, a version byte, then records, then 0xFF.
 * Loaded only when the AOF is off, so the two files don't fight over startup.
 */
public final class Snapshot {
    private static final byte[] MAGIC = {'K', 'E', 'E', 'L', 'S', 'N', 'A', 'P'};
    private static final int VERSION = 1;
    private static final int STRING = 1;
    private static final int LIST = 2;
    private static final int HASH = 3;
    private static final int EOF = 0xFF;
    private static final int MAX_BLOB = 64 * 1024 * 1024;

    private Snapshot() {}

    public static void save(Database db, Path path) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = path.resolveSibling(path.getFileName().toString() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)))) {
            out.write(MAGIC);
            out.writeByte(VERSION);
            for (Database.Stored stored : db.exportLive()) {
                writeRecord(out, stored);
            }
            out.writeByte(EOF);
        }
        try {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static int load(Database db, Path path) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            byte[] magic = in.readNBytes(8);
            if (!Arrays.equals(magic, MAGIC)) {
                throw new IOException("not a keel snapshot");
            }
            int version = in.readUnsignedByte();
            if (version != VERSION) {
                throw new IOException("unsupported snapshot version " + version);
            }
            List<Database.Stored> entries = new ArrayList<>();
            while (true) {
                int type = in.readUnsignedByte();
                if (type == EOF) {
                    break;
                }
                long expiresAt = in.readLong();
                String key = Bytes.str(readBlob(in));
                Value value = switch (type) {
                    case STRING -> new Value.Str(readBlob(in));
                    case LIST -> readList(in);
                    case HASH -> readHash(in);
                    default -> throw new IOException("bad record type " + type);
                };
                entries.add(new Database.Stored(key, value, expiresAt));
            }
            return db.importAll(entries);
        }
    }

    private static void writeRecord(DataOutputStream out, Database.Stored stored) throws IOException {
        switch (stored.value()) {
            case Value.Str str -> {
                out.writeByte(STRING);
                out.writeLong(stored.expiresAt());
                writeBlob(out, Bytes.bytes(stored.key()));
                writeBlob(out, str.data());
            }
            case Value.ListVal list -> {
                out.writeByte(LIST);
                out.writeLong(stored.expiresAt());
                writeBlob(out, Bytes.bytes(stored.key()));
                out.writeInt(list.items().size());
                for (byte[] item : list.items()) {
                    writeBlob(out, item);
                }
            }
            case Value.HashVal hash -> {
                out.writeByte(HASH);
                out.writeLong(stored.expiresAt());
                writeBlob(out, Bytes.bytes(stored.key()));
                out.writeInt(hash.fields().size());
                for (var field : hash.fields().entrySet()) {
                    writeBlob(out, Bytes.bytes(field.getKey()));
                    writeBlob(out, field.getValue());
                }
            }
        }
    }

    private static Value.ListVal readList(DataInputStream in) throws IOException {
        int n = in.readInt();
        if (n < 0 || n > 10_000_000) {
            throw new IOException("bad list length");
        }
        ArrayDeque<byte[]> items = new ArrayDeque<>(Math.min(n, 16));
        for (int i = 0; i < n; i++) {
            items.addLast(readBlob(in));
        }
        return new Value.ListVal(items);
    }

    private static Value.HashVal readHash(DataInputStream in) throws IOException {
        int n = in.readInt();
        if (n < 0 || n > 10_000_000) {
            throw new IOException("bad hash length");
        }
        LinkedHashMap<String, byte[]> fields = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            fields.put(Bytes.str(readBlob(in)), readBlob(in));
        }
        return new Value.HashVal(fields);
    }

    private static void writeBlob(DataOutputStream out, byte[] data) throws IOException {
        out.writeInt(data.length);
        out.write(data);
    }

    private static byte[] readBlob(DataInputStream in) throws IOException {
        int len = in.readInt();
        if (len < 0 || len > MAX_BLOB) {
            throw new IOException("bad blob length");
        }
        byte[] data = new byte[len];
        in.readFully(data);
        return data;
    }
}
