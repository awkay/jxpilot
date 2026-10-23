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
package com.fulcrologic.jxpilot.common.config;

/**
 * Where an option value came from ({@code optOrigin} in C). The {@link #bit} values
 * match the C permission flags so they can be tested against {@link Opt#flags()}.
 */
public enum OptionOrigin {
    /** Built-in default. */
    INIT(0),
    /** Map file. */
    MAP(1),
    /** Server defaults/config file. */
    DEFAULTS(2),
    /** Command line. */
    COMMAND(4),
    /** Password file. */
    PASSWORD(8);

    /** Permission bit. */
    public final int bit;

    OptionOrigin(int bit) {
        this.bit = bit;
    }
}
