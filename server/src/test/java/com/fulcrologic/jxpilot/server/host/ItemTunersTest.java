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

import com.fulcrologic.jxpilot.common.Item;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Tuning: item options set at run time reach the live item table (World.items)")
class ItemTunersTest {
    @Test
    @DisplayName("limits, probabilities, chances and scalar item options change; untouched ones are kept")
    void itemTable() {
        TuningTest.Fx f = new TuningTest.Fx("itemProbMult: 0.000001\nitemMineProb: 0.5\nmaxMines: 4\nmineFuseTime: 0\n");
        int mine = Item.MINE.ordinal();
        int missile = Item.MISSILE.ordinal();
        int chance0 = f.w.items.chance[mine];
        int missileLimit = f.w.items.limit[missile];
        f.t.tune("maxMines", "9");
        f.t.tune("itemMineProb", "0.25");
        f.t.tune("identifyMines", "no");
        f.t.tune("mineFuseTime", "2");
        assertAll(
            () -> assertEquals(9, f.w.items.limit[mine], "maxMines is the mine limit (World.items[].limit)"),
            () -> assertEquals(missileLimit, f.w.items.limit[missile], "another item's limit is untouched"),
            () -> assertEquals(0.25f, f.w.items.prob[mine], "the mine probability"),
            () -> assertEquals(Math.max(1, (int) (1.0 / (0.000001f * 0.25f * f.w.width * f.w.height * f.w.params.fps))),
                f.w.items.chance[mine], "and the spawn chance from it (C Set_world_items)"),
            () -> assertTrue(f.w.items.chance[mine] != chance0, "which changed"),
            () -> assertEquals(false, f.w.items.identifyMines, "a boolean item option"),
            () -> assertEquals(2 * f.w.params.fps, f.w.items.mineFuseTime, "a time option, in frames"));
    }
}
