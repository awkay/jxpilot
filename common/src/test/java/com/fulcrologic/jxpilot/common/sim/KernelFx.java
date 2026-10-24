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
import com.fulcrologic.jxpilot.common.math.Rng;
import com.fulcrologic.jxpilot.common.net.ControlKey;

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

    /**
     * A {@link KernelWorld} for tests: C's {@code rfrac()} from a seeded {@link Rng} (the server world's sequence for
     * the same seed), a few pool objects for the object branch of wormholes, and short names for the parameters
     * and the map.
     */
    static final class TestWorld extends KernelWorld {
        final ShipParams params;
        final WorldMap map;
        final Rng rng;
        final Objs objects = new Objs();

        TestWorld(Options o, int seed) {
            this(WorldMap.fromOptions(o), o, seed);
        }

        private TestWorld(WorldMap m, Options o, int seed) {
            super(m, ShipParams.of(o, m));
            this.params = shipParams();
            this.map = m;
            this.rng = new Rng(seed);
        }

        /** C {@code rfrac()}. */
        double rfrac() {
            return rng.nextFloat();
        }

        @Override
        public int objectPx(int h) {
            return objects.px[h];
        }

        @Override
        public int objectPy(int h) {
            return objects.py[h];
        }
    }

    /** Pool object positions for {@link TestWorld}. */
    static final class Objs {
        final int[] px = new int[8];
        final int[] py = new int[8];
        int n;

        int alloc() {
            return n++;
        }

        void setClicks(int h, int x, int y) {
            px[h] = x >> Click.SHIFT;
            py[h] = y >> Click.SHIFT;
        }
    }

    /**
     * The mover tests' world: one tick per C frame, no friction or coriolis, brake 0.5, wall bounce speed limits 10
     * (unshielded) and 20 (shielded), angle limits 64, fuel drain multiplier 1, edge bounce on, frame clock 7.
     */
    static TestWorld moverWorld(String extra, String... rows) {
        TestWorld w = new TestWorld(options(extra, rows), 1);
        ShipParams p = w.params;
        p.friction = 0;
        p.coriolis = 0;
        p.playerWallBrakeFactor = 0.5f;
        p.maxUnshieldedWallBounceSpeed = 10;
        p.maxShieldedWallBounceSpeed = 20;
        p.maxUnshieldedAngle = 64;
        p.maxShieldedAngle = 64;
        p.wallBounceFuelDrainMult = 1;
        p.edgeBounce = true;
        w.frames = 7;
        return w;
    }

    /**
     * A playing default ship at (cx, cy) clicks with heading {@code dir} and velocity (vx, vy): no abilities, mass and
     * empty mass 20, fuel 10000 of 1e7, the previous position its position, speed |v|, never touched a wall, the C
     * client's default engine settings (power 45, turn speed 35, resistance 0.12), 256 shots allowed.
     */
    static ShipBody ship(int cx, int cy, int dir, double vx, double vy) {
        ShipBody p = new ShipBody();
        p.status = Obj.PLAYING;
        p.used = 0;
        p.have = 0;
        p.mass = 20;
        p.emptyMass = 20;
        p.fuel = 10000;
        p.tankFuel[0] = 10000;
        p.fuelMax = 1e7;
        p.setClicks(cx, cy);
        p.remember();
        p.dir = dir;
        p.floatDir = dir;
        p.vx = (float) vx;
        p.vy = (float) vy;
        p.velocity = (float) StrictMath.hypot(vx, vy);
        p.lastWallTouch = -1;
        p.power = 45;
        p.turnSpeed = 35;
        p.turnResistance = 0.12f;
        p.shotMax = 256;
        return p;
    }

    /** Records every kernel event; the answers to the kernel's questions are settable. */
    static final class Rec implements KernelEvents {
        final StringBuilder log = new StringBuilder();
        boolean room = true;
        boolean warpStops;
        boolean pressedStops;
        int crashes;
        int crashType;
        int crashPoint = -1;
        int crashCannon = -1;
        int crashTarget = -1;
        int crashWormhole = -1;
        int bounces;
        int wallDir = -1;
        long cost = -1;
        int target = -2;
        int wallDamage;
        int shots;
        float shotX;
        float shotY;
        int shotDir;
        float shotVx;
        float shotVy;
        boolean shotOutside;
        final java.util.List<Integer> shotDirs = new java.util.ArrayList<>();
        final java.util.List<Float> shotXs = new java.util.ArrayList<>();
        final java.util.List<Float> shotYs = new java.util.ArrayList<>();
        ControlKey lastKey;
        int turnSparks = -1;

        private void log(String s) {
            log.append(s).append(' ');
        }

        String log() {
            return log.toString().trim();
        }

        @Override
        public void begin(ShipBody b, int phase) {
            log("begin" + phase);
        }

        @Override
        public void end(ShipBody b, int phase) {
            log("end" + phase);
        }

        @Override
        public boolean keyPressed(ShipBody b, ControlKey k) {
            log("pressed:" + k);
            lastKey = k;
            return pressedStops;
        }

        @Override
        public void keyReleased(ShipBody b, ControlKey k) {
            log("released:" + k);
            lastKey = k;
        }

        @Override
        public void autopilotOff(ShipBody b) {
            log("autopilotOff");
            b.used &= ~Has.AUTOPILOT;
        }

        @Override
        public void powerChanged(ShipBody b) {
            log("powerChanged");
        }

        @Override
        public boolean shotRoom() {
            log("shotRoom");
            return room;
        }

        @Override
        public int shot(ShipBody b, float x, float y, int dir, float vx, float vy, boolean outside) {
            log("shot");
            shots++;
            shotX = x;
            shotY = y;
            shotDir = dir;
            shotVx = vx;
            shotVy = vy;
            shotOutside = outside;
            shotDirs.add(dir);
            shotXs.add(x);
            shotYs.add(y);
            return outside ? -1 : shots;
        }

        @Override
        public void devices(ShipBody b) {
            log("devices");
        }

        @Override
        public void upkeep(ShipBody b) {
            log("upkeep");
        }

        @Override
        public boolean warp(ShipBody b) {
            log("warp");
            return warpStops;
        }

        @Override
        public void thrustSparks(ShipBody b) {
            log("thrustSparks");
        }

        @Override
        public void turnSparks(ShipBody b, int count) {
            log("turnSparks");
            turnSparks = count;
        }

        @Override
        public void sensors(ShipBody b) {
            log("sensors");
        }

        @Override
        public void crashed(ShipBody b, MoveState m, int point) {
            log("crashed");
            crashes++;
            crashType = m.crash;
            crashPoint = point;
            crashCannon = m.cannon;
            crashTarget = m.target;
            crashWormhole = m.wormhole;
        }

        @Override
        public void wallDamage(ShipBody b) {
            log("wallDamage");
            wallDamage++;
        }

        @Override
        public void bounced(ShipBody b, int wallDir, long cost, int target) {
            log("bounced");
            bounces++;
            this.wallDir = wallDir;
            this.cost = cost;
            this.target = target;
        }
    }
}
