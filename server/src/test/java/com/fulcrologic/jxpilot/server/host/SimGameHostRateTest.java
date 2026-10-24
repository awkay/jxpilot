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

import com.fulcrologic.jxpilot.server.sim.Simulation;
import com.fulcrologic.jxpilot.common.sim.Obj;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.fulcrologic.jxpilot.server.host.HostFx.THREE;
import static com.fulcrologic.jxpilot.server.host.HostFx.enter;
import static com.fulcrologic.jxpilot.server.host.HostFx.sim;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("SimGameHost.snapshotDue: C's reduced frame rate for paused and dead players (frame.c:1275-1294)")
class SimGameHostRateTest {
    /** "1" or "0" per frame 8..15 for the viewer. */
    static String due(SimGameHost h, World w, int id) {
        StringBuilder b = new StringBuilder();
        for (long f = 8; f < 16; f++) {
            w.frames = f;
            b.append(h.snapshotDue(id) ? '1' : '0');
        }
        return b.toString();
    }

    @Test
    @DisplayName("playing: every frame; paused: every 4th; out of the game: every 2nd; allowViewing: every frame")
    void rates() {
        Simulation s = sim("", THREE);
        World w = s.world;
        SimGameHost h = new SimGameHost(s);
        int ann = enter(h, "Ann", 0);
        Player a = w.byId(ann);
        String playing = due(h, w, ann);
        a.status |= Obj.PAUSE;
        String paused = due(h, w, ann);
        a.status = (a.status & ~Obj.PAUSE) | Obj.GAME_OVER;
        String over = due(h, w, ann);
        a.status |= Obj.PAUSE;
        String both = due(h, w, ann);
        w.items.allowViewing = true;
        String viewing = due(h, w, ann);
        w.items.allowViewing = false;
        String nobody = due(h, w, 99);
        assertAll(
            () -> assertEquals("11111111", playing, "a playing player gets every frame"),
            () -> assertEquals("10001000", paused, "paused: frames 8 and 12 (frame_loops & 3 == 0)"),
            () -> assertEquals("10101010", over, "GAME_OVER: even frames (frame_loops & 1 == 0)"),
            () -> assertEquals("10001000", both, "paused wins over GAME_OVER (C tests PAUSE first)"),
            () -> assertEquals("11111111", viewing, "allowViewing: everyone gets every frame"),
            () -> assertEquals("11111111", nobody, "an unknown viewer is not throttled"));
    }
}
