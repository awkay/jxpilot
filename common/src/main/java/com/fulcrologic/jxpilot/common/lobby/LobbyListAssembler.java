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

import com.fulcrologic.jxpilot.common.net.LobbyMsg;

import java.util.ArrayList;
import java.util.List;

/**
 * Puts the pages of a lobby list back together (client side). Pages of one version may arrive in any order and
 * more than once; a newer version replaces a half-assembled older one, and pages of an older version are ignored.
 * Versions are compared as a wrapping 32-bit counter.
 */
public final class LobbyListAssembler {
    private int building = LobbyMsg.NO_VERSION;
    private List<LobbyEntry>[] pages;
    private int have;
    private int complete = LobbyMsg.NO_VERSION;
    private List<LobbyEntry> entries = List.of();

    /**
     * Take one page.
     * <p>
     * BEHAVIOR: a page of a version newer than the one being assembled (or the first page ever) starts a new
     * assembly; a page of an older version is dropped; a duplicate page changes nothing.
     * BEHAVIOR: when the last missing page of the version being assembled arrives, the list is complete: its
     * entries are the pages' entries in page order, and {@link #version()} is that version. A list of a version
     * already complete is not replaced.
     * BEHAVIOR: a page whose page count differs from the assembly's (same version) is dropped.
     *
     * @return true when this page completed a new list
     */
    @SuppressWarnings("unchecked")
    public boolean accept(int version, int page, int pageCount, List<LobbyEntry> pageEntries) {
        if (building != LobbyMsg.NO_VERSION && version - building < 0) {
            return false;
        }
        if (pages == null || version != building) {
            if (complete != LobbyMsg.NO_VERSION && version == complete) {
                return false;
            }
            building = version;
            pages = new List[pageCount];
            have = 0;
        }
        if (pageCount != pages.length || pages[page] != null) {
            return false;
        }
        pages[page] = List.copyOf(pageEntries);
        have++;
        if (have < pages.length) {
            return false;
        }
        List<LobbyEntry> all = new ArrayList<>();
        for (List<LobbyEntry> p : pages) {
            all.addAll(p);
        }
        entries = List.copyOf(all);
        complete = version;
        pages = null;
        return true;
    }

    /** The version of the newest complete list, {@link LobbyMsg#NO_VERSION} before any. */
    public int version() {
        return complete;
    }

    /** The newest complete list (unsorted, as the server sent it); empty before any. */
    public List<LobbyEntry> entries() {
        return entries;
    }
}
