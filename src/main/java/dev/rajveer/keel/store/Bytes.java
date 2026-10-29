package dev.rajveer.keel.store;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/** Latin-1 so a key round-trips through a String without touching the bytes. */
public final class Bytes {
    public static final Charset LATIN1 = StandardCharsets.ISO_8859_1;

    private Bytes() {}

    public static String str(byte[] data) {
        return new String(data, LATIN1);
    }

    public static byte[] bytes(String text) {
        return text.getBytes(LATIN1);
    }
}
