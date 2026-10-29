package dev.rajveer.keel.store;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LongsTest {
    @Test
    void parsesPlainIntegers() {
        assertEquals(0, Longs.parse(b("0")));
        assertEquals(42, Longs.parse(b("42")));
        assertEquals(-7, Longs.parse(b("-7")));
        assertEquals(Long.MAX_VALUE, Longs.parse(b("9223372036854775807")));
        assertEquals(Long.MIN_VALUE, Longs.parse(b("-9223372036854775808")));
    }

    @Test
    void rejectsRedisNonIntegers() {
        for (String bad : new String[] {"", "-0", "00", "01", "+1", "+0", "-", "1 ", " 1", "9223372036854775808", "-9223372036854775809"}) {
            assertThrows(StoreException.class, () -> Longs.parse(b(bad)), bad);
        }
    }

    @Test
    void bytesRoundTrip() {
        assertEquals("-12", new String(Longs.bytes(-12), StandardCharsets.US_ASCII));
    }

    private static byte[] b(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }
}
