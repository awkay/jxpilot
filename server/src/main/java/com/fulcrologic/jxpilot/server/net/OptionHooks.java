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
package com.fulcrologic.jxpilot.server.net;

import com.fulcrologic.jxpilot.common.config.Options;
import com.fulcrologic.jxpilot.common.map.MapSource;

/** Extra option sources {@link Server#readOptions(String[], OptionHooks)} reads besides C's own. */
public interface OptionHooks {
    /** No extra sources. */
    OptionHooks NONE = new OptionHooks() { };

    /** Called right after the command line was read. */
    default void afterCommandLine(Options opts) { }

    /** Called after the defaults file and the positional config file were read. */
    default void afterConfig(Options opts) { }

    /** The map to load instead of the one {@code mapFileName} names, or null to use that. */
    default MapSource map() {
        return null;
    }
}
