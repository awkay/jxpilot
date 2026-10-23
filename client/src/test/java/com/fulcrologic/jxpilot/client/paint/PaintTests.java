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

/** Readable views of batch contents for painter tests. */
final class PaintTests {
    private PaintTests() {}

    static VertexBatch batch() {
        return Fixtures.batch(16384, 256, b -> {
            throw new AssertionError("test batch overflowed");
        });
    }

    /** Rounded to 3 decimals; integers without a decimal point. */
    static String r(float v) {
        float q = Math.round(v * 1000) / 1000f;
        if (q == 0) {
            q = 0; // no "-0"
        }
        return q == Math.rint(q) ? Integer.toString((int) q) : Float.toString(q);
    }

    static List<String> segs(VertexBatch b) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < b.segmentCount(); i++) {
            out.add(r(b.segX1(i)) + "," + r(b.segY1(i)) + "-" + r(b.segX2(i)) + "," + r(b.segY2(i)));
        }
        return out;
    }

    static List<Integer> segColors(VertexBatch b) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < b.segmentCount(); i++) {
            out.add(b.segColor(i));
        }
        return out;
    }

    static List<String> points(VertexBatch b) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < b.pointCount(); i++) {
            out.add(r(b.pointX(i)) + "," + r(b.pointY(i)) + "/" + r(b.pointSize(i)));
        }
        return out;
    }

    static List<String> tris(VertexBatch b) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i + 2 < b.vertexCount(); i += 3) {
            out.add(r(b.x(i)) + "," + r(b.y(i)) + " " + r(b.x(i + 1)) + "," + r(b.y(i + 1)) + " "
                + r(b.x(i + 2)) + "," + r(b.y(i + 2)));
        }
        return out;
    }

    static List<Integer> modes(VertexBatch b) {
        List<Integer> out = new ArrayList<>();
        for (int c = 0; c < b.commandCount(); c++) {
            out.add(b.commandMode(c));
        }
        return out;
    }

    /** Every command and item of a batch, with colours, widths, sizes and UVs, for exact comparison. */
    static List<String> dump(VertexBatch b) {
        List<String> out = new ArrayList<>();
        for (int c = 0; c < b.commandCount(); c++) {
            int m = b.commandMode(c);
            out.add("cmd " + m + " x" + b.commandLength(c));
            for (int i = b.commandFirst(c); i < b.commandFirst(c) + b.commandLength(c); i++) {
                out.add(switch (m) {
                    case VertexBatch.TRIANGLES -> r(b.x(i)) + "," + r(b.y(i)) + " uv " + r(b.u(i)) + "," + r(b.v(i))
                        + " #" + Integer.toHexString(b.color(i));
                    case VertexBatch.LINES -> r(b.segX1(i)) + "," + r(b.segY1(i)) + "-" + r(b.segX2(i)) + "," + r(b.segY2(i))
                        + " w" + r(b.segWidth(i)) + " #" + Integer.toHexString(b.segColor(i));
                    default -> r(b.pointX(i)) + "," + r(b.pointY(i)) + " s" + r(b.pointSize(i)) + " #"
                        + Integer.toHexString(b.pointColor(i));
                });
            }
        }
        return out;
    }
}
