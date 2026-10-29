package dev.rajveer.keel.store;

/** A command failed. The message is the RESP error text, without the leading '-'. */
public final class StoreException extends RuntimeException {
    public static final String SYNTAX = "ERR syntax error";
    public static final String NOT_INT = "ERR value is not an integer or out of range";
    public static final String OVERFLOW = "ERR increment or decrement would overflow";
    public static final String WRONG_TYPE =
            "WRONGTYPE Operation against a key holding the wrong kind of value";

    public StoreException(String message) {
        super(message);
    }

    public static StoreException notInt() {
        return new StoreException(NOT_INT);
    }

    public static StoreException overflow() {
        return new StoreException(OVERFLOW);
    }

    public static StoreException wrongType() {
        return new StoreException(WRONG_TYPE);
    }
}
