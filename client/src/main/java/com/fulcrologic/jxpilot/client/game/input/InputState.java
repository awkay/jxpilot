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

/**
 * Key events to the 64-bit {@link ControlKey} mask sent to the server, a port of C {@code keyv}
 * handling ({@code Key_init}, {@code Key_press}, {@code Key_release}, {@code Reset_shields} in
 * {@code src/client/xevent.c}). A press sets every function bound to the key; a release clears every
 * function bound to the key, even if another key bound to the same function is still held (C semantics).
 * While the talk line is open game keys are ignored (the talk window has the keyboard in C).
 * <p>
 * Shields: the mask starts with SHIELD set (C {@code Key_init}). With {@link #autoShield} (C default on)
 * pressing a fire key (shot, laser, missile, torpedo, heat, drop/detach mine) lowers the shield, and
 * releasing the last held one raises it again while the shields are "considered up"; pressing the shield
 * key sets that, releasing it clears it. With {@link #toggleShield} (default off) the shield key toggles.
 * {@link #setPlaying} re-raises the shield when the player (re)enters play, like C {@code Reset_shields}.
 * Allocation free.
 */
public final class InputState {
    /** The fire keys of C's auto-shield hack. */
    public static final long FIRE_KEYS = ControlKey.FIRE_SHOT.mask() | ControlKey.FIRE_LASER.mask()
        | ControlKey.FIRE_MISSILE.mask() | ControlKey.FIRE_TORPEDO.mask() | ControlKey.FIRE_HEAT.mask()
        | ControlKey.DROP_MINE.mask() | ControlKey.DETACH_MINE.mask();
    private static final long SHIELD = ControlKey.SHIELD.mask();

    /** C {@code autoShield} (default on): shields drop for fire. */
    public boolean autoShield = true;
    /** C {@code toggleShield} (default off): the shield key toggles. */
    public boolean toggleShield;

    private final KeyBindings bindings;
    private long keys = SHIELD;
    private boolean shields = true;
    private boolean talking;
    private boolean playing;

    public InputState(KeyBindings bindings) {
        this.bindings = bindings;
    }

    public KeyBindings bindings() {
        return bindings;
    }

    /**
     * A key went down.
     * <p>
     * BEHAVIOR: while not talking, every game function bound to the key is set; a fire function with
     * autoShield first clears SHIELD; the shield key with toggleShield toggles SHIELD (and the shields
     * state), with autoShield marks the shields as up.
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
        long m = bindings.gameMask(glfwKey);
        if ((m & FIRE_KEYS) != 0 && autoShield) {
            keys &= ~SHIELD;
        }
        if ((m & SHIELD) != 0) {
            if (toggleShield) {
                shields = !shields;
                keys = shields ? keys | SHIELD : keys & ~SHIELD;
                m &= ~SHIELD;
            } else if (autoShield) {
                shields = true;
            }
        }
        keys |= m;
        return acts & ~cursor;
    }

    private static long cursorMask() {
        return 1L << ClientAction.TALK_CURSOR_LEFT.ordinal() | 1L << ClientAction.TALK_CURSOR_RIGHT.ordinal()
            | 1L << ClientAction.TALK_CURSOR_UP.ordinal() | 1L << ClientAction.TALK_CURSOR_DOWN.ordinal();
    }

    /**
     * A key went up.
     * <p>
     * BEHAVIOR: every game function bound to the key is cleared, even if another key bound to it is
     * still held (C {@code Key_release}).
     * BEHAVIOR: with autoShield and the shields up, releasing a fire function while the shield is down
     * raises SHIELD once no fire function remains set.
     * BEHAVIOR: the shield key: ignored with toggleShield; with autoShield it marks the shields as down.
     * BEHAVIOR: ignored while talking and for out-of-range codes.
     */
    public void release(int glfwKey) {
        if (talking || glfwKey < 0 || glfwKey >= KeyBindings.SIZE) {
            return;
        }
        long m = bindings.gameMask(glfwKey);
        if ((m & FIRE_KEYS) != 0 && autoShield && shields && (keys & SHIELD) == 0) {
            keys &= ~m;
            if ((keys & FIRE_KEYS) == 0) {
                keys |= SHIELD;
            }
        }
        if ((m & SHIELD) != 0) {
            if (toggleShield) {
                m &= ~SHIELD;
            } else if (autoShield) {
                shields = false;
            }
        }
        keys &= ~m;
    }

    /**
     * BEHAVIOR: releases every game function (focus lost); the shield stays up iff the shields are
     * considered up under autoShield or toggleShield.
     */
    public void releaseAll() {
        keys = shields && (autoShield || toggleShield) ? SHIELD : 0;
    }

    /**
     * C {@code Reset_shields} on entering play.
     * <p>
     * BEHAVIOR: with autoShield or toggleShield: SHIELD set and the shields up; with autoShield and a
     * fire function held, SHIELD stays down. Without either option nothing changes.
     */
    public void resetShields() {
        if (toggleShield || autoShield) {
            keys |= SHIELD;
            shields = true;
            if (autoShield && (keys & FIRE_KEYS) != 0) {
                keys &= ~SHIELD;
            }
        }
    }

    /**
     * Feed whether the own ship is in play (alive) each frame (C {@code Game_over_action}).
     * <p>
     * BEHAVIOR: {@link #resetShields()} on each transition from not playing to playing.
     */
    public void setPlaying(boolean inPlay) {
        if (inPlay && !playing) {
            resetShields();
        }
        playing = inPlay;
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

    /** True while the shields are considered up (C {@code shields}). */
    public boolean shieldsUp() {
        return shields;
    }

    /** The current {@link ControlKey} mask. */
    public long keys() {
        return keys;
    }
}
