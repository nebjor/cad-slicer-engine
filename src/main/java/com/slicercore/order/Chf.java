package com.slicercore.order;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Swiss franc amounts: two decimals, cash-rounded to the nearest 0.05. */
public final class Chf {

    private static final BigDecimal STEP = new BigDecimal("0.05");

    private Chf() {
    }

    public static BigDecimal of(double value) {
        return round(BigDecimal.valueOf(value));
    }

    public static BigDecimal round(BigDecimal value) {
        return value.divide(STEP, 0, RoundingMode.HALF_UP).multiply(STEP).setScale(2, RoundingMode.HALF_UP);
    }
}
