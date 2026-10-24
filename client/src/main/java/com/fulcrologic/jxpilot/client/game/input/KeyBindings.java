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

import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_LAST;

/**
 * Key bindings: for every {@link KeyFunctions.Fn} the X11 keysyms bound to it, and derived from those, for
 * every GLFW key code, the {@link ControlKey} mask it sets on the wire and the {@link ClientAction}s it
 * triggers. One key may drive several functions and several keys one function, exactly as XPilot's resources
 * allow (by default {@code f} is both refuel and repair, {@code h} both change-home and autopilot,
 * {@code Control_L} both refuel and connector).
 * <p>
 * Editing ({@link #set}, {@link #bind}, {@link #unbind}) happens at startup and may allocate; the per-key
 * lookups ({@link #gameMask}, {@link #actionMask}) are allocation free.
 */
public final class KeyBindings {
    /** Table size: GLFW key codes 0..GLFW_KEY_LAST. */
    public static final int SIZE = GLFW_KEY_LAST + 1;

    private final long[] game = new long[SIZE];
    private final long[] actions = new long[SIZE];
    private final List<List<String>> keysyms = new ArrayList<>();

    /** No bindings at all. */
    public KeyBindings() {
        for (int i = 0; i < KeyFunctions.count(); i++) {
            keysyms.add(new ArrayList<>());
        }
    }

    /**
     * The defaults of {@code src/client/default.c} for every function (game keys and client actions), with
     * the JXPilot changes of {@link ClientAction} (F1 is the key help, keySendMsg1 unbound).
     */
    public static KeyBindings defaults() {
        KeyBindings b = new KeyBindings();
        for (KeyFunctions.Fn f : KeyFunctions.all()) {
            b.add(f, f.defaultKeys());
        }
        return b;
    }

    /**
     * Add keysyms to a function (duplicates are kept once).
     * <p>
     * BEHAVIOR: keysyms without a GLFW key are remembered (shown in listings) but bind nothing; returns how
     * many keysyms map to a GLFW key.
     */
    public int add(KeyFunctions.Fn f, String keysymList) {
        List<String> l = keysyms.get(f.index());
        int n = 0;
        for (String s : split(keysymList)) {
            if (!l.contains(s)) {
                l.add(s);
            }
            if (KeySyms.glfwKey(s) != KeySyms.NONE) {
                n++;
            }
        }
        rebuild();
        return n;
    }

    /**
     * Replace the keysyms of a function (C per-resource override).
     * <p>
     * BEHAVIOR: the function loses every earlier keysym; an empty list leaves it unbound; returns as
     * {@link #add}.
     */
    public int set(KeyFunctions.Fn f, String keysymList) {
        keysyms.get(f.index()).clear();
        return add(f, keysymList);
    }

    /** Add bindings of a game key (see {@link #add}). */
    public int bind(ControlKey key, String keysymList) {
        return add(KeyFunctions.of(key), keysymList);
    }

    /** Add bindings of a client action (see {@link #add}). */
    public int bind(ClientAction action, String keysymList) {
        return add(KeyFunctions.of(action), keysymList);
    }

    /** BEHAVIOR: removes every keysym that maps to the GLFW key from every function. */
    public void unbind(int glfwKey) {
        for (List<String> l : keysyms) {
            l.removeIf(s -> KeySyms.glfwKey(s) == glfwKey);
        }
        rebuild();
    }

    /** The keysyms of a function, in binding order (a copy). */
    public List<String> keysyms(KeyFunctions.Fn f) {
        return List.copyOf(keysyms.get(f.index()));
    }

    /**
     * BEHAVIOR: the functions a GLFW key triggers, in {@link KeyFunctions#all()} order; empty for unbound or
     * out-of-range codes.
     */
    public List<KeyFunctions.Fn> functionsOf(int glfwKey) {
        List<KeyFunctions.Fn> out = new ArrayList<>();
        if (glfwKey < 0 || glfwKey >= SIZE) {
            return out;
        }
        for (KeyFunctions.Fn f : KeyFunctions.all()) {
            for (String s : keysyms.get(f.index())) {
                if (KeySyms.glfwKey(s) == glfwKey) {
                    out.add(f);
                    break;
                }
            }
        }
        return out;
    }

    private void rebuild() {
        java.util.Arrays.fill(game, 0);
        java.util.Arrays.fill(actions, 0);
        for (KeyFunctions.Fn f : KeyFunctions.all()) {
            for (String s : keysyms.get(f.index())) {
                int g = KeySyms.glfwKey(s);
                if (g < 0 || g >= SIZE) {
                    continue;
                }
                if (f.key() != null) {
                    game[g] |= f.key().mask();
                } else {
                    actions[g] |= 1L << f.action().ordinal();
                }
            }
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
