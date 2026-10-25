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
package com.fulcrologic.jxpilot.client.viewer;

import com.fulcrologic.jxpilot.client.paint.Particles;
import com.fulcrologic.jxpilot.client.paint.SceneData;
import com.fulcrologic.jxpilot.client.paint.ShipPainter;
import com.fulcrologic.jxpilot.client.render.Palette;
import com.fulcrologic.jxpilot.common.Item;
import com.fulcrologic.jxpilot.common.math.ShipShape;

import java.util.SplittableRandom;

/**
 * Fake moving objects of every kind for the {@code --demo} viewer mode: ships circling with every
 * shield/cloak/phasing look, streams of shots, particle bursts with XPilot's cooling colours,
 * missiles, lasers, mines, balls on connectors, all 21 items, wreckage, asteroids, ECM bursts, paused
 * markers and beams. Deterministic for a seed; positions are pure functions of time, so
 * {@link #fill} allocates nothing. Ship 0 is "self" and the rest gather around it.
 * <p>
 * {@code loops} advances at the map's {@code framesPerSecond} (one server tick per C frame); an
 * optional round delay and ECM damage count down from the start in those frames.
 */
public final class DemoWorld {
    /** Seconds a shot flies before it is fired again. */
    static final double SHOT_LIFE = 1.5;
    /** Shots younger than this (seconds) get a fading tracer from the gun, as new bullets do with prediction on. */
    static final double TRACER_AGE = 0.15;
    /** Particle life in game frames. */
    static final int PARTICLE_LIFE = 40;

    private final double worldW;
    private final double worldH;
    private final boolean wrap;
    private final float homeX;
    private final float homeY;
    private final ShipShape shape = ShipShape.defaultShip();

    private final int ships;
    private final float[] orbitX;
    private final float[] orbitY;
    private final float[] orbitR;
    private final float[] orbitW;
    private final float[] orbitPhase;
    private final String[] names;

    private final int shots;
    private final int[] shotShip;
    private final float[] shotSpread;
    private final float[] shotSpeed;
    private final float[] shotPhase;

    private final int particles;
    private final int[] partShip;
    private final float[] partDir;
    private final float[] partSpeed;
    private final float[] partPhase;

    private final int fps;
    private int roundDelayFrames;
    private int damagedFrames;
    private double time;

    /**
     * A demo of {@code ships} ships, {@code shots} shots and {@code particles} particles gathered
     * around world point ({@code homeX}, {@code homeY}), with {@code loops} at {@code fps} frames per
     * second (the map's {@code framesPerSecond}).
     */
    public DemoWorld(int fps, double worldW, double worldH, boolean wrap, double homeX, double homeY, long seed,
                     int ships, int shots, int particles) {
        this.fps = Math.max(1, fps);
        this.worldW = worldW;
        this.worldH = worldH;
        this.wrap = wrap;
        this.homeX = (float) homeX;
        this.homeY = (float) homeY;
        SplittableRandom r = new SplittableRandom(seed);
        this.ships = ships;
        orbitX = new float[ships];
        orbitY = new float[ships];
        orbitR = new float[ships];
        orbitW = new float[ships];
        orbitPhase = new float[ships];
        names = new String[ships];
        for (int i = 0; i < ships; i++) {
            double a = r.nextDouble(2 * Math.PI);
            double d = i == 0 ? 0 : 120 + r.nextDouble(600);
            orbitX[i] = (float) (homeX + d * Math.cos(a));
            orbitY[i] = (float) (homeY + d * Math.sin(a));
            orbitR[i] = i == 0 ? 60 : 40 + (float) r.nextDouble(140);
            orbitW[i] = (float) ((r.nextBoolean() ? 1 : -1) * (0.3 + r.nextDouble(0.9)));
            orbitPhase[i] = (float) r.nextDouble(2 * Math.PI);
            names[i] = i == 0 ? "self" : "pilot" + (i < 10 ? "0" : "") + i;
        }
        this.shots = shots;
        shotShip = new int[shots];
        shotSpread = new float[shots];
        shotSpeed = new float[shots];
        shotPhase = new float[shots];
        for (int i = 0; i < shots; i++) {
            shotShip[i] = r.nextInt(ships);
            shotSpread[i] = (float) (r.nextDouble(0.5) - 0.25);
            shotSpeed[i] = 250 + (float) r.nextDouble(200);
            shotPhase[i] = (float) r.nextDouble(SHOT_LIFE);
        }
        this.particles = particles;
        partShip = new int[particles];
        partDir = new float[particles];
        partSpeed = new float[particles];
        partPhase = new float[particles];
        for (int i = 0; i < particles; i++) {
            partShip[i] = r.nextInt(ships);
            partDir[i] = (float) r.nextDouble(2 * Math.PI);
            partSpeed[i] = 10 + (float) r.nextDouble(120);
            partPhase[i] = (float) r.nextDouble(PARTICLE_LIFE);
        }
    }

