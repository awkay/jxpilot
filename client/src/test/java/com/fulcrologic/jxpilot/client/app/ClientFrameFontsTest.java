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

import com.fulcrologic.jxpilot.client.game.hud.FontRole;
import com.fulcrologic.jxpilot.client.game.hud.HudFrame;
import com.fulcrologic.jxpilot.client.game.hud.TextMetrics;
import com.fulcrologic.jxpilot.client.game.input.InputState;
import com.fulcrologic.jxpilot.client.net.ConnectionState;
import com.fulcrologic.jxpilot.client.paint.SceneData;
import com.fulcrologic.jxpilot.common.net.ReliableType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static com.fulcrologic.jxpilot.client.app.AppFixture.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;

/** {@link ClientFrame#step} hands each part its role's metrics (a distinct ascent per role shows which). */
class ClientFrameFontsTest {
    record Fake(int ascent) implements TextMetrics {
        public int descent() { return 2; }

        public int width(CharSequence s, int len) { return 5 * len; }
    }

    /** 100 px per char. */
    record Wide() implements TextMetrics {
        public int ascent() { return 10; }

        public int descent() { return 2; }

        public int width(CharSequence s, int len) { return 100 * len; }
    }

    /** Ascent 10 + ordinal per role. */
    static final Fake[] BY_ROLE = new Fake[FontRole.values().length];

    static {
        for (FontRole r : FontRole.values()) {
            BY_ROLE[r.ordinal()] = new Fake(10 + r.ordinal());
        }
    }

    static final FontRole.Metrics FONTS = r -> BY_ROLE[r.ordinal()];

    final FakeConnection conn = new FakeConnection();

    ClientFrame frame(AppFixture f) {
        return new ClientFrame(f.game, conn, new InputState(KeyRouter.bindings(null)), new SceneData(8, 16, 64, 8),
            new RadarBlips(8));
    }

    static int asc(FontRole r) {
        return 10 + r.ordinal();
    }

    static String run(HudFrame h, int i) {
        return h.textRole(i) + " " + (int) h.textY(i);
    }

    @Test
    @DisplayName("score list in scoreListFont, key help in the key list font, messages in messageFont")
    void rolesPerPart() {
        AppFixture f = new AppFixture(true).players();
        f.game.onStateChange(ConnectionState.PLAYING, "Playing");
        ClientFrame cf = frame(f);
        cf.key(GLFW_KEY_F1, GLFW_PRESS, 0, T0);
        cf.step(T0, 800, 600, 1, FONTS, 256, 100);
        HudFrame v = cf.viewHud();
        int hint = -1;
        for (int i = 0; i < v.textCount(); i++) {
            if (v.text(i).toString().startsWith("F1")) {
                hint = i;
            }
        }
        int h = hint;
        assertAll(
            () -> assertEquals(100 + BY_ROLE[FontRole.BUTTON.ordinal()].ascent()
                + BY_ROLE[FontRole.BUTTON.ordinal()].descent() + 8 + 2, cf.ui().scoreTop(),
                "the button bar is laid out in buttonFont (C ButtonHeight)"),
            () -> assertEquals("SCORE_LIST " + (cf.ui().scoreTop() + 6 + asc(FontRole.SCORE_LIST)), run(cf.panelHud(), 0),
                "score heading at scoreTop + border 6 + scoreListFont ascent"),
            () -> assertEquals("KEY_LIST " + (10 + 5 + asc(FontRole.KEY_LIST)), run(cf.helpHud(), 0),
                "key help title at 15 + the key list font's ascent"),
            () -> assertTrue(h >= 0, "the 'F1: keys' hint is shown"),
            () -> assertEquals(FontRole.MESSAGE, v.textRole(h), "messages are messageFont runs"));
    }

    @Test
    @DisplayName("the MOTD panel in motdFont; the talk line is limited with talkFont")
    void motdAndTalk() {
        AppFixture f = new AppFixture(true).players();
        f.game.onStateChange(ConnectionState.PLAYING, "Playing");
        byte[] text = "Welcome".getBytes(StandardCharsets.ISO_8859_1);
        f.game.onReliable(ReliableType.MOTD, ByteBuffer.allocate(8 + text.length).putInt(0).putInt(text.length)
            .put(text).flip());
        ClientFrame cf = frame(f);
        Wide wide = new Wide();
        FontRole.Metrics wideTalk = r -> r == FontRole.TALK ? wide : BY_ROLE[r.ordinal()];
        cf.step(T0, 800, 600, 1, wideTalk, 256, 100);
        cf.talk().open();
        for (int i = 0; i < 20; i++) {
            cf.talk().type('x');
        }
        assertAll(
            () -> assertEquals("MOTD " + (10 + 5 + asc(FontRole.MOTD)), run(cf.helpHud(), 0),
                "MOTD title at 15 + motdFont's ascent"),
            () -> assertEquals(7, cf.talk().text().length(),
                "talk window 700 - 11: 100 px talkFont chars stop at 7"));
    }
}
