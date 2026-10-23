package com.slicercore.upload;

/** Raised when an upload could not be staged or kept for a reason on the server's side. */
public class UploadFailedException extends RuntimeException {

    public UploadFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
