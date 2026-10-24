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
import com.fulcrologic.jxpilot.common.config.ConfigParser;
import com.fulcrologic.jxpilot.common.config.OptionOrigin;
import com.fulcrologic.jxpilot.common.config.Options;
import com.fulcrologic.jxpilot.common.map.BlockType;
import com.fulcrologic.jxpilot.common.map.FuelStation;
import com.fulcrologic.jxpilot.common.map.WorldMap;
import com.fulcrologic.jxpilot.common.net.Msg;
import com.fulcrologic.jxpilot.server.sim.Simulation;
import com.fulcrologic.jxpilot.server.world.Events;
import com.fulcrologic.jxpilot.common.sim.Has;
import com.fulcrologic.jxpilot.common.sim.Obj;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Robots playing on the shipped default.xp (12 FPS, one tick per C frame). */
class RobotScenarioTest {

    private static Simulation defaultXp(int robots, int seed) throws Exception {
        Options o = new Options();
        ConfigParser.parseFile(Path.of("..", "maps", "default.xp"), OptionOrigin.MAP, o);
        ConfigParser.parse("maxRobots: " + robots + "\nminRobots: " + robots + "\n", "test", OptionOrigin.COMMAND, o);
        World w = new World(o, seed);
        Simulation s = new Simulation(w);
        s.robots.setEnabled(robots > 0);
        return s;
    }

    /** Lower-left block of a free area of {@code bw} x {@code bh} blocks (nothing but space). */
    private static int[] openArea(WorldMap m, int bw, int bh) {
        for (int by = 2; by < m.height() - bh - 2; by++) {
            for (int bx = 2; bx < m.width() - bw - 2; bx++) {
                boolean free = true;
                for (int y = by; y < by + bh && free; y++) {
                    for (int x = bx; x < bx + bw && free; x++) {
                        free = m.block(x, y) == BlockType.SPACE;
                    }
                }
                if (free) {
                    return new int[] {bx, by};
                }
            }
        }
        throw new IllegalStateException("no open area");
    }

    private static void run(Simulation s, int frames) {
        long[] in = new long[s.world.players.length];
        for (int i = 0; i < frames; i++) {
            s.tick(in);
        }
    }

    @Test
    @DisplayName("a robot joins, takes off from its base and flies around")
    void flies() throws Exception {
        Simulation s = defaultXp(1, 3);
        World w = s.world;
        run(s, 2 * 12);
        Player p = w.playerAt(0);
        com.fulcrologic.jxpilot.common.map.Base home = w.map.bases().get(p.homeBase);
        double maxAway = 0;
        double speedSum = 0;
        int thrust = 0;
        long[] in = new long[w.players.length];
        for (int i = 0; i < 12 * 60; i++) {
            s.tick(in);
            maxAway = Math.max(maxAway, w.wrapLength(p.px - home.x(), p.py - home.y()));
            speedSum += Math.hypot(p.vx, p.vy);
            thrust += (p.status & Obj.THRUSTING) != 0 ? 1 : 0;
        }
        double away = maxAway;
        double speed = speedSum / (12 * 60);
        int th = thrust;
        assertAll(
            () -> assertTrue(p.robot, "a robot joined"),
            () -> assertTrue(away > 5 * Const.BLOCK_SZ, "flew more than 5 blocks from its base (" + away + ")"),
            () -> assertTrue(speed > 2, "average speed over 2 (" + speed + ")"),
            () -> assertTrue(th > 12 * 6, "thrusting more than 10% of the time (" + th + ")"));
    }

    @Test
    @DisplayName("robots rarely crash into walls: under 0.15 wall crashes per robot-minute (C: <= 0.08 near-wall deaths)")
    void avoidsWalls() throws Exception {
        Simulation s = defaultXp(4, 11);
        World w = s.world;
        long[] in = new long[w.players.length];
        int wallCrashes = 0;
        long robotFrames = 0;
        long cursor = w.events.seq();
        for (int i = 0; i < 12 * 600; i++) {
            s.tick(in);
            for (long q = cursor; q < w.events.seq(); q++) {
                int e = Events.index(q);
                if (w.events.kind[e] == Events.GAME_EVENT && w.events.a[e] == Msg.GameEvent.CRASH
                    && w.events.c[e] == Obj.NO_ID && (int) w.events.value[e] == Msg.GameEvent.CRASH_WALL) {
                    wallCrashes++;
                }
            }
            cursor = w.events.seq();
            for (int k = 0; k < w.numPlayers; k++) {
                if (w.playerAt(k).isAlive()) {
                    robotFrames++;
                }
            }
        }
        double perMinute = wallCrashes / (robotFrames / 12.0 / 60.0);
        long frames = robotFrames;
        assertAll(
            () -> assertTrue(frames > 12 * 60 * 30, "about 40 robot-minutes flown (" + frames / 720.0 + ")"),
            () -> assertTrue(perMinute < 0.15, "wall crashes per robot-minute " + perMinute));
    }

