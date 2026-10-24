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

import com.fulcrologic.jxpilot.client.game.CharBuf;
import com.fulcrologic.jxpilot.client.game.hud.HudFrame;
import com.fulcrologic.jxpilot.client.game.hud.TextMetrics;
import com.fulcrologic.jxpilot.client.game.input.InputState;
import com.fulcrologic.jxpilot.client.net.ConnectionState;
import com.fulcrologic.jxpilot.client.paint.SceneData;
import com.fulcrologic.jxpilot.common.map.Base;
import com.fulcrologic.jxpilot.common.net.ControlKey;
import com.fulcrologic.jxpilot.common.net.Msg;
import com.fulcrologic.jxpilot.common.net.Records;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.fulcrologic.jxpilot.client.app.AppFixture.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;

class ClientFrameTest {
    record Mono() implements TextMetrics {
        public int ascent() { return 10; }

        public int descent() { return 3; }

        public int width(CharSequence s, int len) { return 7 * len; }
    }

    static final Mono M = new Mono();
    final FakeConnection conn = new FakeConnection();
    final InputState input = new InputState(KeyRouter.bindings(null));

    ClientFrame frame(AppFixture f) {
        return new ClientFrame(f.game, conn, input, new SceneData(8, 16, 64, 8), new RadarBlips(8));
    }

    static boolean hasText(HudFrame h, String s) {
        for (int i = 0; i < h.textCount(); i++) {
            if (h.text(i).toString().equals(s)) {
                return true;
            }
        }
        return false;
    }

    @Nested
    @DisplayName("step")
    class Step {
        @Test
        @DisplayName("calls the connection in order: update, input with the current keys, flush")
        void order() {
            AppFixture f = new AppFixture(true).players();
            ClientFrame cf = frame(f);
            cf.key(GLFW_KEY_RIGHT_SHIFT, GLFW_PRESS, 0, T0);
            cf.step(T0, 800, 600, 1, M, 256, 100);
            assertAll(
                () -> assertEquals(List.of("update", "input", "flush"), conn.calls, "update, input, flush"),
                () -> assertEquals(ControlKey.THRUST.mask(), conn.lastKeys & ControlKey.THRUST.mask(),
                    "the INPUT carries the held thrust key"));
        }

        @Test
        @DisplayName("the first map centres the view on the first base and bumps the map version once")
        void firstMap() {
            AppFixture f = new AppFixture(true).players();
            ClientFrame cf = frame(f);
            int before = cf.mapVersion();
            cf.step(T0, 800, 600, 1, M, 256, 100);
            int after = cf.mapVersion();
            cf.step(T0 + 1000, 800, 600, 1, M, 256, 100);
            Base b = f.map.bases().get(0);
            assertAll(
                () -> assertEquals(0, before, "no map seen yet"),
                () -> assertEquals(1, after, "first map"),
                () -> assertEquals(1, cf.mapVersion(), "unchanged map: same version"),
                () -> assertEquals(b.x(), cf.centreX(), "centre x on the base"),
                () -> assertEquals(b.y(), cf.centreY(), "centre y on the base"));
        }

        @Test
        @DisplayName("a map without bases centres on the map centre; a replaced map bumps the version again")
        void noBaseAndReplacement() {
            AppFixture f = new AppFixture(true).players();
            ClientFrame cf = frame(f);
            cf.step(T0, 800, 600, 1, M, 256, 100);
            AppFixture other = new AppFixture(true);
            com.fulcrologic.jxpilot.common.config.Options o = new com.fulcrologic.jxpilot.common.config.Options();
            com.fulcrologic.jxpilot.common.config.ConfigParser.parse(
                "mapWidth: 4\nmapHeight: 2\nmapData: \\multiline: E\n    \n    \nE\n", "t",
                com.fulcrologic.jxpilot.common.config.OptionOrigin.MAP, o);
            com.fulcrologic.jxpilot.common.map.WorldMap m = com.fulcrologic.jxpilot.common.map.WorldMap.fromOptions(o);
            f.game.onConfig(other.welcome, o, m);
            cf.step(T0 + 1000, 800, 600, 1, M, 256, 100);
            assertAll(
                () -> assertEquals(2, cf.mapVersion(), "new map object: version 2"),
                () -> assertEquals(70.0, cf.centreX(), "4 blocks wide: x 70"),
                () -> assertEquals(35.0, cf.centreY(), "2 blocks high: y 35"));
        }

