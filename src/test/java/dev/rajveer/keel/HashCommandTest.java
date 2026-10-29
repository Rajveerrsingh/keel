package dev.rajveer.keel;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HashCommandTest {
    @Test
    void hsetHgetHdelHgetall() {
        Check c = new Check();
        Check.integer(c.call("HSET", "h", "f", "1", "g", "2"), 2);
        Check.integer(c.call("HSET", "h", "f", "9", "z", "3"), 1);
        Check.bulk(c.call("HGET", "h", "f"), "9");
        Check.nil(c.call("HGET", "h", "nope"));
        assertEquals(List.of("f", "9", "g", "2", "z", "3"), Check.strings(c.call("HGETALL", "h")));
        Check.integer(c.call("HDEL", "h", "g", "nope"), 1);
        assertEquals(List.of("f", "9", "z", "3"), Check.strings(c.call("HGETALL", "h")));
        Check.integer(c.call("HDEL", "h", "f", "z"), 2);
        Check.integer(c.call("EXISTS", "h"), 0);
        assertEquals(List.of(), Check.strings(c.call("HGETALL", "missing")));
        Check.nil(c.call("HGET", "missing", "f"));
        Check.integer(c.call("HDEL", "missing", "f"), 0);
    }

    @Test
    void arityAndWrongType() {
        Check c = new Check();
        Check.error(c.call("HSET", "h", "f"), "wrong number");
        Check.error(c.call("HSET", "h", "f", "v", "extra"), "wrong number");
        Check.ok(c.call("SET", "s", "v"));
        Check.error(c.call("HSET", "s", "f", "v"), "WRONGTYPE");
        Check.bulk(c.call("GET", "s"), "v");
        Check.integer(c.call("HSET", "h", "f", "v"), 1);
        Check.error(c.call("GET", "h"), "WRONGTYPE");
    }
}
