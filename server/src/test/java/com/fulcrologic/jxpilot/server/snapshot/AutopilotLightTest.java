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
package com.fulcrologic.jxpilot.server.snapshot;

import com.fulcrologic.jxpilot.common.net.Records;
import com.fulcrologic.jxpilot.common.sim.Has;
import com.fulcrologic.jxpilot.common.sim.Obj;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("SELF's AUTOPILOT flag is C's autopilot light (frame.c:424-429)")
class AutopilotLightTest extends ItemSectionsTest {
    String light(int frame) {
        w.frames = frame;
        int flags = Integer.parseInt(records(a, ItemSectionsTest.SELF_FLAGS, false).get(0));
        return (flags & Records.ShipFlags.AUTOPILOT) != 0 ? "on" : "off";
    }

    @Test
    @DisplayName("autopilot: on in frames 0-3 of every 8; hover pause: blinking, steady once its count ran out")
    void light() {
        String none = light(1);
        a.used |= Has.AUTOPILOT;
        String ap3 = light(11);
        String ap4 = light(12);
        a.used &= ~Has.AUTOPILOT;
        a.status |= Obj.HOVERPAUSE;
        a.count = 5;
        String hover3 = light(3);
        String hover4 = light(4);
        a.count = 0;
        String hoverDone = light(4);
        assertAll(
            () -> assertEquals("off", none, "no autopilot, no hover pause: off"),
            () -> assertEquals("on", ap3, "autopilot, frame 11 (11 % 8 = 3): on"),
            () -> assertEquals("off", ap4, "autopilot, frame 12 (12 % 8 = 4): off"),
            () -> assertEquals("on", hover3, "hover pause counting down, frame 3: on"),
            () -> assertEquals("off", hover4, "hover pause counting down, frame 4: off"),
            () -> assertEquals("on", hoverDone, "hover pause with its count run out: steady on"));
    }
}
