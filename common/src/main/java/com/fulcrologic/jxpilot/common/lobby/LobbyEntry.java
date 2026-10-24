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

/**
 * One map a lobby server offers, as the client lists it.
 *
 * @param id         the map's number in the server's catalog, stable while the server runs (what SELECT names)
 * @param name       the name to show and to sort by (file name without extension)
 * @param title      the map's own title ({@code mapName})
 * @param author     the map's author
 * @param width      map width in blocks
 * @param height     map height in blocks
 * @param maxPlayers how many players fit (the map's bases)
 * @param active     whether a game of this map is running
 * @param humans     human players in that game (0 when not active)
 */
public record LobbyEntry(int id, String name, String title, String author, int width, int height, int maxPlayers,
                         boolean active, int humans) {
    /** BEHAVIOR: the same map with a new running state. */
    public LobbyEntry withState(boolean active, int humans) {
        return new LobbyEntry(id, name, title, author, width, height, maxPlayers, active, humans);
    }
}