        @Test
        @DisplayName("the own ship is the centre; shields reset on entering play; scene and radar filled")
        void followsSelf() {
            AppFixture f = new AppFixture(true).players();
            ClientFrame cf = frame(f);
            input.press(GLFW_KEY_SPACE);
            input.release(GLFW_KEY_SPACE);
            long lowered = input.keys();
            conn.onUpdate = t -> {
                if (f.game.world().hasSnapshot()) {
                    return;
                }
                for (int tick = T0_TICK; tick <= T0_TICK + 1; tick++) {
                    f.begin(tick).self(Records.ShipFlags.SHIELD, 420, 140, 0, 0xFFFF).send(tick);
                }
            };
            cf.step(T0 + Math.round(1e9 / 14) + 100_000_000L, 800, 600, 1, M, 256, 100);
            assertAll(
                () -> assertEquals(0L, lowered & ControlKey.SHIELD.mask(), "shield lowered before play"),
                () -> assertEquals(ControlKey.SHIELD.mask(), conn.lastKeys & ControlKey.SHIELD.mask(),
                    "entering play raised it again (Reset_shields) before the INPUT"),
                () -> assertEquals(420.0, cf.centreX(), "centre on the own ship x"),
                () -> assertEquals(140.0, cf.centreY(), "centre on the own ship y"),
                () -> assertEquals(1, cf.scene().shipCount, "scene filled"),
                () -> assertTrue(cf.radar().selfVisible, "radar filled"),
                () -> assertEquals(102L, cf.scene().loops, "loops from the filler"),
                () -> assertEquals(102L, cf.mapState().loops(), "map symbols share loops"),
                () -> assertTrue(cf.extras().active, "HUD extras for the own ship"),
                () -> assertTrue(cf.viewHud().lineCount() >= 2, "HUD lines built"));
        }

        @Test
        @DisplayName("a dead own ship keeps the centre but is not in play (shields not reset)")
        void deadSelf() {
            AppFixture f = new AppFixture(true).players();
            ClientFrame cf = frame(f);
            conn.onUpdate = t -> {
                if (!f.game.world().hasSnapshot()) {
                    for (int tick = T0_TICK; tick <= T0_TICK + 1; tick++) {
                        f.begin(tick).self(Records.ShipFlags.DEAD, 420, 140, 0, 0xFFFF).send(tick);
                    }
                }
            };
            input.press(GLFW_KEY_SPACE);
            input.release(GLFW_KEY_SPACE);
            cf.step(T0 + Math.round(1e9 / 14) + 100_000_000L, 800, 600, 1, M, 256, 100);
            assertAll(
                () -> assertEquals(420.0, cf.centreX(), "dead ship still the centre"),
                () -> assertEquals(0L, conn.lastKeys & ControlKey.SHIELD.mask(),
                    "shield key released and no Reset_shields while dead"));
        }

        @Test
        @DisplayName("while not playing the HUD status is the connection's current status; while playing it is left alone")
        void status() {
            AppFixture f = new AppFixture(true).players();
            ClientFrame cf = frame(f);
            f.game.onStateChange(ConnectionState.JOINING, "Joining");
            conn.status = "Receiving map 40%";
            cf.step(T0, 800, 600, 1, M, 256, 100);
            boolean shown = hasText(cf.viewHud(), "Receiving map 40%");
            f.game.onStateChange(ConnectionState.PLAYING, "Playing on x");
            conn.status = "ignored";
            cf.step(T0 + 1000, 800, 600, 1, M, 256, 100);
            CharBuf playing = f.game.status();
            assertAll(
                () -> assertTrue(shown, "status text in the HUD while joining"),
                () -> assertEquals("Playing on x", playing.toString(), "untouched while playing"));
        }

        @Test
        @DisplayName("the score list goes into the panel at the given place; the HUD zoom is set")
        void panel() {
            AppFixture f = new AppFixture(true).players().score(SELF, 5, 3);
            ClientFrame cf = frame(f);
            cf.step(T0, 800, 600, 2, M, 256, 100);
            HudFrame p = cf.panelHud();
            assertAll(
                () -> assertTrue(p.textCount() >= 4, "heading and three rows"),
                () -> assertEquals(6f, p.textX(0), "left at SCORE_BORDER"),
                () -> assertEquals(100f + 6 + 10, p.textY(0), "heading baseline: top + border + ascent"),
                () -> assertEquals(2.0, cf.hud().zoom, "HUD zoom from the step"));
        }

        @Test
        @DisplayName("the talk line width is limited to the view's talk window")
        void talkWidth() {
            AppFixture f = new AppFixture(true).players();
            ClientFrame cf = frame(f);
            cf.step(T0, 200, 600, 1, M, 256, 100);
            cf.key(GLFW_KEY_M, GLFW_PRESS, 0, T0);
            for (int i = 0; i < 40; i++) {
                cf.character('x');
            }
            // window 200 - 2*50 = 100 wide; limit 100 - 6 - 5 = 89 px = 13 chars of 7 px
            assertEquals(13, cf.talk().text().length(), "typing stops at the talk window width");
        }

