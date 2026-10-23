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

/**
 * Client-only key actions of XPilot ({@code keys_t} values after the 64 game keys, plus
 * {@code KEY_TALK}, which opens the talk line instead of going to the server), with their defaults
 * from {@code src/client/default.c}. Not part of the wire mask.
 */
public enum ClientAction {
    TALK("m"),
    ID_MODE("u"),
    TOGGLE_OWNED_ITEMS("KP_8"),
    TOGGLE_MESSAGES("KP_9"),
    POINTER_CONTROL("KP_Enter"),
    TOGGLE_RECORD("KP_5"),
    PRINT_MSGS_STDOUT("Print"),
    TALK_CURSOR_LEFT("Left"),
    TALK_CURSOR_RIGHT("Right"),
    TALK_CURSOR_UP("Up"),
    TALK_CURSOR_DOWN("Down"),
    SWAP_SCALEFACTOR(""),
    MSG_1("F1"),
    MSG_2("F2"),
    MSG_3("F3"),
    MSG_4("F4"),
    MSG_5("F5"),
    MSG_6("F6"),
    MSG_7("F7"),
    MSG_8("F8"),
    MSG_9("F9"),
    MSG_10("F10"),
    MSG_11("F11"),
    MSG_12("F12"),
    MSG_13(""),
    MSG_14(""),
    MSG_15(""),
    MSG_16(""),
    MSG_17(""),
    MSG_18(""),
    MSG_19(""),
    MSG_20("");

    /** Default X11 keysym names, space separated (empty = unbound). */
    public final String defaultKeys;

    ClientAction(String defaultKeys) {
        this.defaultKeys = defaultKeys;
    }

    /** BEHAVIOR: true for the four talk-line cursor keys (only active while talking, as in C). */
    public boolean talkCursor() {
        return this == TALK_CURSOR_LEFT || this == TALK_CURSOR_RIGHT || this == TALK_CURSOR_UP
            || this == TALK_CURSOR_DOWN;
    }
}
