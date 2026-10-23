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
package com.fulcrologic.jxpilot.client.map;

import com.fulcrologic.jxpilot.client.render.Camera;
import com.fulcrologic.jxpilot.client.render.Palette;
import com.fulcrologic.jxpilot.client.render.Shapes;
import com.fulcrologic.jxpilot.client.render.TextWriter;
import com.fulcrologic.jxpilot.client.render.VertexBatch;
import com.fulcrologic.jxpilot.common.Const;
import com.fulcrologic.jxpilot.common.map.Base;
import com.fulcrologic.jxpilot.common.map.Cannon;
import com.fulcrologic.jxpilot.common.map.Checkpoint;
import com.fulcrologic.jxpilot.common.map.Concentrator;
import com.fulcrologic.jxpilot.common.map.Dir;
import com.fulcrologic.jxpilot.common.map.FuelStation;
import com.fulcrologic.jxpilot.common.map.Target;
import com.fulcrologic.jxpilot.common.map.Treasure;
import com.fulcrologic.jxpilot.common.map.WorldMap;
import com.fulcrologic.jxpilot.common.map.Wormhole;

import java.util.List;

/**
 * Per-frame map symbols whose look depends on game state or time: fuel stations, cannons,
 * targets, treasures, wormholes, item/asteroid concentrators, checkpoints and base team digits.
 * Shapes follow {@code src/client/guimap.c}.
 * <p>
 * {@link #draw} emits three passes over all visible world copies: fills, lines, then text, so the
 * whole thing costs three draw commands. Objects whose block (plus a text margin) is off-screen
 * are skipped. Shapes are written in world coordinates through {@link Camera#applyTo}; text is
 * placed in screen pixels at unscaled font size, as the C client does.
 * Allocation-free.
 */
public final class MapSymbols {
    static final int S = Const.BLOCK_SZ;
    static final int HALF = S / 2;
    static final int THIRD = S / 3;
    static final int TWO_THIRDS = 2 * S / 3;
    /** C {@code FUEL_BORDER}. */
    static final int FUEL_BORDER = 2;
    /** C {@code BORDER} for base text, in screen pixels. */
    static final int BASE_TEXT_BORDER = 4;
    static final int CIRCLE_SEGMENTS = 24;
    /** Screen-pixel margin for culling (text can stick out of its block). */
    static final float CULL_MARGIN = 48;

    /** Wormhole animation offsets per {@code loops & 7} (C {@code wormOffset}). */
    static final int[][] WORM_OFFSET = {
        {10, 10, 10}, {5, 10, 10}, {0, 10, 10}, {0, 5, 10},
        {0, 0, 10}, {5, 0, 10}, {10, 0, 10}, {10, 5, 10}
    };
    /** Item concentrator triangles: radius, displacement, direction offset (C {@code tris}). */
    static final int[][] CONC_TRIS = {{7, 3, 0}, {11, 5, 3}, {14, 8, 5}};
    /** Asteroid concentrator squares: size, displacement, direction offset (C {@code sqrs}). */
    static final int[][] CONC_SQRS = {{8, 3, 0}, {12, 5, 3}, {16, 8, 5}};

    private final WorldMap map;
    private final Palette palette;
    private final float[] px = new float[4];
    private final float[] py = new float[4];

    public MapSymbols(WorldMap map, Palette palette) {
        this.map = map;
        this.palette = palette;
    }

    /** Draw all symbols for the camera's visible world copies. */
    public void draw(VertexBatch b, TextWriter text, Camera cam, MapState st) {
        for (int k = 0; k < cam.offsetCount(); k++) {
            cam.applyTo(b, k);
            fills(b, cam, k, st);
        }
        for (int k = 0; k < cam.offsetCount(); k++) {
            cam.applyTo(b, k);
            lines(b, cam, k, st);
        }
        b.identity();
        for (int k = 0; k < cam.offsetCount(); k++) {
            texts(b, text, cam, k, st);
        }
    }

    static boolean visible(Camera cam, int bx, int by, int k) {
        return cam.isVisible(bx * (double) S, by * (double) S, (bx + 1) * (double) S, (by + 1) * (double) S,
            k, CULL_MARGIN);
    }

    // ------------------------------------------------------------------ passes

