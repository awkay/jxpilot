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
 * Pressed keys to the 64-bit {@link com.fulcrologic.jxpilot.common.net.ControlKey} mask sent every
 * frame. Feed GLFW key events; the mask is the OR of the masks of all held keys, so two keys bound to
 * the same function (both shift keys) behave like XPilot. While the talk line is open game keys are
 * ignored (the talk window has the keyboard in C). Allocation free.
 */
public final class InputState {
    private final KeyBindings bindings;
    private final boolean[] down = new boolean[KeyBindings.SIZE];
    private long keys;
    private boolean talking;

    public InputState(KeyBindings bindings) {
        this.bindings = bindings;
    }

    public KeyBindings bindings() {
        return bindings;
    }

    /**
     * A key went down.
     * <p>
     * BEHAVIOR: while not talking, the key's game mask joins the wire mask.
     * BEHAVIOR: returns the key's {@link ClientAction} mask; while talking only the talk-cursor actions
     * are returned, and while not talking the talk-cursor actions are not (C semantics).
     * BEHAVIOR: out-of-range codes are ignored (0).
     */
    public long press(int glfwKey) {
        if (glfwKey < 0 || glfwKey >= KeyBindings.SIZE) {
            return 0;
        }
        long acts = bindings.actionMask(glfwKey);
        long cursor = cursorMask();
        if (talking) {
            return acts & cursor;
        }
        down[glfwKey] = true;
        keys |= bindings.gameMask(glfwKey);
        return acts & ~cursor;
    }

    private static long cursorMask() {
        return 1L << ClientAction.TALK_CURSOR_LEFT.ordinal() | 1L << ClientAction.TALK_CURSOR_RIGHT.ordinal()
            | 1L << ClientAction.TALK_CURSOR_UP.ordinal() | 1L << ClientAction.TALK_CURSOR_DOWN.ordinal();
    }

    /**
     * A key went up.
     * <p>
     * BEHAVIOR: the mask is recomputed from the keys still held, so a function stays on while any of
     * its keys is held.
     */
    public void release(int glfwKey) {
        if (glfwKey < 0 || glfwKey >= KeyBindings.SIZE || !down[glfwKey]) {
            return;
        }
        down[glfwKey] = false;
        recompute();
    }

    private void recompute() {
        long m = 0;
        for (int k = 0; k < KeyBindings.SIZE; k++) {
            if (down[k]) {
                m |= bindings.gameMask(k);
            }
        }
        keys = m;
    }

    /** BEHAVIOR: releases everything (focus lost). */
    public void releaseAll() {
        java.util.Arrays.fill(down, false);
        keys = 0;
    }

    /** BEHAVIOR: opening the talk line releases all game keys. */
    public void setTalking(boolean on) {
        if (on && !talking) {
            releaseAll();
        }
        talking = on;
    }

    public boolean talking() {
        return talking;
    }

    /** The current {@link com.fulcrologic.jxpilot.common.net.ControlKey} mask. */
    public long keys() {
        return keys;
    }
}
