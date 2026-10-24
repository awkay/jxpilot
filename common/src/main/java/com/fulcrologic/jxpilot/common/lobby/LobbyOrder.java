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
package com.fulcrologic.jxpilot.common.lobby;

import java.util.Comparator;
import java.util.List;

/** The order of the lobby list: active maps first, then alphabetical. */
public final class LobbyOrder {
    private LobbyOrder() {}

    /** Active before inactive; then names compared without case, then with case, then by id. */
    public static final Comparator<LobbyEntry> COMPARATOR = Comparator
        .comparing((LobbyEntry e) -> !e.active())
        .thenComparing(LobbyEntry::name, String.CASE_INSENSITIVE_ORDER)
        .thenComparing(LobbyEntry::name)
        .thenComparingInt(LobbyEntry::id);

    /**
     * BEHAVIOR: sorts in place: every active map before every inactive one; inside each group by name ignoring
     * case ("alpha" before "Beta"), equal names ignoring case by their exact characters, then by id.
     */
    public static void sort(List<LobbyEntry> entries) {
        entries.sort(COMPARATOR);
    }
}
