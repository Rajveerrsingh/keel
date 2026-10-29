package dev.rajveer.keel.store;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlobTest {
    @Test
    void starAndQuestion() {
        assertTrue(Glob.matches("*", ""));
        assertTrue(Glob.matches("*", "abc"));
        assertTrue(Glob.matches("h?llo", "hello"));
        assertFalse(Glob.matches("h?llo", "hllo"));
        assertTrue(Glob.matches("h*llo", "hllo"));
        assertTrue(Glob.matches("h*llo", "heeeello"));
        assertFalse(Glob.matches("h*llo", "world"));
    }

    @Test
    void classesAndEscapes() {
        assertTrue(Glob.matches("h[ae]llo", "hello"));
        assertTrue(Glob.matches("h[ae]llo", "hallo"));
        assertFalse(Glob.matches("h[ae]llo", "hxllo"));
        assertTrue(Glob.matches("h[^e]llo", "hallo"));
        assertFalse(Glob.matches("h[^e]llo", "hello"));
        assertTrue(Glob.matches("a[b-d]", "ac"));
        assertFalse(Glob.matches("a[b-d]", "ae"));
        assertTrue(Glob.matches("h\\*llo", "h*llo"));
        assertFalse(Glob.matches("h\\*llo", "hello"));
    }
}
