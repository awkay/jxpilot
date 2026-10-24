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
import java.util.List;
import java.util.Properties;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Statistical regression against the C reference server: Java robots on {@code default.xp} behave like the
 * original robots in aggregate (frame-by-frame comparison is impossible: the RNGs differ).
 * <p>
 * Both sides read XPilot's lib/defaults (gravity -0.25 on default.xp): the C reference
 * (src/test/resources/robots/README.md) runs with {@code -defaults}, Java applies the bundled copy as the
 * server does. Java pools {@link #SEEDS} seeds x {@link #MINUTES} minutes (12 FPS, one tick per frame, seeds run
 * in parallel) per robot count; the C numbers pool 4-robot runs of about 230 robot-minutes and 8-robot runs of
 * about 380. Tolerances come from the spread between single C runs of the same setup plus Poisson noise of the
 * counts: rates of common events within 40%, rare wall deaths within a factor 2, means within 20-25%.
 * <p>
 * Thrust is compared on moving frames only: stuck runs (a robot pinned in place for 10 s or more, see
 * {@link RobotStats}) are rare and long, so a single one swings a run's overall thrust share. Their share of
 * the alive frames is printed for both sides and bounded loosely.
 */
class RobotStatsRegressionTest {
    static final int SEEDS = 16;
    static final int MINUTES = 10;

    private static Properties c(String file) throws Exception {
        Properties p = new Properties();
        try (InputStream in = RobotStatsRegressionTest.class.getResourceAsStream("/robots/" + file)) {
            p.load(in);
        }
        return p;
    }

    /** One seed of Java robots on default.xp with lib/defaults (as {@code Server.readOptions} sets it up). */
    private static RobotStats run(int robots, int seed) {
        try {
            Options o = new Options();
            DefaultsFile.applyBundled(o);
            ConfigParser.parseFile(Path.of("..", "maps", "default.xp"), OptionOrigin.MAP, o);
            ConfigParser.parse("maxRobots: " + robots + "\nminRobots: " + robots + "\n", "test", OptionOrigin.COMMAND, o);
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

    /** Pooled statistics of Java robots on default.xp. */
    private static Properties java(int robots) {
        List<RobotStats> runs = IntStream.rangeClosed(1, SEEDS).parallel().mapToObj(seed -> run(robots, seed)).toList();
        RobotStats pooled = runs.get(0);
        for (int i = 1; i < runs.size(); i++) {
            pooled.add(runs.get(i));
        }
        return pooled.summary();
    }

    private static double v(Properties p, String k) {
        return Double.parseDouble(p.getProperty(k));
    }

    private static void within(Properties j, Properties c, String key, double factor) {
        double jv = v(j, key);
        double cv = v(c, key);
        assertTrue(jv <= cv * factor && jv >= cv / factor,
            key + ": Java " + jv + " is within a factor " + factor + " of C " + cv);
    }

    private static void close(Properties j, Properties c, String key, double rel) {
        double jv = v(j, key);
        double cv = v(c, key);
        assertTrue(Math.abs(jv - cv) <= rel * cv, key + ": Java " + jv + " is within " + rel * 100 + "% of C " + cv);
    }

    private static void compare(Properties j, Properties c) {
        System.out.println("[RobotStatsRegressionTest] Java " + new java.util.TreeMap<>(j));
        System.out.println("[RobotStatsRegressionTest] C    " + new java.util.TreeMap<>(c));
        assertAll(
            () -> close(j, c, "deathsPerRobotMinute", 0.40),
            () -> close(j, c, "otherDeathsPerRobotMinute", 0.40),
            () -> within(j, c, "wallDeathsPerRobotMinute", 2.0),
            () -> close(j, c, "meanSpeed", 0.20),
            () -> close(j, c, "speedP50", 0.25),
            () -> close(j, c, "speedP90", 0.25),
            () -> close(j, c, "movingThrustFraction", 0.25),
            () -> assertTrue(v(j, "stuckShare") <= Math.max(0.05, 3 * v(c, "stuckShare")),
                "stuckShare: Java " + v(j, "stuckShare") + " is at most max(5%, 3x C " + v(c, "stuckShare") + ")"),
            () -> within(j, c, "refuelFraction", 2.0),
            () -> within(j, c, "shieldOrShotFraction", 1.6),
            () -> within(j, c, "hitFraction", 1.6),
            () -> close(j, c, "meanFuel", 0.25));
    }

    @Test
    @DisplayName("4 robots on default.xp: deaths, wall deaths, speed, thrust, refuelling, shields and fuel match C")
    void fourRobots() throws Exception {
        compare(java(4), c("c-default-4robots.properties"));
    }

    @Test
    @DisplayName("8 robots on default.xp: the same statistics match C")
    void eightRobots() throws Exception {
        compare(java(8), c("c-default-8robots.properties"));
    }
}
