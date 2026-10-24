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
 * C pointer control ({@code pointerControl}, {@code keyPointerControl}; {@code xevent.c}
 * {@code Pointer_control_set_state}, {@code xevent_pointer}, {@code xeventhandlers.c}
 * {@code MotionNotify_event}): while on, the window grabs and hides the pointer, horizontal mouse movement
 * accumulates in window pixels and is sent once per frame (C {@code Send_pointer_move}; the server turns the
 * ship by it), and the pointer is put back in the window centre when it strays more than 3/8 of the width or
 * 1/8 of the height from it.
 * <p>
 * The talk line takes the pointer away (C {@code Talk_set_state}) and gives it back when it closes; hiding the
 * window does the same (C {@code UnmapNotify_event}, {@code FocusIn_event}); the {@code pointerControl}
 * resource only arms it for the first focus. Pure and allocation free; the app applies {@link #grabbed()} and
 * {@link #takeWarp()} to the window.
 */
public final class PointerControl {
    private boolean on;
    /** C {@code initialPointerControl}: turn on again at the next chance. */
    private boolean pending;
    private double mouseX;
    private double mouseY;
    private int movement;
    private boolean warp;
    private int width = 1;
    private int height = 1;

    /** BEHAVIOR: the {@code pointerControl} resource arms pointer control for the first focus (C). */
    public PointerControl(boolean pointerControl) {
        pending = pointerControl;
    }

    /** True while the pointer steers (C {@code pointerControl}). */
    public boolean on() { return on; }

    /** True while the window must grab and hide the pointer. */
    public boolean grabbed() { return on; }

    /** The window size in pixels, for the centre and the stray limits. */
    public void setWindow(int w, int h) {
        width = Math.max(1, w);
        height = Math.max(1, h);
    }

    /**
     * C {@code Pointer_control_set_state}.
     * <p>
     * BEHAVIOR: on: the pointer is put in the window centre ({@link #takeWarp()}); off: released.
     */
    private void set(boolean state) {
        on = state;
        if (state) {
            centre();
        }
    }

    private void centre() {
        mouseX = width / 2;
        mouseY = height / 2;
        warp = true;
    }

    /** BEHAVIOR: keyPointerControl flips the state (C {@code Key_press_pointer_control}). */
    public void toggle() {
        set(!on);
    }

    /** BEHAVIOR: the talk line opened: an active pointer control is suspended until it closes. */
    public void talkOpened() {
        if (on) {
            pending = true;
            set(false);
        }
    }

    /** BEHAVIOR: the talk line closed: a suspended pointer control resumes. */
    public void talkClosed() {
        if (pending) {
            pending = false;
            set(true);
        }
    }

    /** BEHAVIOR: focus came back: a suspended or armed pointer control resumes unless talking. */
    public void focusGained(boolean talking) {
        if (pending && !talking) {
            pending = false;
            set(true);
        }
    }

    /** BEHAVIOR: the window was hidden or lost focus: an active pointer control is suspended. */
    public void focusLost() {
        if (on) {
            pending = true;
            set(false);
        }
    }

    /**
     * C {@code MotionNotify_event}: the pointer moved to (x, y) window pixels.
     * <p>
     * BEHAVIOR: while on: the horizontal step adds to the movement unless talking; the position is remembered
     * either way. While off nothing happens.
     */
    public void moved(double x, double y, boolean talking) {
        if (!on) {
            return;
        }
        if (!talking) {
            movement += (int) x - (int) mouseX;
        }
        mouseX = x;
        mouseY = y;
    }

    /**
     * C {@code xevent_pointer} then {@code movement = 0}: this frame's pointer move.
     * <p>
     * BEHAVIOR: while on and not talking, returns the summed horizontal movement (0 when none); a non-zero move
     * puts the pointer back in the centre when it is more than 3/8 of the width or 1/8 of the height away.
     * BEHAVIOR: the sum restarts at 0 every frame; off or talking returns 0.
     */
    public int takeMovement(boolean talking) {
        int m = movement;
        movement = 0;
        if (!on || talking || m == 0) {
            return 0;
        }
        double dx = width / 2 - (int) mouseX;
        double dy = height / 2 - (int) mouseY;
        if (Math.abs(dx) > 3 * width / 8 || Math.abs(dy) > height / 8) {
            centre();
        }
        return m;
    }

    /** BEHAVIOR: true once after the pointer must be moved to {@link #mouseX()}, {@link #mouseY()}. */
    public boolean takeWarp() {
        boolean w = warp;
        warp = false;
        return w;
    }

    public double mouseX() { return mouseX; }

    public double mouseY() { return mouseY; }
}
