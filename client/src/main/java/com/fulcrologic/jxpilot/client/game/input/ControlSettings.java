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
package com.fulcrologic.jxpilot.client.game.input;

import com.fulcrologic.jxpilot.common.Const;
import com.fulcrologic.jxpilot.common.net.Msg;

/**
 * The ship control settings the client sends (C client {@code power}, {@code turnspeed},
 * {@code turnresistance} and their {@code _s} alternates), with the C key handlers that change them
 * ({@code Key_press_increase_power} ... and {@code Key_press_swap_settings} in {@code xevent.c}).
 * <p>
 * Defaults are C's ({@code default.c}) except turnSpeed: 40 instead of 35 (a JXPilot user decision).
 */
public final class ControlSettings {
    /** JXPilot default turn speed (C: 35). */
    public static final double DEFAULT_TURN_SPEED = 40.0;

    public double power = 45.0;
    public double turnSpeed = DEFAULT_TURN_SPEED;
    public double turnResistance = 0.12;
    public double altPower = 35.0;
    public double altTurnSpeed = 25.0;
    public double altTurnResistance = 0.12;

    /** BEHAVIOR: power * 1.10, at most MAX_PLAYER_POWER (C Key_press_increase_power). */
    public void increasePower() {
        power = Math.min(power * 1.10, Const.MAX_PLAYER_POWER);
    }

    /** BEHAVIOR: power * 0.90, at least MIN_PLAYER_POWER. */
    public void decreasePower() {
        power = Math.max(power * 0.90, Const.MIN_PLAYER_POWER);
    }

    /** BEHAVIOR: turnSpeed * 1.05, at most MAX_PLAYER_TURNSPEED (C Key_press_increase_turnspeed). */
    public void increaseTurnSpeed() {
        turnSpeed = Math.min(turnSpeed * 1.05, Const.MAX_PLAYER_TURNSPEED);
    }

    /** BEHAVIOR: turnSpeed * 0.95, at least MIN_PLAYER_TURNSPEED. */
    public void decreaseTurnSpeed() {
        turnSpeed = Math.max(turnSpeed * 0.95, Const.MIN_PLAYER_TURNSPEED);
    }

    /** BEHAVIOR: exchanges power, turn speed and turn resistance with their alternates (C Key_press_swap_settings). */
    public void swap() {
        double t = power;
        power = altPower;
        altPower = t;
        t = turnSpeed;
        turnSpeed = altTurnSpeed;
        altTurnSpeed = t;
        t = turnResistance;
        turnResistance = altTurnResistance;
        altTurnResistance = t;
    }

    /** BEHAVIOR: copies all six values into the wire message (flags untouched). */
    public Msg.Settings copyTo(Msg.Settings m) {
        m.power = (float) power;
        m.turnSpeed = (float) turnSpeed;
        m.turnResistance = (float) turnResistance;
        m.altPower = (float) altPower;
        m.altTurnSpeed = (float) altTurnSpeed;
        m.altTurnResistance = (float) altTurnResistance;
        return m;
    }
}
