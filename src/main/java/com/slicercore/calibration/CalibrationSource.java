package com.slicercore.calibration;

import com.fasterxml.jackson.annotation.JsonValue;

/** Where a calibrated model came from; serialised in lower case, as the admin API spells it. */
public enum CalibrationSource {
    /** An item of a placed order; the file lives in the order-files storage until erasure. */
    ORDER("order"),
    /** Queued by the owner from the upload store; the file is copied into the calibration directory. */
    MANUAL("manual");

    private final String code;

    CalibrationSource(String code) {
        this.code = code;
    }

    @JsonValue
    public String code() {
        return code;
    }
}
