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
import com.fulcrologic.jxpilot.client.game.MessageLog;
import com.fulcrologic.jxpilot.client.game.hud.Hud;
import com.fulcrologic.jxpilot.client.game.hud.TalkLine;
import com.fulcrologic.jxpilot.client.game.options.ClientOption;
import com.fulcrologic.jxpilot.client.game.options.ClientOptions;
import com.fulcrologic.jxpilot.common.net.Msg;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Live client options")
class LiveOptionsTest {
    @Nested
    @DisplayName("apply")
    class Apply {
        @Test
        @DisplayName("the 5-argument apply also sets the scene's showMineName, once per change")
        void mineNames() {
            ClientOptions o = new ClientOptions();
            LiveOptions live = new LiveOptions(o);
            SceneFiller f = new SceneFiller();
            f.showMineName = false;
            boolean first = live.apply(new Hud(), new ClientGame(), new HudExtras(), new TalkLine(), f);
            boolean defaultOn = f.showMineName;
            boolean again = live.apply(new Hud(), new ClientGame(), new HudExtras(), new TalkLine(), f);
            o.apply(ClientOption.SHOW_MINE_NAME, "no");
            live.apply(new Hud(), new ClientGame(), new HudExtras(), new TalkLine(), f);
            assertAll(
                () -> assertTrue(first, "the first call applies"),
                () -> assertTrue(defaultOn, "C's default: mine names shown"),
                () -> assertEquals(false, again, "no change, nothing applied"),
                () -> assertEquals(false, f.showMineName, "showMineName no reaches the filler"));
        }

        @Test
        @DisplayName("copies every option into its consumer once per change")
        void copies() {
            ClientOptions o = new ClientOptions();
            LiveOptions live = new LiveOptions(o);
            Hud hud = new Hud();
            ClientGame g = new ClientGame();
            HudExtras x = new HudExtras();
            TalkLine t = new TalkLine();
            boolean first = live.apply(hud, g, x, t);
            boolean again = live.apply(hud, g, x, t);
            o.apply(ClientOption.SHOW_HUD, "no");
            o.apply(ClientOption.HORIZONTAL_HUD_LINE, "no");
            o.apply(ClientOption.VERTICAL_HUD_LINE, "yes");
            o.apply(ClientOption.FUEL_GAUGE, "no");
            o.apply(ClientOption.FUEL_METER, "yes");
            o.apply(ClientOption.POWER_METER, "yes");
            o.apply(ClientOption.TURN_SPEED_METER, "yes");
            o.apply(ClientOption.FUEL_NOTIFY, "600");
            o.apply(ClientOption.FUEL_WARNING, "300");
            o.apply(ClientOption.FUEL_CRITICAL, "150");
            o.apply(ClientOption.SHOW_MESSAGES, "no");
            o.apply(ClientOption.REVERSE_SCROLL, "yes");
            o.apply(ClientOption.SPEED_FACT_HUD, "1.5");
            o.apply(ClientOption.SPEED_FACT_PTR, "-2");
            o.apply(ClientOption.MAX_MESSAGES, "3");
            o.apply(ClientOption.CHARS_PER_SECOND, "100");
            o.apply(ClientOption.SHOW_SCORE_DECIMALS, "2");
            o.apply(ClientOption.SHOW_ITEMS, "no");
            o.apply(ClientOption.SHOW_ITEMS_TIME, "4.5");
            o.apply(ClientOption.MAX_LINES_IN_HISTORY, "5");
            boolean changed = live.apply(hud, g, x, t);
            assertAll(
                () -> assertTrue(first, "the first call applies"),
                () -> assertFalse(again, "an unchanged version does nothing"),
                () -> assertTrue(changed, "a change applies"),
                () -> assertFalse(hud.showHud, "showHUD"),
                () -> assertFalse(hud.horizontalLines, "horizontalHUDLine"),
                () -> assertTrue(hud.verticalLines, "verticalHUDLine"),
                () -> assertFalse(hud.fuelGauge, "fuelGauge"),
                () -> assertTrue(hud.fuelMeter, "fuelMeter"),
                () -> assertTrue(hud.powerMeter, "powerMeter"),
                () -> assertTrue(hud.turnSpeedMeter, "turnSpeedMeter"),
                () -> assertEquals(600, hud.fuelNotify, "fuelNotify"),
                () -> assertEquals(300, hud.fuelWarning, "fuelWarning"),
                () -> assertEquals(150, hud.fuelCritical, "fuelCritical"),
                () -> assertFalse(hud.showMessages, "showMessages"),
                () -> assertTrue(hud.reverseScroll, "reverseScroll"),
                () -> assertEquals(1.5, hud.hudMoveFact, "speedFactHUD"),
                () -> assertEquals(-2.0, hud.ptrMoveFact, "speedFactPTR"),
                () -> assertEquals(3, g.messages().maxMessages(), "maxMessages"),
                () -> assertEquals(2, g.scoreboard().decimals(), "showScoreDecimals"),
                () -> assertFalse(x.showItems, "showItems"),
                () -> assertEquals(4.5, x.showItemsTime, "showItemsTime"),
                () -> assertEquals(5, t.historySize(), "maxLinesInHistory"));
        }

