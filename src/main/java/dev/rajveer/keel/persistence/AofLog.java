package dev.rajveer.keel.persistence;

import dev.rajveer.keel.protocol.Resp;
import dev.rajveer.keel.protocol.RespException;
import dev.rajveer.keel.protocol.RespReader;
import dev.rajveer.keel.protocol.RespWriter;

import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Append-only log of mutating commands, stored as RESP arrays.
 * Replay reads them back through the same dispatcher as a live client.
 */
public final class AofLog implements Closeable {
    private final FileOutputStream file;
    private final FileChannel channel;
    private final RespWriter writer;
    private final boolean fsync;
    private boolean closed;

    private AofLog(FileOutputStream file, FileChannel channel, RespWriter writer, boolean fsync) {
        this.file = file;
        this.channel = channel;
        this.writer = writer;
        this.fsync = fsync;
    }

    public static AofLog disabled() {
        return new AofLog(null, null, null, false);
    }

    public static AofLog open(Path path, boolean fsync) throws IOException {
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        FileOutputStream file = new FileOutputStream(path.toFile(), true);
        return new AofLog(file, file.getChannel(), new RespWriter(file), fsync);
    }

    public boolean enabled() {
        return writer != null && !closed;
    }

    public synchronized void append(String command, byte[]... args) {
        if (!enabled()) {
            return;
        }
        try {
            writer.write(Resp.command(command, args));
            if (fsync) {
                channel.force(false);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Applies every complete command in the file. A torn tail (crash mid-write)
     * is cut off so the next append stays aligned.
     */
    public static int replay(Path path, Consumer<List<byte[]>> sink) throws IOException {
        if (!Files.isRegularFile(path) || Files.size(path) == 0) {
            return 0;
        }
        byte[] data = Files.readAllBytes(path);
        CountingInputStream counting = new CountingInputStream(new ByteArrayInputStream(data));
        RespReader reader = new RespReader(counting);
        int applied = 0;
        long good = 0;
        try {
            while (true) {
                Resp value = reader.read();
                if (value == null) {
                    break;
                }
                sink.accept(argv(value));
                applied++;
                good = counting.count;
            }
        } catch (EOFException | RespException e) {
            if (good < data.length) {
                try (FileChannel ch = FileChannel.open(path, StandardOpenOption.WRITE)) {
                    ch.truncate(good);
                }
                System.err.println("aof ended early, kept " + applied + " commands");
            }
        }
        return applied;
    }

    private static List<byte[]> argv(Resp value) throws RespException {
        if (!(value instanceof Resp.Array array) || array.items() == null) {
            throw new RespException("aof command is not an array");
        }
        List<byte[]> args = new ArrayList<>(array.items().size());
        for (Resp item : array.items()) {
            if (!(item instanceof Resp.Bulk bulk) || bulk.data() == null) {
                throw new RespException("aof argument is not a bulk string");
            }
            args.add(bulk.data());
        }
        if (args.isEmpty()) {
            throw new RespException("empty aof command");
        }
        return args;
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed || file == null) {
            return;
        }
        closed = true;
        file.close();
    }

    private static final class CountingInputStream extends FilterInputStream {
        private long count;

        CountingInputStream(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) {
                count++;
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) {
                count += n;
            }
            return n;
        }
    }
}
