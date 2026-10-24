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
package com.fulcrologic.jxpilot.server;

import com.fulcrologic.jxpilot.common.config.ConfigParser;
import com.fulcrologic.jxpilot.common.config.OptionOrigin;
import com.fulcrologic.jxpilot.common.config.Options;
import com.fulcrologic.jxpilot.server.sim.Simulation;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.World;

import java.nio.file.Path;

/**
 * Test fixtures: small worlds built from map rows (row 0 is the TOP of the map, as in .xp files) plus
 * option lines, and helpers to run ticks. One sim tick is exactly one C frame at every framesPerSecond,
 * so C formulas apply verbatim.
 */
public final class TestWorlds {
    private TestWorlds() {}

    /** Map file text for the rows with extra option lines ({@code "name: value\n..."}). */
    public static String text(String extra, String... rows) {
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
    public static Options options(String extra, String... rows) {
        Options o = new Options();
        ConfigParser.parse(text(extra, rows), "test", OptionOrigin.MAP, o);
        return o;
    }

    /** A simulation over a world made from the rows, RNG seed 1. */
    public static Simulation sim(String extra, String... rows) {
        return new Simulation(new World(options(extra, rows), 1));
    }

    /** A simulation for one of the shipped maps ({@code maps/NAME} relative to the repo root). */
    public static Simulation shipped(String name, int seed) {
        try {
            Options o = new Options();
            Path p = Path.of("..", "maps", name);
            if (!p.toFile().exists()) {
                p = Path.of("maps", name);
            }
            ConfigParser.parseFile(p, OptionOrigin.MAP, o);
            return new Simulation(new World(o, seed));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Run n ticks with the same key mask for every slot. */
    public static void run(Simulation s, int n, long keys) {
        long[] in = new long[s.world.players.length];
        java.util.Arrays.fill(in, keys);
        for (int i = 0; i < n; i++) {
            s.tick(in);
        }
    }

    /** Run n ticks with per-slot masks. */
    public static void run(Simulation s, int n, long[] in) {
        for (int i = 0; i < n; i++) {
            s.tick(in);
        }
    }

    /** Join a player named {@code name} (team ignored outside team play). */
    public static Player join(Simulation s, String name, int team) {
        Player p = s.ships.join(name, team);
        if (p == null) {
            throw new IllegalStateException("no free base");
        }
        seeAll(s);
        return p;
    }

    /**
     * The visibility pass of a frame (C update.c:937-951) for every player, as the next tick would run it: a new
     * player's tables start empty like C's calloc'ed ones, so fixtures that skip the tick run it here.
     */
    public static void seeAll(Simulation s) {
        for (int k = 0; k < s.world.numPlayers; k++) {
            s.locks.updateVisibility(s.world.playerAt(k));
        }
    }
}
