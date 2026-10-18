package com.slicercore.model;

/** Raised when the slicer could not produce a usable result for an otherwise valid request. */
public class SlicingException extends RuntimeException {

    public SlicingException(String message) {
        super(message);
    }

    public SlicingException(String message, Throwable cause) {
        super(message, cause);
    }
}
