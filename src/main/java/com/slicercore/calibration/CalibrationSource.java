package com.slicercore.calibration;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/** Where a calibrated model came from; serialised in lower case, as the admin API spells it. */
public enum CalibrationSource {
    /** A slice the quote path performed anyway: PLA, one copy, recorded without a queue. */
    QUOTE("quote"),
    /** An order item queued by the owner; the file lives in the order-files storage until erasure. */
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

    /** The source for its code as the API spells it ({@code quote}, {@code order}, {@code manual}), any case. */
    public static CalibrationSource fromCode(String code) {
        String wanted = code.trim().toLowerCase(Locale.ROOT);
        for (CalibrationSource source : values()) {
            if (source.code.equals(wanted)) {
                return source;
            }
        }
        throw new IllegalArgumentException("Unknown calibration source '" + code + "'; use quote, order or manual.");
    }
}
