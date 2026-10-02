package com.gavinhsmith.evoker;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Captures what evoker writes to stderr (warnings, errors) or stdout (progress, installer output). */
final class Output {
    private Output() {}

    static String err(Runnable action) {
        return capture(action, () -> System.err, System::setErr);
    }

    static String out(Runnable action) {
        return capture(action, () -> System.out, System::setOut);
    }

    private static String capture(Runnable action, Supplier<PrintStream> get, Consumer<PrintStream> set) {
        PrintStream original = get.get();
        var buffer = new ByteArrayOutputStream();
        set.accept(new PrintStream(buffer, true));
        try {
            action.run();
        } finally {
            set.accept(original);
        }
        return buffer.toString();
    }
}
