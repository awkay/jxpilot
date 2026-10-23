package com.fulcrologic.jxpilot.client.render;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NumberTextTest {
    static String intText(int v) {
        char[] out = new char[20];
        int n = NumberText.formatInt(v, out, 3);
        return new String(out, 3, n);
    }

    static String fixed(double v, int d) {
        char[] out = new char[40];
        int n = NumberText.formatFixed(v, d, out, 2);
        return new String(out, 2, n);
    }

    @Nested
    class FormatInt {
        @Test
        void writesDecimalAtTheOffset() {
            assertAll(
                () -> assertEquals("0", intText(0), "zero"),
                () -> assertEquals("7", intText(7), "one digit"),
                () -> assertEquals("10", intText(10), "power of ten"),
                () -> assertEquals("1234567", intText(1234567), "many digits"),
                () -> assertEquals("-42", intText(-42), "negative with a minus sign"),
                () -> assertEquals("2147483647", intText(Integer.MAX_VALUE), "max int"),
                () -> assertEquals("-2147483648", intText(Integer.MIN_VALUE), "min int is exact"),
                () -> assertEquals(11, NumberText.MAX_INT_CHARS, "longest output is 11 chars"));
        }
    }

    @Nested
    class FormatFixed {
        @Test
        void roundsHalfUpToTheRequestedDecimals() {
            assertAll(
                () -> assertEquals("3.14", fixed(3.14159, 2), "truncating digit below half"),
                () -> assertEquals("0.13", fixed(0.125, 2), "exact half rounds up"),
                () -> assertEquals("1.0", fixed(0.96, 1), "carry into the integer part"),
                () -> assertEquals("60.0", fixed(59.96, 1), "carry across digits"),
                () -> assertEquals("0.05", fixed(0.05, 2), "leading zero in the fraction"),
                () -> assertEquals("12", fixed(12.4, 0), "no point for zero decimals"),
                () -> assertEquals("13", fixed(12.5, 0), "half rounds up with zero decimals"));
        }

        @Test
        void negativeValuesRoundAwayFromZero() {
            assertAll(
                () -> assertEquals("-0.13", fixed(-0.125, 2), "negative half rounds away from zero"),
                () -> assertEquals("-2.50", fixed(-2.5, 2), "negative with trailing zero"),
                () -> assertEquals("0.000", fixed(-0.0004, 3), "rounds to zero without a minus sign"));
        }

        @Test
        void clampsDecimals() {
            assertAll(
                () -> assertEquals("2", fixed(1.6, -3), "negative decimals act as 0"),
                () -> assertEquals("0.123456789", fixed(0.123456789, 12), "more than 9 decimals act as 9"));
        }

        @Test
        void specialValues() {
            assertAll(
                () -> assertEquals("NaN", fixed(Double.NaN, 2), "NaN"),
                () -> assertEquals("Inf", fixed(Double.POSITIVE_INFINITY, 2), "positive infinity"),
                () -> assertEquals("-Inf", fixed(Double.NEGATIVE_INFINITY, 2), "negative infinity"),
                () -> assertEquals("Inf", fixed(4503599627370496.0, 0), "scaled value 2^52 is too large"),
                () -> assertEquals("Inf", fixed(1e14, 2), "scaling counts: 1e14 with 2 decimals is 1e16"),
                () -> assertEquals("-Inf", fixed(-4503599627370496.0, 0), "and negative"),
                () -> assertEquals("4503599627370495", fixed(4503599627370495.0, 0), "just below 2^52 is exact"),
                () -> assertEquals("123456789012345.5", fixed(123456789012345.5, 1), "large values keep their decimals"));
        }
    }
}
