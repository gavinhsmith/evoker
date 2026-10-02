package com.gavinhsmith.evoker;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

/** Captures what evoker writes to stderr (warnings, errors). */
final class Stderr {
    private Stderr() {}

    static String capture(Runnable action) {
        PrintStream original = System.err;
        var buffer = new ByteArrayOutputStream();
        System.setErr(new PrintStream(buffer, true));
        try {
            action.run();
        } finally {
            System.setErr(original);
        }
        return buffer.toString();
    }
}
