package dev.rajveer.keel.protocol;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Writes RESP2. The caller flushes by calling {@link #write}, which pushes one value. */
public final class RespWriter {
    private final OutputStream out;

    public RespWriter(OutputStream out) {
        this.out = out;
    }

    public void write(Resp value) throws IOException {
        writeValue(value);
        out.flush();
    }

    private void writeValue(Resp value) throws IOException {
        switch (value) {
            case Resp.Simple s -> {
                out.write('+');
                writeLine(s.text());
            }
            case Resp.Error e -> {
                out.write('-');
                writeLine(e.text());
            }
            case Resp.Int i -> {
                out.write(':');
                writeLine(Long.toString(i.value()));
            }
            case Resp.Bulk b -> writeBulk(b.data());
            case Resp.Array a -> writeArray(a.items());
        }
    }

    private void writeBulk(byte[] data) throws IOException {
        out.write('$');
        if (data == null) {
            writeLine("-1");
            return;
        }
        writeLine(Integer.toString(data.length));
        out.write(data);
        out.write('\r');
        out.write('\n');
    }

    private void writeArray(java.util.List<Resp> items) throws IOException {
        out.write('*');
        if (items == null) {
            writeLine("-1");
            return;
        }
        writeLine(Integer.toString(items.size()));
        for (Resp item : items) {
            writeValue(item);
        }
    }

    private void writeLine(String text) throws IOException {
        out.write(text.getBytes(StandardCharsets.UTF_8));
        out.write('\r');
        out.write('\n');
    }
}
