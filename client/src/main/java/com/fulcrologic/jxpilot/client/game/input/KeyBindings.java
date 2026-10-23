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

import com.fulcrologic.jxpilot.common.net.ControlKey;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_LAST;

/**
 * XPilot key bindings: for every GLFW key code, the {@link ControlKey} mask it sets on the wire and
 * the {@link ClientAction}s it triggers. One key may drive several functions and several keys one
 * function, exactly as XPilot's resources allow (by default {@code f} is both refuel and repair,
 * {@code h} both change-home and autopilot, {@code Control_L} both refuel and connector).
 * Pure table; lookups are allocation free.
 */
public final class KeyBindings {
    /** Table size: GLFW key codes 0..GLFW_KEY_LAST. */
    public static final int SIZE = GLFW_KEY_LAST + 1;

    private final long[] game = new long[SIZE];
    private final long[] actions = new long[SIZE];

    /**
     * The defaults of {@code src/client/default.c}: every {@link ControlKey#defaultKeys} except
     * {@link ControlKey#TALK} (talk is the client action {@link ClientAction#TALK}, never sent), and every
     * {@link ClientAction#defaultKeys}.
     */
    public static KeyBindings defaults() {
        KeyBindings b = new KeyBindings();
        for (ControlKey k : ControlKey.values()) {
            if (k != ControlKey.TALK) {
                b.bind(k, k.defaultKeys);
            }
        }
        for (ClientAction a : ClientAction.values()) {
            b.bind(a, a.defaultKeys);
        }
        return b;
    }

    /**
     * Add bindings of {@code key} to space-separated X11 keysyms.
     * <p>
     * BEHAVIOR: keysyms without a GLFW key are skipped; returns how many keysyms were bound.
     */
    public int bind(ControlKey key, String keysyms) {
        int n = 0;
        for (String s : split(keysyms)) {
            int g = KeySyms.glfwKey(s);
            if (g >= 0 && g < SIZE) {
                game[g] |= key.mask();
                n++;
            }
        }
        return n;
    }

    /** Add bindings of a client action (see {@link #bind(ControlKey, String)}). */
    public int bind(ClientAction action, String keysyms) {
        int n = 0;
        for (String s : split(keysyms)) {
            int g = KeySyms.glfwKey(s);
            if (g >= 0 && g < SIZE) {
                actions[g] |= 1L << action.ordinal();
                n++;
            }
        }
        return n;
    }

    /** BEHAVIOR: removes every binding of the GLFW key. */
    public void unbind(int glfwKey) {
        if (glfwKey >= 0 && glfwKey < SIZE) {
            game[glfwKey] = 0;
            actions[glfwKey] = 0;
        }
    }

    private static String[] split(String keysyms) {
        String t = keysyms.trim();
        return t.isEmpty() ? new String[0] : t.split("\\s+");
    }

    /** BEHAVIOR: {@link ControlKey} mask of a GLFW key; 0 for unbound or out-of-range codes. */
    public long gameMask(int glfwKey) {
        return glfwKey >= 0 && glfwKey < SIZE ? game[glfwKey] : 0;
    }

    /** BEHAVIOR: {@link ClientAction} bit mask (bit = ordinal) of a GLFW key; 0 when none. */
    public long actionMask(int glfwKey) {
        return glfwKey >= 0 && glfwKey < SIZE ? actions[glfwKey] : 0;
    }

    /** True if the key triggers the action. */
    public static boolean has(long actionMask, ClientAction a) {
        return (actionMask & (1L << a.ordinal())) != 0;
    }
}
