package com.navan.travel;

import java.math.BigDecimal;

/**
 * Amounts are long minor units internally; decimal conversion happens only at the boundary.
 */
public final class Money {
    private Money() {
    }

    public static long minor(BigDecimal amount) {
        if (amount == null || amount.signum() < 0) throw ApiException.bad("Money must be a non-negative amount");
        try {
            return amount.movePointRight(2).longValueExact();
        } catch (ArithmeticException e) {
            throw ApiException.bad("Money must fit in long minor units and have at most two decimal places");
        }
    }

    public static BigDecimal decimal(long minor) {
        return BigDecimal.valueOf(minor, 2);
    }

    public static long add(long a, long b) {
        try {
            return Math.addExact(a, b);
        } catch (ArithmeticException e) {
            throw ApiException.bad("Money total exceeds long minor-unit capacity");
        }
    }
}
