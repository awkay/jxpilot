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
import com.fulcrologic.jxpilot.common.sim.Obj;
import com.fulcrologic.jxpilot.server.world.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertSame;

@DisplayName("Locks.viewOf: whose eyes a snapshot shows (C frame.c:1314-1336)")
class LocksViewTest {
    static final String[] MAP = {"xxxxxxxxxx", "x 1 1 2  x", "x        x", "xxxxxxxxxx"};

    static Simulation sim(String extra) {
        return TestWorlds.sim("framesPerSecond: 60\ngravity: 0\nteamPlay: yes\n" + extra, MAP);
    }

    static void lock(Player pl, Player on) {
        pl.lockTagged = Player.LOCK_PLAYER;
        pl.lockPlId = on.id;
    }

    @Test
    @DisplayName("out of the game: through the locked player; playing or without a lock: own eyes")
    void gameOver() {
        Simulation s = sim("");
        Player a = TestWorlds.join(s, "a", 1);
        Player foe = TestWorlds.join(s, "foe", 2);
        Player alive = s.locks.viewOf(a);
        lock(a, foe);
        Player lockedPlaying = s.locks.viewOf(a);
        a.status |= Obj.GAME_OVER;
        Player over = s.locks.viewOf(a);
        a.lockTagged = 0;
        Player noLock = s.locks.viewOf(a);
        a.lockTagged = Player.LOCK_PLAYER;
        a.lockPlId = 999;
        Player gone = s.locks.viewOf(a);
        assertAll(
            () -> assertSame(a, alive, "no lock, playing: own eyes"),
            () -> assertSame(a, lockedPlaying, "a lock while playing: own eyes"),
            () -> assertSame(foe, over, "game over with a lock: the locked player's eyes"),
            () -> assertSame(a, noLock, "game over without a lock: own eyes"),
            () -> assertSame(a, gone, "a lock on a player who left: own eyes"));
    }

    @Test
    @DisplayName("paused: through a team mate in team play, anyone only with allowViewing")
    void paused() {
        Simulation s = sim("");
        Player a = TestWorlds.join(s, "a", 1);
        Player mate = TestWorlds.join(s, "mate", 1);
        Player foe = TestWorlds.join(s, "foe", 2);
        a.status = (a.status & ~Obj.PLAYING) | Obj.PAUSE;
        lock(a, mate);
        Player onMate = s.locks.viewOf(a);
        lock(a, foe);
        Player onFoe = s.locks.viewOf(a);
        Simulation v = sim("allowViewing: yes\n");
        Player b = TestWorlds.join(v, "b", 1);
        Player foe2 = TestWorlds.join(v, "foe", 2);
        b.status = (b.status & ~Obj.PLAYING) | Obj.PAUSE;
        lock(b, foe2);
        Player viewing = v.locks.viewOf(b);
        assertAll(
            () -> assertSame(mate, onMate, "a paused player looks through a team mate"),
            () -> assertSame(a, onFoe, "but not through an opponent"),
            () -> assertSame(foe2, viewing, "allowViewing: through an opponent too"));
    }
}
