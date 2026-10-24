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
package com.fulcrologic.jxpilot.client.game.hud;

/**
 * The C client's {@code lose_item_active}: whether the HUD item list outlines the item KEY_LOSE_ITEM drops.
 * Fed with the presses and releases of the SELECT_ITEM and LOSE_ITEM functions (C {@code xevent.c}
 * {@code Key_press_select_lose_item}, 481-497, and {@code Key_release}, 640-648) and aged by the painted HUD
 * frames that list the lose item (C {@code painthud.c}:558-566). Pure; allocation free.
 * <p>
 * C's values: 0 off; 1 one key held; 2 the other key pressed while one is held (each press toggles 1 and 2,
 * a release of 2 goes back to 1); a release from 1 (or 0) starts the fade, {@code -FPS}, counted up once per
 * painted frame, so the outline stays for FPS of C's frames, one second of game time. JXPilot paints at the
 * display rate, not once per game frame, so the fade is kept in seconds and each painted frame ages it by its
 * elapsed time ({@code dt}): one second of painted frames, as C at the game's frame rate.
 */
public final class LoseItemSelection {
    /** C's fade, {@code -FPS} frames: one second. */
    public static final double FADE_SECONDS = 1.0;

    /** C {@code lose_item_active} as 0, 1, 2, or -1 while fading. */
    private int state;
    /** Seconds of painted frames the fade still shows the outline. */
    private double fade;

    /**
     * A press of SELECT_ITEM or LOSE_ITEM (once per function, as C calls {@code Key_press} per bound function).
     * <p>
     * BEHAVIOR: 1 becomes 2; anything else (off, 2, fading) becomes 1.
     */
    public void press() {
        state = state == 1 ? 2 : 1;
    }

    /**
     * A release of SELECT_ITEM or LOSE_ITEM.
     * <p>
     * BEHAVIOR: 2 becomes 1; anything else starts the fade ({@link #FADE_SECONDS}), even from off or fading
     * (C sets {@code -FPS} again).
     */
    public void release() {
        if (state == 2) {
            state = 1;
        } else {
            state = -1;
            fade = FADE_SECONDS;
        }
    }

    /**
     * One painted HUD frame that lists the lose item (C counts only those).
     * <p>
     * BEHAVIOR: returns true iff the outline is drawn, i.e. the selection is not off.
     * BEHAVIOR: while fading the frame ages the fade by {@code dt} seconds; the frame that uses it up still draws
     * the outline (C: {@code -1} counts up to 0 and draws), later frames do not (off).
     */
    public boolean paint(double dt) {
        if (state == 0) {
            return false;
        }
        if (state < 0) {
            fade -= dt;
            if (fade <= 0) {
                state = 0;
            }
        }
        return true;
    }

    /** C's {@code lose_item_active}: 0 off, 1, 2, or -1 while fading. */
    public int state() {
        return state;
    }
}
