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
package com.fulcrologic.jxpilot.client.app;

import com.fulcrologic.jxpilot.client.game.ClientGame;
import com.fulcrologic.jxpilot.client.game.hud.Hud;
import com.fulcrologic.jxpilot.client.game.hud.TalkLine;
import com.fulcrologic.jxpilot.client.game.options.ClientOptions;

import static com.fulcrologic.jxpilot.client.game.options.ClientOption.*;

/**
 * Pushes the {@link ClientOptions} into the game-side consumers whenever they change (the C client reads its
 * globals directly; the CONFIG panel changes them live): the HUD switches and limits, the message limits, the
 * score decimals, the HUD item list and the talk history. Allocation free; one int compare per frame when
 * nothing changed.
 */
public final class LiveOptions {
    private final ClientOptions options;
    private int applied = -1;

    public LiveOptions(ClientOptions options) {
        this.options = options;
    }

    public ClientOptions options() { return options; }

    /**
     * BEHAVIOR: when the options' version differs from the last applied one, copies them into the consumers:
     * Hud showHUD, horizontalHUDLine, verticalHUDLine, fuelGauge, fuelMeter, powerMeter, turnSpeedMeter,
     * fuelNotify/fuelWarning/fuelCritical, showMessages, speedFactHUD, speedFactPTR; messages maxMessages and
     * charsPerSecond; score list showScoreDecimals; HUD items showItems and showItemsTime; talk
     * maxLinesInHistory. Returns whether it applied.
     */
    public boolean apply(Hud hud, ClientGame game, HudExtras extras, TalkLine talk) {
        return apply(hud, game, extras, talk, null, null);
    }

    /**
     * {@link #apply(Hud, ClientGame, HudExtras, TalkLine)}, plus (each when not null) showMineName into the
     * {@link SceneFiller} and toggleShield / autoShield into {@code input}.
     */
    public boolean apply(Hud hud, ClientGame game, HudExtras extras, TalkLine talk, SceneFiller filler,
                         com.fulcrologic.jxpilot.client.game.input.InputState input) {
        if (options.version() == applied) {
            return false;
        }
        applied = options.version();
        hud.showHud = options.flag(SHOW_HUD);
        hud.horizontalLines = options.flag(HORIZONTAL_HUD_LINE);
        hud.verticalLines = options.flag(VERTICAL_HUD_LINE);
        hud.fuelGauge = options.flag(FUEL_GAUGE);
        hud.fuelMeter = options.flag(FUEL_METER);
        hud.powerMeter = options.flag(POWER_METER);
        hud.turnSpeedMeter = options.flag(TURN_SPEED_METER);
        hud.fuelNotify = options.integer(FUEL_NOTIFY);
        hud.fuelWarning = options.integer(FUEL_WARNING);
        hud.fuelCritical = options.integer(FUEL_CRITICAL);
        hud.showMessages = options.flag(SHOW_MESSAGES);
        hud.hudMoveFact = options.real(SPEED_FACT_HUD);
        hud.ptrMoveFact = options.real(SPEED_FACT_PTR);
        game.messages().setMaxMessages(options.integer(MAX_MESSAGES));
        game.messages().setCharsPerSecond(options.integer(CHARS_PER_SECOND));
        game.scoreboard().setDecimals(options.integer(SHOW_SCORE_DECIMALS));
        extras.showItems = options.flag(SHOW_ITEMS);
        extras.showItemsTime = options.real(SHOW_ITEMS_TIME);
        talk.setHistorySize(options.integer(MAX_LINES_IN_HISTORY));
        if (input != null) {
            input.toggleShield = options.flag(TOGGLE_SHIELD);
            input.autoShield = options.flag(AUTO_SHIELD);
        }
        if (filler != null) {
            filler.showMineName = options.flag(SHOW_MINE_NAME);
        }
        return true;
    }

    /**
     * {@link #apply(Hud, ClientGame, HudExtras, TalkLine)}, and the scene's item drawing options: showMineName (C
     * SHOW_MINE_NAME instrument) into the {@link SceneFiller}.
     * <p>
     * BEHAVIOR: as the 4-argument apply; when it applies, the filler's showMineName is the option's value.
     */
    public boolean apply(Hud hud, ClientGame game, HudExtras extras, TalkLine talk, SceneFiller filler) {
        return apply(hud, game, extras, talk, filler, null);
    }
}
