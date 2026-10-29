package dev.rajveer.keel;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KeyCommandTest {
    @Test
    void keysGlob() {
        Check c = new Check();
        Check.ok(c.call("SET", "hello", "1"));
        Check.ok(c.call("SET", "hallo", "1"));
        Check.ok(c.call("SET", "heeeello", "1"));
        Check.ok(c.call("SET", "world", "1"));
        Check.ok(c.call("SET", "h*llo", "1"));
        assertEquals(setOf("hello", "hallo", "heeeello", "world", "h*llo"), found(c, "*"));
        assertEquals(setOf("hello", "hallo", "h*llo"), found(c, "h?llo"));
        assertEquals(setOf("hello", "hallo", "heeeello", "h*llo"), found(c, "h*llo"));
        assertEquals(setOf("hallo", "h*llo"), found(c, "h[^e]llo"));
        assertEquals(setOf("h*llo"), found(c, "h\\*llo"));
        assertEquals(setOf("world"), found(c, "w*"));
        Check.error(c.call("KEYS"), "wrong number");
    }

    @Test
    void keysSkipsExpired() {
        Check c = new Check();
        Check.ok(c.call("SET", "live", "1"));
        Check.ok(c.call("SET", "dead", "1", "PX", "10"));
        c.now[0] += 10;
        assertEquals(List.of("live"), Check.strings(c.call("KEYS", "*")));
    }

    private static Set<String> found(Check c, String pattern) {
        return new HashSet<>(Check.strings(c.call("KEYS", pattern)));
    }

    private static Set<String> setOf(String... keys) {
        return new HashSet<>(List.of(keys));
    }
}
