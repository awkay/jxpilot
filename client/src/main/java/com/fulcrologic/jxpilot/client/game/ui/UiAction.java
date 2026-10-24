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
package com.fulcrologic.jxpilot.client.game.ui;

/** What a click on the C client's windows asks the client to do. */
public enum UiAction {
    NONE,
    /** QUIT button (C {@code Quit_callback}). */
    QUIT,
    /** ABOUT button: open the information window. */
    ABOUT,
    /** MENU / KEYS: the key list (JXPilot's key help). */
    KEYS,
    /** MENU / CONFIG: open the CONFIG panel. */
    CONFIG,
    /** MENU / SCORE: the score list shows nicks (C {@code showRealName = false}). */
    SCORE,
    /** MENU / PLAYER: the score list shows NICK=USER@HOST (C {@code showRealName = true}). */
    PLAYER,
    /** MENU / MOTD: reopen the server's message of the day. */
    MOTD,
    /** CONFIG "Save Configuration". */
    SAVE
}
