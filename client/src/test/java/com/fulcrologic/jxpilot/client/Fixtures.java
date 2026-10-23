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
package com.fulcrologic.jxpilot.client;

import com.fulcrologic.jxpilot.client.render.Font;
import com.fulcrologic.jxpilot.client.render.VertexBatch;
import com.fulcrologic.jxpilot.common.config.ConfigParser;
import com.fulcrologic.jxpilot.common.config.OptionOrigin;
import com.fulcrologic.jxpilot.common.config.Options;
import com.fulcrologic.jxpilot.common.map.WorldMap;

import java.nio.ByteBuffer;
import java.nio.file.Path;

/** Shared test fixtures: tiny maps from text rows and a synthetic font. */
public final class Fixtures {
    private Fixtures() {}

    /** Repository maps directory. */
    public static final Path MAPS = Path.of("..", "maps");

    /** A map from rows (first row = top), with optional extra option lines. */
    public static WorldMap mapWith(boolean wrap, String extra, String... rows) {
        StringBuilder sb = new StringBuilder();
        sb.append("mapWidth: ").append(rows[0].length()).append('\n');
        sb.append("mapHeight: ").append(rows.length).append('\n');
        sb.append("edgeWrap: ").append(wrap ? "yes" : "no").append('\n');
        sb.append(extra);
        sb.append("mapData: \\multiline: EndOfMapdata\n");
        for (String r : rows) {
            sb.append(r).append('\n');
        }
        sb.append("EndOfMapdata\n");
        Options o = new Options();
        ConfigParser.parse(sb.toString(), "fixture", OptionOrigin.MAP, o);
        return WorldMap.fromOptions(o);
    }

    /** A fixed batch with room for {@code n} triangle vertices, {@code n} segments and {@code n} points. */
    public static VertexBatch batch(int n, int commands, VertexBatch.Sink sink) {
        return VertexBatch.fixed(ByteBuffer.allocateDirect(n * VertexBatch.STRIDE),
            ByteBuffer.allocateDirect(n * VertexBatch.SEGMENT_STRIDE), ByteBuffer.allocateDirect(n * VertexBatch.POINT_STRIDE),
            commands, sink);
    }

    public static WorldMap map(boolean wrap, String... rows) {
        return mapWith(wrap, "", rows);
    }

    /**
     * Synthetic font in a 1000x100 atlas: ascent 10, descent 3. Glyph for char c (except space,
     * which is empty) occupies atlas x [(c-32)*8, +6], y [0, 9], offset (1, -8), advance 7.
     * '1' is narrower: width 4, advance 5.
     */
    public static Font font() {
        Font f = new Font(1000, 100, 10, 3);
        for (char c = Font.FIRST; c <= Font.LAST; c++) {
            float x0 = (c - 32) * 8;
            if (c == ' ') {
                f.setGlyph(c, x0, 0, x0, 0, 0, 0, 7);
            } else if (c == '1') {
                f.setGlyph(c, x0, 0, x0 + 4, 9, 1, -8, 5);
            } else {
                f.setGlyph(c, x0, 0, x0 + 6, 9, 1, -8, 7);
            }
        }
        return f;
    }
}
