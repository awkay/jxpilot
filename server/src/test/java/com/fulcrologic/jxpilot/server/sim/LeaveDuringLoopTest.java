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

import com.fulcrologic.jxpilot.server.TestWorlds;
import com.fulcrologic.jxpilot.server.world.Obj;
import com.fulcrologic.jxpilot.server.world.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Players that leave while the sim loops over them: robots out of lives (player.c:690-694, update.c:1287-1316). */
class LeaveDuringLoopTest {
    private static final String MAP = "framesPerSecond: 60\ngravity: 0\nlimitedLives: yes\nworldLives: 2\n";

    private static Simulation sim(String extra) {
        return TestWorlds.sim(MAP + extra, "      ", "      ", "_ _ _ ");
    }

    @Test
    @DisplayName("a round reset that kills a robot out of the game goes on with the player moved into its place")
    void roundReset() {
        Simulation s = sim("reset: yes\n");
        Player r = s.ships.join("R", 0, true);
        Player h = TestWorlds.join(s, "H", 0);
        r.life = 0;
        h.kills = 5;
        h.status |= Obj.GAME_OVER;
        s.rounds.resetAllPlayers();
        assertAll(
            () -> assertFalse(r.inUse, "the robot left"),
            () -> assertEquals(0, h.kills, "the human (moved into the robot's place) was still reset"),
            () -> assertEquals(0, h.status & Obj.GAME_OVER, "and is back in the game"),
            () -> assertEquals(2, h.life, "with full lives"),
            () -> assertEquals(1, s.world.numPlayers, "one player left"));
    }

    @Test
    @DisplayName("the kill loop skips the idle-pause check for a robot that left (it is no longer a robot)")
    void killLoop() {
        Simulation s = sim("");
        Player h = TestWorlds.join(s, "H", 0);
        Player h2 = TestWorlds.join(s, "H2", 0);
        Player r = s.ships.join("R", 0, true);
        r.life = 0;
        r.status |= Obj.KILLED;
        s.world.frames = 100_000;
        r.frameLastBusy = 0;
        s.killPlayers();
        assertAll(
            () -> assertFalse(r.inUse, "the robot left"),
            () -> assertEquals(0, r.status & Obj.PAUSE, "and was not paused afterwards"),
            () -> assertTrue(h.inUse && h2.inUse, "the humans stay"));
    }
}
