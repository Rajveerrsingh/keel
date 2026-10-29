package dev.rajveer.keel.store;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;

/** What a key points at. Strings, lists, and hashes are the only types keel stores. */
public sealed interface Value {
    record Str(byte[] data) implements Value {}

    record ListVal(ArrayDeque<byte[]> items) implements Value {}

    record HashVal(LinkedHashMap<String, byte[]> fields) implements Value {}
}
