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

import com.fulcrologic.jxpilot.client.game.options.ClientOption;

/**
 * The C client's text fonts by role ({@code xinit.c} {@code Init_top}: one X font per GC), each set by its
 * resource. KEY_LIST has no resource in C (the key list viewer's fixed 10-pixel font, {@code default.c}
 * {@code KEY_LIST_FONT}).
 */
public enum FontRole {
    /** {@code gameFont}: the HUD, meters, names and every other text in the game view. */
    GAME(ClientOption.GAME_FONT),
    /** {@code messageFont}: the talk and game messages. */
    MESSAGE(ClientOption.MESSAGE_FONT),
    /** {@code scoreListFont}: the score list window. */
    SCORE_LIST(ClientOption.SCORE_LIST_FONT),
    /** {@code buttonFont}: buttons. */
    BUTTON(ClientOption.BUTTON_FONT),
    /** {@code textFont}: labels and the information windows. */
    TEXT(ClientOption.TEXT_FONT),
    /** {@code talkFont}: the talk window. */
    TALK(ClientOption.TALK_FONT),
    /** {@code motdFont}: the MOTD viewer. */
    MOTD(ClientOption.MOTD_FONT),
    /** C {@code KEY_LIST_FONT}: the key list viewer (no resource). */
    KEY_LIST(null);

    /** The resource choosing the font, or null. */
    public final ClientOption option;

    FontRole(ClientOption option) {
        this.option = option;
    }

    /** Metrics for every role (the renderer's baked fonts; tests use one fixed-width fake for all). */
    @FunctionalInterface
    public interface Metrics {
        TextMetrics of(FontRole role);
    }
}
