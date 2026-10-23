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

    final int shipCap;
    int ships;
    final int[] shipId;
    final int[] shipFlags;
    final float[] shipX;
    final float[] shipY;
    final float[] shipHeading;
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
    /** Index of the same shot in the next frame of the last association, or -1. */
    final int[] shotNext;
    /** Point particles sent as positions only (C debris on the XPilot path), drawn tracked like shots. */
    final int pointCap;
    int points;
    final float[] pointX;
    final float[] pointY;
    final int[] pointColor;
    final float[] pointEstVx;
    final float[] pointEstVy;
    final int[] pointNext;
    /** The frame and parts this frame was last associated with (see {@code ClientWorld#associate}). */
    int assocTick;
    long assocParts;
    long assocOwnParts;
    boolean assocValid;

    SnapshotFrame(int shipCap, int shotCap) {
        this.shipCap = shipCap;
        this.shotCap = shotCap;
        shipId = new int[shipCap];
        shipFlags = new int[shipCap];
        shipX = new float[shipCap];
        shipY = new float[shipCap];
        shipHeading = new float[shipCap];
        shipVx = new float[shipCap];
        shipVy = new float[shipCap];
        shotX = new float[shotCap];
        shotY = new float[shotCap];
        shotVx = new byte[shotCap];
        shotVy = new byte[shotCap];
        shotVariant = new byte[shotCap];
        shotEstVx = new float[shotCap];
        shotEstVy = new float[shotCap];
        shotNext = new int[shotCap];
        pointCap = shotCap;
        pointX = new float[pointCap];
        pointY = new float[pointCap];
        pointColor = new int[pointCap];
        pointEstVx = new float[pointCap];
        pointEstVy = new float[pointCap];
        pointNext = new int[pointCap];
    }

    void begin(int t, long now, int sparkFromTick) {
        valid = true;
        tick = t;
        arrivalNanos = now;
        parts = 0;
        sparkFrom = sparkFromTick;
        hasSelf = false;
        ships = 0;
        shots = 0;
        points = 0;
        assocValid = false;
    }
}
