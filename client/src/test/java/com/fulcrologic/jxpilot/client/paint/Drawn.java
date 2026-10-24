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
import com.fulcrologic.jxpilot.client.render.VertexBatch;

import java.util.ArrayList;
import java.util.List;

/**
 * Decodes what painters wrote into a batch, for exact assertions: text runs (from the synthetic
 * {@link Fixtures#font()} glyphs), item symbols, solid triangles and segments, each as a string.
 */
final class Drawn {
    private Drawn() {}

    static VertexBatch batch() {
        return Fixtures.batch(8192, 64, b -> { throw new AssertionError("test batch overflowed"); });
    }

    /** Glyph quads have v = 9/100 at their first vertex (the synthetic font's glyph bottom). */
    static final float GLYPH_V = 0.09f;

    /** A text run: chars (spaces rebuilt from gaps), the pen x of its first glyph, baseline, colour. */
    record Run(String text, float x, float baseline, int color) {
        @Override
        public String toString() {
            return "'" + text + "'@" + r(x) + "," + r(baseline) + "#" + Integer.toHexString(color);
        }
    }

    static String r(float v) {
        float q = Math.round(v * 1000) / 1000f;
        return q == Math.rint(q) ? Integer.toString((int) q) : Float.toString(q);
    }

    static float advance(char c) {
        return c == '1' ? 5 : 7;
    }

    /** Text runs in drawing order; a run continues while glyphs follow at the next pen position (up to 6 spaces). */
    static List<Run> runs(VertexBatch b) {
        List<Run> out = new ArrayList<>();
        StringBuilder sb = null;
        float startX = 0;
        float base = 0;
        int color = 0;
        float nextPen = 0;
        for (int i = 0; i < b.vertexCount(); ) {
            if (!isGlyph(b, i)) {
                i += isIcon(b, i) ? 6 : 3;
                continue;
            }
            char c = (char) (Math.round(b.u(i) * 1000 / 8) + 32);
            float pen = b.x(i) - 1;
            float baseline = b.y(i) + 9 - 8;
            int col = b.color(i);
            boolean joins = false;
            int spaces = 0;
            if (sb != null && baseline == base && col == color) {
                float gap = pen - nextPen;
                spaces = Math.round(gap / 7);
                joins = spaces >= 0 && spaces <= 6 && Math.abs(gap - spaces * 7) <= 1;
            }
            if (!joins) {
                if (sb != null) {
                    out.add(new Run(sb.toString(), startX, base, color));
                }
                sb = new StringBuilder();
                startX = pen;
                base = baseline;
                color = col;
                spaces = 0;
            }
            sb.append(" ".repeat(spaces)).append(c);
            nextPen = pen + advance(c);
            i += 6;
        }
        if (sb != null) {
            out.add(new Run(sb.toString(), startX, base, color));
        }
        return out;
    }

    static List<String> runStrings(VertexBatch b) {
        List<String> out = new ArrayList<>();
        for (Run r : runs(b)) {
            out.add(r.toString());
        }
        return out;
    }

    static List<String> texts(VertexBatch b) {
        List<String> out = new ArrayList<>();
        for (Run r : runs(b)) {
            out.add(r.text());
        }
        return out;
    }

    /** Item symbol quads: "item k x0,y0-x1,y1 #colour". */
    static List<String> icons(VertexBatch b) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < b.vertexCount(); ) {
            if (isIcon(b, i)) {
                int ax = Math.round(b.u(i) * HudPainter.ATLAS_SIZE);
                int ay = Math.round(b.v(i + 2) * HudPainter.ATLAS_SIZE);
                int item = (ax - ItemBitmaps.ATLAS_X) / 16 + ay / 16 * 16;
                out.add("item " + item + " " + r(b.x(i)) + "," + r(b.y(i)) + "-" + r(b.x(i + 2)) + "," + r(b.y(i + 2))
                    + " v" + Math.round(b.v(i) * HudPainter.ATLAS_SIZE) + "#" + Integer.toHexString(b.color(i)));
                i += 6;
            } else {
                i += isGlyph(b, i) ? 6 : 3;
            }
        }
        return out;
    }

    /** Solid triangles "x1,y1 x2,y2 x3,y3 #colour". */
    static List<String> tris(VertexBatch b) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < b.vertexCount(); ) {
            if (isGlyph(b, i) || isIcon(b, i)) {
                i += 6;
                continue;
            }
            out.add(r(b.x(i)) + "," + r(b.y(i)) + " " + r(b.x(i + 1)) + "," + r(b.y(i + 1)) + " "
                + r(b.x(i + 2)) + "," + r(b.y(i + 2)) + "#" + Integer.toHexString(b.color(i)));
            i += 3;
        }
        return out;
    }

    /** The two triangles {@code fillRect(x0, y0, x1, y1)} writes. */
    static List<String> rectTris(float x0, float y0, float x1, float y1, int color) {
        String c = "#" + Integer.toHexString(color);
        return List.of(
            r(x0) + "," + r(y0) + " " + r(x1) + "," + r(y0) + " " + r(x1) + "," + r(y1) + c,
            r(x0) + "," + r(y0) + " " + r(x1) + "," + r(y1) + " " + r(x0) + "," + r(y1) + c);
    }

    /** Segments "x1,y1-x2,y2". */
    static List<String> segs(VertexBatch b) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < b.segmentCount(); i++) {
            out.add(r(b.segX1(i)) + "," + r(b.segY1(i)) + "-" + r(b.segX2(i)) + "," + r(b.segY2(i)));
        }
        return out;
    }

    /** The four segments of {@code strokeRect(x0, y0, x1, y1)}. */
    static List<String> rectSegs(float x0, float y0, float x1, float y1) {
        return List.of(
            r(x0) + "," + r(y0) + "-" + r(x1) + "," + r(y0),
            r(x1) + "," + r(y0) + "-" + r(x1) + "," + r(y1),
            r(x1) + "," + r(y1) + "-" + r(x0) + "," + r(y1),
            r(x0) + "," + r(y1) + "-" + r(x0) + "," + r(y0));
    }

    static boolean isGlyph(VertexBatch b, int i) {
        return Math.abs(b.v(i) - GLYPH_V) < 1e-4f;
    }

    static boolean isIcon(VertexBatch b, int i) {
        return !isGlyph(b, i) && (b.u(i) != 0 || b.v(i) != 0 || b.u(i + 2) != 0 || b.v(i + 2) != 0);
    }
}
