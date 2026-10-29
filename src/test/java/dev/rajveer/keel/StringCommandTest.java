package dev.rajveer.keel;

import dev.rajveer.keel.store.StoreException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StringCommandTest {
    @Test
    void pingAndEcho() {
        Check c = new Check();
        Check.pong(c.call("PING"));
        Check.bulk(c.call("PING", "hey"), "hey");
        Check.error(c.call("PING", "a", "b"), "wrong number");
        Check.bulk(c.call("ECHO", "hi"), "hi");
        Check.error(c.call("ECHO"), "wrong number");
        Check.pong(c.call("ping"));
    }

    @Test
    void setAndGet() {
        Check c = new Check();
        Check.nil(c.call("GET", "missing"));
        Check.ok(c.call("SET", "city", "Toronto"));
        Check.bulk(c.call("GET", "city"), "Toronto");
        Check.ok(c.call("SET", "city", "Ottawa"));
        Check.bulk(c.call("GET", "city"), "Ottawa");
        Check.error(c.call("GET"), "wrong number");
        Check.error(c.call("SET", "onlykey"), "wrong number");
    }

    @Test
    void setNxAndXx() {
        Check c = new Check();
        Check.nil(c.call("SET", "k", "1", "XX"));
        Check.ok(c.call("SET", "k", "1", "NX"));
        Check.nil(c.call("SET", "k", "2", "NX"));
        Check.bulk(c.call("GET", "k"), "1");
        Check.ok(c.call("SET", "k", "3", "XX"));
        Check.bulk(c.call("GET", "k"), "3");
        Check.error(c.call("SET", "k", "v", "NX", "XX"), "syntax");
        Check.error(c.call("SET", "k", "v", "EX"), "syntax");
        Check.error(c.call("SET", "k", "v", "EX", "1", "PX", "1"), "syntax");
        Check.error(c.call("SET", "k", "v", "EX", "0"), "invalid expire");
        Check.error(c.call("SET", "k", "v", "PX", "0"), "invalid expire");
        Check.error(c.call("SET", "k", "v", "EX", "-1"), "invalid expire");
        Check.error(c.call("SET", "k", "v", "EX", "abc"), "not an integer");
    }

    @Test
    void setClearsTtlUnlessAsked() {
        Check c = new Check();
        Check.ok(c.call("SET", "k", "v", "EX", "30"));
        Check.integer(c.call("TTL", "k"), 30);
        Check.ok(c.call("SET", "k", "v2"));
        Check.integer(c.call("TTL", "k"), -1);
        Check.ok(c.call("SET", "k", "v3", "EX", "10"));
        Check.ok(c.call("SET", "k", "v4", "XX"));
        Check.integer(c.call("TTL", "k"), -1);
        Check.bulk(c.call("GET", "k"), "v4");
    }

    @Test
    void delAndExists() {
        Check c = new Check();
        Check.integer(c.call("DEL", "nope"), 0);
        Check.ok(c.call("SET", "a", "1"));
        Check.ok(c.call("SET", "b", "2"));
        Check.integer(c.call("EXISTS", "a", "a", "nope"), 2);
        Check.integer(c.call("DEL", "a", "missing", "b"), 2);
        Check.integer(c.call("EXISTS", "a"), 0);
        Check.integer(c.call("DEL", "a", "a"), 0);
        Check.error(c.call("DEL"), "wrong number");
        Check.error(c.call("EXISTS"), "wrong number");
    }

    @Test
    void incrAndDecr() {
        Check c = new Check();
        Check.integer(c.call("INCR", "n"), 1);
        Check.integer(c.call("INCR", "n"), 2);
        Check.integer(c.call("DECR", "z"), -1);
        Check.ok(c.call("SET", "n", "x"));
        Check.error(c.call("INCR", "n"), "not an integer");
        Check.bulk(c.call("GET", "n"), "x");
        Check.ok(c.call("SET", "lead", "007"));
        Check.error(c.call("INCR", "lead"), "not an integer");
        Check.ok(c.call("SET", "plus", "+12"));
        Check.error(c.call("INCR", "plus"), "not an integer");
        Check.ok(c.call("SET", "big", "9223372036854775807"));
        Check.error(c.call("INCR", "big"), "overflow");
        Check.bulk(c.call("GET", "big"), "9223372036854775807");
        Check.ok(c.call("SET", "min", "-9223372036854775808"));
        Check.error(c.call("DECR", "min"), "overflow");
        Check.integer(c.call("INCR", "min"), Long.MIN_VALUE + 1);
    }

    @Test
    void incrKeepsTtlAndIsAtomic() throws Exception {
        Check c = new Check();
        Check.ok(c.call("SET", "n", "0", "EX", "50"));
        Check.integer(c.call("INCR", "n"), 1);
        Check.integer(c.call("TTL", "n"), 50);

        int threads = 8;
        int each = 250;
        List<Thread> workers = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            workers.add(Thread.startVirtualThread(() -> {
                for (int j = 0; j < each; j++) {
                    c.call("INCR", "hits");
                }
            }));
        }
        for (Thread worker : workers) {
            worker.join();
        }
        Check.bulk(c.call("GET", "hits"), Integer.toString(threads * each));
        assertEquals(StoreException.NOT_INT, "ERR value is not an integer or out of range");
    }

    @Test
    void binaryValueRoundTrip() {
        Check c = new Check();
        byte[] key = new byte[] {'k'};
        byte[] value = new byte[] {0, 'a', '\r', '\n', (byte) 0xff};
        Check.ok(c.callBytes("SET".getBytes(StandardCharsets.US_ASCII), key, value));
        var reply = c.callBytes("GET".getBytes(StandardCharsets.US_ASCII), key);
        org.junit.jupiter.api.Assertions.assertArrayEquals(
                value,
                org.junit.jupiter.api.Assertions.assertInstanceOf(dev.rajveer.keel.protocol.Resp.Bulk.class, reply).data());
    }

    @Test
    void unknownCommand() {
        Check c = new Check();
        Check.error(c.call("NOPE"), "unknown command");
        Check.pong(c.call("PING"));
    }
}
