package com.slicercore.model;

/** A quote referenced a stored model id that is unknown or has expired; the client re-uploads. */
public class ModelNotFoundException extends RuntimeException {

    public ModelNotFoundException(String modelId) {
        super("Unknown or expired model id; upload the model again.");
    }
}