    private void fills(VertexBatch b, Camera cam, int k, MapState st) {
        List<FuelStation> fuels = map.fuels();
        int red = palette.color(Palette.RED);
        for (int i = 0; i < fuels.size(); i++) {
            FuelStation f = fuels.get(i);
            if (visible(cam, f.bx(), f.by(), k)) {
                fuelBar(b, f.bx() * S, f.by() * S, st.fuel(i), red);
            }
        }
        List<Checkpoint> checks = map.checkpoints();
        int next = st.nextCheckpoint();
        if (next >= 0 && next < checks.size()) {
            Checkpoint c = checks.get(next);
            if (visible(cam, c.bx(), c.by(), k)) {
                int x = c.bx() * S;
                int y = c.by() * S;
                int blue = palette.color(Palette.BLUE);
                b.triangle(x + HALF, y, x + S, y + HALF, x + HALF, y + S, blue);
                b.triangle(x + HALF, y, x + HALF, y + S, x, y + HALF, blue);
            }
        }
    }

    private void lines(VertexBatch b, Camera cam, int k, MapState st) {
        int white = palette.color(Palette.WHITE);
        int red = palette.color(Palette.RED);
        int blue = palette.color(Palette.BLUE);
        List<Cannon> cannons = map.cannons();
        for (int i = 0; i < cannons.size(); i++) {
            Cannon c = cannons.get(i);
            if (st.cannonAlive(i) && visible(cam, c.bx(), c.by(), k)) {
                cannon(b, c.dir(), c.bx() * S, c.by() * S, white);
            }
        }
        List<Target> targets = map.targets();
        for (int i = 0; i < targets.size(); i++) {
            Target t = targets.get(i);
            if (st.targetAlive(i) && visible(cam, t.bx(), t.by(), k)) {
                int c = own(t.team(), st) ? blue : red;
                target(b, t.bx() * S, t.by() * S, st.targetDamage(i), c, red);
            }
        }
        List<Treasure> treasures = map.treasures();
        for (int i = 0; i < treasures.size(); i++) {
            Treasure t = treasures.get(i);
            if (visible(cam, t.bx(), t.by(), k)) {
                treasure(b, t.bx() * S, t.by() * S, own(t.team(), st) ? blue : red);
            }
        }
        List<Wormhole> worms = map.wormholes();
        int wormCount = (int) (st.loops() & 7);
        for (int i = 0; i < worms.size(); i++) {
            Wormhole w = worms.get(i);
            if (w.type() != Wormhole.Type.OUT && visible(cam, w.bx(), w.by(), k)) {
                wormhole(b, w.bx() * S, w.by() * S, wormCount, red);
            }
        }
        List<Concentrator> items = map.itemConcentrators();
        for (int i = 0; i < items.size(); i++) {
            Concentrator c = items.get(i);
            if (visible(cam, c.bx(), c.by(), k)) {
                concentrator(b, c.bx() * S, c.by() * S, st.loops(), CONC_TRIS, 3, red);
            }
        }
        List<Concentrator> asteroids = map.asteroidConcentrators();
        for (int i = 0; i < asteroids.size(); i++) {
            Concentrator c = asteroids.get(i);
            if (visible(cam, c.bx(), c.by(), k)) {
                concentrator(b, c.bx() * S, c.by() * S, st.loops(), CONC_SQRS, 4, red);
            }
        }
        List<Checkpoint> checks = map.checkpoints();
        for (int i = 0; i < checks.size(); i++) {
            Checkpoint c = checks.get(i);
            if (i != st.nextCheckpoint() && visible(cam, c.bx(), c.by(), k)) {
                checkpoint(b, c.bx() * S, c.by() * S, blue);
            }
        }
    }

    private void texts(VertexBatch b, TextWriter text, Camera cam, int k, MapState st) {
        boolean team = map.teamPlay();
        int black = palette.color(Palette.BLACK);
        int red = palette.color(Palette.RED);
        int blue = palette.color(Palette.BLUE);
        int white = palette.color(Palette.WHITE);
        List<FuelStation> fuels = map.fuels();
        for (int i = 0; i < fuels.size(); i++) {
            FuelStation f = fuels.get(i);
            if (visible(cam, f.bx(), f.by(), k)) {
                int c = fuelCoversCentre(st.fuel(i)) ? black : red;
                centredChar(b, text, 'F', cam.screenX(f.bx() * S + HALF, k), cam.screenY(f.by() * S + HALF, k), c);
            }
        }
        if (!team) {
            return;
        }
        List<Base> bases = map.bases();
        for (int i = 0; i < bases.size(); i++) {
            Base base = bases.get(i);
            if (base.team() >= 0 && visible(cam, base.bx(), base.by(), k)) {
                baseDigit(b, text, cam, k, base, white);
            }
        }
        List<Target> targets = map.targets();
        for (int i = 0; i < targets.size(); i++) {
            Target t = targets.get(i);
            if (t.team() >= 0 && st.targetAlive(i) && visible(cam, t.bx(), t.by(), k)) {
                centredChar(b, text, digit(t.team()), cam.screenX(t.bx() * S + HALF, k),
                    cam.screenY(t.by() * S + HALF, k), own(t.team(), st) ? blue : red);
            }
        }
        List<Treasure> treasures = map.treasures();
        for (int i = 0; i < treasures.size(); i++) {
            Treasure t = treasures.get(i);
            if (t.team() >= 0 && visible(cam, t.bx(), t.by(), k)) {
                char d = digit(t.team());
                float x = cam.screenX(t.bx() * S + HALF, k) - (int) text.width(d) / 2;
                text.draw(b, d, x, cam.screenY(t.by() * S + 2 * Const.BALL_RADIUS, k),
                    own(t.team(), st) ? blue : red);
            }
        }
    }

