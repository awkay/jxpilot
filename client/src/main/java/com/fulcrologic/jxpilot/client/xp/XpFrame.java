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
package com.fulcrologic.jxpilot.client.xp;

import com.fulcrologic.jxpilot.common.SoundEvent;

/**
 * One decoded C frame update (everything between {@code PKT_START} and {@code PKT_END}), held in
 * preallocated arrays (struct of arrays). Values are as on the wire: world pixels, RES headings,
 * C ids. Filled by {@link XpFrameDecoder}, read by {@link XpTranslator}. Objects beyond a list's
 * capacity are counted in {@link #overflow} and dropped.
 */
public final class XpFrame {
    public static final int SHIP_CAP = 128;
    public static final int OBJ_CAP = 512;
    public static final int PARTICLE_CAP = 8192;
    public static final int MAP_CAP = 512;
    /** PKT_AUDIO entries kept per frame: a C server sends each sound at most once per frame. */
    public static final int AUDIO_CAP = SoundEvent.COUNT;

    public int loops;
    public int keyAck;
    public boolean started;
    public boolean ended;
    /** Objects dropped because a list was full. */
    public int overflow;

    // PKT_SELF (+ PKT_SELF_ITEMS, PKT_MODIFIERS)
    public boolean hasSelf;
    public int selfX;
    public int selfY;
    public int selfVx;
    public int selfVy;
    public int selfDir;
    public int selfPower;
    public int selfTurnspeed;
    public int selfTurnresistance;
    public int selfLockId;
    public int selfLockDist;
    public int selfLockDir;
    public int selfCheck;
    public int selfCurrentTank;
    public int selfFuelSum;
    public int selfFuelMax;
    public int viewWidth = XpProto.DEF_VIEW_SIZE;
    public int viewHeight = XpProto.DEF_VIEW_SIZE;
    public int debrisColors;
    public int selfStat;
    public int autopilotLight;
    public final int[] items = new int[XpProto.NUM_ITEMS];
    public final StringBuilder modifiers = new StringBuilder(XpProto.MAX_CHARS);

    // HUD timers (-1 = not sent this frame)
    public int damaged;
    public int destruct;
    public int shutdownCount;
    public int shutdownDelay;
    public int thrustTime;
    public int thrustMax;
    public int shieldTime;
    public int shieldMax;
    public int phasingTime;
    public int phasingMax;
    public int roundDelay;
    public int roundDelayMax;
    public int timeLeft;
    public int eyes;
    public int loseItem;
    /** PKT_AUDIO packets in this frame (the first {@link #AUDIO_CAP} are kept). */
    public int audioCount;
    /** C sound index ({@link SoundEvent} ordinal) of each kept PKT_AUDIO. */
    public final int[] audioType = new int[AUDIO_CAP];
    /** Its volume (C 10..100). */
    public final int[] audioVolume = new int[AUDIO_CAP];

    // PKT_SHIP
    public int ships;
    public final int[] shipX = new int[SHIP_CAP];
    public final int[] shipY = new int[SHIP_CAP];
    public final int[] shipId = new int[SHIP_CAP];
    public final int[] shipDir = new int[SHIP_CAP];
    /** C bits: 1 shield, 2 cloak, 4 emergency shield, 8 phased, 16 deflector. */
    public final int[] shipFlags = new int[SHIP_CAP];

    // PKT_PAUSED
    public int paused;
    public final int[] pausedX = new int[SHIP_CAP];
    public final int[] pausedY = new int[SHIP_CAP];
    public final int[] pausedCount = new int[SHIP_CAP];

    // PKT_REFUEL
    public int refuels;
    public final int[] refuelX0 = new int[SHIP_CAP];
    public final int[] refuelY0 = new int[SHIP_CAP];
    public final int[] refuelX1 = new int[SHIP_CAP];
    public final int[] refuelY1 = new int[SHIP_CAP];

    // PKT_CONNECTOR
    public int connectors;
    public final int[] connX0 = new int[OBJ_CAP];
    public final int[] connY0 = new int[OBJ_CAP];
    public final int[] connX1 = new int[OBJ_CAP];
    public final int[] connY1 = new int[OBJ_CAP];
    public final int[] connTractor = new int[OBJ_CAP];

    // PKT_LASER
    public int lasers;
    public final int[] laserColor = new int[OBJ_CAP];
    public final int[] laserX = new int[OBJ_CAP];
    public final int[] laserY = new int[OBJ_CAP];
    public final int[] laserLen = new int[OBJ_CAP];
    public final int[] laserDir = new int[OBJ_CAP];

    // PKT_MISSILE
    public int missiles;
    public final int[] missileX = new int[OBJ_CAP];
    public final int[] missileY = new int[OBJ_CAP];
    public final int[] missileLen = new int[OBJ_CAP];
    public final int[] missileDir = new int[OBJ_CAP];

    // PKT_BALL
    public int balls;
    public final int[] ballX = new int[OBJ_CAP];
    public final int[] ballY = new int[OBJ_CAP];
    public final int[] ballId = new int[OBJ_CAP];

