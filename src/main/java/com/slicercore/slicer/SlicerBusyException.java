package com.slicercore.slicer;

/** Raised when no slicer slot became free within the configured wait time. */
public class SlicerBusyException extends RuntimeException {

    private final long retryAfterSeconds;

    public SlicerBusyException(String message, long retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
