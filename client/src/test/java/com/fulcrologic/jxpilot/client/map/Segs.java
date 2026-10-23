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

import com.fulcrologic.jxpilot.client.render.VertexBatch;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/** Test helpers to read batches back as readable segment / triangle strings. */
final class Segs {
    private Segs() {}

    static VertexBatch batch() {
        return VertexBatch.fixed(ByteBuffer.allocateDirect(65536 * VertexBatch.STRIDE), 256, b -> {
            throw new AssertionError("test batch overflowed");
        });
    }

    static String p(float x, float y) {
        return fmt(x) + "," + fmt(y);
    }

    static String fmt(float v) {
        return v == Math.rint(v) ? Integer.toString((int) v) : Float.toString(v);
    }

    /** All line segments of the batch in order, as "x1,y1-x2,y2". */
    static List<String> lines(VertexBatch b) {
        return lines(b, null);
    }

    /** Line segments of one colour (all colours for null). */
    static List<String> lines(VertexBatch b, Integer onlyColor) {
        List<String> out = new ArrayList<>();
        for (int c = 0; c < b.commandCount(); c++) {
            if (b.commandMode(c) != VertexBatch.LINES) {
                continue;
            }
            for (int i = b.commandFirst(c); i < b.commandFirst(c) + b.commandVertices(c); i += 2) {
                if (onlyColor != null && b.color(i) != onlyColor) {
                    continue;
                }
                out.add(p(b.x(i), b.y(i)) + "-" + p(b.x(i + 1), b.y(i + 1)));
            }
        }
        return out;
    }

    /** All triangles of the batch in order, as "x1,y1 x2,y2 x3,y3". */
    static List<String> triangles(VertexBatch b) {
        List<String> out = new ArrayList<>();
        for (int c = 0; c < b.commandCount(); c++) {
            if (b.commandMode(c) != VertexBatch.TRIANGLES) {
                continue;
            }
            for (int i = b.commandFirst(c); i < b.commandFirst(c) + b.commandVertices(c); i += 3) {
                out.add(p(b.x(i), b.y(i)) + " " + p(b.x(i + 1), b.y(i + 1)) + " " + p(b.x(i + 2), b.y(i + 2)));
            }
        }
        return out;
    }

    /** Distinct colours used by vertices, in first-use order. */
    static List<Integer> colors(VertexBatch b) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < b.vertexCount(); i++) {
            if (!out.contains(b.color(i))) {
                out.add(b.color(i));
            }
        }
        return out;
    }
}
