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
 * player leaves. Units follow C: positions in clicks/pixels, velocities in pixels per original frame,
 * headings in RES units, fuel in C fixed point ({@code FUEL_SCALE_FACT} = 256 per fuel unit, kept as a
 * double so per-tick fractions survive). Timers that C counts in frames are doubles in original frames,
 * decremented by {@code dt} each sim tick.
 */
public final class Player {
    /** C {@code MAX_RECORDED_SHOVES}. */
    public static final int MAX_SHOVES = 4;

    /** Slot index in {@link World#players}. */
    public final int slot;
    /** Wire id (stable while the player is in the game). */
    public int id;
    public boolean inUse;
    public final StringBuilder name = new StringBuilder(Const.MAX_CHARS);
    public int team = Const.TEAM_NOT_SET;
    public int pseudoTeam;
    public ShipGeom ship = ShipGeom.defaultShip();

    // ---- position (clicks authoritative, pixels/blocks derived as C objposition)
    public int cx;
    public int cy;
    public int px;
    public int py;
    public int bx;
    public int by;
    public int prevX;
    public int prevY;
    /** Sub-click remainder of the per-tick move (vel*dt is rarely a whole number of clicks). */
    public double remX;
    public double remY;

    public double vx;
    public double vy;
    public double accX;
    public double accY;
    /** C {@code velocity}: |v| after the last move. */
    public double velocity;
    public double mass;
    public double emptyMass;

    public int status;
    public int have;
    public int used;
    /** Respawn / self-destruct countdown in original frames; -1 when idle (C {@code count}). */
    public double count = -1;

    public int dir;
    public double floatDir;
    public double turnVel;
    public double oldTurnVel;
    public double turnAcc;
    public double turnSpeed;
    public double turnResistance;
    public double power;
    public double powerS;
    public double turnSpeedS;
    public double turnResistanceS;

    /** Fuel in C fixed point units (256 per displayed unit). */
    public double fuel;
    public double fuelMax;
    public double shieldTime;
    public double damaged;
    public double stunned;

    public int shots;
    public int shotMax;
    /** Frame clock value of the last shot (C {@code shot_time}). */
    public double shotTime;
    /** Fuel station being used (C {@code fs}). */
    public int fs;
    public int homeBase;
    public double score;
    public int kills;
    public int deaths;
    public int life;
    public final int[] items = new int[Item.COUNT];

    public final int[] shovePusher = new int[MAX_SHOVES];
    public final double[] shoveTime = new double[MAX_SHOVES];
    public int shoveNext;

    public int wormHoleHit = -1;
    public int wormHoleDest = -1;
    public double lastWallTouch;

    /** Control keys as last handled ({@link com.fulcrologic.jxpilot.common.net.ControlKey} mask, C {@code prev_keyv}). */
    public long prevKeys;

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

    /** C {@code Player_position_init_pixels}: set the position and make it the previous position too. */
    public void initPixels(double x, double y) {
        setClicks(Click.fromPixels(x), Click.fromPixels(y));
        remember();
        remX = 0;
        remY = 0;
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
