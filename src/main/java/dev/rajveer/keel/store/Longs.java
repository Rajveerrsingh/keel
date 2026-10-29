package dev.rajveer.keel.store;

import java.nio.charset.StandardCharsets;

/**
 * Redis integer rules ({@code string2ll}): optional leading '-', no '+',
 * no leading zeros except the number 0 itself, and the value has to fit in a long.
 */
public final class Longs {
    private Longs() {}

    public static long parse(byte[] raw) {
        if (raw == null || raw.length == 0 || raw.length > 20) {
            throw StoreException.notInt();
        }
        int i = 0;
        boolean neg = raw[0] == '-';
        if (neg) {
            i++;
            if (i == raw.length) {
                throw StoreException.notInt();
            }
        }
        if (raw[i] == '0') {
            if (!neg && raw.length == 1) {
                return 0;
            }
            throw StoreException.notInt();
        }
        if (raw[i] < '1' || raw[i] > '9') {
            throw StoreException.notInt();
        }
        // Accumulate negative so Long.MIN_VALUE fits.
        long v = 0;
        for (; i < raw.length; i++) {
            int d = raw[i] - '0';
            if (d < 0 || d > 9) {
                throw StoreException.notInt();
            }
            if (v < Long.MIN_VALUE / 10) {
                throw StoreException.notInt();
            }
            v *= 10;
            if (v < Long.MIN_VALUE + d) {
                throw StoreException.notInt();
            }
            v -= d;
        }
        if (!neg) {
            if (v == Long.MIN_VALUE) {
                throw StoreException.notInt();
            }
            return -v;
        }
        return v;
    }

    public static byte[] bytes(long value) {
        return Long.toString(value).getBytes(StandardCharsets.US_ASCII);
    }
}
