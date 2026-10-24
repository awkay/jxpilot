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
package com.fulcrologic.jxpilot.common.sim;

import com.fulcrologic.jxpilot.common.config.ConfigParser;
import com.fulcrologic.jxpilot.common.config.OptionOrigin;
import com.fulcrologic.jxpilot.common.config.Options;
import com.fulcrologic.jxpilot.common.map.WorldMap;

/**
 * Test fixtures for the ship kernel: small maps built from rows (row 0 is the TOP of the map, as in .xp files)
 * plus option lines, the same text the server's {@code TestWorlds} builds.
 */
final class KernelFx {
    private KernelFx() {}

    /** Map file text for the rows with extra option lines ({@code "name: value\n..."}). */
    static String text(String extra, String... rows) {
        StringBuilder sb = new StringBuilder();
        sb.append("mapWidth: ").append(rows[0].length()).append('\n');
        sb.append("mapHeight: ").append(rows.length).append('\n');
        sb.append(extra).append('\n');
        sb.append("mapData: \\multiline: EndOfMapdata\n");
        for (String r : rows) {
            sb.append(r).append('\n');
        }
        sb.append("EndOfMapdata\n");
        return sb.toString();
    }

    /** Parsed options for the rows. */
    static Options options(String extra, String... rows) {
        Options o = new Options();
        ConfigParser.parse(text(extra, rows), "test", OptionOrigin.MAP, o);
        return o;
    }

    /** The map of the rows. */
    static WorldMap map(String extra, String... rows) {
        return WorldMap.fromOptions(options(extra, rows));
    }
}