        @Test
        @DisplayName("charsPerSecond sets the typing speed")
        void chars() {
            ClientOptions o = new ClientOptions();
            o.apply(ClientOption.CHARS_PER_SECOND, "100");
            ClientGame g = new ClientGame();
            new LiveOptions(o).apply(new Hud(), g, new HudExtras(), new TalkLine());
            g.messages().add(Msg.Text.KIND_SERVER, "x".repeat(50));
            g.messages().tick(10);
            assertEquals(20, g.messages().visibleChars(MessageLog.GAME, 0, 50),
                "100 chars/s at 50 fps over 10 frames types 20 chars (50 would type 10)");
        }
    }

    @Nested
    @DisplayName("consumers")
    class Consumers {
        @Test
        @DisplayName("HUD items: always while showItems, else for showItemsTime after a change (zero counts too)")
        void items() {
            HudExtras x = new HudExtras();
            int mine = com.fulcrologic.jxpilot.common.Item.MINE.ordinal(); // C skips fuel (painthud.c:531)
            int on0 = x.itemCount(mine, 0);
            int on3 = x.itemCount(mine, 3);
            x.showItems = false;
            x.showItemsTime = 1;
            x.step(20, 50);
            int changed = x.itemCount(1, 2);
            int still = x.itemCount(1, 2);
            int left = x.itemCount(1, 2);
            int gone = x.itemCount(1, 2);
            int zero = x.itemCount(1, 0);
            int sameAsBefore = x.itemCount(mine, 3);
            assertAll(
                () -> assertEquals(-1, on0, "showItems: a zero count is hidden"),
                () -> assertEquals(3, on3, "showItems: a count above 0 is shown"),
                () -> assertEquals(2, changed, "a changed count shows (50 frames to run)"),
                () -> assertEquals(2, still, "30 frames left: shown"),
                () -> assertEquals(2, left, "10 frames left: shown"),
                () -> assertEquals(-1, gone, "no frames left: hidden"),
                () -> assertEquals(0, zero, "a change to zero shows the 0"),
                () -> assertEquals(-1, sameAsBefore, "a count unchanged since showItems was on is hidden"));
        }

        @Test
        @DisplayName("maxMessages trims the lists; history size trims the history; decimals clamp")
        void limits() {
            MessageLog log = new MessageLog();
            for (int i = 0; i < 6; i++) {
                log.add(Msg.Text.KIND_SERVER, "m" + i);
            }
            log.setMaxMessages(4);
            int afterTrim = log.gameCount();
            log.setMaxMessages(99);
            int clampedHigh = log.maxMessages();
            log.setMaxMessages(0);
            int clampedLow = log.maxMessages();
            TalkLine t = new TalkLine();
            for (int i = 0; i < 3; i++) {
                t.open();
                t.type('a' + i);
                t.submit(new StringBuilder());
            }
            t.setHistorySize(2);
            String kept = t.history(0) + "," + t.history(1) + "," + t.history(2);
            t.setHistorySize(500);
            int bigHist = t.historySize();
            t.setHistorySize(0);
            int smallHist = t.historySize();
            com.fulcrologic.jxpilot.client.game.Scoreboard sb = new com.fulcrologic.jxpilot.client.game.Scoreboard();
            sb.setDecimals(7);
            int high = sb.decimals();
            sb.setDecimals(-1);
            assertAll(
                () -> assertEquals(4, afterTrim, "the oldest beyond 4 are dropped"),
                () -> assertEquals(MessageLog.MAX_MSGS, clampedHigh, "at most MAX_MSGS"),
                () -> assertEquals(1, clampedLow, "at least 1"),
                () -> assertEquals("c,b,", kept, "the history keeps the 2 newest lines"),
                () -> assertEquals(TalkLine.MAX_HISTORY, bigHist, "at most MAX_HIST_MSGS"),
                () -> assertEquals(1, smallHist, "at least 1"),
                () -> assertEquals(2, high, "decimals at most 2"),
                () -> assertEquals(0, sb.decimals(), "decimals at least 0"));
        }
    }
}
