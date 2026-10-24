/*
 * JXPilot, a Java reimplementation of XPilot, a multiplayer gravity war game.
 * Copyright (C) 2026 Tony Kay and the JXPilot contributors.
 *
 * Derived from XPilot 4.5.5, Copyright (C) 1991-2002 by Bjørn Stabell,
 * Ken Ronny Schouten, Bert Gijsbers and Dick Balaska. JXPilot is an
 * independent derivative port: the original XPilot authors were not involved
 * in it and do not endorse it. Ported to Java and modified starting in 2026;
 * see the git history for the changes.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, see <https://www.gnu.org/licenses/>.
 */
package com.fulcrologic.jxpilot.server.host;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** C printf "%g". */
class CFormatTest {
    @Test
    @DisplayName("%g: 6 significant digits, plain or exponent form, trailing zeros dropped, special values")
    void g() {
        assertAll(
            () -> assertEquals("0", CFormat.g(0), "zero"),
            () -> assertEquals("-0", CFormat.g(-0.0), "negative zero"),
            () -> assertEquals("100", CFormat.g(100), "an integer"),
            () -> assertEquals("0.1", CFormat.g((float) 0.1), "a float 0.1 rounds to 0.1"),
            () -> assertEquals("-0.25", CFormat.g(-0.25), "negative"),
            () -> assertEquals("123457", CFormat.g(123456.7), "rounded to 6 digits"),
            () -> assertEquals("1e+06", CFormat.g(999999.7), "rounding up to 7 digits switches to the exponent"),
            () -> assertEquals("1.23457e+08", CFormat.g(123456789), "big"),
            () -> assertEquals("0.0001", CFormat.g(0.0001), "exponent -4 is plain"),
            () -> assertEquals("1e-05", CFormat.g(0.00001), "exponent -5 is not"),
            () -> assertEquals("2.40292e-09", CFormat.g(2.40292e-09), "a tiny probability"),
            () -> assertEquals("1.5e+100", CFormat.g(1.5e100), "three exponent digits"),
            () -> assertEquals("0.125", CFormat.g(0.125), "exact"),
            () -> assertEquals("2.5", CFormat.g(2.5), "a tie keeps its digits"),
            () -> assertEquals("inf", CFormat.g(Double.POSITIVE_INFINITY), "inf"),
            () -> assertEquals("-inf", CFormat.g(Double.NEGATIVE_INFINITY), "-inf"),
            () -> assertEquals("nan", CFormat.g(Double.NaN), "nan"));
    }
}
