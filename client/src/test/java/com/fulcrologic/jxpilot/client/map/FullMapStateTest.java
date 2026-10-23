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
package com.fulcrologic.jxpilot.client.map;

import com.fulcrologic.jxpilot.common.Const;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FullMapStateTest {
    @Test
    void everythingIsAliveAndFull() {
        FullMapState s = new FullMapState();
        s.setLoops(42);
        assertAll(
            () -> assertEquals(Const.MAX_STATION_FUEL, s.fuel(3), "full fuel"),
            () -> assertTrue(s.cannonAlive(1), "cannons alive"),
            () -> assertTrue(s.targetAlive(1), "targets alive"),
            () -> assertEquals(Const.TARGET_DAMAGE, s.targetDamage(1), "targets undamaged"),
            () -> assertEquals(Const.TEAM_NOT_SET, s.selfTeam(), "no team"),
            () -> assertEquals(0, s.nextCheckpoint(), "first checkpoint next"),
            () -> assertEquals(42, s.loops(), "frame counter as set"));
    }
}
