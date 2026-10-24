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

import com.fulcrologic.jxpilot.common.Item;
import com.fulcrologic.jxpilot.server.TestWorlds;
import com.fulcrologic.jxpilot.server.sim.Simulation;
import com.fulcrologic.jxpilot.server.world.Events;
import com.fulcrologic.jxpilot.server.world.Has;
import com.fulcrologic.jxpilot.server.world.Objects;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.World;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Robot test fixtures: small worlds where one sim tick is one C frame, robots and humans placed at will. */
final class RobotFixture {
    private RobotFixture() {}

    /** dt = 1 (one tick per C frame), no gravity. */
    static final String FRAME = "framesPerSecond: 60\ngravity: 0\n";

    /** An open map {@code w} x {@code h} blocks with bases (chars of {@code bases}) at x = 1, 3, 5... on row y 0. */
    static String[] open(int w, int h, String bases) {
        String[] rows = new String[h];
        char[] blank = new char[w];
        Arrays.fill(blank, ' ');
        for (int r = 0; r < h - 1; r++) {
            rows[r] = new String(blank);
        }
        char[] bottom = blank.clone();
        for (int i = 0; i < bases.length(); i++) {
            bottom[1 + 2 * i] = bases.charAt(i);
        }
        rows[h - 1] = new String(bottom);
        return rows;
    }

    static Simulation sim(String extra, String... rows) {
        return TestWorlds.sim(FRAME + extra, rows);
    }

    /** Join a robot (Ships.join robot=true), shields down, at rest at (x, y), with default-robot data 50/50. */
    static Player robot(Simulation s, String name, double x, double y) {
        Player p = s.ships.join(name, 0, true);
        if (p == null) {
            throw new IllegalStateException("no base");
        }
        p.used &= ~Has.SHIELD;
        place(p, x, y);
        RobotData d = s.robots.data(p);
        d.clear();
        d.active = true;
        d.def = 0;
        d.attack = 50;
        d.defense = 50;
        d.normalSpeed = 6;
        d.attackSpeed = 17;
        d.maxSpeed = 30;
        d.fuelL1 = 100 * 256;
        d.fuelL2 = 200 * 256;
        d.fuelL3 = 500 * 256;
        d.robotCount = 500;
        d.robotMode = DefaultRobot.RM_ROBOT_IDLE;
        p.turnSpeed = 64;
        p.power = 55;
        p.turnResistance = 0.12f;
        s.robots.numRobots++;
        return p;
    }

    /** Join a human, shields down, at rest at (x, y). */
    static Player human(Simulation s, String name, double x, double y) {
        Player p = s.ships.join(name, 0);
        if (p == null) {
            throw new IllegalStateException("no base");
        }
        p.used &= ~Has.SHIELD;
        place(p, x, y);
        return p;
    }

    static void place(Player p, double x, double y) {
        p.initPixels(x, y);
        p.vx = 0;
        p.vy = 0;
        p.velocity = 0;
        p.status &= ~com.fulcrologic.jxpilot.server.world.Obj.THRUSTING;
    }

    static void velocity(Player p, double vx, double vy) {
        p.vx = (float) vx;
        p.vy = (float) vy;
        p.velocity = (float) Math.hypot(vx, vy);
    }

    /** A pool object at (x, y). */
    static int obj(World w, int type, int owner, double x, double y, double vx, double vy) {
        Objects o = w.objects;
        int h = o.alloc();
        o.type[h] = type;
        o.owner[h] = owner;
        o.initPixels(h, x, y);
        o.vx[h] = (float) vx;
        o.vy[h] = (float) vy;
        o.mass[h] = 0.1f;
        o.life[h] = 100;
        o.fuseLife[h] = 100;
        return h;
    }

    /** MESSAGE texts since {@code from}. */
    static List<String> messages(World w, long from) {
        List<String> out = new ArrayList<>();
        for (long s = from; s < w.events.seq(); s++) {
            int i = Events.index(s);
            if (w.events.kind[i] == Events.MESSAGE) {
                out.add(w.events.text[i].toString());
            }
        }
        return out;
    }

