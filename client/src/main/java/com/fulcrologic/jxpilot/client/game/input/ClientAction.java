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
 * {@code KEY_TALK}, which opens the talk line instead of going to the server), with their resource names,
 * defaults and help texts from {@code src/client/default.c}. Not part of the wire mask.
 * <p>
 * JXPilot deviations: {@link #HELP} (F1, the key help overlay) is new, so {@code keySendMsg1} has no
 * default key (C: F1).
 */
public enum ClientAction {
    TALK("keyTalk", "m", "Toggle talk window."),
    ID_MODE("keyIdMode", "u", "Toggle User mode (show real names)."),
    TOGGLE_OWNED_ITEMS("keyToggleOwnedItems", "KP_8", "Toggle list of owned items on HUD."),
    TOGGLE_MESSAGES("keyToggleMessages", "KP_9", "Toggle showing of messages."),
    POINTER_CONTROL("keyPointerControl", "KP_Enter", "Toggle pointer control."),
    TOGGLE_RECORD("keyToggleRecord", "KP_5", "Toggle recording of session (see recordFile)."),
    PRINT_MSGS_STDOUT("keyPrintMessagesStdout", "Print", "Print the current messages to stdout."),
    TALK_CURSOR_LEFT("keyTalkCursorLeft", "Left", "Move Cursor to the left in the talk window."),
    TALK_CURSOR_RIGHT("keyTalkCursorRight", "Right", "Move Cursor to the right in the talk window."),
    TALK_CURSOR_UP("keyTalkCursorUp", "Up", "Browsing in the history of the talk window."),
    TALK_CURSOR_DOWN("keyTalkCursorDown", "Down", "Browsing in the history of the talk window."),
    SWAP_SCALEFACTOR("keySwapScaleFactor", "", "Swap scalefactor settings."),
    MSG_1("keySendMsg1", "", "Sends the talkmessage stored in msg1."),
    MSG_2("keySendMsg2", "F2", "Sends the talkmessage stored in msg2."),
    MSG_3("keySendMsg3", "F3", "Sends the talkmessage stored in msg3."),
    MSG_4("keySendMsg4", "F4", "Sends the talkmessage stored in msg4."),
    MSG_5("keySendMsg5", "F5", "Sends the talkmessage stored in msg5."),
    MSG_6("keySendMsg6", "F6", "Sends the talkmessage stored in msg6."),
    MSG_7("keySendMsg7", "F7", "Sends the talkmessage stored in msg7."),
    MSG_8("keySendMsg8", "F8", "Sends the talkmessage stored in msg8."),
    MSG_9("keySendMsg9", "F9", "Sends the talkmessage stored in msg9."),
    MSG_10("keySendMsg10", "F10", "Sends the talkmessage stored in msg10."),
    MSG_11("keySendMsg11", "F11", "Sends the talkmessage stored in msg11."),
    MSG_12("keySendMsg12", "F12", "Sends the talkmessage stored in msg12."),
    MSG_13("keySendMsg13", "", "Sends the talkmessage stored in msg13."),
    MSG_14("keySendMsg14", "", "Sends the talkmessage stored in msg14."),
    MSG_15("keySendMsg15", "", "Sends the talkmessage stored in msg15."),
    MSG_16("keySendMsg16", "", "Sends the talkmessage stored in msg16."),
    MSG_17("keySendMsg17", "", "Sends the talkmessage stored in msg17."),
    MSG_18("keySendMsg18", "", "Sends the talkmessage stored in msg18."),
    MSG_19("keySendMsg19", "", "Sends the talkmessage stored in msg19."),
    MSG_20("keySendMsg20", "", "Sends the talkmessage stored in msg20."),
    /**
     * JXPilot only: sound on/off. C has no such key (its sound is the {@code sound} resource); the default is a
     * key C leaves unbound.
     */
    TOGGLE_SOUND("keyToggleSound", "minus", "Toggle sound on/off (JXPilot)."),
    /** JXPilot: show or hide the key help overlay. */
    HELP("keyHelp", "F1", "Show or hide this key help (JXPilot).");

    /** The X resource name ({@code keyXxx}). */
    public final String resource;
    /** Default X11 keysym names, space separated (empty = unbound). */
    public final String defaultKeys;
    /** One-line help text (C default.c). */
    public final String help;

    ClientAction(String resource, String defaultKeys, String help) {
        this.resource = resource;
        this.defaultKeys = defaultKeys;
        this.help = help;
    }

    /** BEHAVIOR: true for every action except {@link #TOGGLE_RECORD} (recording is not in JXPilot). */
    public boolean implemented() {
        return this != TOGGLE_RECORD;
    }

    /** BEHAVIOR: true for the four talk-line cursor keys (only active while talking, as in C). */
    public boolean talkCursor() {
        return this == TALK_CURSOR_LEFT || this == TALK_CURSOR_RIGHT || this == TALK_CURSOR_UP
            || this == TALK_CURSOR_DOWN;
    }
}
