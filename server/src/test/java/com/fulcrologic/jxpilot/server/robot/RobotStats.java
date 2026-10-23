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

import com.fulcrologic.jxpilot.common.Const;
import com.fulcrologic.jxpilot.common.map.BlockType;
import com.fulcrologic.jxpilot.common.map.WorldMap;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.World;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * Robot behaviour statistics from per-frame player samples, computed the same way for the C reference
 * server's {@code XPTRACE} lines (tools/xpilot-ref, patch 0002) and for the Java simulation, so the two can
 * be compared statistically (their RNG sequences differ, so frame-by-frame comparison is impossible).
 * <p>
 * A sample is the state of one robot after a frame. A robot is alive when its PLAYING bit is set; a death is
 * an alive sample followed by a not-alive one in the next frame. Deaths are classed as wall deaths when a
 * solid map block (or the world edge without wrap) is within {@link #WALL_RADIUS} pixels of the death
 * position, otherwise as other deaths (shots, cannons, collisions). Refuelling frames are alive frames whose
 * fuel rose by at least {@link #REFUEL_STEP} (C REFUEL_RATE is 1280 per frame).
 */
final class RobotStats {
    /** Death position within this many pixels of a solid block counts as a wall death. */
    static final double WALL_RADIUS = 22;
    /** Fuel rise per frame (fixed point) that counts as refuelling. */
    static final double REFUEL_STEP = 600;
    static final int PLAYING = 1;
    static final int THRUSTING = 1 << 3;

    private final WorldMap map;
    private final int fps;
    private final Map<Integer, Track> tracks = new HashMap<>();

    long aliveFrames;
    long deaths;
    long wallDeaths;
    long refuelFrames;
    long thrustFrames;
    long joins;
    /** Alive frames that burnt 50..399 fuel (shields up or a shot fired). */
    long shieldOrShotFrames;
    /** Alive frames that lost 400 or more fuel (shielded hits, crashes against ships). */
    long hitFrames;
    double speedSum;
    double fuelSum;
    final long[] speedHist = new long[200];

    private static final class Track {
        String name;
        boolean alive;
        double fuel;
        long tick;
    }

    RobotStats(WorldMap map, int fps) {
        this.map = map;
        this.fps = fps;
    }

    /** One robot after one frame. */
    void sample(long tick, int id, String name, int x, int y, double vx, double vy, double fuel, int status) {
        Track t = tracks.get(id);
        if (t == null || !t.name.equals(name)) {
            t = new Track();
            t.name = name;
            t.tick = tick - 1;
            tracks.put(id, t);
            joins++;
        }
        boolean alive = (status & PLAYING) != 0;
        boolean consecutive = tick == t.tick + 1;
        if (alive) {
            aliveFrames++;
            double v = Math.hypot(vx, vy);
            speedSum += v;
            speedHist[(int) Math.min(speedHist.length - 1, v)]++;
            fuelSum += fuel;
            if ((status & THRUSTING) != 0) {
                thrustFrames++;
            }
            if (consecutive && t.alive) {
                double df = fuel - t.fuel;
                if (df >= REFUEL_STEP) {
                    refuelFrames++;
                } else if (df <= -400) {
                    hitFrames++;
                } else if (df <= -50) {
                    shieldOrShotFrames++;
                }
            }
        } else if (consecutive && t.alive) {
            deaths++;
            if (nearWall(x, y)) {
                wallDeaths++;
            }
        }
        t.alive = alive;
        t.fuel = fuel;
        t.tick = tick;
    }

    /** Sample every robot of the Java world (after a tick). */
    void sample(World w) {
        for (int k = 0; k < w.numPlayers; k++) {
            Player p = w.playerAt(k);
            if (p.robot) {
                sample(w.tick, p.id, p.name.toString(), p.px, p.py, p.vx, p.vy, p.fuel, p.status);
            }
        }
    }

    /** A solid block (or the edge of a non-wrapping world) within WALL_RADIUS of (x, y). */
    boolean nearWall(int x, int y) {
        int r = (int) Math.ceil(WALL_RADIUS / Const.BLOCK_SZ) + 1;
        int bx0 = Math.floorDiv(x, Const.BLOCK_SZ);
        int by0 = Math.floorDiv(y, Const.BLOCK_SZ);
        for (int by = by0 - r; by <= by0 + r; by++) {
            for (int bx = bx0 - r; bx <= bx0 + r; bx++) {
                int cx = bx;
                int cy = by;
                if (map.edgeWrap()) {
                    cx = Math.floorMod(bx, map.width());
                    cy = Math.floorMod(by, map.height());
                }
                boolean solid = cx < 0 || cy < 0 || cx >= map.width() || cy >= map.height()
                    || BlockType.isSolid(map.block(cx, cy));
                if (!solid) {
                    continue;
                }
                double nx = Math.max(bx * Const.BLOCK_SZ, Math.min(x, bx * Const.BLOCK_SZ + Const.BLOCK_SZ));
                double ny = Math.max(by * Const.BLOCK_SZ, Math.min(y, by * Const.BLOCK_SZ + Const.BLOCK_SZ));
                if (Math.hypot(x - nx, y - ny) <= WALL_RADIUS) {
                    return true;
                }
            }
        }
        return false;
    }

    double robotMinutes() {
        return aliveFrames / (double) fps / 60.0;
    }

    /** Speed below which {@code q} of the alive frames lie (1-pixel buckets). */
    double speedQuantile(double q) {
        long target = (long) Math.ceil(q * aliveFrames);
        long sum = 0;
        for (int i = 0; i < speedHist.length; i++) {
            sum += speedHist[i];
            if (sum >= target) {
                return i + 1;
            }
        }
        return speedHist.length;
    }

    /** The comparable summary. */
    Properties summary() {
        Properties p = new Properties();
        double min = robotMinutes();
        p.setProperty("robotMinutes", fmt(min));
        p.setProperty("joins", Long.toString(joins));
        p.setProperty("deaths", Long.toString(deaths));
        p.setProperty("wallDeaths", Long.toString(wallDeaths));
        p.setProperty("deathsPerRobotMinute", fmt(deaths / min));
        p.setProperty("wallDeathsPerRobotMinute", fmt(wallDeaths / min));
        p.setProperty("otherDeathsPerRobotMinute", fmt((deaths - wallDeaths) / min));
        p.setProperty("refuelFraction", fmt(refuelFrames / (double) aliveFrames));
        p.setProperty("shieldOrShotFraction", fmt(shieldOrShotFrames / (double) aliveFrames));
        p.setProperty("hitFraction", fmt(hitFrames / (double) aliveFrames));
        p.setProperty("thrustFraction", fmt(thrustFrames / (double) aliveFrames));
        p.setProperty("meanSpeed", fmt(speedSum / aliveFrames));
        p.setProperty("speedP50", fmt(speedQuantile(0.5)));
        p.setProperty("speedP90", fmt(speedQuantile(0.9)));
        p.setProperty("meanFuel", fmt(fuelSum / aliveFrames / 256));
        return p;
    }

    static String fmt(double v) {
        return String.format(Locale.ROOT, "%.4f", v);
    }

    /**
     * Feed an XPTRACE file ({@code XPTRACE loop frame id robot x y cx cy vx vy ax ay dir float_dir turnvel
     * turnacc power mass fuel thrusting status name}); only robot lines are used.
     */
    void readTrace(Path trace) throws IOException {
        tracks.clear();
        try (BufferedReader r = Files.newBufferedReader(trace, StandardCharsets.ISO_8859_1)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.startsWith("XPTRACE ")) {
                    continue;
                }
                String[] f = line.split(" ", 23);
                if (f.length < 23 || !f[4].equals("1")) {
                    continue;
                }
                sample(Long.parseLong(f[1]), Integer.parseInt(f[3]), f[22], Integer.parseInt(f[5]),
                    Integer.parseInt(f[6]), Double.parseDouble(f[9]), Double.parseDouble(f[10]),
                    Double.parseDouble(f[19]), Integer.decode(f[21]));
            }
        }
    }

    /** {@code RobotStats MAP.xp FPS TRACE...}: print the summary of C traces (to commit as a resource). */
    public static void main(String[] args) throws IOException {
        WorldMap m = WorldMap.load(Path.of(args[0]));
        RobotStats s = new RobotStats(m, Integer.parseInt(args[1]));
        for (int i = 2; i < args.length; i++) {
            s.readTrace(Path.of(args[i]));
        }
        s.summary().store(System.out, "C XPilot 4.5.5 robot statistics");
    }
}
