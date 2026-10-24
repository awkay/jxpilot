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

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/** C {@code printf} number formats the server's text messages need. */
final class CFormat {
    private CFormat() {}

    private static final MathContext SIX = new MathContext(6, RoundingMode.HALF_EVEN);

    /**
     * C {@code "%g"} (precision 6) of {@code v}.
     * <p>
     * BEHAVIOR: the value rounded to 6 significant digits (the exact binary value, ties to even, as glibc); with
     * that value's decimal exponent X below -4 or at least 6 it is written d.ddddde+XX (at least two exponent
     * digits), else in plain notation; trailing zeros and a trailing '.' are removed. Zero is "0" ("-0" for
     * negative zero); infinities are "inf"/"-inf" and NaN "nan".
     */
    static String g(double v) {
        if (Double.isNaN(v)) {
            return "nan";
        }
        if (Double.isInfinite(v)) {
            return v > 0 ? "inf" : "-inf";
        }
        if (v == 0) {
            return 1 / v < 0 ? "-0" : "0";
        }
        BigDecimal r = new BigDecimal(v).round(SIX);
        int x = r.precision() - r.scale() - 1;
        if (x < -4 || x >= 6) {
            String digits = r.unscaledValue().abs().toString();
            StringBuilder sb = new StringBuilder();
            if (r.signum() < 0) {
                sb.append('-');
            }
            sb.append(digits.charAt(0));
            String frac = stripZeros(digits.substring(1));
            if (!frac.isEmpty()) {
                sb.append('.').append(frac);
            }
            sb.append('e').append(x < 0 ? '-' : '+');
            int ax = Math.abs(x);
            if (ax < 10) {
                sb.append('0');
            }
            return sb.append(ax).toString();
        }
        String plain = r.toPlainString();
        if (plain.indexOf('.') >= 0) {
            plain = stripZeros(plain);
            if (plain.endsWith(".")) {
                plain = plain.substring(0, plain.length() - 1);
            }
        }
        return plain;
    }

    private static String stripZeros(String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '0') {
            end--;
        }
        return s.substring(0, end);
    }
}
