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

import com.fulcrologic.jxpilot.client.game.ClientGame;
import com.fulcrologic.jxpilot.client.game.Players;
import com.fulcrologic.jxpilot.client.game.Scoreboard;
import com.fulcrologic.jxpilot.common.net.Msg;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.fulcrologic.jxpilot.client.game.hud.HudTest.lines;
import static com.fulcrologic.jxpilot.client.game.hud.HudTest.rects;
import static org.junit.jupiter.api.Assertions.*;

/** Each HUD part is laid out with, and tagged with, its C font role (painthud.c, talk.c, paint.c). */
class HudFontRolesTest {
    /** Fixed-width fake font: {@code adv} px per char. */
    record Fake(int ascent, int descent, int adv) implements TextMetrics {
        @Override
        public int width(CharSequence s, int len) { return adv * len; }
    }

    static final Fake GAME = new Fake(10, 3, 7);
    static final Fake MESSAGE = new Fake(20, 5, 9);
    static final Fake TALK = new Fake(30, 4, 11);
    static final Fake TEXT = new Fake(40, 6, 13);
    static final Fake SCORE = new Fake(12, 2, 5);
    static final Fake OTHER = new Fake(1, 1, 1);

    /** A distinct fake per role, so a position shows which role's metrics placed it. */
    static final FontRole.Metrics FONTS = role -> switch (role) {
        case GAME -> GAME;
        case MESSAGE -> MESSAGE;
        case TALK -> TALK;
        case TEXT -> TEXT;
        case SCORE_LIST -> SCORE;
        default -> OTHER;
    };

    /** "ROLE x,y text" per run. */
    static List<String> runs(HudFrame f) {
        List<String> l = new ArrayList<>();
        for (int i = 0; i < f.textCount(); i++) {
            l.add(f.textRole(i) + " " + (int) f.textX(i) + "," + (int) f.textY(i) + " " + f.text(i));
        }
        return l;
    }

    @Test
    @DisplayName("build: messages in messageFont, the talk window in talkFont sized by textFont, the HUD in gameFont")
    void build() {
        ClientGame g = HudBuildTest.game(100, 0, 0, 0);
        g.messages().add(Msg.Text.KIND_CHAT, "hi");
        g.messages().add(Msg.Text.KIND_GAME, "go");
        TalkLine t = new TalkLine();
        t.open();
        t.type('a');
        t.type('b');
        HudFrame f = new HudFrame();
        Hud h = new Hud();
        h.horizontalLines = false; // only the talk window's lines and rects
        h.fuelGauge = false;
        h.build(f, g, t, 800, 600, FONTS, 0);
        List<String> r = runs(f);
        assertAll(
            () -> assertTrue(r.contains("GAME 473,383 0100"), "fuel text at the HUD corner + gameFont ascent 10: " + r),
            () -> assertTrue(r.contains("MESSAGE 10,30 hi"), "talk message at border 10 + messageFont ascent 20: " + r),
            () -> assertTrue(r.contains("MESSAGE 10,585 go"), "game message at 600 - messageFont descent 5 - 10: " + r),
            () -> assertTrue(r.contains("TALK 53,459 ab"),
                "talk text at window top 426 + border 3 + talkFont ascent 30: " + r),
            () -> assertEquals(List.of("fill 50,426 700x52", "box 48,424 704x56"), rects(f),
                "talk window height textFont 46 + 2*3, centred on 450"),
            () -> assertTrue(r.contains("TALK 75,459 _"), "the '_' cursor after 2 talkFont chars (2*11): " + r),
            () -> assertEquals(List.of(), lines(f), "no cursor line"));
    }

    @Test
    @DisplayName("build with one metrics uses it for every role")
    void oneMetrics() {
        ClientGame g = HudBuildTest.game(100, 0, 0, 0);
        g.messages().add(Msg.Text.KIND_GAME, "go");
        HudFrame f = new HudFrame();
        new Hud().build(f, g, new TalkLine(), 800, 600, MESSAGE, 0);
        List<String> r = runs(f);
        assertAll(
            () -> assertTrue(r.contains("GAME 473,393 0100"), "fuel text with the given ascent 20: " + r),
            () -> assertTrue(r.contains("MESSAGE 10,585 go"), "message with the given descent 5: " + r));
    }

    @Test
    @DisplayName("the score list runs are scoreListFont runs")
    void scoreList() {
        Players p = new Players();
        Msg.PlayerJoin j = new Msg.PlayerJoin();
        j.id = 1;
        j.name.append("Ann");
        p.join(j);
        Scoreboard sb = new Scoreboard();
        sb.configure(false, false, false, 1);
        sb.update(p);
        HudFrame f = new HudFrame();
        new Hud().scoreboard(f, sb, 0, 0, 300, SCORE);
        assertAll(
            () -> assertEquals(FontRole.SCORE_LIST, f.textRole(0), "heading"),
            () -> assertEquals(FontRole.SCORE_LIST, f.textRole(1), "row"),
            () -> assertEquals(18, (int) f.textY(0), "heading baseline 6 + ascent 12"));
    }

    @Test
    @DisplayName("HudFrame keeps each run's role; the role-less overload is GAME")
    void frameRoles() {
        HudFrame f = new HudFrame();
        f.text(1, 2, HudColor.WHITE);
        f.text(3, 4, FontRole.MOTD, HudColor.RED);
        assertAll(
            () -> assertEquals(FontRole.GAME, f.textRole(0), "default role"),
            () -> assertEquals(FontRole.MOTD, f.textRole(1), "given role"),
            () -> assertEquals(HudColor.RED, f.textColor(1), "colour kept beside the role"),
            () -> assertEquals(3f, f.textX(1), "position kept beside the role"));
    }

    @Test
    @DisplayName("KeyHelp lays out in its role: KEY_LIST by default, or the one given")
    void keyHelpRoles() {
        KeyHelp keys = new KeyHelp("T", List.<String[]>of(new String[] {"a", "b"}));
        KeyHelp motd = new KeyHelp("M", List.<String[]>of(new String[] {"", "line"}), FontRole.MOTD);
        keys.toggle();
        motd.toggle();
        HudFrame kf = new HudFrame();
        HudFrame mf = new HudFrame();
        keys.layout(kf, 800, 600, OTHER);
        motd.layout(mf, 800, 600, OTHER);
        assertAll(
            () -> assertEquals(FontRole.KEY_LIST, keys.role(), "key list role"),
            () -> assertEquals(FontRole.MOTD, motd.role(), "MOTD role"),
            () -> assertEquals(List.of(FontRole.KEY_LIST, FontRole.KEY_LIST, FontRole.KEY_LIST),
                List.of(kf.textRole(0), kf.textRole(1), kf.textRole(2)), "title, keys and help in KEY_LIST"),
            () -> assertEquals(FontRole.MOTD, mf.textRole(2), "MOTD lines in MOTD"));
    }
}
