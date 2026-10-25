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

import com.fulcrologic.jxpilot.common.sim.Has;
import com.fulcrologic.jxpilot.server.TestWorlds;
import com.fulcrologic.jxpilot.server.world.Objects;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * BEHAVIOR (Simulation.autofire, update.c:507-514): every player in the game holding fire fires, and only those: a
 * player who left (its slot stays just past the game's players) does not.
 */
@DisplayName("autofire")
class AutofireTest {
    static final String[] MAP = {"xxxxxxxxxxx", "x         x", "x _  _  _ x", "x         x", "xxxxxxxxxxx"};

    static int shotsOf(World w, int id) {
        Objects o = w.objects;
        int n = 0;
        for (int k = 0; k < o.count(); k++) {
            n += o.owner[o.active[k]] == id ? 1 : 0;
        }
        return n;
    }

    @Test
    @DisplayName("the players in the game holding fire fire; one who left does not")
    void onlyPlayersInTheGame() {
        Simulation s = TestWorlds.sim("framesPerSecond: 60\ngravity: 0\nmaxRobots: 0\nfireRepeatRate: 2\n", MAP);
        World w = s.world;
        Player bob = TestWorlds.join(s, "Bob", 0);
        Player ann = TestWorlds.join(s, "Ann", 0);
        bob.used &= ~Has.SHIELD;
        ann.used &= ~Has.SHIELD;
        TestWorlds.run(s, 2, 0L);
        int annId = ann.id;
        s.ships.leave(ann);
        bob.used |= Has.SHOT;
        ann.used |= Has.SHOT;
        w.frames += 10;
        int before = shotsOf(w, annId);
        int bobBefore = shotsOf(w, bob.id);
        s.autofire();
        assertAll(
            () -> assertEquals(1, w.numPlayers, "Bob is the only one in the game"),
            () -> assertEquals(bobBefore + 1, shotsOf(w, bob.id), "Bob, holding fire, fired one shot"),
            () -> assertEquals(before, shotsOf(w, annId), "Ann, who left, fired nothing"));
    }
}
