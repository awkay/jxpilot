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

import com.fulcrologic.jxpilot.client.Fixtures;
import com.fulcrologic.jxpilot.client.net.PacketStats;
import com.fulcrologic.jxpilot.client.paint.PaintStyle;
import com.fulcrologic.jxpilot.client.paint.RadarPainter;
import com.fulcrologic.jxpilot.client.paint.SceneData;
import com.fulcrologic.jxpilot.client.paint.ScenePainter;
import com.fulcrologic.jxpilot.client.paint.ScreenSpace;
import com.fulcrologic.jxpilot.client.paint.VisibleBorder;
import com.fulcrologic.jxpilot.client.render.Camera;
import com.fulcrologic.jxpilot.client.render.Palette;
import com.fulcrologic.jxpilot.client.render.TextWriter;
import com.fulcrologic.jxpilot.client.render.VertexBatch;
import com.fulcrologic.jxpilot.common.math.ShipShape;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;

import static org.junit.jupiter.api.Assertions.*;

/**
 * client-ui-look's per-frame paths outside {@link ClientFrame#step} (which {@link AppAllocationTest} covers with
 * the look options on): the scene with nasty shots, textured balls and changing sizes, the sliding radar picture and
 * blips, the visible-area border, the HUD radar's blip conversion and the packet measurements. Zero steady-state
 * allocation.
 */
class LookAllocationTest {
    @Test
    void lookPathsAllocateNothingInTheSteadyState() {
        com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        Palette p = Palette.xpilotDefault();
        PaintStyle st = new PaintStyle();
        ScenePainter scene = new ScenePainter(p, st);
        RadarPainter radar = new RadarPainter(p, st);
        TextWriter text = new TextWriter(Fixtures.font());
        long[] flushes = new long[1];
        VertexBatch b = Fixtures.batch(256, 16, x -> flushes[0]++);
        ScreenSpace ss = new ScreenSpace();
        ss.set(1200, 2);
        Camera cam = new Camera();
        cam.setWorld(5000, 5000, true);
        cam.setViewport(1600, 1200, 2);
        cam.setZoom(1);
        SceneData s = new SceneData(4, 8, 8, 4);
        RadarBlips blips = new RadarBlips(32);
        HudRadarBlips hudBlips = new HudRadarBlips(32);
        PacketStats stats = new PacketStats();
        stats.reset(14);
        Runnable frame = new Runnable() {
            int k;

            @Override
            public void run() {
                k++;
                st.nastyShots = (k & 1) != 0;
                st.texturedBalls = (k & 2) != 0;
                st.markingLights = (k & 4) != 0;
                st.showShipName = (k & 8) != 0;
                st.shotSize = 1 + k % 8;
                st.sparkSize = 1 + k % 8;
                st.maxColors = (k & 16) != 0 ? 4 : 8;
                s.clear();
                s.loops = k;
                s.fps = 14;
                s.addShip(2500 + k % 50, 2500, k % 128, ShipShape.defaultShip(), 1, 0, Palette.WHITE, false, "pilot");
                s.addShot(2480, 2490 + k % 10, Palette.WHITE, false);
                s.addShot(2470, 2490, Palette.BLUE, true);
                s.addParticle(2460, 2460, 5 + k % 3);
                s.addBall(2400 + k % 30, 2400);
                cam.setCentre(2500, 2500);
                cam.update();
                b.clear();
                scene.draw(b, text, cam, s);
                blips.clear();
                for (int i = 0; i < 10; i++) {
                    blips.add((k * 37 + i * 400) % 5000, (k * 11 + i * 300) % 5000, i % 3 == 0 ? 0x83 : i % 4);
                }
                hudBlips.fill(blips, 5000, 5000, 256);
                radar.begin(0, 0, 512, 512, 256, 5000, 5000, true);
                radar.slide((k * 13) % 5000, (k * 7) % 5000);
                radar.picture(b, 0.5f, 0, 0.75f, 0.25f, -1);
                for (int i = 0; i < blips.count(); i++) {
                    radar.blipByte(b, blips.x(i), blips.y(i), blips.sizeByte(i));
                }
                radar.self(b, (k * 13) % 5000, (k * 7) % 5000, k * 0.1, k);
                VisibleBorder.paint(b, ss, 1000 + k % 200, 1100, 1, -1);
                stats.measure(k, (k % 17 == 0) ? PacketStats.DROP : PacketStats.DRAW);
                stats.keyboardSent(k / 3, k);
                stats.keyboardAck(k / 3 - 1, k);
                stats.frameSize(300 + k % 200);
            }
        };
        for (int i = 0; i < 30_000; i++) {
            frame.run();
        }
        long[] windows = new long[4];
        for (int w = 0; w < windows.length; w++) {
            long before = mx.getCurrentThreadAllocatedBytes();
            for (int i = 0; i < 5_000; i++) {
                frame.run();
            }
            windows[w] = mx.getCurrentThreadAllocatedBytes() - before;
        }
        String all = java.util.Arrays.toString(windows);
        assertAll(
            () -> assertTrue(b.commandCount() > 0, "the last frame drew"),
            () -> assertTrue(hudBlips.count() == 10, "HUD radar blips converted"),
            () -> assertTrue(windows[1] == 0 && windows[2] == 0 && windows[3] == 0,
                "at most the first window allocates (a late JIT install); windows: " + all));
    }
}