    /** Advance by {@code dt} seconds. */
    public void update(double dt) {
        time += dt;
    }

    /** Elapsed seconds. */
    public double time() {
        return time;
    }

    /** Game frame counter at the current time. */
    public long loops() {
        return (long) (time * fps);
    }

    /** Frames per second of {@link #loops}. */
    public int fps() {
        return fps;
    }

    /**
     * Start with a round delay of {@code roundDelay} frames and {@code damaged} frames of ECM damage on
     * the own ship, both counting down with {@link #loops} (0 = none).
     */
    public void setCountdowns(int roundDelay, int damaged) {
        this.roundDelayFrames = roundDelay;
        this.damagedFrames = damaged;
    }

    /** Ship i's x at the current time. */
    public float shipX(int i) {
        return wrapX(orbitX[i] + orbitR[i] * (float) Math.cos(angle(i)));
    }

    /** Ship i's y at the current time. */
    public float shipY(int i) {
        return wrapY(orbitY[i] + orbitR[i] * (float) Math.sin(angle(i)));
    }

    /** Ship i's heading: tangent to its circle, in its direction of travel. */
    public double shipHeading(int i) {
        return angle(i) + (orbitW[i] > 0 ? Math.PI / 2 : -Math.PI / 2);
    }

    private double angle(int i) {
        return orbitPhase[i] + orbitW[i] * time;
    }

    /** Ship look by index: plain, shield, cloaked, phasing, deflector, emergency shield, all, plain. */
    static int shipFlags(int i) {
        return switch (i % 8) {
            case 1 -> ShipPainter.SHIELD;
            case 2 -> ShipPainter.CLOAKED;
            case 3 -> ShipPainter.PHASING;
            case 4 -> ShipPainter.DEFLECTOR;
            case 5 -> ShipPainter.SHIELD | ShipPainter.EMERGENCY_SHIELD;
            case 6 -> ShipPainter.SHIELD | ShipPainter.EMERGENCY_SHIELD | ShipPainter.DEFLECTOR;
            default -> 0;
        };
    }

    /** Write the current state into {@code s} (cleared first). */
    public void fill(SceneData s) {
        s.clear();
        long loops = loops();
        s.loops = loops;
        s.fps = fps;
        s.selfId = 0;
        s.roundDelay = (int) Math.max(0, roundDelayFrames - loops);
        s.damaged = (int) Math.max(0, damagedFrames - loops);
        for (int i = 0; i < ships; i++) {
            int color = ShipPainter.shipColor(i % 3 == 1, i == 0, s.roundDelay > 0);
            s.addShip(shipX(i), shipY(i), shipHeading(i), shape, i, shipFlags(i), color, i == 5, names[i]);
        }
        fillShots(s);
        fillParticles(s);
        fillOthers(s, loops);
    }

    private void fillShots(SceneData s) {
        for (int i = 0; i < shots; i++) {
            int k = shotShip[i];
            double age = (time + shotPhase[i]) % SHOT_LIFE;
            double fired = time - age;
            double h = headingAt(k, fired) + shotSpread[i];
            float x0 = xAt(k, fired);
            float y0 = yAt(k, fired);
            float d = (float) (shotSpeed[i] * age) + 16;
            boolean team = i % 4 == 0;
            float x = wrapX(x0 + d * (float) Math.cos(h));
            float y = wrapY(y0 + d * (float) Math.sin(h));
            s.addShot(x, y, team ? Palette.BLUE : Palette.WHITE, team);
            if (age < TRACER_AGE) {
                // from the gun (16 px out), unwrapped next to the shot
                s.addTracer(x - (d - 16) * (float) Math.cos(h), y - (d - 16) * (float) Math.sin(h), x, y,
                    team ? Palette.BLUE : Palette.WHITE, (float) (1 - age / TRACER_AGE));
            }
        }
    }

