package dev.rajveer.keel.protocol;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RespTest {

    @Test
    void roundTripSimpleErrorAndInteger() throws Exception {
        Resp value = new Resp.Array(List.of(
                new Resp.Simple("OK"),
                new Resp.Error("ERR nope"),
                new Resp.Int(-7),
                new Resp.Int(0)
        ));
        Resp back = read(write(value));
        Resp.Array array = assertInstanceOf(Resp.Array.class, back);
        assertEquals("OK", assertInstanceOf(Resp.Simple.class, array.items().get(0)).text());
        assertEquals("ERR nope", assertInstanceOf(Resp.Error.class, array.items().get(1)).text());
        assertEquals(-7, assertInstanceOf(Resp.Int.class, array.items().get(2)).value());
        assertEquals(0, assertInstanceOf(Resp.Int.class, array.items().get(3)).value());
    }

    @Test
    void bulkHoldsRawBytesIncludingCrlf() throws Exception {
        byte[] raw = new byte[] {0, 'a', '\r', '\n', (byte) 0xff};
        Resp back = read(write(new Resp.Bulk(raw)));
        assertArrayEquals(raw, assertInstanceOf(Resp.Bulk.class, back).data());
    }

    @Test
    void emptyBulkAndEmptyArray() throws Exception {
        Resp back = read(write(new Resp.Array(List.of(new Resp.Bulk(new byte[0])))));
        Resp.Array array = assertInstanceOf(Resp.Array.class, back);
        assertEquals(0, assertInstanceOf(Resp.Bulk.class, array.items().get(0)).data().length);
    }

    @Test
    void nullBulkAndNullArray() throws Exception {
        assertNull(assertInstanceOf(Resp.Bulk.class, read(bytes("$-1\r\n"))).data());
        assertNull(assertInstanceOf(Resp.Array.class, read(bytes("*-1\r\n"))).items());
    }

    @Test
    void commandArrayRoundTrip() throws Exception {
        byte[] key = "user".getBytes(StandardCharsets.UTF_8);
        byte[] value = "raj".getBytes(StandardCharsets.UTF_8);
        Resp.Array cmd = Resp.command("SET", key, value);
        Resp.Array back = assertInstanceOf(Resp.Array.class, read(write(cmd)));
        assertEquals("SET", new String(assertInstanceOf(Resp.Bulk.class, back.items().get(0)).data()));
        assertArrayEquals(key, assertInstanceOf(Resp.Bulk.class, back.items().get(1)).data());
        assertArrayEquals(value, assertInstanceOf(Resp.Bulk.class, back.items().get(2)).data());
    }

    @Test
    void cleanEofIsNull() throws Exception {
        assertNull(new RespReader(new ByteArrayInputStream(new byte[0])).read());
    }

    @Test
    void badPrefix() {
        assertThrows(RespException.class, () -> read(bytes("PING\r\n")));
    }

    @Test
    void truncatedBulk() {
        assertThrows(EOFException.class, () -> read(bytes("$4\r\nabc")));
    }

    @Test
    void missingCrlfAfterBulk() {
        assertThrows(RespException.class, () -> read(bytes("$3\r\nfooXX")));
    }

    private static byte[] write(Resp value) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new RespWriter(out).write(value);
        return out.toByteArray();
    }

    private static Resp read(byte[] data) throws Exception {
        return new RespReader(new ByteArrayInputStream(data)).read();
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }
}
