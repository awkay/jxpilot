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
package com.fulcrologic.jxpilot.client.game;

/**
 * Decoded view-culled state of one server tick (all parts received so far). A slot of the
 * {@link ClientWorld} snapshot ring; fixed capacity, reused, allocation free.
 */
final class SnapshotFrame {
    boolean valid;
    int tick;
    long arrivalNanos;
    /** Bit p set once part p arrived (parts 0..63). */
    long parts;
    /** The provisional own shots were reconciled with this (complete) frame. */
    boolean shotsConfirmed;
    /** Every part of this tick arrived. */
    boolean complete;
    /** Bumped whenever the frame's contents change (a part, a link): caches over it compare it. */
    long version;
    /** First tick whose thrust sparks this frame generates (later than {@link #tick} = none). */
    int sparkFrom;

    boolean hasSelf;
    int selfId;
    int selfFlags;
    float selfX;
    float selfY;
    float selfVx;
    float selfVy;
    float selfHeading;
    float selfFuel;
    float selfFuelMax;
    int selfLockId;
    int selfNumTanks;
    int selfCurrentTank;
    int selfRespawnFrames;
    int selfPower;
    int selfTurnSpeed;
    int selfTurnRes255;
    int selfLockDist;
    int selfLockDir;
    int selfCheck;
    int selfMods;

    final int shipCap;
    int ships;
    final int[] shipId;
    final int[] shipFlags;
    final float[] shipX;
    final float[] shipY;
    final float[] shipHeading;
    /** Main-gun points of the ships, then of SELF (at index {@link #ships}): bullet sources, for {@link #gunParts}. */
    final float[] gunX;
    final float[] gunY;
    /** The {@link #parts} the gun points were computed for; -1: none yet. */
    long gunParts = -1;
    final float[] shipVx;
    final float[] shipVy;

    final int shotCap;
    int shots;
    final float[] shotX;
    final float[] shotY;
    final byte[] shotVx;
    final byte[] shotVy;
    final byte[] shotVariant;
    /** Velocity (px per original frame) estimated from the shot's predecessor (shots sent without one). */
    final float[] shotEstVx;
    final float[] shotEstVy;
    /** {@link Tracker} velocity state of each shot (KNOWN from the wire or a link, GUESSED seed, UNKNOWN). */
    final byte[] shotState;
    /**
     * The SHOT record's velocity, exact to 1/16 px per frame (0 when the record has none): the dead reckoning's
     * velocity.
     */
    final float[] shotWireVx;
    final float[] shotWireVy;
    /** Ticks since the shot was first seen (through its links), 0 when new. */
    final int[] shotAge;
    /** Own bullets a provisional shot was matched to when the frame was reconciled ({@link #shotsConfirmed}). */
    final boolean[] shotOwnMatched;
    /** Index of the same shot in the next frame of the last association, or -1. */
    final int[] shotNext;
    /**
     * When the shot was first seen (local nanoseconds, the first frame's arrival; {@link Long#MIN_VALUE} when not
     * known to be new, e.g. no earlier frame to tell) and where its tracer starts (its source, see
     * {@code ClientWorld#tracerOrigin}).
     */
    final long[] shotSeenNanos;
    final float[] shotOriginX;
    final float[] shotOriginY;
    /** Point particles sent as positions only (C debris on the XPilot path), drawn tracked like shots. */
    final int pointCap;
    int points;
    final float[] pointX;
    final float[] pointY;
    final int[] pointColor;
    final float[] pointEstVx;
    final float[] pointEstVy;
    final byte[] pointState;
    final int[] pointNext;
    /** The frame and parts this frame was last associated with (see {@code ClientWorld#associate}). */
    int assocTick;
    long assocParts;
    long assocOwnParts;
    boolean assocValid;
    /** M2 objects of this frame. */
    final ObjectLists objs;

    SnapshotFrame(int shipCap, int shotCap) {
        this.shipCap = shipCap;
        this.shotCap = shotCap;
        shipId = new int[shipCap];
        shipFlags = new int[shipCap];
        shipX = new float[shipCap];
        shipY = new float[shipCap];
        shipHeading = new float[shipCap];
        gunX = new float[shipCap + 1];
        gunY = new float[shipCap + 1];
        shipVx = new float[shipCap];
        shipVy = new float[shipCap];
        shotX = new float[shotCap];
        shotY = new float[shotCap];
        shotVx = new byte[shotCap];
        shotVy = new byte[shotCap];
        shotVariant = new byte[shotCap];
        shotEstVx = new float[shotCap];
        shotWireVx = new float[shotCap];
        shotWireVy = new float[shotCap];
        shotAge = new int[shotCap];
        shotOwnMatched = new boolean[shotCap];
        shotEstVy = new float[shotCap];
        shotNext = new int[shotCap];
        shotState = new byte[shotCap];
        shotSeenNanos = new long[shotCap];
        shotOriginX = new float[shotCap];
        shotOriginY = new float[shotCap];
        pointCap = shotCap;
        pointX = new float[pointCap];
        pointY = new float[pointCap];
        pointColor = new int[pointCap];
        pointEstVx = new float[pointCap];
        pointEstVy = new float[pointCap];
        pointNext = new int[pointCap];
        pointState = new byte[pointCap];
        objs = new ObjectLists(OBJECT_CAP);
    }

    /** Objects per M2 kind and frame. */
    static final int OBJECT_CAP = 256;

    void begin(int t, long now, int sparkFromTick) {
        valid = true;
        tick = t;
        arrivalNanos = now;
        parts = 0;
        shotsConfirmed = false;
        complete = false;
        version++;
        sparkFrom = sparkFromTick;
        hasSelf = false;
        ships = 0;
        shots = 0;
        points = 0;
        assocValid = false;
        gunParts = -1;
        objs.clear();
    }
}
