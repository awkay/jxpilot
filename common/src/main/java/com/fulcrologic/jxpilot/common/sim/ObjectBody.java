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

/**
 * The state {@link ObjectMover} moves: a pool object's position (clicks), velocity (px per tick), life, status bits,
 * heading (missiles) and type. Scratch, reused; the server copies a pool slot in and out, the client keeps its
 * bullets in them.
 */
public final class ObjectBody implements ObjectState {
    public int cx;
    public int cy;
    public float vx;
    public float vy;
    public double life;
    public int status;
    public int dir;
    public int type;

    @Override
    public double life() { return life; }

    @Override
    public void life(double v) { life = v; }

    @Override
    public int status() { return status; }

    @Override
    public void status(int v) { status = v; }
}
