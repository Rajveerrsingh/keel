package dev.rajveer.keel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExpiryTest {
    @Test
    void lazyExpiryOnRead() {
        Check c = new Check();
        Check.ok(c.call("SET", "a", "1", "PX", "500"));
        Check.bulk(c.call("GET", "a"), "1");
        Check.integer(c.call("TTL", "a"), 0);
        c.now[0] += 500;
        Check.nil(c.call("GET", "a"));
        Check.integer(c.call("TTL", "a"), -2);
        Check.integer(c.call("EXISTS", "a"), 0);
    }

    @Test
    void exIsSeconds() {
        Check c = new Check();
        Check.ok(c.call("SET", "a", "1", "EX", "10"));
        Check.integer(c.call("TTL", "a"), 10);
        c.now[0] += 1500;
        Check.integer(c.call("TTL", "a"), 8);
    }

    @Test
    void expireZeroDeletesAndNegativeToo() {
        Check c = new Check();
        Check.ok(c.call("SET", "k", "v"));
        Check.integer(c.call("EXPIRE", "k", "0"), 1);
        Check.integer(c.call("EXISTS", "k"), 0);
        Check.ok(c.call("SET", "k", "v"));
        Check.integer(c.call("EXPIRE", "k", "-1"), 1);
        Check.integer(c.call("EXISTS", "k"), 0);
        Check.integer(c.call("EXPIRE", "missing", "5"), 0);
        Check.integer(c.call("TTL", "missing"), -2);
        Check.ok(c.call("SET", "k", "v"));
        Check.integer(c.call("TTL", "k"), -1);
        Check.integer(c.call("EXPIRE", "k", "5"), 1);
        Check.integer(c.call("TTL", "k"), 5);
    }

    @Test
    void pexpireatInThePastDeletes() {
        Check c = new Check();
        Check.ok(c.call("SET", "k", "v"));
        Check.integer(c.call("PEXPIREAT", "k", "1"), 1);
        Check.integer(c.call("EXISTS", "k"), 0);
        Check.integer(c.call("PEXPIREAT", "missing", "1"), 0);
    }

    @Test
    void nxTreatsExpiredKeyAsMissing() {
        Check c = new Check();
        Check.ok(c.call("SET", "k", "old", "PX", "10"));
        c.now[0] += 10;
        Check.ok(c.call("SET", "k", "new", "NX"));
        Check.bulk(c.call("GET", "k"), "new");
    }

    @Test
    void activeExpiryRemovesDueKeysAndLeavesTheRest() {
        Check c = new Check();
        Check.ok(c.call("SET", "keep", "yes", "EX", "100"));
        for (int i = 0; i < 15; i++) {
            Check.ok(c.call("SET", "old" + i, "x", "PX", "5"));
        }
        c.now[0] += 5;
        int removed = c.db.activeExpireCycle();
        assertEquals(15, removed);
        assertEquals(1, c.db.liveSize());
        Check.bulk(c.call("GET", "keep"), "yes");
    }

    @Test
    void activeExpirySamplesALargerKeyspace() {
        Check c = new Check();
        Check.ok(c.call("SET", "keep", "yes"));
        for (int i = 0; i < 30; i++) {
            Check.ok(c.call("SET", "t" + i, "x", "PX", "1"));
        }
        c.now[0] += 1;
        for (int i = 0; i < 5 && c.db.liveSize() > 1; i++) {
            c.db.activeExpireCycle();
        }
        assertEquals(1, c.db.liveSize());
        Check.bulk(c.call("GET", "keep"), "yes");
    }
}
