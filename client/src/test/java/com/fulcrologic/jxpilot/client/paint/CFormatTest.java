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
package com.fulcrologic.jxpilot.client.paint;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CFormatTest {
    static String padInt(int v, int width, boolean zero) {
        char[] out = new char[32];
        out[0] = '#';
        int n = CFormat.padInt(v, width, zero, out, 1);
        return new String(out, 0, n + 1);
    }

    @Nested
    class PadInt {
        @Test
        void rightAlignsShortNumbersAndKeepsLongOnesWhole() {
            assertAll(
                () -> assertEquals("#   12", padInt(12, 5, false), "%5d pads with spaces, at the offset"),
                () -> assertEquals("#0012", padInt(12, 4, true), "%04d pads with zeros"),
                () -> assertEquals("#  -7", padInt(-7, 4, false), "%4d puts spaces before the minus"),
                () -> assertEquals("#-007", padInt(-7, 4, true), "%04d puts zeros after the minus"),
                () -> assertEquals("#12345", padInt(12345, 3, true), "a longer number is written whole"),
                () -> assertEquals("#123", padInt(123, 3, false), "an exact fit has no padding"),
                () -> assertEquals("#5", padInt(5, 0, false), "%d: width 0 pads nothing"));
        }
    }
}
