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
import com.fulcrologic.jxpilot.server.sim.Digest;
import com.fulcrologic.jxpilot.server.sim.RandomPilot;
import com.fulcrologic.jxpilot.server.sim.Simulation;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.World;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;

/**
 * Headless simulation runner for determinism checks and profiling:
 * {@code ServerSim --map maps/X.xp [--ticks N] [--seed S] [--players P] [--every K]}.
 * Players fly with {@link RandomPilot} inputs (seeded from S). Prints the state digest every K ticks (if
 * given) and at the end, plus a few counters. Same arguments, same output.
 */
public final class ServerSim {
    private ServerSim() {}

    /** Parsed command line. */
    record Args(String map, int ticks, int seed, int players, int every) {
        /**
         * BEHAVIOR: defaults ticks 600, seed 1, players 4, every 0; unknown flags or a missing --map throw
         * IllegalArgumentException.
         */
        static Args parse(String[] a) {
            String map = null;
            int ticks = 600;
            int seed = 1;
            int players = 4;
            int every = 0;
            for (int i = 0; i < a.length; i++) {
                String v = i + 1 < a.length ? a[i + 1] : null;
                switch (a[i]) {
                    case "--map" -> map = v;
                    case "--ticks" -> ticks = Integer.parseInt(v);
                    case "--seed" -> seed = Integer.parseInt(v);
                    case "--players" -> players = Integer.parseInt(v);
                    case "--every" -> every = Integer.parseInt(v);
                    default -> throw new IllegalArgumentException("unknown argument " + a[i]);
                }
                i++;
            }
            if (map == null) {
                throw new IllegalArgumentException(
                    "usage: ServerSim --map FILE [--ticks N] [--seed S] [--players P] [--every K]");
            }
            return new Args(map, ticks, seed, players, every);
        }
    }

    public static void main(String[] argv) throws IOException {
        Args a;
        try {
            a = Args.parse(argv);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.exit(2);
            return;
        }
        Options o = new Options();
        ConfigParser.parseFile(Path.of(a.map()), OptionOrigin.MAP, o);
        long t0 = System.nanoTime();
        run(o, a, System.out);
        System.err.printf("%.1f us/tick%n", (System.nanoTime() - t0) / 1000.0 / Math.max(1, a.ticks()));
    }

    /** Run the simulation described by {@code a} on parsed options and print the report to {@code out}. */
    static long run(Options o, Args a, PrintStream out) {
        World w = new World(o, a.seed());
        Simulation sim = new Simulation(w);
        for (int i = 0; i < a.players(); i++) {
            Player p = sim.ships.join("pilot" + i, i % 2);
            if (p == null) {
                break;
            }
        }
        RandomPilot pilot = new RandomPilot(a.seed() + 1, w.players.length);
        for (int t = 1; t <= a.ticks(); t++) {
            sim.tick(pilot.next());
            if (a.every() > 0 && t % a.every() == 0) {
                out.printf("tick %d digest %016x objects %d%n", t, Digest.of(w), w.objects.count());
            }
        }
        long digest = Digest.of(w);
        out.printf("map %s players %d ticks %d seed %d%n", w.map.name(), w.numPlayers, a.ticks(), a.seed());
        for (int k = 0; k < w.numPlayers; k++) {
            Player p = w.playerAt(k);
            out.printf("  %s id %d score %.2f kills %d deaths %d%n", p.name, p.id, p.score, p.kills, p.deaths);
        }
        out.printf("objects %d events %d bursts %d%n", w.objects.count(), w.events.seq(), w.events.burstSeq());
        out.printf("digest %016x%n", digest);
        return digest;
    }
}
