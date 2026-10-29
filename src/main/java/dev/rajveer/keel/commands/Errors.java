package dev.rajveer.keel.commands;

public final class Errors {
    private Errors() {}

    public static String arity(String command) {
        return "ERR wrong number of arguments for '" + command + "' command";
    }
}