    // PKT_MINE
    public int mines;
    public final int[] mineX = new int[OBJ_CAP];
    public final int[] mineY = new int[OBJ_CAP];
    public final int[] mineTeam = new int[OBJ_CAP];
    public final int[] mineId = new int[OBJ_CAP];

    // PKT_ITEM
    public int itemObjs;
    public final int[] itemX = new int[OBJ_CAP];
    public final int[] itemY = new int[OBJ_CAP];
    public final int[] itemType = new int[OBJ_CAP];

    // PKT_ECM
    public int ecms;
    public final int[] ecmX = new int[OBJ_CAP];
    public final int[] ecmY = new int[OBJ_CAP];
    public final int[] ecmSize = new int[OBJ_CAP];

    // PKT_TRANS
    public int trans;
    public final int[] transX1 = new int[OBJ_CAP];
    public final int[] transY1 = new int[OBJ_CAP];
    public final int[] transX2 = new int[OBJ_CAP];
    public final int[] transY2 = new int[OBJ_CAP];

    // PKT_WRECKAGE
    public int wrecks;
    public final int[] wreckX = new int[OBJ_CAP];
    public final int[] wreckY = new int[OBJ_CAP];
    public final int[] wreckType = new int[OBJ_CAP];
    public final int[] wreckSize = new int[OBJ_CAP];
    public final int[] wreckRot = new int[OBJ_CAP];

    // PKT_ASTEROID
    public int asteroids;
    public final int[] astX = new int[OBJ_CAP];
    public final int[] astY = new int[OBJ_CAP];
    public final int[] astType = new int[OBJ_CAP];
    public final int[] astSize = new int[OBJ_CAP];
    public final int[] astRot = new int[OBJ_CAP];

    // PKT_WORMHOLE
    public int wormholes;
    public final int[] wormX = new int[OBJ_CAP];
    public final int[] wormY = new int[OBJ_CAP];

    // PKT_RADAR / PKT_FASTRADAR, converted to world pixels
    public int radar;
    public final float[] radarX = new float[OBJ_CAP];
    public final float[] radarY = new float[OBJ_CAP];
    /** C size byte: low 3 bits size, 0x80 friendly (team mate) or asteroid. */
    public final int[] radarSize = new int[OBJ_CAP];

    // PKT_FUEL / PKT_CANNON / PKT_TARGET (C object numbers)
    public int fuelUpdates;
    public final int[] fuelNum = new int[MAP_CAP];
    /** Fuel in units (0..500). */
    public final int[] fuelUnits = new int[MAP_CAP];
    public int cannonUpdates;
    public final int[] cannonNum = new int[MAP_CAP];
    public final int[] cannonDeadTime = new int[MAP_CAP];
    public int targetUpdates;
    public final int[] targetNum = new int[MAP_CAP];
    public final int[] targetDeadTime = new int[MAP_CAP];
    public final int[] targetDamage = new int[MAP_CAP];

    // PKT_DEBRIS + type, PKT_FASTSHOT: positions relative to the view area (bytes)
    public int debris;
    public final short[] debrisType = new short[PARTICLE_CAP];
    public final byte[] debrisX = new byte[PARTICLE_CAP];
    public final byte[] debrisY = new byte[PARTICLE_CAP];
    public int fastshots;
    public final short[] fastshotType = new short[PARTICLE_CAP];
    public final byte[] fastshotX = new byte[PARTICLE_CAP];
    public final byte[] fastshotY = new byte[PARTICLE_CAP];

    /** BEHAVIOR: empties every list and resets per-frame values (C {@code Handle_start}). */
    public void clear() {
        started = false;
        ended = false;
        overflow = 0;
        hasSelf = false;
        java.util.Arrays.fill(items, 0);
        modifiers.setLength(0);
        damaged = 0;
        destruct = 0;
        shutdownCount = -1;
        shutdownDelay = 0;
        thrustTime = -1;
        thrustMax = 0;
        shieldTime = -1;
        shieldMax = 0;
        phasingTime = -1;
        phasingMax = 0;
        roundDelay = -1;
        roundDelayMax = 0;
        timeLeft = -1;
        eyes = -1;
        loseItem = -1;
        audioCount = 0;
        ships = 0;
        paused = 0;
        refuels = 0;
        connectors = 0;
        lasers = 0;
        missiles = 0;
        balls = 0;
        mines = 0;
        itemObjs = 0;
        ecms = 0;
        trans = 0;
        wrecks = 0;
        asteroids = 0;
        wormholes = 0;
        radar = 0;
        fuelUpdates = 0;
        cannonUpdates = 0;
        targetUpdates = 0;
        debris = 0;
        fastshots = 0;
    }

    /** Index of own ship {@code id} in the ship list, -1 when absent. */
    public int findShip(int id) {
        for (int i = 0; i < ships; i++) {
            if (shipId[i] == id) {
                return i;
            }
        }
        return -1;
    }
}