        @Test
        @DisplayName("elapsed time ages messages: none on the first step, dt * fps after")
        void ageing() {
            AppFixture f = new AppFixture(true).players();
            ClientFrame cf = frame(f);
            Msg.Text t = new Msg.Text();
            t.kind = Msg.Text.KIND_SERVER;
            t.text.append("hello");
            f.rel.clear();
            t.write(f.rel);
            f.rel.flip();
            f.game.onReliable(com.fulcrologic.jxpilot.common.net.ReliableType.MESSAGE, f.rel);
            cf.step(T0, 800, 600, 1, M, 256, 100);
            double life0 = f.game.messages().game(0).life();
            cf.step(T0 + 500_000_000L, 800, 600, 1, M, 256, 100);
            double life1 = f.game.messages().game(0).life();
            assertAll(
                () -> assertEquals(life0 - 7.0, life1, 1e-9, "0.5 s at 14 fps = 7 frames"),
                () -> assertEquals(7.0, cf.hud().loops() + 0.0, "HUD loops advanced 7 frames"));
        }
    }

    @Nested
    @DisplayName("keys and lifecycle")
    class Keys {
        @Test
        @DisplayName("a submitted talk line is sent as chat")
        void chat() {
            ClientFrame cf = frame(new AppFixture(true).players());
            cf.key(GLFW_KEY_M, GLFW_PRESS, 0, T0);
            cf.character('m');
            cf.character('h');
            cf.character('i');
            cf.key(GLFW_KEY_ENTER, GLFW_PRESS, 0, T0);
            assertEquals(List.of("chat:hi"), conn.calls, "one chat with the typed text");
        }

        @Test
        @DisplayName("Escape requests quit; the swap key is reported once")
        void quitAndSwap() {
            ClientFrame cf = new ClientFrame(new AppFixture(true).game, conn, new InputState(KeyRouter.bindings("F12")),
                new SceneData(), new RadarBlips(4));
            boolean before = cf.quitRequested();
            cf.key(GLFW_KEY_F12, GLFW_PRESS, 0, T0);
            boolean swap1 = cf.takeSwapScale();
            boolean swap2 = cf.takeSwapScale();
            boolean noSound = cf.takeToggleSound();
            cf.key(GLFW_KEY_0, GLFW_PRESS, 0, T0);
            boolean sound1 = cf.takeToggleSound();
            boolean sound2 = cf.takeToggleSound();
            cf.key(GLFW_KEY_ESCAPE, GLFW_PRESS, 0, T0);
            assertAll(
                () -> assertFalse(before, "no quit at start"),
                () -> assertTrue(swap1, "swap reported"),
                () -> assertFalse(swap2, "only once"),
                () -> assertFalse(noSound, "the swap key is no sound toggle"),
                () -> assertTrue(sound1, "the sound key is reported"),
                () -> assertFalse(sound2, "once"),
                () -> assertTrue(cf.quitRequested(), "Escape: quit"));
        }

        @Test
        @DisplayName("disconnect is sent unless the connection is already closed; focus loss releases keys")
        void disconnectAndFocus() {
            ClientFrame cf = frame(new AppFixture(true).players());
            cf.key(GLFW_KEY_RIGHT_SHIFT, GLFW_PRESS, 0, T0);
            cf.focusLost();
            long keys = input.keys();
            cf.disconnect(T0);
            cf.disconnect(T0);
            assertAll(
                () -> assertEquals(0L, keys & ControlKey.THRUST.mask(), "focus loss released thrust"),
                () -> assertEquals(List.of("disconnect"), conn.calls, "one DISCONNECT, none when closed"));
        }
    }

    @Test
    @DisplayName("a game key is no chat, quit or swap; the panel is rebuilt, not appended, each step")
    void noSpuriousEvents() {
        AppFixture f = new AppFixture(true).players();
        ClientFrame cf = frame(f);
        cf.key(GLFW_KEY_RIGHT_SHIFT, GLFW_PRESS, 0, T0);
        cf.step(T0, 800, 600, 1, M, 256, 100);
        int panel = cf.panelHud().textCount();
        cf.step(T0 + 1000, 800, 600, 1, M, 256, 100);
        assertAll(
            () -> assertFalse(conn.calls.stream().anyMatch(c -> c.startsWith("chat")), "no chat sent"),
            () -> assertFalse(cf.quitRequested(), "no quit"),
            () -> assertFalse(cf.takeSwapScale(), "no swap"),
            () -> assertEquals(panel, cf.panelHud().textCount(), "same score list size after another step"),
            () -> assertSame(f.game, cf.game(), "game accessor"),
            () -> assertSame(conn, cf.connection(), "connection accessor"),
            () -> assertSame(input, cf.input(), "input accessor"));
    }
}
