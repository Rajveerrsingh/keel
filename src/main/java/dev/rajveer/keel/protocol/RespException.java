package dev.rajveer.keel.protocol;

import java.io.IOException;

/** A frame that is not valid RESP. The connection is usually closed after this. */
public final class RespException extends IOException {
    public RespException(String message) {
        super(message);
    }
}