    // ------------------------------------------------------------------ shapes (world coordinates)

    static boolean own(int team, MapState st) {
        return team != Const.TEAM_NOT_SET && team == st.selfTeam();
    }

    static char digit(int team) {
        return (char) ('0' + team);
    }

    /** Fuel bar height in pixels (C: {@code (BLOCK_SZ - 2*FUEL_BORDER) * fuel / MAX_STATION_FUEL}, int). */
    static int fuelHeight(double fuel) {
        return (int) ((S - 2 * FUEL_BORDER) * fuel / Const.MAX_STATION_FUEL);
    }

    /**
     * True when the fuel bar reaches the block centre. The C client XORs the "F" over the bar, so it
     * shows black on the bar and red above it; we pick one colour by the centre.
     */
    static boolean fuelCoversCentre(double fuel) {
        return FUEL_BORDER + fuelHeight(fuel) >= S / 2f;
    }

    /** Red bar {@code [x+2, x+33] x [y+2, y+2+height]}; nothing for an empty station. */
    static void fuelBar(VertexBatch b, int x, int y, double fuel, int c) {
        int h = fuelHeight(fuel);
        if (h > 0) {
            b.fillRect(x + FUEL_BORDER, y + FUEL_BORDER, x + S - FUEL_BORDER, y + FUEL_BORDER + h, c);
        }
    }

    /**
     * Closed cannon triangle (C {@code Gui_paint_cannon}): base on the mounting side, apex a third of
     * a block in.
     */
    static void cannon(VertexBatch b, int dir, int x, int y, int c) {
        switch (dir) {
            case Dir.UP -> tri(b, x, y, x + S, y, x + HALF, y + THIRD, c);
            case Dir.DOWN -> tri(b, x, y + S, x + S, y + S, x + HALF, y + TWO_THIRDS, c);
            case Dir.RIGHT -> tri(b, x, y, x, y + S, x + THIRD, y + HALF, c);
            default -> tri(b, x + S, y, x + S, y + S, x + TWO_THIRDS, y + HALF, c);
        }
    }

    private static void tri(VertexBatch b, float x1, float y1, float x2, float y2, float x3, float y3, int c) {
        b.line(x1, y1, x2, y2, c);
        b.line(x2, y2, x3, y3, c);
        b.line(x3, y3, x1, y1, c);
    }

    /**
     * Target (C {@code Gui_paint_setup_target}): block outline, inner square
     * {@code [x+9, x+26]^2}, and when damaged a red cross whose arms shrink inward with damage.
     */
    static void target(VertexBatch b, int x, int y, double damage, int c, int red) {
        b.strokeRect(x, y, x + S, y + S, c);
        int q = (S + 2) / 4;
        b.strokeRect(x + q, y + q, x + q + HALF, y + q + HALF, c);
        if (damage != Const.TARGET_DAMAGE) {
            int size = (int) (damage * S / (Const.TARGET_DAMAGE * 2));
            int a1 = x + size;
            int a2 = y + size;
            int b1 = x + S - size;
            int b2 = y + S - size;
            b.line(a1, a2, b1, b2, red);
            b.line(a1, b2, b1, a2, red);
        }
    }

    /** Treasure (C {@code Gui_paint_setup_treasure}): open-topped box of half height under a half circle. */
    static void treasure(VertexBatch b, int x, int y, int c) {
        b.line(x, y, x, y + HALF, c);
        b.line(x + S, y, x + S, y + HALF, c);
        b.line(x, y, x + S, y, c);
        Shapes.arc(b, x + S / 2f, y + S / 2f, S / 2f, 0, 180, CIRCLE_SEGMENTS / 2, c);
    }

