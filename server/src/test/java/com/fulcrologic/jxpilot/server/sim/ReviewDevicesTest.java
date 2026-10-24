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
package com.fulcrologic.jxpilot.server.sim;

import com.fulcrologic.jxpilot.common.Item;
import com.fulcrologic.jxpilot.common.sim.Has;
import com.fulcrologic.jxpilot.server.world.Player;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Review findings (devices), kept as regression tests vs C item.c / update.c. */
class ReviewDevicesTest {

    /**
     * item.c:564 {@code if (pl->fuel.sum < -ED_DEFLECTOR)} compares the long fuel with the double 38.4
     * (ED_DEFLECTOR = -0.15*256), so with fuel 38 the deflector switches off and costs nothing.
     */
    @Test
    void deflectorFuelThresholdIsTheUncastDouble() {
        Simulation s = SimFixture.sim(SimFixture.FRAME, 30, 30, "__");
        Player p = SimFixture.ready(s, "P", 0, 350, 350);
        p.items[Item.DEFLECTOR.ordinal()] = 1;
        p.have |= Has.DEFLECTOR;
        p.used |= Has.DEFLECTOR;
        p.fuel = 38;
        p.tankFuel[0] = 38;
        s.devices.doDeflector(p);
        assertAll(
            () -> assertEquals(0, p.used & Has.DEFLECTOR, "C: 38 < 38.4, the deflector goes off"),
            () -> assertEquals(38.0, p.fuel, "C: no fuel is taken"));
    }
}
