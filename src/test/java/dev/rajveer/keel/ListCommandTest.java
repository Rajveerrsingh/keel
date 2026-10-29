package dev.rajveer.keel;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ListCommandTest {
    @Test
    void pushPopAndRange() {
        Check c = new Check();
        Check.integer(c.call("LPUSH", "l", "a", "b", "c"), 3);
        assertEquals(List.of("c", "b", "a"), Check.strings(c.call("LRANGE", "l", "0", "-1")));
        Check.integer(c.call("RPUSH", "r", "a", "b", "c"), 3);
        assertEquals(List.of("a", "b", "c"), Check.strings(c.call("LRANGE", "r", "0", "-1")));
        Check.bulk(c.call("LPOP", "r"), "a");
        Check.bulk(c.call("RPOP", "r"), "c");
        assertEquals(List.of("b"), Check.strings(c.call("LRANGE", "r", "0", "-1")));
        Check.bulk(c.call("LPOP", "r"), "b");
        Check.integer(c.call("EXISTS", "r"), 0);
        Check.nil(c.call("LPOP", "missing"));
        assertEquals(List.of(), Check.strings(c.call("LRANGE", "missing", "0", "-1")));
    }

    @Test
    void rangeEdges() {
        Check c = new Check();
        Check.integer(c.call("RPUSH", "r", "a", "b", "c"), 3);
        assertEquals(List.of("a", "b"), Check.strings(c.call("LRANGE", "r", "0", "-2")));
        assertEquals(List.of(), Check.strings(c.call("LRANGE", "r", "5", "1")));
        assertEquals(List.of("a", "b", "c"), Check.strings(c.call("LRANGE", "r", "-100", "100")));
        Check.error(c.call("LRANGE", "r", "x", "1"), "not an integer");
        Check.error(c.call("LPUSH", "r"), "wrong number");
    }

    @Test
    void wrongTypeLeavesTheString() {
        Check c = new Check();
        Check.ok(c.call("SET", "s", "v"));
        Check.error(c.call("LPUSH", "s", "a"), "WRONGTYPE");
        Check.bulk(c.call("GET", "s"), "v");
        Check.integer(c.call("LPUSH", "l", "a"), 1);
        Check.error(c.call("GET", "l"), "WRONGTYPE");
        Check.bulk(c.call("LPOP", "l"), "a");
    }

    @Test
    void pushKeepsTtl() {
        Check c = new Check();
        Check.ok(c.call("SET", "placeholder", "x"));
        Check.integer(c.call("RPUSH", "jobs", "a"), 1);
        Check.integer(c.call("EXPIRE", "jobs", "20"), 1);
        Check.integer(c.call("RPUSH", "jobs", "b"), 2);
        Check.integer(c.call("TTL", "jobs"), 20);
    }
}
