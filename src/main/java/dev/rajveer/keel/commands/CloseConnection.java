package dev.rajveer.keel.commands;

/** QUIT. The server writes +OK and closes the socket. */
public final class CloseConnection extends RuntimeException {
    public CloseConnection() {
        super("QUIT");
    }
}
