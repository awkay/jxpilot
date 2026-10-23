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
package com.fulcrologic.jxpilot.client.paint;

import com.fulcrologic.jxpilot.client.Fixtures;
import com.fulcrologic.jxpilot.client.render.Palette;
import com.fulcrologic.jxpilot.client.render.TextWriter;
import com.fulcrologic.jxpilot.client.render.VertexBatch;
import com.fulcrologic.jxpilot.common.Item;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;

import static org.junit.jupiter.api.Assertions.*;

/** Every HUD painter, every frame, with values that change each frame, allocates nothing in the steady state. */
class HudAllocationTest {
    static final int WARMUP = 40_000;
    static final int WINDOWS = 5;
    static final int WINDOW_FRAMES = 10_000;

    /** A preallocated draw list like client-net's HudFrame: fixed arrays refilled each frame. */
    static final class FixedList implements HudDrawList {
        final float[][] line = new float[16][4];
        final int[] lineStyle = new int[16];
        final float[][] rect = new float[8][4];
        final boolean[] filled = new boolean[8];
        final StringBuilder[] text = new StringBuilder[8];
        final float[][] textXY = new float[8][2];
        final int[] textColor = new int[8];
        int lines;
        int rects;
        int texts;

        FixedList() {
            for (int i = 0; i < text.length; i++) {
                text[i] = new StringBuilder(64);
            }
        }

        @Override public int lineCount() { return lines; }
        @Override public float lineX0(int i) { return line[i][0]; }
        @Override public float lineY0(int i) { return line[i][1]; }
        @Override public float lineX1(int i) { return line[i][2]; }
        @Override public float lineY1(int i) { return line[i][3]; }
        @Override public int lineStyle(int i) { return lineStyle[i]; }
        @Override public int lineColor(int i) { return i % 7; }
        @Override public int rectCount() { return rects; }
        @Override public float rectX(int i) { return rect[i][0]; }
        @Override public float rectY(int i) { return rect[i][1]; }
        @Override public float rectW(int i) { return rect[i][2]; }
        @Override public float rectH(int i) { return rect[i][3]; }
        @Override public boolean rectFilled(int i) { return filled[i]; }
        @Override public int rectColor(int i) { return i % 7; }
        @Override public int textCount() { return texts; }
        @Override public CharSequence text(int i) { return text[i]; }
        @Override public int textLength(int i) { return text[i].length(); }
        @Override public float textX(int i) { return textXY[i][0]; }
        @Override public float textY(int i) { return textXY[i][1]; }
        @Override public int textColor(int i) { return textColor[i]; }
    }