    @Test
    @DisplayName("a robot low on fuel next to a station refuels")
    void refuels() throws Exception {
        Simulation s = defaultXp(0, 5);
        World w = s.world;
        FuelStation f = null;
        for (FuelStation c : w.map.fuels()) {
            if (c.by() > 2 && w.map.block(c.bx(), c.by() - 1) == BlockType.SPACE
                && w.map.block(c.bx(), c.by() - 2) == BlockType.SPACE) {
                f = c;
                break;
            }
        }
        FuelStation st = f;
        Player p = s.ships.join("Robo", 0, true);
        RobotData d = s.robots.data(p);
        d.clear();
        d.active = true;
        s.robots.robot().create(d, "50 50");
        d.fuelL1 += 100 * 256;
        d.fuelL2 += 200 * 256;
        d.fuelL3 += 500 * 256;
        s.robots.numRobots = 1;
        s.robots.setEnabled(true);
        s.world.params.maxRobots = 1;
        s.world.params.minRobots = 1;
        RobotFixture.place(p, st.x(), st.y() - 2 * Const.BLOCK_SZ);
        p.used &= ~Has.SHIELD;
        p.fuel = 300 * 256;
        java.util.Arrays.fill(w.fuelLevel, Const.MAX_STATION_FUEL * 256);
        // no cannon fire: a shot nearby makes the robot evade (towards C's Obj[mine_i]) away from the station
        java.util.Arrays.fill(w.cannonDeadTime, Integer.MAX_VALUE);
        double before = p.fuel;
        boolean refuelSeen = false;
        long[] in = new long[w.players.length];
        for (int i = 0; i < 24; i++) {
            s.tick(in);
            refuelSeen |= (p.used & Has.REFUEL) != 0;
        }
        boolean seen = refuelSeen;
        double gained = p.fuel - before;
        assertAll(
            () -> assertTrue(seen, "used REFUEL"),
            () -> assertTrue(gained > 10 * 1280 - 24 * 64, "gained at least 10 frames of refuelling (" + gained / 256 + ")"));
    }

    @Test
    @DisplayName("a robot hunts down and shoots a stationary player in the open")
    void huntsAndShoots() throws Exception {
        Simulation s = defaultXp(0, 7);
        World w = s.world;
        int[] a = openArea(w.map, 16, 8);
        Player h = s.ships.join("Target", 0);
        Player p = s.ships.join("Robo", 0, true);
        RobotData d = s.robots.data(p);
        d.clear();
        d.active = true;
        s.robots.robot().create(d, "94 20");
        d.fuelL1 += 100 * 256;
        d.fuelL2 += 200 * 256;
        d.fuelL3 += 500 * 256;
        s.robots.numRobots = 1;
        s.robots.setEnabled(true);
        s.world.params.maxRobots = 2;
        s.world.params.minRobots = 1;
        double y = (a[1] + 4) * Const.BLOCK_SZ;
        RobotFixture.place(h, (a[0] + 12) * Const.BLOCK_SZ, y);
        RobotFixture.place(p, (a[0] + 3) * Const.BLOCK_SZ, y);
        h.used &= ~Has.SHIELD;
        h.have &= ~Has.SHIELD;
        p.used &= ~Has.SHIELD;
        p.dir = 0;
        long[] in = new long[w.players.length];
        long cursor = w.events.seq();
        int shots = 0;
        int kills = 0;
        int firstKill = -1;
        for (int i = 0; i < 12 * 60; i++) {
            int before = p.shots;
            s.tick(in);
            shots += Math.max(0, p.shots - before);
            for (long q = cursor; q < w.events.seq(); q++) {
                int e = Events.index(q);
                if (w.events.kind[e] == Events.GAME_EVENT && w.events.a[e] == Msg.GameEvent.KILL
                    && w.events.b[e] == p.id && w.events.c[e] == h.id) {
                    kills++;
                    if (firstKill < 0) {
                        firstKill = i;
                    }
                }
            }
            cursor = w.events.seq();
        }
        int k = kills;
        int fk = firstKill;
        int sh = shots;
        assertAll(
            () -> assertEquals(1, s.robots.numRobots(), "one robot"),
            () -> assertTrue(sh > 5, "fired shots (" + sh + ")"),
            () -> assertTrue(k >= 1, "killed the target (" + k + " times)"),
            () -> assertTrue(fk >= 0 && fk < 12 * 30, "within 30 seconds (" + fk + " frames)"));
    }
}
