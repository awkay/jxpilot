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
package com.fulcrologic.jxpilot.client.app;

import com.fulcrologic.jxpilot.client.game.ClientGame;
import com.fulcrologic.jxpilot.common.config.ConfigParser;
import com.fulcrologic.jxpilot.common.config.OptionOrigin;
import com.fulcrologic.jxpilot.common.config.Options;
import com.fulcrologic.jxpilot.common.map.WorldMap;
import com.fulcrologic.jxpilot.common.net.Msg;
import com.fulcrologic.jxpilot.common.net.Protocol;
import com.fulcrologic.jxpilot.common.net.Records;
import com.fulcrologic.jxpilot.common.net.Section;
import com.fulcrologic.jxpilot.common.net.SnapshotWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression for the 12 Hz sawtooth: a 12 fps server (one snapshot per tick, as the C server path
 * delivers) with an own ship and another ship under constant acceleration, snapshots arriving with
 * +-12 ms jitter and every 9th packet 40 ms late (measured over Docker: +-4 ms typical), rendered at 120 Hz. The drawn own ship (which
 * the camera follows) and the other ship must move monotonically along x and smoothly: the per-frame
 * displacement may only change by what the acceleration explains, never by a snapshot-sized jump.
 */
class SmoothMotionTest {
    static final int FPS = 12;
    /** px per frame along x, constant. */
    static final double VX = 6;
    /** px per frame per frame along y (thrust/gravity-like: 2, as a C ship under full thrust). */
    static final double AY = 2;

    static double x(double t) {
        return 1000 + VX * t;
    }

    static double y(double t) {
        return 1000 + AY * t * t / 2;
    }

    @Test
    @DisplayName("own and other ship move monotonically and smoothly at 120 Hz from jittered 12 Hz snapshots")
    void smooth() {
        Options opts = new Options();
        ConfigParser.parse("mapWidth: 200\nmapHeight: 200\nedgeWrap: yes\nframesPerSecond: 12\nmapData: \\multiline: E\n"
            + (" ".repeat(200) + "\n").repeat(200) + "E\n", "t", OptionOrigin.MAP, opts);
        WorldMap map = WorldMap.fromOptions(opts);
        Msg.Welcome w = new Msg.Welcome();
        w.playerId = 1;
        w.simHz = FPS;
        w.snapshotHz = FPS;
        w.gameFps = FPS;
        w.flags = Msg.Welcome.FLAG_EDGE_WRAP;
        ClientGame g = new ClientGame();
        g.onAccepted(1);
        g.onWelcome(w);
        g.onConfig(w, opts, map);
        SnapshotWriter sw = new SnapshotWriter();
        sw.setWorld(map.pixelWidth(), map.pixelHeight(), true);
        ByteBuffer buf = ByteBuffer.allocate(Protocol.MAX_PACKET);
        SplittableRandom rnd = new SplittableRandom(12);
        long t0 = 1_000_000_000L;
        int ticks = 60;
        long[] arrive = new long[ticks];
        for (int k = 0; k < ticks; k++) {
            long jitter = rnd.nextLong(-12_000_000L, 12_000_000L);
            arrive[k] = t0 + Math.round(k * 1e9 / FPS) + jitter;
            if (k % 9 == 4) {
                arrive[k] += 40_000_000L; // a late packet: 40 ms behind its neighbours
            }
        }
        int next = 0;
        int frames = 0;
        double[] ox = new double[600];
        double[] oy = new double[600];
        double[] sx = new double[600];
        double[] sy = new double[600];
        for (long now = t0 + 500_000_000L; now < t0 + 4_500_000_000L; now += 1_000_000_000L / 120) {
            while (next < ticks && arrive[next] <= now) {
                double t = next;
                buf.clear();
                sw.begin(buf, next, 0, 0, 1, SnapshotWriter.FLAG_LAST_PART, x(t), y(t));
                sw.beginSection(Section.SELF);
                Records.Self.put(sw, 1, 0, (float) x(t), (float) y(t), (float) VX, (float) (AY * t), 0, 500, 1000,
                    Protocol.NO_ID, 0, 0, 0);
                sw.endSection();
                sw.beginSection(Section.SHIP);
                Records.Ship.put(sw, 2, 0, x(t) + 50, y(t) - 50, 0, VX, AY * t);
                sw.endSection();
                buf.flip();
                g.onSnapshot(buf, arrive[next]);
                next++;
            }
            g.frame(now, 1.0 / 120);
            if (now >= t0 + 1_000_000_000L && frames < ox.length) {
                ox[frames] = g.world().selfX();
                oy[frames] = g.world().selfY();
                sx[frames] = g.world().shipX(0);
                sy[frames] = g.world().shipY(0);
                frames++;
            }
        }
        int n = frames;
        // per render frame: 6 px/frame * 12/120 = 0.6 px along x; y speed grows to 2*50/10 = 10 px/frame
        double maxStepChange = 0.5; // 2 px/frame^2 changes the 120 Hz step by 0.2 px per tick, plus clock smoothing
        double[] worst = new double[4];
        boolean[] monotonic = {true, true};
        for (int i = 2; i < n; i++) {
            double dx = ox[i] - ox[i - 1];
            double sdx = sx[i] - sx[i - 1];
            if (dx < 0) {
                monotonic[0] = false;
            }
            if (sdx < 0) {
                monotonic[1] = false;
            }
            worst[0] = Math.max(worst[0], Math.abs(dx - (ox[i - 1] - ox[i - 2])));
            worst[1] = Math.max(worst[1], Math.abs((oy[i] - oy[i - 1]) - (oy[i - 1] - oy[i - 2])));
            worst[2] = Math.max(worst[2], Math.abs(sdx - (sx[i - 1] - sx[i - 2])));
            worst[3] = Math.max(worst[3], Math.abs((sy[i] - sy[i - 1]) - (sy[i - 1] - sy[i - 2])));
        }
        double fn = n;
        assertAll(
            () -> assertTrue(fn > 300, "rendered 3 s at 120 Hz"),
            () -> assertTrue(monotonic[0], "own ship x never moves backwards"),
            () -> assertTrue(monotonic[1], "other ship x never moves backwards"),
            () -> assertTrue(worst[0] < maxStepChange, "own x step changes at most " + maxStepChange + ": " + worst[0]),
            () -> assertTrue(worst[1] < maxStepChange, "own y step changes at most " + maxStepChange + ": " + worst[1]),
            () -> assertTrue(worst[2] < maxStepChange, "other x step changes at most " + maxStepChange + ": " + worst[2]),
            () -> assertTrue(worst[3] < maxStepChange, "other y step changes at most " + maxStepChange + ": " + worst[3]));
    }
}