    private void fillParticles(SceneData s) {
        double frames = time * fps;
        for (int i = 0; i < particles; i++) {
            int k = partShip[i];
            double age = (frames + partPhase[i]) % PARTICLE_LIFE;
            double born = (frames - age) / fps;
            float d = (float) (partSpeed[i] * age / fps);
            float x = xAt(k, born) + d * (float) Math.cos(partDir[i]);
            float y = yAt(k, born) + d * (float) Math.sin(partDir[i]);
            s.addParticle(wrapX(x), wrapY(y), Particles.colorIndex(PARTICLE_LIFE - (int) age, false));
        }
    }

    private void fillOthers(SceneData s, long loops) {
        float sx = shipX(0);
        float sy = shipY(0);
        for (int i = 0; i < Item.COUNT; i++) {
            double a = 2 * Math.PI * i / Item.COUNT;
            s.addItem(wrapX(homeX + 260 * (float) Math.cos(a)), wrapY(homeY + 260 * (float) Math.sin(a)), i);
        }
        for (int i = 0; i < 12; i++) {
            s.addMine(wrapX(homeX - 200 + 36 * i), wrapY(homeY - 120), i % 2 == 0, i % 4 == 1 && ships > 1 ? names[1 + i % (ships - 1)] : null);
        }
        for (int i = 1; i <= 4 && i < ships; i++) {
            float bx = wrapX(shipX(i) + 90 * (float) Math.cos(time * 2 + i));
            float by = wrapY(shipY(i) + 90 * (float) Math.sin(time * 2 + i));
            s.addBall(bx, by);
            s.addLink(shipX(i), shipY(i), bx, by, SceneData.LINK_BALL);
        }
        for (int i = 0; i < 16 && i < ships; i++) {
            s.addMissile(shipX(i) + 30, shipY(i) - 30, 15, (int) (loops * 3 + i * 8) & 127);
        }
        for (int i = 0; i < 6 && i < ships; i++) {
            int dir = (int) (shipHeading(i) * 128 / (2 * Math.PI)) & 127;
            s.addLaser(shipX(i), shipY(i), 120, dir, i % 2 == 0 ? Palette.RED : Palette.BLUE);
        }
        for (int i = 0; i < 9; i++) {
            s.addWreck(wrapX(homeX + 150 + 40 * (i % 3)), wrapY(homeY + 150 + 40 * (i / 3)), i % 2 == 0, i,
                (int) (loops + i * 14) & 127, 160 + 30 * (i % 4));
        }
        for (int i = 0; i < 4; i++) {
            s.addAsteroid(wrapX(homeX - 300 + 90 * i), wrapY(homeY + 220), i, (int) (loops * 2 + i * 30) & 127, 1 + i % 3);
        }
        s.addEcm(wrapX(homeX + 330), wrapY(homeY - 220), 10 + (loops % 20) * 4);
        s.addPaused(wrapX(homeX - 330), wrapY(homeY - 220), 0);
        s.addPaused(wrapX(homeX - 260), wrapY(homeY - 220), 10);
        if (ships > 1) { // a paused marker riding on ship 1, among its sparks: shows the draw order
            s.addPaused(shipX(1) + 30, shipY(1), 0);
        }
        if (ships > 7) {
            s.addLink(sx, sy, shipX(6), shipY(6), SceneData.LINK_REFUEL);
            s.addLink(shipX(2), shipY(2), shipX(7), shipY(7), SceneData.LINK_TRANSPORTER);
            s.addLink(sx, sy, shipX(3), shipY(3), SceneData.LINK_TRACTOR);
            s.addBall(wrapX(homeX + 200), wrapY(homeY - 300)); // a ball being connected to ship 4
            s.addLink(wrapX(homeX + 200), wrapY(homeY - 300), shipX(4), shipY(4), SceneData.LINK_CONNECTOR);
        }
    }

    private float xAt(int i, double t) {
        return orbitX[i] + orbitR[i] * (float) Math.cos(orbitPhase[i] + orbitW[i] * t);
    }

    private float yAt(int i, double t) {
        return orbitY[i] + orbitR[i] * (float) Math.sin(orbitPhase[i] + orbitW[i] * t);
    }

    private double headingAt(int i, double t) {
        return orbitPhase[i] + orbitW[i] * t + (orbitW[i] > 0 ? Math.PI / 2 : -Math.PI / 2);
    }

    private float wrapX(float x) {
        return wrap ? (float) floorMod(x, worldW) : x;
    }

    private float wrapY(float y) {
        return wrap ? (float) floorMod(y, worldH) : y;
    }

    private static double floorMod(double v, double m) {
        double r = v % m;
        return r < 0 ? r + m : r;
    }
}
