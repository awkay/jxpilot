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

import java.util.Arrays;
import java.util.Locale;
import java.util.function.BiConsumer;

/**
 * The play resources of {@code ~/.xpilotrc} beyond keys and control settings (C {@code default.c}): the talk
 * macros {@code msg1..msg20}, {@code pointerControl}, {@code pointerButton1..5} and {@code messagesToStdout}.
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
     * Take one resource if it is a play resource.
     * <p>
     * BEHAVIOR: returns false (not taken) for other names. Names match ignoring case.
     * BEHAVIOR: {@code msgN} (1..20) keeps at most {@link TalkMacros#MSG_SIZE} - 1 chars (C's buffer).
     * BEHAVIOR: {@code pointerControl} is an X boolean (yes/no, true/false, on/off, 1/0); anything else is a
     * problem and keeps the value.
     * BEHAVIOR: {@code pointerButtonN} (1..5) as {@link #pointerButton}.
     * BEHAVIOR: {@code messagesToStdout} is an integer; anything else is a problem and keeps the value.
     *
     * @param problem receives (line, message) for every problem
     */
    public boolean apply(XpilotRc.Entry e, BiConsumer<Integer, String> problem) {
        String n = e.name().toLowerCase(Locale.ROOT);
        int msg = indexed(n, "msg", TalkMacros.COUNT);
        if (msg >= 0) {
            String v = e.value();
            talkMacros[msg] = v.length() < TalkMacros.MSG_SIZE ? v : v.substring(0, TalkMacros.MSG_SIZE - 1);
            return true;
        }
        int button = indexed(n, "pointerbutton", BUTTONS);
        if (button >= 0) {
            pointerButton(button, e.value(), m -> problem.accept(e.line(), m));
            return true;
        }
        if (n.equals("pointercontrol")) {
            Boolean b = bool(e.value());
            if (b == null) {
                problem.accept(e.line(), e.name() + ": not a boolean: '" + e.value() + "'");
            } else {
                pointerControl = b;
            }
            return true;
        }
        if (n.equals("messagestostdout")) {
            try {
                messagesToStdout = Integer.parseInt(e.value().trim());
            } catch (NumberFormatException ex) {
                problem.accept(e.line(), e.name() + ": not an integer: '" + e.value() + "'");
            }
            return true;
        }
        return false;
    }

    /** BEHAVIOR: the 0-based index of {@code prefix1..prefixMax}, -1 for any other name. */
    static int indexed(String lower, String prefix, int max) {
        if (!lower.startsWith(prefix) || lower.length() == prefix.length()) {
            return -1;
        }
        String d = lower.substring(prefix.length());
        if (d.charAt(0) == '0' || d.length() > 2) {
            return -1;
        }
        for (int i = 0; i < d.length(); i++) {
            if (d.charAt(i) < '0' || d.charAt(i) > '9') {
                return -1;
            }
        }
        int v = Integer.parseInt(d);
        return v <= max ? v - 1 : -1;
    }

    /**
     * C {@code default.c} pointer button bindings: whitespace separated key function names, each with or without
     * its "key" prefix, any case.
     * <p>
     * BEHAVIOR: replaces button {@code b}'s functions; at most {@link #BUTTON_DEFS} are kept ("Can only have 3
     * keys bound to pointer button N"); an unknown name is reported ("Unknown key ... for pointer button N", N
     * 0-based as C prints it) and skipped. A null problem sink ignores problems.
     */
    public void pointerButton(int b, String value, java.util.function.Consumer<String> problem) {
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

    /** BEHAVIOR: X resource booleans: yes/true/on/1 and no/false/off/0 (any case); null otherwise. */
    static Boolean bool(String v) {
        return switch (v.trim().toLowerCase(Locale.ROOT)) {
            case "yes", "true", "on", "1" -> Boolean.TRUE;
            case "no", "false", "off", "0" -> Boolean.FALSE;
            default -> null;
        };
    }
}
