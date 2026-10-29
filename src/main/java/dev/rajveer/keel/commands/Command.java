package dev.rajveer.keel.commands;

import dev.rajveer.keel.protocol.Resp;
import dev.rajveer.keel.store.Database;

import java.util.List;

@FunctionalInterface
public interface Command {
    Resp execute(Database db, List<byte[]> args);
}
