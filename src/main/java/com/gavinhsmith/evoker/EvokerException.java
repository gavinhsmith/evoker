package com.gavinhsmith.evoker;

/** A user-facing error: printed as "error: message" and exits 1. */
class EvokerException extends RuntimeException {
    EvokerException(String message) {
        super(message);
    }

    EvokerException(String message, Throwable cause) {
        super(message, cause);
    }
}