    /** Events of a kind since {@code from} as "a:b:c". */
    static List<String> events(World w, int kind, long from) {
        List<String> out = new ArrayList<>();
        for (long s = from; s < w.events.seq(); s++) {
            int i = Events.index(s);
            if (w.events.kind[i] == kind) {
                out.add(w.events.a[i] + ":" + w.events.b[i] + ":" + w.events.c[i]);
            }
        }
        return out;
    }

    /** The first {@code n} rfrac values a world RNG reseeded with {@code seed} gives. */
    static double[] rfracs(int seed, int n) {
        com.fulcrologic.jxpilot.common.math.Rng r = new com.fulcrologic.jxpilot.common.math.Rng(seed);
        double[] v = new double[n];
        for (int i = 0; i < n; i++) {
            v[i] = r.nextFloat();
        }
        return v;
    }

    /** A seed whose first rfrac is below (or at/above) a threshold. */
    static int seedWithFirst(boolean below, double threshold) {
        for (int s = 1; ; s++) {
            double v = rfracs(s, 1)[0];
            if ((v < threshold) == below) {
                return s;
            }
        }
    }

    /** An item gate with settable counts and abilities that records every action. */
    static final class SpyItems extends RobotItems {
        SpyItems() {
            super(null);
        }

        final Map<Item, Integer> counts = new EnumMap<>(Item.class);
        final List<String> calls = new ArrayList<>();
        int haveMask = ~0;
        boolean see = true;
        int pulses;
        int tanks;
        int limit = 10;
        int defensives;
        double damage = DefaultRobot.TARGET_DAMAGE;
        boolean treasureBall;
        int towed = -1;
        int ballOwner = -1;
        int asteroid = 1;
        int itemType;
        boolean random;
        double pulseX;
        double pulseY;
        int pulseDir;
        int pulseOwner = -1;
        boolean reflected;

        SpyItems with(Item i, int n) {
            counts.put(i, n);
            return this;
        }

        @Override public int count(Player pl, Item item) {
            return counts.getOrDefault(item, 0);
        }

        @Override public int have(Player pl) {
            return pl.have & haveMask;
        }

        @Override public boolean canSee(Player viewer, Player target) {
            return see;
        }

        @Override public int numPulses(Player pl) {
            return 0;
        }

        @Override public int numTanks(Player pl) {
            return tanks;
        }

        @Override public int limit(Item item) {
            return limit;
        }

        @Override public int activeDefensives(Player pl) {
            return defensives;
        }

        @Override public double targetDamage(int target) {
            return damage;
        }

        @Override public boolean treasureHasBall(int treasure) {
            return treasureBall;
        }

        @Override public int towedBall(Player pl) {
            return towed;
        }

        @Override public int ballOwner(int h) {
            return ballOwner;
        }

        @Override public int asteroidSize(int h) {
            return asteroid;
        }

        @Override public int itemType(int h) {
            return itemType;
        }

        @Override public boolean randomItem(int h) {
            return random;
        }

        @Override public int pulseCount() {
            return pulses;
        }

        @Override public double pulseX(int i) {
            return pulseX;
        }

        @Override public double pulseY(int i) {
            return pulseY;
        }

        @Override public int pulseDir(int i) {
            return pulseDir;
        }

        @Override public int pulseOwner(int i) {
            return pulseOwner;
        }

        @Override public boolean pulseReflected(int i) {
            return reflected;
        }

        @Override public void emergencyShield(Player pl) {
            calls.add("emergencyShield");
        }

        @Override public void emergencyThrust(Player pl) {
            calls.add("emergencyThrust");
        }

        @Override public void deflector(Player pl) {
            calls.add("deflector");
        }

        @Override public void placeMine(Player pl, boolean moving) {
            calls.add(moving ? "movingMine" : "mine");
        }

        @Override public void fireEcm(Player pl) {
            calls.add("ecm");
        }

        @Override public void transporter(Player pl) {
            calls.add("transporter");
        }

        @Override public void fireMissile(Player pl, int objType, int dir) {
            calls.add("missile:" + objType + ":" + dir);
        }

        @Override public void hyperjump(Player pl) {
            calls.add("hyperjump");
        }

        @Override public void detachTank(Player pl) {
            calls.add("detachTank");
        }

        @Override public void detachBall(Player pl) {
            calls.add("detachBall");
        }

        @Override public void sensorRange(Player pl) {
            calls.add("sensorRange");
        }
    }
}
