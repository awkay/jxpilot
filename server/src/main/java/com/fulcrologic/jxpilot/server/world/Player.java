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
package com.fulcrologic.jxpilot.server.world;

import com.fulcrologic.jxpilot.common.Const;
import com.fulcrologic.jxpilot.common.Item;

/**
 * A player slot (C {@code player}, the subset needed for S1). Preallocated by {@link World}; reused when a
 * player leaves. Units and types follow C: positions in clicks/pixels, velocities in pixels per frame,
 * headings in RES units, fuel in C fixed point ({@code FUEL_SCALE_FACT} = 256 per fuel unit). Fields that C
 * keeps as {@code DFLOAT} (float) are {@code float} here so the physics rounds exactly like C; timers count
 * frames (one sim tick is one C frame).
 */
public final class Player {
    /** C {@code MAX_RECORDED_SHOVES}. */
    public static final int MAX_SHOVES = 4;

    /** Slot index in {@link World#players}. */
    public final int slot;
    /** Wire id (stable while the player is in the game). */
    public int id;
    public boolean inUse;
    /** False between {@code Ships.reserve} and {@code Ships.spawn}: the slot is taken but not in the game. */
    public boolean spawned;
    /** A computer-controlled player (C {@code IS_ROBOT_PTR}); set at join, shown as 'R' on scoreboards. */
    public boolean robot;
    public final StringBuilder name = new StringBuilder(Const.MAX_CHARS);
    public int team = Const.TEAM_NOT_SET;
    public int pseudoTeam;
    public ShipGeom ship = ShipGeom.defaultShip();
    /**
     * C {@code mychar}: the score list marker: ' ' playing, 'P' paused, 'W' waiting for the next round
     * (limited lives), 'D' dead until the next round.
     */
    public char mychar = ' ';

    // ---- position (clicks authoritative, pixels/blocks derived as C objposition)
    public int cx;
    public int cy;
    public int px;
    public int py;
    public int bx;
    public int by;
    public int prevX;
    public int prevY;

    public float vx;
    public float vy;
    public float accX;
    public float accY;
    /** C {@code velocity}: |v| after the last move. */
    public float velocity;
    public float mass;
    public float emptyMass;

    public int status;
    public int have;
    public int used;
    /** Respawn / self-destruct / pause countdown in frames; -1 when idle (C {@code count}). */
    public int count = -1;

    public int dir;
    public float floatDir;
    public float turnVel;
    public float oldTurnVel;
    public float turnAcc;
    public float turnSpeed;
    public float turnResistance;
    public float power;
    public float powerS;
    public float turnSpeedS;
    public float turnResistanceS;

    /** Fuel in C fixed point units (256 per displayed unit, C {@code long fuel.sum}). */
    public double fuel;
    public double fuelMax;
    public int shieldTime;
    public int damaged;
    public int stunned;

    public int shots;
    public int shotMax;
    /** Frame clock value of the last shot (C {@code shot_time}). */
    public long shotTime;
    /** Fuel station being used (C {@code fs}). */
    public int fs;
    public int homeBase;
    /** Score (C keeps a float; double here, see docs/DEVIATIONS-server.md). */
    public double score;
    public int kills;
    public int deaths;
    public int life;
    public final int[] items = new int[Item.COUNT];
    /** Sounds for this player's next snapshot (C {@code pl->audio}); see {@code sim.Sounds}. */
    public final AudioQueue audio = new AudioQueue();

    public final int[] shovePusher = new int[MAX_SHOVES];
    public final long[] shoveTime = new long[MAX_SHOVES];
    public int shoveNext;

    public int wormHoleHit = -1;
    public int wormHoleDest = -1;
    public long lastWallTouch;
    /** Frame of the last key activity (C {@code frame_last_busy}): idle pause and maxPauseTime. */
    public long frameLastBusy;

    // ---- game modes (server-modes): balls, targets, race
    /** C {@code pl->ball}: handle of the ball the connector is reaching for, or -1. */
    public int ball = -1;
    /** C {@code repair_target}: the target being repaired. */
    public int repairTarget;
    /** C {@code check}: the next checkpoint to pass. */
    public int check;
    /** C {@code round}: laps started (0 before the first checkpoint). */
    public int round;
    /** C {@code time}: race frames since the first checkpoint. */
    public int time;
    /** C {@code best_lap}, {@code last_lap}, {@code last_lap_time} in frames. */
    public int bestLap;
    public int lastLap;
    public int lastLapTime;
    /** C {@code last_check_dir}: heading when the last checkpoint was passed. */
    public int lastCheckDir;
    /** Race progress changed this tick (C {@code prev_check}/{@code prev_round}: a TIMING event is due). */
    public boolean timingDirty;

    /** Control keys as last handled ({@link com.fulcrologic.jxpilot.common.net.ControlKey} mask, C {@code prev_keyv}). */
    public long prevKeys;
    /** The key mask the client sent last (whether or not it was handled). */
    public long lastReceivedKeys;
    /**
     * Set by Go_home (C clears {@code last_keyv}/{@code prev_keyv}): keys still held are ignored until the
     * client's key state changes.
     */
    public boolean keysCleared;

    /** Score, lives or status changed this tick (a SCORE event is emitted at the end of the tick). */
    public boolean scoreDirty;

    public Player(int slot) {
        this.slot = slot;
    }

    /**
     * C {@code Player_position_set_clicks}.
     * <p>
     * BEHAVIOR: pixels are the clicks shifted down (floor); blocks are pixels / BLOCK_SZ truncated toward zero (C pos.bx).
     */
    public void setClicks(int x, int y) {
        cx = x;
        cy = y;
        px = x >> Click.SHIFT;
        py = y >> Click.SHIFT;
        bx = px / Const.BLOCK_SZ;
        by = py / Const.BLOCK_SZ;
    }

    /**
     * C {@code Player_position_init_pixels}: set the position and make it the previous position too.
     * <p>
     * BEHAVIOR: the pixel arguments are C {@code DFLOAT}s: rounded to float, then truncated to clicks.
     */
    public void initPixels(double x, double y) {
        setClicks(Click.fromPixels((float) x), Click.fromPixels((float) y));
        remember();
    }

    /** C {@code Player_position_remember}. */
    public void remember() {
        prevX = px;
        prevY = py;
    }

    /** C {@code BIT(status, PLAYING|PAUSE|GAME_OVER|KILLED) == PLAYING}: alive and flying. */
    public boolean isAlive() {
        return (status & (Obj.PLAYING | Obj.PAUSE | Obj.GAME_OVER | Obj.KILLED)) == Obj.PLAYING;
    }

    /** Both shield bits used (C {@code BIT(used, HAS_SHIELD|HAS_EMERGENCY_SHIELD) == both}). */
    public boolean fullyShielded() {
        return (used & Has.BOTH_SHIELDS) == Has.BOTH_SHIELDS;
    }

    /** Afterburners in effect for thrust: {@code MAX_AFTERBURNER} with emergency thrust, else the item count. */
    public int afterburners() {
        return (used & Has.EMERGENCY_THRUST) != 0 ? 15 : items[Item.AFTERBURNER.ordinal()];
    }

    /** C {@code Add_fuel} on the single tank: the sum is clamped to {@code [0, fuelMax]}. */
    public void addFuel(double f) {
        double v = fuel + f;
        fuel = v > fuelMax ? fuelMax : (v < 0 ? 0 : v);
    }
}
