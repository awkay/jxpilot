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

import com.fulcrologic.jxpilot.client.game.TalkMacros;
import com.fulcrologic.jxpilot.client.game.options.ClientOption;
import com.fulcrologic.jxpilot.client.game.options.ClientOptions;

import java.util.Arrays;
import java.util.function.Consumer;

/**
 * The play resources in the form the play keys use (C {@code default.c}): the talk macros {@code msg1..msg20},
 * {@code pointerControl}, the pointer button bindings {@code pointerButton1..5} as function masks and
 * {@code messagesToStdout}, read from the {@link ClientOptions}.
 */
public final class PlayResources {
    /** C {@code MAX_POINTER_BUTTONS}. */
    public static final int BUTTONS = 5;
    /** C {@code MAX_BUTTON_DEFS}: functions per pointer button. */
    public static final int BUTTON_DEFS = 3;

    /** {@code msg1..msg20}: the talk macros (C default ""). */
    public final String[] talkMacros = new String[TalkMacros.COUNT];
    /** {@code pointerControl} (C default No): pointer control is on from the first focus. */
    public boolean pointerControl;
    /** Per pointer button 1..5 (index 0..4): its {@link com.fulcrologic.jxpilot.common.net.ControlKey} mask. */
    public final long[] buttonGame = new long[BUTTONS];
    /** Per pointer button: its {@link ClientAction} mask (bit = ordinal). */
    public final long[] buttonActions = new long[BUTTONS];
    /** {@code messagesToStdout} (C default 0): 0 none, 1 player messages, 2 all. */
    public int messagesToStdout;

    public PlayResources() {
        Arrays.fill(talkMacros, "");
        pointerButton(0, "keyFireShot", null);
        pointerButton(1, "keyThrust", null);
        pointerButton(2, "keyShield", null);
    }

    /**
     * Take the values of {@code o}.
     * <p>
     * BEHAVIOR: each {@code msgN} keeps at most {@link TalkMacros#MSG_SIZE} - 1 chars (C's buffer); the pointer
     * buttons as {@link #pointerButton}; pointerControl and messagesToStdout as they are.
     *
     * @param problems receives the pointer button problems (null: ignored)
     */
    public void update(ClientOptions o, Consumer<String> problems) {
        for (int i = 0; i < TalkMacros.COUNT; i++) {
            String v = o.text(ClientOption.message(i + 1));
            talkMacros[i] = v.length() < TalkMacros.MSG_SIZE ? v : v.substring(0, TalkMacros.MSG_SIZE - 1);
        }
        for (int b = 0; b < BUTTONS; b++) {
            pointerButton(b, o.text(ClientOption.pointerButton(b + 1)), problems);
        }
        pointerControl = o.flag(ClientOption.POINTER_CONTROL);
        messagesToStdout = o.integer(ClientOption.MESSAGES_TO_STDOUT);
    }

    /**
     * C {@code default.c} pointer button bindings: whitespace separated key function names, each with or without
     * its "key" prefix, any case.
     * <p>
     * BEHAVIOR: replaces button {@code b}'s functions; at most {@link #BUTTON_DEFS} are kept ("Can only have 3
     * keys bound to pointer button N"); an unknown name is reported ("Unknown key ... for pointer button N", N
     * 0-based as C prints it) and skipped. A null problem sink ignores problems.
     */
    public void pointerButton(int b, String value, Consumer<String> problem) {
        buttonGame[b] = 0;
        buttonActions[b] = 0;
        int defs = 0;
        for (String tok : value.trim().isEmpty() ? new String[0] : value.trim().split("[ \t\r\n]+")) {
            String name = tok.regionMatches(true, 0, "key", 0, 3) ? tok.substring(3) : tok;
            KeyFunctions.Fn f = null;
            for (KeyFunctions.Fn k : KeyFunctions.all()) {
                if (k.resource().substring(3).equalsIgnoreCase(name)) {
                    f = k;
                    break;
                }
            }
            if (f == null) {
                if (problem != null) {
                    problem.accept("Unknown key \"" + tok + "\" for pointer button " + b);
                }
                continue;
            }
            if (defs == BUTTON_DEFS) {
                if (problem != null) {
                    problem.accept("Can only have " + BUTTON_DEFS + " keys bound to pointer button " + b);
                }
                continue;
            }
            defs++;
            if (f.key() != null) {
                buttonGame[b] |= f.key().mask();
            } else {
                buttonActions[b] |= 1L << f.action().ordinal();
            }
        }
    }
}