    /**
     * Wormhole (C {@code Gui_paint_setup_worm}): three nested circles, the inner ones displaced by the
     * animation step {@code count} (0..7). Circle j has X11 box origin
     * {@code (x + O0*j, top - O1*j)} (screen y down) and diameter {@code BLOCK_SZ - 2 - O2*j}.
     */
    static void wormhole(VertexBatch b, int x, int y, int count, int c) {
        int[] o = WORM_OFFSET[count];
        for (int j = 0; j < 3; j++) {
            float d = (S - 2) - o[2] * j;
            float cx = x + o[0] * j + d / 2;
            float cy = y + S - o[1] * j - d / 2;
            Shapes.circle(b, cx, cy, d / 2, CIRCLE_SEGMENTS, c);
        }
    }

    /**
     * Item (3-gon) or asteroid (4-gon) concentrator (C {@code Gui_paint_setup_*_concentrator}).
     * At frame {@code loops} the centres orbit at RES heading {@code 5*loops + dirOff} and the shapes
     * spin at {@code 3*loops}; C works in screen space (y down), so world y uses {@code -sin}.
     */
    void concentrator(VertexBatch b, int x, int y, long loops, int[][] shapes, int corners, int c) {
        float bcx = x + HALF;
        float bcy = y + HALF;
        int step = Const.RES / corners;
        for (int[] s : shapes) {
            int rdir = (int) ((5 * loops + s[2]) & (Const.RES - 1));
            float cx = bcx + (float) (s[1] * tcos(rdir));
            float cy = bcy - (float) (s[1] * tsin(rdir));
            int tdir = (int) ((3 * loops) & (Const.RES - 1));
            for (int m = 0; m < corners; m++) {
                int d = (tdir + m * step) & (Const.RES - 1);
                px[m] = cx + (float) (s[0] * tcos(d));
                py[m] = cy - (float) (s[0] * tsin(d));
            }
            Shapes.closedPolyline(b, px, py, corners, c);
        }
    }

    static double tcos(int res) {
        return Math.cos(res * 2 * Math.PI / Const.RES);
    }

    static double tsin(int res) {
        return Math.sin(res * 2 * Math.PI / Const.RES);
    }

    /** Checkpoint diamond outline (C {@code Gui_paint_setup_check}). */
    static void checkpoint(VertexBatch b, int x, int y, int c) {
        b.line(x + HALF, y, x, y + HALF, c);
        b.line(x, y + HALF, x + HALF, y + S, c);
        b.line(x + HALF, y + S, x + S, y + HALF, c);
        b.line(x + S, y + HALF, x + HALF, y, c);
    }

    // ------------------------------------------------------------------ text (screen pixels)

    /** One char centred horizontally on sx with its baseline half an ascent below sy (C style). */
    static void centredChar(VertexBatch b, TextWriter text, char ch, float sx, float sy, int c) {
        float x = sx - (int) text.width(ch) / 2;
        float base = sy - (int) text.font().ascent() / 2;
        text.draw(b, ch, x, base, c);
    }

    /**
     * Base team digit placed beside the base opposite its line (C {@code Gui_paint_base} with no
     * player name): below an up-facing base, above a down-facing one, right of a left-facing one and
     * left of a right-facing one, {@link #BASE_TEXT_BORDER} logical pixels from the block.
     */
    static void baseDigit(VertexBatch b, TextWriter text, Camera cam, int k, Base base, int c) {
        char d = digit(base.team());
        int w = (int) text.width(d);
        int ascent = (int) text.font().ascent();
        int descent = (int) text.font().descent();
        int x = base.bx() * S;
        int y = base.by() * S;
        float border = BASE_TEXT_BORDER * (float) cam.pixelRatio();
        float sx;
        float sy;
        switch (base.dir()) {
            case Dir.UP -> {
                sx = cam.screenX(x + HALF, k) - w / 2;
                sy = cam.screenY(y, k) - border - ascent;
            }
            case Dir.DOWN -> {
                sx = cam.screenX(x + HALF, k) - w / 2;
                sy = cam.screenY(y + S, k) + border + descent;
            }
            case Dir.LEFT -> {
                sx = cam.screenX(x + S, k) + border;
                sy = cam.screenY(y + HALF, k) - ascent / 2;
            }
            default -> {
                sx = cam.screenX(x, k) - border - w;
                sy = cam.screenY(y + HALF, k) - ascent / 2;
            }
        }
        text.draw(b, d, sx, sy, c);
    }
}
