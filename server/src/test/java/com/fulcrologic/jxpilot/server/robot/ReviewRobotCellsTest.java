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
package com.fulcrologic.jxpilot.server.robot;

import com.fulcrologic.jxpilot.common.Item;
import com.fulcrologic.jxpilot.server.sim.Simulation;
import com.fulcrologic.jxpilot.common.sim.Obj;
import com.fulcrologic.jxpilot.server.world.Objects;
import com.fulcrologic.jxpilot.server.world.Player;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Review findings (robots), kept as regression tests: robot object lookup vs C robotdef.c. */
class ReviewRobotCellsTest {

    static void reset(DefaultRobot r) {
        r.mineI = -1;
        r.mineDist = 14 + 200;
        r.itemI = -1;
        r.itemDist = (int) r.visibilityDistance;
        r.itemImp = DefaultRobot.ROBOT_IGNORE_ITEM;
    }

    /**
     * C's Cell_add_object is immediate (shot.c:1054 for missiles), so a robot playing later in the same
     * Robot_update loop sees an earlier robot's fresh torpedo in Robot_default_play_check_objects
     * (robotdef.c:1980). (Mines would not count: they are not in killing_shots, robotdef.c:2036.)
     */
    @Test
    void torpedoFiredByEarlierRobotIsSeenByLaterRobot() {
        Simulation s = RobotFixture.sim("", RobotFixture.open(40, 40, "__"));
        Player r1 = RobotFixture.robot(s, "R1", 700, 700);
        Player r2 = RobotFixture.robot(s, "R2", 760, 700);
        DefaultRobot r = s.robots.robot();
        r.refreshCells();                 // start of the robot loop
        int torp = RobotFixture.obj(s.world, Obj.TORPEDO, r1.id, 730, 700, 0, 0); // R1 fires during its turn
        reset(r);
        r.checkObjects(r2, s.robots.data(r2)); // R2 plays later in the same frame
        assertEquals(torp, r.mineI, "C: the later robot notes the fresh torpedo 30 px away as its nearest danger");
    }

    /**
     * robotdef.c:2162-2170 converts item_i by searching Obj[] for the first object whose id equals the chosen
     * item's id. Items have id NO_ID (item.c:204), so C's item_i is the first NO_ID object in Obj[], not the
     * item it ranked. Java keeps the ranked item's handle.
     */
    @Test
    void itemIndexIsFirstNoIdObjectAsInC() {
        Simulation s = RobotFixture.sim("", RobotFixture.open(60, 60, "__"));
        Player p = RobotFixture.robot(s, "R", 700, 700);
        DefaultRobot r = s.robots.robot();
        Objects o = s.world.objects;
        int far = RobotFixture.obj(s.world, Obj.ITEM, Obj.NO_ID, 1990, 1990, 0, 0); // first NO_ID object, out of view
        o.info[far] = Item.CLOAK.ordinal();
        int near = RobotFixture.obj(s.world, Obj.ITEM, Obj.NO_ID, 760, 700, 0, 0);   // the item C ranks best
        o.info[near] = Item.CLOAK.ordinal();
        r.refreshCells();
        reset(r);
        r.checkObjects(p, s.robots.data(p));
        assertEquals(far, r.itemI, "C: item_i becomes the first Obj[] entry with id NO_ID (the far item), not "
            + near);
    }
}
