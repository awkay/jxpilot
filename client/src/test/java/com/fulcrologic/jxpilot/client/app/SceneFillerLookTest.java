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

import com.fulcrologic.jxpilot.client.game.ClientWorld;
import com.fulcrologic.jxpilot.client.game.Players;
import com.fulcrologic.jxpilot.client.paint.PaintStyle;
import com.fulcrologic.jxpilot.common.net.Msg;
import com.fulcrologic.jxpilot.common.net.Records;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Scene filler: look options and own tanks")
class SceneFillerLookTest {
    static final int TANK = 30;
    static final int OTHER_TANK = 31;
    static final int NAMED = 32;

    @Test
    @DisplayName("myTank: a tank named after the viewer, on the viewer's team in team play (L6)")
    void myTank() {
        AppFixture f = new AppFixture(false).players()
            .join(TANK, 1, Msg.PlayerJoin.FLAG_TANK, "Me's tank", "")
            .join(OTHER_TANK, 2, Msg.PlayerJoin.FLAG_TANK, "Me's tank", "")
            .join(NAMED, 1, 0, "Me's tank", "")
            .join(40, 1, Msg.PlayerJoin.FLAG_TANK, "Mate's tank", "");
        Players p = f.game.players();
        int self = p.slotOf(AppFixture.SELF);
        assertAll(
            () -> assertTrue(SceneFiller.myTank(p, self, p.slotOf(TANK), false), "own tank"),
            () -> assertTrue(SceneFiller.myTank(p, self, p.slotOf(TANK), true), "own tank, same team in team play"),
            () -> assertFalse(SceneFiller.myTank(p, self, p.slotOf(OTHER_TANK), true), "other team in team play"),
            () -> assertTrue(SceneFiller.myTank(p, self, p.slotOf(OTHER_TANK), false), "any team without team play"),
            () -> assertFalse(SceneFiller.myTank(p, self, p.slotOf(NAMED), false), "the right name but no tank"),
            () -> assertFalse(SceneFiller.myTank(p, self, p.slotOf(40), false), "another player's tank"),
            () -> assertTrue(SceneFiller.friendly(0, p, p.slotOf(TANK), false), "the own tank counts as friendly (blue)"),
            () -> assertFalse(SceneFiller.friendly(0, p, p.slotOf(40), false), "another's tank does not"));
    }

    @Test
    @DisplayName("myTank: the expected name is cut to 15 characters as C's strlcat cuts it")
    void longNames() {
        AppFixture f = new AppFixture(false)
            .join(AppFixture.SELF, 1, Msg.PlayerJoin.FLAG_SELF, "ABCDEFGHIJKLM", "")
            .join(TANK, 1, Msg.PlayerJoin.FLAG_TANK, "ABCDEFGHIJKLM's", "")
            .join(OTHER_TANK, 1, Msg.PlayerJoin.FLAG_TANK, "ABCDEFGHIJKLM's t", "")
            .join(NAMED, 1, Msg.PlayerJoin.FLAG_TANK, "ABCDEFGHIJKLM'x", "")
            .join(41, 1, Msg.PlayerJoin.FLAG_TANK, "ABCDEFGHIJKLM'", "");
        Players p = f.game.players();
        int self = p.slotOf(AppFixture.SELF);
        assertAll(
            () -> assertTrue(SceneFiller.myTank(p, self, p.slotOf(TANK), false), "13 + 7 cut to 15: \"ABCDEFGHIJKLM's\""),
            () -> assertFalse(SceneFiller.myTank(p, self, p.slotOf(OTHER_TANK), false), "a longer name does not match"),
            () -> assertFalse(SceneFiller.myTank(p, self, p.slotOf(41), false), "a shorter one neither"),
            () -> assertFalse(SceneFiller.myTank(p, self, p.slotOf(NAMED), false), "one character differs"));
    }

    @Test
    @DisplayName("the live sparkColors colour the point particles and lasers")
    void sparkColours() {
        PaintStyle st = new PaintStyle();
        st.setSparkColors(new int[] {9, 10}, 2);
        int base = ClientWorld.POINT_LEVEL_BASE;
        assertAll(
            () -> assertEquals(9, SceneFiller.pointColor(base, st), "level 0: the first colour"),
            () -> assertEquals(10, SceneFiller.pointColor(base + 3, st), "level 3 clamped to the last of two"),
            () -> assertEquals(com.fulcrologic.jxpilot.client.render.Palette.RED,
                SceneFiller.pointColor(Records.ObjColor.RED, st), "object colours unchanged"),
            () -> assertEquals(9, SceneFiller.particleColor(5, 0, 0, 1, Records.ObjColor.WHITE, st),
                "two colours are two levels (< 3: always level 0)"),
            () -> assertEquals(5, SceneFiller.particleColor(5, 0, 0, 1, Records.ObjColor.WHITE),
                "the default style keeps sparkColors 5,6,7,3"));
    }
}