    @Test
    void steadyStateHudFramesAllocateZeroBytes() {
        com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        Palette p = Palette.xpilotDefault();
        long[] flushes = new long[1];
        VertexBatch b = Fixtures.batch(256, 16, batch -> flushes[0]++);
        TextWriter text = new TextWriter(Fixtures.font());
        HudPainter hud = new HudPainter(text, p);
        HudListPainter list = HudListPainter.xpilot(text, p);
        ScreenSpace ss = new ScreenSpace();
        ss.set(1200, 2);
        FixedList fl = new FixedList();
        int[] counts = new int[Item.COUNT];
        float[] shipX = {15, -9, -9};
        float[] shipY = {0, 8, -8};
        int[] rx = new int[20];
        int[] ry = new int[20];
        int[] rs = new int[20];
        StringBuilder mods = new StringBuilder("FX");
        StringBuilder score = new StringBuilder(16);

        Runnable frame = new Runnable() {
            long n;

            @Override
            public void run() {
                n++;
                int k = (int) n;
                int hx = 512 + k % 20;
                int hy = 300 - k % 15;
                for (int i = 0; i < counts.length; i++) {
                    counts[i] = (k / 50 + i) % 9 - 2;
                }
                for (int i = 0; i < rx.length; i++) {
                    rx[i] = (k * 7 + i * 31) % 256;
                    ry[i] = (k * 3 + i * 17) % 256;
                    rs[i] = i % 5 == 0 ? 0x81 : i % 4;
                }
                fl.lines = 2 + k % 10;
                for (int i = 0; i < fl.lines; i++) {
                    fl.line[i][0] = hx - 90;
                    fl.line[i][1] = hy - 70 + i;
                    fl.line[i][2] = hx + 90;
                    fl.line[i][3] = hy - 70 + i;
                    fl.lineStyle[i] = i & 1;
                }
                fl.rects = 1 + k % 8;
                for (int i = 0; i < fl.rects; i++) {
                    fl.rect[i][0] = 10 * i;
                    fl.rect[i][1] = 20;
                    fl.rect[i][2] = k % 30;
                    fl.rect[i][3] = 8;
                    fl.filled[i] = (i & 1) == 0;
                }
                fl.texts = 1 + k % 8;
                for (int i = 0; i < fl.texts; i++) {
                    fl.text[i].setLength(0);
                    fl.text[i].append("message ").append(i);
                    fl.textXY[i][0] = 10;
                    fl.textXY[i][1] = 20 + 14 * i;
                    fl.textColor[i] = (k + i) % 7;
                }
                score.setLength(0);
                score.append(k % 100 - 50);
                b.clear();
                list.paint(b, ss, fl);
                hud.paintItems(b, ss, hx, hy, counts, k % 21, (k & 32) != 0, PaintConfig.HUD_COLOR);
                hud.paintLockText(b, ss, hx, hy, "Target", (k & 256) != 0, k % 4, k % 1200, PaintConfig.HUD_COLOR);
                hud.paintLockShip(b, ss, hx, hy, shipX, shipY, 3, PaintConfig.HUD_COLOR);
                hud.paintLockDisc(b, ss, hx, hy, k % 1200, k & 127, 5000, (k & 64) != 0, (k & 1) == 0,
                    PaintConfig.HUD_COLOR, PaintConfig.HUD_LOCK_COLOR);
                hud.paintTimeLeft(b, ss, hx, hy, 4000 - k % 4000, PaintConfig.HUD_COLOR);
                hud.paintMods(b, ss, hx, hy, mods, PaintConfig.HUD_COLOR);
                hud.paintAutopilot(b, ss, hx, hy, PaintConfig.HUD_COLOR);
                hud.paintHudRadar(b, ss, 1024, 600, rx, ry, rs, rx.length, 256, 3500, 3500, k % 3500, k % 2000,
                    (k & 2) != 0, PaintConfig.HUD_COLOR);
                if (HudPainter.scoreObjectVisible(k % 100)) {
                    hud.paintScoreObject(b, ss, score, 100 + k % 50, 200, PaintConfig.HUD_COLOR);
                }
            }
        };
        for (int i = 0; i < WARMUP; i++) {
            frame.run();
        }
        // Several back-to-back windows: a late JIT (re)install can allocate once inside the JVM, so the
        // claim is about the steady state, i.e. the last window; all windows are shown on failure.
        long[] windows = new long[WINDOWS];
        for (int w = 0; w < WINDOWS; w++) {
            long before = mx.getCurrentThreadAllocatedBytes();
            for (int i = 0; i < WINDOW_FRAMES; i++) {
                frame.run();
            }
            windows[w] = mx.getCurrentThreadAllocatedBytes() - before;
        }
        String all = java.util.Arrays.toString(windows);
        assertAll(
            () -> assertTrue(flushes[0] > 0, "the batch overflowed and flushed during the run"),
            () -> assertTrue(allZeroAfterFirst(windows),
                "at most the first window allocates (a late JIT install); windows: " + all));
    }

    /** True when every window but the first allocated nothing. */
    static boolean allZeroAfterFirst(long[] windows) {
        for (int w = 1; w < windows.length; w++) {
            if (windows[w] != 0) {
                return false;
            }
        }
        return true;
    }
}
