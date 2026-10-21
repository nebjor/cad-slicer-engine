package com.slicercore.order;

/**
 * Something another module deletes on the nightly retention run (see {@link OrderRetentionJob}),
 * such as expired login tokens. Implementations are picked up as beans.
 */
public interface RetentionSweep {

    /** What is swept, for the log, in the plural: "expired login tokens". */
    String subject();

    /** Deletes what is due and returns how many rows went. */
    int sweep();
}
