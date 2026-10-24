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
package com.fulcrologic.jxpilot.server.robot;

import com.fulcrologic.jxpilot.common.config.ConfigParser;
import com.fulcrologic.jxpilot.common.config.OptionOrigin;
import com.fulcrologic.jxpilot.common.config.Options;
import com.fulcrologic.jxpilot.server.net.DefaultsFile;
import com.fulcrologic.jxpilot.server.sim.Simulation;
import com.fulcrologic.jxpilot.server.world.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Statistical regression against the C reference server: Java robots on {@code default.xp} behave like the
 * original robots in aggregate (frame-by-frame comparison is impossible: the RNGs differ).
 * <p>
 * Both sides read XPilot's lib/defaults (gravity -0.25 on default.xp, random items): the C reference
 * (src/test/resources/robots/README.md) runs with {@code -defaults}, Java applies the bundled copy as the
 * server does. Each setup is compared twice: as is (items on both sides), and with {@code -itemProbMult 0}
 * (no items), which isolates the robot AI and the physics from the item world. Java pools {@link #SEEDS} seeds
 * x {@link #MINUTES} minutes (12 FPS, one tick per frame, seeds run in parallel); C pools 3 to 6 runs of 10 or
 * 20 minutes.
 * <p>
 * Every compared statistic ({@link RobotStats#COMPARED}) is compared as the mean of its single-run values
 * ({@link RobotStats#spread}), which must agree within {@link #Z} standard errors of the difference:
 * |meanJ - meanC| &lt;= Z * sqrt(sdJ^2/nJ + sdC^2/nC), with sd the run-to-run standard deviation and n the number
 * of runs. (Pooled values would weight stuckThrustFraction by stuck frames, so one long episode would decide
 * it, and pooled speed quantiles move in whole pixels.) C has few runs, so its sd is taken as at
 * least Java's (the same process should not vary less). Stuck runs (a robot pinned in place for 10 s or more)
 * are rare and long, which is what the spread of stuckShare and stuckThrustFraction reflects; both are bounded
 * from above and below.
 */
class RobotStatsRegressionTest {
    static final int SEEDS = 16;
    static final int MINUTES = 10;
    /** Allowed difference in standard errors. */
    static final double Z = 3.0;

    private static Properties c(String file) throws Exception {
        Properties p = new Properties();
        try (InputStream in = RobotStatsRegressionTest.class.getResourceAsStream("/robots/" + file)) {
            p.load(in);
        }
        return p;
    }

    /** One seed of Java robots on default.xp with lib/defaults (as {@code Server.readOptions} sets it up). */
    private static RobotStats run(int robots, int seed, String extra) {
        try {
            Options o = new Options();
            DefaultsFile.applyBundled(o);
            ConfigParser.parseFile(Path.of("..", "maps", "default.xp"), OptionOrigin.MAP, o);
            ConfigParser.parse("maxRobots: " + robots + "\nminRobots: " + robots + "\n" + extra, "test",
                OptionOrigin.COMMAND, o);
            World w = new World(o, seed * 7919);
            Simulation s = new Simulation(w);
            s.robots.setEnabled(true);
            RobotStats st = new RobotStats(w.map, w.params.fps);
            long[] in = new long[w.players.length];
            for (int t = 0; t < w.params.fps * 60 * MINUTES; t++) {
                s.tick(in);
                st.sample(w);
            }
            return st;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Pooled statistics of Java robots on default.xp, with the {@link RobotStats#spread} of the seeds. */
    static Properties java(int robots, String extra) {
        List<RobotStats> runs = IntStream.rangeClosed(1, SEEDS).parallel().mapToObj(seed -> run(robots, seed, extra))
            .toList();
        List<Properties> single = new ArrayList<>();
        for (RobotStats r : runs) {
            single.add(r.summary());
        }
        RobotStats pooled = runs.get(0);
        for (int i = 1; i < runs.size(); i++) {
            pooled.add(runs.get(i));
        }
        Properties p = pooled.summary();
        p.putAll(RobotStats.spread(single));
        return p;
    }

    private static double v(Properties p, String k) {
        return Double.parseDouble(p.getProperty(k));
    }

    /** BEHAVIOR: the allowed difference of {@code key}: Z * sqrt(sdJ^2/nJ + max(sdC, sdJ)^2/nC). */
    static double tolerance(Properties j, Properties c, String key) {
        double sdJ = v(j, key + ".sd");
        double sdC = Math.max(v(c, key + ".sd"), sdJ);
        return Z * Math.sqrt(sdJ * sdJ / v(j, key + ".runs") + sdC * sdC / v(c, key + ".runs"));
    }

    private static void matches(Properties j, Properties c, String key) {
        double jv = v(j, key + ".mean");
        double cv = v(c, key + ".mean");
        double tol = tolerance(j, c, key);
        assertTrue(Math.abs(jv - cv) <= tol,
            key + " (mean of runs): Java " + jv + " is within " + String.format(java.util.Locale.ROOT, "%.4f", tol) + " of C " + cv);
    }

    static void compare(Properties j, Properties c) {
        System.out.println("[RobotStatsRegressionTest] Java " + new java.util.TreeMap<>(j));
        System.out.println("[RobotStatsRegressionTest] C    " + new java.util.TreeMap<>(c));
        assertAll(RobotStats.COMPARED.stream().map(k -> () -> matches(j, c, k)));
    }

    @Test
    @DisplayName("4 robots on default.xp: deaths, speed, thrust (moving and stuck), stuck share, refuelling, shields and fuel match C")
    void fourRobots() throws Exception {
        compare(java(4, ""), c("c-default-4robots.properties"));
    }

    @Test
    @DisplayName("8 robots on default.xp: the same statistics match C")
    void eightRobots() throws Exception {
        compare(java(8, ""), c("c-default-8robots.properties"));
    }

    @Test
    @DisplayName("4 robots without items (itemProbMult 0): the same statistics match C")
    void fourRobotsNoItems() throws Exception {
        compare(java(4, "itemProbMult: 0\n"), c("c-default-4robots-noitems.properties"));
    }

    @Test
    @DisplayName("8 robots without items (itemProbMult 0): the same statistics match C")
    void eightRobotsNoItems() throws Exception {
        compare(java(8, "itemProbMult: 0\n"), c("c-default-8robots-noitems.properties"));
    }
}
