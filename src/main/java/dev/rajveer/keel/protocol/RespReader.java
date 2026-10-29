package dev.rajveer.keel.protocol;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Reads one RESP2 value at a time. Each bulk string is a fresh array, so the
 * store can keep it.
 */
public final class RespReader {
    static final int MAX_BULK = 64 * 1024 * 1024;
    static final int MAX_ARRAY = 1_000_000;
    static final int MAX_LINE = 64 * 1024;

    private final InputStream in;

    public RespReader(InputStream in) {
        this.in = in;
    }

    /** @return the next value, or null when the peer closes cleanly before a byte */
    public Resp read() throws IOException {
        int b = in.read();
        if (b < 0) {
            return null;
        }
        return readPrefixed((byte) b);
    }

    private Resp readPrefixed(byte prefix) throws IOException {
        return switch (prefix) {
            case '+' -> new Resp.Simple(readLine());
            case '-' -> new Resp.Error(readLine());
            case ':' -> new Resp.Int(parseLen(readLine()));
            case '$' -> readBulk();
            case '*' -> readArray();
            default -> throw new RespException("bad prefix: " + (char) prefix);
        };
    }

    private Resp readBulk() throws IOException {
        long len = parseLen(readLine());
        if (len < 0) {
            if (len == -1) {
                return new Resp.Bulk(null);
            }
            throw new RespException("invalid bulk length");
        }
        if (len > MAX_BULK) {
            throw new RespException("bulk too large");
        }
        byte[] data = readExact((int) len);
        expectCrlf();
        return new Resp.Bulk(data);
    }

    private Resp readArray() throws IOException {
        long len = parseLen(readLine());
        if (len < 0) {
            if (len == -1) {
                return new Resp.Array(null);
            }
            throw new RespException("invalid array length");
        }
        if (len > MAX_ARRAY) {
            throw new RespException("array too large");
        }
        List<Resp> items = new ArrayList<>((int) len);
        for (long i = 0; i < len; i++) {
            int b = in.read();
            if (b < 0) {
                throw new EOFException("truncated array");
            }
            items.add(readPrefixed((byte) b));
        }
        return new Resp.Array(items);
    }

    private String readLine() throws IOException {
        byte[] buf = new byte[64];
        int n = 0;
        while (true) {
            int b = in.read();
            if (b < 0) {
                throw new EOFException("truncated line");
            }
            if (b == '\r') {
                int next = in.read();
                if (next != '\n') {
                    throw new RespException("expected LF");
                }
                return new String(buf, 0, n, StandardCharsets.UTF_8);
            }
            if (b == '\n') {
                throw new RespException("bare LF");
            }
            if (n == buf.length) {
                if (n >= MAX_LINE) {
                    throw new RespException("line too long");
                }
                buf = Arrays.copyOf(buf, Math.min(buf.length * 2, MAX_LINE));
            }
            buf[n++] = (byte) b;
        }
    }

    private byte[] readExact(int len) throws IOException {
        byte[] data = new byte[len];
        int off = 0;
        while (off < len) {
            int n = in.read(data, off, len - off);
            if (n < 0) {
                throw new EOFException("truncated bulk");
            }
            off += n;
        }
        return data;
    }

    private void expectCrlf() throws IOException {
        int c1 = in.read();
        int c2 = in.read();
        if (c1 < 0 || c2 < 0) {
            throw new EOFException("truncated bulk");
        }
        if (c1 != '\r' || c2 != '\n') {
            throw new RespException("expected CRLF");
        }
    }

    private static long parseLen(String line) throws RespException {
        if (line.isEmpty()) {
            throw new RespException("empty length");
        }
        int i = 0;
        boolean neg = false;
        if (line.charAt(0) == '-') {
            neg = true;
            i++;
            if (i == line.length()) {
                throw new RespException("bad length");
            }
        } else if (line.charAt(0) == '+') {
            i++;
            if (i == line.length()) {
                throw new RespException("bad length");
            }
        }
        long n = 0;
        for (; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c < '0' || c > '9') {
                throw new RespException("bad length");
            }
            n = n * 10 + (c - '0');
            if (n > Integer.MAX_VALUE) {
                throw new RespException("length too big");
            }
        }
        return neg ? -n : n;
    }
}
