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
package com.fulcrologic.jxpilot.client.paint;

import com.fulcrologic.jxpilot.common.math.ShipShape;

/**
 * Everything dynamic {@link ScenePainter} draws in one frame, as preallocated parallel arrays with a
 * count per kind (struct of arrays). The game client fills it each frame (positions interpolated,
 * in world pixels, y up) and the painter only reads it. Capacities are fixed at construction;
 * adders return false when a kind is full. Allocation-free after construction.
 * <p>
 * Colours are palette indices. Headings: ships in radians (counter-clockwise from +x); missiles and
 * lasers in RES units, as on the wire.
 */
public final class SceneData {
    /** Link kinds. */
    public static final int LINK_REFUEL = 0;
    public static final int LINK_TRANSPORTER = 1;
    public static final int LINK_TRACTOR = 2;
    public static final int LINK_BALL = 3;

    /** Frame counter (C {@code loops}) driving blinks, dashes and marking lights. */
    public long loops;
    /** Id of the viewer's own ship, or -1. */
    public int selfId = -1;

    // ships
    public final int maxShips;
    public int shipCount;
    public final float[] shipX;
    public final float[] shipY;
    public final double[] shipHeading;
    public final ShipShape[] shipShape;
    public final int[] shipId;
    /** {@link ShipPainter} flag bits. */
    public final int[] shipFlags;
    /** Palette index ({@link ShipPainter#shipColor}). */
    public final int[] shipColor;
    public final boolean[] shipLocked;
    /** Name label, or null for none (C labels other players' ships only). */
    public final CharSequence[] shipName;

    // shots
    public final int maxShots;
    public int shotCount;
    public final float[] shotX;
    public final float[] shotY;
    public final int[] shotColor;
    /** Team (or blinking nuclear) shot: drawn as an X or + ({@link ObjectPainter#teamShot}). */
    public final boolean[] shotTeam;

    // particles
    public final int maxParticles;
    public int particleCount;
    public final float[] particleX;
    public final float[] particleY;
    /** Palette index ({@link Particles#colorIndex}). */
    public final int[] particleColor;

    // missiles
    public final int maxMissiles;
    public int missileCount;
    public final float[] missileX;
    public final float[] missileY;
    public final float[] missileLen;
    public final int[] missileDir;

    // lasers
    public final int maxLasers;
    public int laserCount;
    public final float[] laserX;
    public final float[] laserY;
    public final float[] laserLen;
    public final int[] laserDir;
    public final int[] laserColor;

    // mines
    public final int maxMines;
    public int mineCount;
    public final float[] mineX;
    public final float[] mineY;
    public final boolean[] mineTeam;
    /** Owner name, or null. */
    public final CharSequence[] mineName;

    // balls
    public final int maxBalls;
    public int ballCount;
    public final float[] ballX;
    public final float[] ballY;

    // items
    public final int maxItems;
    public int itemCount;
    public final float[] itemX;
    public final float[] itemY;
    public final int[] itemType;

    // wreckage
    public final int maxWrecks;
    public int wreckCount;
    public final float[] wreckX;
    public final float[] wreckY;
    public final boolean[] wreckDeadly;
    public final int[] wreckType;
    public final int[] wreckRot;
    public final int[] wreckSize;

    // asteroids
    public final int maxAsteroids;
    public int asteroidCount;
    public final float[] asteroidX;
    public final float[] asteroidY;
    public final int[] asteroidType;
    public final int[] asteroidRot;
    public final int[] asteroidSize;

    // ECM bursts
    public final int maxEcms;
    public int ecmCount;
    public final float[] ecmX;
    public final float[] ecmY;
    public final float[] ecmSize;

    // paused players
    public final int maxPaused;
    public int pausedCount;
    public final float[] pausedX;
    public final float[] pausedY;
    public final int[] pausedCountdown;

