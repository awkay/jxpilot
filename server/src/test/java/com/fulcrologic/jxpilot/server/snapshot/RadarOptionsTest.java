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
import com.fulcrologic.jxpilot.common.net.Section;
import com.fulcrologic.jxpilot.common.net.SnapshotReader;
import com.fulcrologic.jxpilot.server.TestWorlds;
import com.fulcrologic.jxpilot.server.sim.Simulation;
import com.fulcrologic.jxpilot.server.world.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** C {@code playersOnRadar} (frame.c:1152-1170). */
class RadarOptionsTest {

    private static List<Integer> radarFlags(Simulation s, Player viewer) {
        ByteBuffer one = ByteBuffer.allocate(1200);
        new SnapshotBuilder(s.world).build(viewer, 0, 0, true, new ByteBuffer[] {one});
        SnapshotReader r = new SnapshotReader();
        r.setWorld(s.world.pixelWidth, s.world.pixelHeight, false);
        r.begin(one.flip());
        Records.Radar rd = new Records.Radar();
        List<Integer> flags = new ArrayList<>();
        while (r.nextSection()) {
            if (r.type() == Section.RADAR) {
                for (int i = 0; i < r.count(); i++) {
                    flags.add(rd.wrap(r, i).flags());
                }
            }
        }
        return flags;
    }

    private static Simulation teams(String extra) {
        return TestWorlds.sim("framesPerSecond: 60\ngravity: 0\nteamPlay: yes\n" + extra, "xxxxxxxxxx",
            "x 1 1 2  x", "x        x", "xxxxxxxxxx");
    }

    @Test
    @DisplayName("without playersOnRadar only the viewer and its team mates are on the radar")
    void off() {
        Simulation s = teams("playersOnRadar: no\n");
        Player p1 = TestWorlds.join(s, "p1", 1);
        TestWorlds.join(s, "p2", 1);
        TestWorlds.join(s, "p3", 2);
        Simulation free = TestWorlds.sim("framesPerSecond: 60\ngravity: 0\nplayersOnRadar: no\n", "xxxxxxx",
            "x _ _ x", "xxxxxxx");
        Player a = TestWorlds.join(free, "a", 0);
        TestWorlds.join(free, "b", 0);
        assertAll(
            () -> assertEquals(List.of(Records.Radar.FLAG_SELF, Records.Radar.FLAG_FRIENDLY), radarFlags(s, p1),
                "self and the team mate; the opponent is hidden"),
            () -> assertEquals(List.of(Records.Radar.FLAG_SELF), radarFlags(free, a), "no teams: only self"));
    }

    @Test
    @DisplayName("with playersOnRadar (the default) everyone in play is on the radar")
    void on() {
        Simulation s = teams("");
        Player p1 = TestWorlds.join(s, "p1", 1);
        TestWorlds.join(s, "p2", 1);
        TestWorlds.join(s, "p3", 2);
        assertEquals(List.of(Records.Radar.FLAG_SELF, Records.Radar.FLAG_FRIENDLY, 0), radarFlags(s, p1),
            "self, team mate, opponent");
    }

    @Test
    @DisplayName("allies are on the radar without playersOnRadar, and friendly (frame.c:1168, 1187)")
    void allies() {
        Simulation s = TestWorlds.sim("framesPerSecond: 60\ngravity: 0\nplayersOnRadar: no\n", "xxxxxxxxx",
            "x _ _ _ x", "xxxxxxxxx");
        Player a = TestWorlds.join(s, "a", 0);
        Player b = TestWorlds.join(s, "b", 0);
        TestWorlds.join(s, "c", 0);
        s.alliances.invite(a, b);
        s.alliances.accept(b, a);
        Simulation on = TestWorlds.sim("framesPerSecond: 60\ngravity: 0\n", "xxxxxxx", "x _ _ x", "xxxxxxx");
        Player x = TestWorlds.join(on, "x", 0);
        Player y = TestWorlds.join(on, "y", 0);
        on.alliances.invite(x, y);
        on.alliances.accept(y, x);
        assertAll(
            () -> assertEquals(List.of(Records.Radar.FLAG_SELF, Records.Radar.FLAG_FRIENDLY), radarFlags(s, a),
                "self and the ally; the stranger is hidden"),
            () -> assertEquals(List.of(Records.Radar.FLAG_SELF, Records.Radar.FLAG_FRIENDLY), radarFlags(on, x),
                "with playersOnRadar the ally is marked friendly"));
    }
}
