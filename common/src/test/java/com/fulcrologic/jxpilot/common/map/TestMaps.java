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
package com.fulcrologic.jxpilot.common.map;

import com.fulcrologic.jxpilot.common.config.ConfigParser;
import com.fulcrologic.jxpilot.common.config.OptionOrigin;
import com.fulcrologic.jxpilot.common.config.Options;

/** Builds small maps from rows for tests. */
final class TestMaps {
    private TestMaps() {}

    static String text(int w, int h, String extra, String... rows) {
        StringBuilder sb = new StringBuilder();
        sb.append("mapWidth: ").append(w).append('\n');
        sb.append("mapHeight: ").append(h).append('\n');
        sb.append(extra).append('\n');
        sb.append("mapData: \\multiline: EndOfMapdata\n");
        for (String r : rows) {
            sb.append(r).append('\n');
        }
        sb.append("EndOfMapdata\n");
        return sb.toString();
    }

    static WorldMap build(int w, int h, String extra, String... rows) {
        Options o = new Options();
        ConfigParser.parse(text(w, h, extra, rows), "test", OptionOrigin.MAP, o);
        return WorldMap.fromOptions(o);
    }

    static WorldMap build(String extra, String... rows) {
        return build(rows[0].length(), rows.length, extra, rows);
    }
}