    // links (refuel, transporter, tractor beam, ball connector)
    public final int maxLinks;
    public int linkCount;
    public final float[] linkX1;
    public final float[] linkY1;
    public final float[] linkX2;
    public final float[] linkY2;
    public final int[] linkKind;

    /** Default capacities: 64 ships, 4096 shots, 16384 particles, 256 of most other kinds. */
    public SceneData() {
        this(64, 4096, 16384, 256);
    }

    /** Capacities: ships, shots, particles, and every other kind. */
    public SceneData(int ships, int shots, int particles, int others) {
        maxShips = ships;
        shipX = new float[ships];
        shipY = new float[ships];
        shipHeading = new double[ships];
        shipShape = new ShipShape[ships];
        shipId = new int[ships];
        shipFlags = new int[ships];
        shipColor = new int[ships];
        shipLocked = new boolean[ships];
        shipName = new CharSequence[ships];
        maxShots = shots;
        shotX = new float[shots];
        shotY = new float[shots];
        shotColor = new int[shots];
        shotTeam = new boolean[shots];
        maxParticles = particles;
        particleX = new float[particles];
        particleY = new float[particles];
        particleColor = new int[particles];
        maxMissiles = others;
        missileX = new float[others];
        missileY = new float[others];
        missileLen = new float[others];
        missileDir = new int[others];
        maxLasers = others;
        laserX = new float[others];
        laserY = new float[others];
        laserLen = new float[others];
        laserDir = new int[others];
        laserColor = new int[others];
        maxMines = others;
        mineX = new float[others];
        mineY = new float[others];
        mineTeam = new boolean[others];
        mineName = new CharSequence[others];
        maxBalls = others;
        ballX = new float[others];
        ballY = new float[others];
        maxItems = others;
        itemX = new float[others];
        itemY = new float[others];
        itemType = new int[others];
        maxWrecks = others;
        wreckX = new float[others];
        wreckY = new float[others];
        wreckDeadly = new boolean[others];
        wreckType = new int[others];
        wreckRot = new int[others];
        wreckSize = new int[others];
        maxAsteroids = others;
        asteroidX = new float[others];
        asteroidY = new float[others];
        asteroidType = new int[others];
        asteroidRot = new int[others];
        asteroidSize = new int[others];
        maxEcms = others;
        ecmX = new float[others];
        ecmY = new float[others];
        ecmSize = new float[others];
        maxPaused = others;
        pausedX = new float[others];
        pausedY = new float[others];
        pausedCountdown = new int[others];
        maxLinks = others;
        linkX1 = new float[others];
        linkY1 = new float[others];
        linkX2 = new float[others];
        linkY2 = new float[others];
        linkKind = new int[others];
    }

    /** Empty every kind (keeps {@link #loops} and {@link #selfId}). */
    public void clear() {
        shipCount = 0;
        shotCount = 0;
        particleCount = 0;
        missileCount = 0;
        laserCount = 0;
        mineCount = 0;
        ballCount = 0;
        itemCount = 0;
        wreckCount = 0;
        asteroidCount = 0;
        ecmCount = 0;
        pausedCount = 0;
        linkCount = 0;
    }

    /** Add a ship; false when full. */
    public boolean addShip(float x, float y, double heading, ShipShape shape, int id, int flags, int colorIndex,
                           boolean locked, CharSequence name) {
        if (shipCount == maxShips) {
            return false;
        }
        int i = shipCount++;
        shipX[i] = x;
        shipY[i] = y;
        shipHeading[i] = heading;
        shipShape[i] = shape;
        shipId[i] = id;
        shipFlags[i] = flags;
        shipColor[i] = colorIndex;
        shipLocked[i] = locked;
        shipName[i] = name;
        return true;
    }

    /** Add a shot; false when full. */
    public boolean addShot(float x, float y, int colorIndex, boolean team) {
        if (shotCount == maxShots) {
            return false;
        }
        int i = shotCount++;
        shotX[i] = x;
        shotY[i] = y;
        shotColor[i] = colorIndex;
        shotTeam[i] = team;
        return true;
    }

    /** Add a spark/debris particle; false when full. */
    public boolean addParticle(float x, float y, int colorIndex) {
        if (particleCount == maxParticles) {
            return false;
        }
        int i = particleCount++;
        particleX[i] = x;
        particleY[i] = y;
        particleColor[i] = colorIndex;
        return true;
    }

    /** Add a missile; false when full. */
    public boolean addMissile(float x, float y, float len, int dir) {
        if (missileCount == maxMissiles) {
            return false;
        }
        int i = missileCount++;
        missileX[i] = x;
        missileY[i] = y;
        missileLen[i] = len;
        missileDir[i] = dir;
        return true;
    }

    /** Add a laser beam; false when full. */
    public boolean addLaser(float x, float y, float len, int dir, int colorIndex) {
        if (laserCount == maxLasers) {
            return false;
        }
        int i = laserCount++;
        laserX[i] = x;
        laserY[i] = y;
        laserLen[i] = len;
        laserDir[i] = dir;
        laserColor[i] = colorIndex;
        return true;
    }

    /** Add a mine; false when full. */
    public boolean addMine(float x, float y, boolean team, CharSequence name) {
        if (mineCount == maxMines) {
            return false;
        }
        int i = mineCount++;
        mineX[i] = x;
        mineY[i] = y;
        mineTeam[i] = team;
        mineName[i] = name;
        return true;
    }

    /** Add a ball; false when full. */
    public boolean addBall(float x, float y) {
        if (ballCount == maxBalls) {
            return false;
        }
        int i = ballCount++;
        ballX[i] = x;
        ballY[i] = y;
        return true;
    }

    /** Add an item pickup; false when full. */
    public boolean addItem(float x, float y, int type) {
        if (itemCount == maxItems) {
            return false;
        }
        int i = itemCount++;
        itemX[i] = x;
        itemY[i] = y;
        itemType[i] = type;
        return true;
    }

    /** Add a wreck; false when full. */
    public boolean addWreck(float x, float y, boolean deadly, int type, int rot, int size) {
        if (wreckCount == maxWrecks) {
            return false;
        }
        int i = wreckCount++;
        wreckX[i] = x;
        wreckY[i] = y;
        wreckDeadly[i] = deadly;
        wreckType[i] = type;
        wreckRot[i] = rot;
        wreckSize[i] = size;
        return true;
    }

    /** Add an asteroid; false when full. */
    public boolean addAsteroid(float x, float y, int type, int rot, int size) {
        if (asteroidCount == maxAsteroids) {
            return false;
        }
        int i = asteroidCount++;
        asteroidX[i] = x;
        asteroidY[i] = y;
        asteroidType[i] = type;
        asteroidRot[i] = rot;
        asteroidSize[i] = size;
        return true;
    }

    /** Add an ECM burst; false when full. */
    public boolean addEcm(float x, float y, float size) {
        if (ecmCount == maxEcms) {
            return false;
        }
        int i = ecmCount++;
        ecmX[i] = x;
        ecmY[i] = y;
        ecmSize[i] = size;
        return true;
    }

    /** Add a paused-player marker; false when full. */
    public boolean addPaused(float x, float y, int countdown) {
        if (pausedCount == maxPaused) {
            return false;
        }
        int i = pausedCount++;
        pausedX[i] = x;
        pausedY[i] = y;
        pausedCountdown[i] = countdown;
        return true;
    }

    /** Add a link ({@link #LINK_REFUEL} etc.); false when full. */
    public boolean addLink(float x1, float y1, float x2, float y2, int kind) {
        if (linkCount == maxLinks) {
            return false;
        }
        int i = linkCount++;
        linkX1[i] = x1;
        linkY1[i] = y1;
        linkX2[i] = x2;
        linkY2[i] = y2;
        linkKind[i] = kind;
        return true;
    }
}
