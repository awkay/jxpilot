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

import com.fulcrologic.jxpilot.client.game.hud.Hud;
import com.fulcrologic.jxpilot.client.game.hud.TalkLine;
import com.fulcrologic.jxpilot.client.game.input.ClientAction;
import com.fulcrologic.jxpilot.client.game.input.InputState;
import com.fulcrologic.jxpilot.client.game.input.KeyConfig;
import com.fulcrologic.jxpilot.client.game.input.PlayResources;
import com.fulcrologic.jxpilot.client.paint.SceneData;
import com.fulcrologic.jxpilot.common.net.ControlKey;
import com.fulcrologic.jxpilot.common.net.Msg;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static com.fulcrologic.jxpilot.client.app.AppFixture.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;

/** The client-side play keys and pointer control (C xevent.c), alone and through ClientFrame. */
class PlayActionsTest {
    final FakeConnection conn = new FakeConnection();
    final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
    final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
    final PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
    final PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);
    final Hud hud = new Hud();
    final HudExtras extras = new HudExtras();
    final PlayResources res = new PlayResources();

    PlayActions actions(AppFixture f) {
        return new PlayActions(f.game, conn, hud, extras, res, out, err, 7);
    }

    List<String> chats() {
        return conn.calls.stream().filter(c -> c.startsWith("chat:")).toList();
    }

    @Nested
    @DisplayName("handle")
    class Handle {
        @Test
        @DisplayName("each toggle flips its flag; print writes the messages; pointer control toggles")
        void toggles() {
            AppFixture f = new AppFixture(true).players();
            PlayActions a = actions(f);
            f.game.messages().add(Msg.Text.KIND_GAME, "Me was killed.");
            a.handle(KeyRouter.ID_MODE | KeyRouter.TOGGLE_ITEMS | KeyRouter.TOGGLE_MESSAGES | KeyRouter.PRINT_MESSAGES
                | KeyRouter.POINTER_CONTROL, 0, T0);
            boolean id = f.game.scoreboard().idMode();
            boolean items = extras.showItems;
            boolean msgs = hud.showMessages;
            boolean pointer = a.pointer().on();
            a.handle(KeyRouter.ID_MODE | KeyRouter.TOGGLE_ITEMS | KeyRouter.TOGGLE_MESSAGES | KeyRouter.POINTER_CONTROL,
                0, T0);
            assertAll(
                () -> assertTrue(id, "ID mode on"),
                () -> assertFalse(items, "showItems off"),
                () -> assertFalse(msgs, "showMessages off"),
                () -> assertTrue(pointer, "pointer control on"),
                () -> assertEquals("[talk messages]\n[server messages]\n  Me was killed.\n\n",
                    outBytes.toString(StandardCharsets.UTF_8), "C Print_messages_to_stdout"),
                () -> assertFalse(f.game.scoreboard().idMode(), "ID mode off again"),
                () -> assertTrue(extras.showItems && hud.showMessages, "both back on"),
                () -> assertFalse(a.pointer().on(), "pointer control off again"),
                () -> assertEquals(List.of(), chats(), "nothing sent"));
        }

        @Test
        @DisplayName("each pressed macro is expanded and sent in number order; empty macros send nothing")
        void macros() {
            AppFixture f = new AppFixture(true).players();
            PlayActions a = actions(f);
            res.talkMacros[1] = "I am #sn";
            res.talkMacros[4] = "five";
            a.handle(KeyRouter.TALK_MACRO, 1 << 4 | 1 << 1 | 1 << 2, T0);
            assertEquals(List.of("chat:I am Me", "chat:five"), chats(), "msg2 then msg5; msg3 is empty");
        }

        @Test
        @DisplayName("the macro context: no lock without an own ship; limited lives from the options; a missing file is null")
        void context() {
            AppFixture f = new AppFixture(true).players();
            PlayActions a = actions(f);
            a.error("Couldn't open file x");
            assertAll(
                () -> assertEquals(-1, a.lockSlot(), "no lock"),
                () -> assertEquals(f.game.players().slotOf(SELF), a.selfSlot(), "the own player"),
                () -> assertTrue(a.limitedLives(), "the fixture's limitedLives: yes"),
                () -> assertFalse(new PlayActions(new com.fulcrologic.jxpilot.client.game.ClientGame(), conn, hud, extras,
                    res, out, err, 1).limitedLives(), "no options yet: no limited lives"),
                () -> assertNull(a.readFile("/nonexistent/jxpilot/macro"), "unreadable"),
                () -> assertEquals(System.getenv("HOME"), a.home(), "$HOME"),
                () -> assertTrue(a.random(3) >= 0 && a.random(3) < 3, "in range"),
                () -> assertEquals("Couldn't open file x\n", errBytes.toString(StandardCharsets.UTF_8), "errors on stderr"));
        }

        @Test
        @DisplayName("messagesToStdout comes from the resource")
        void stdout() {
            res.messagesToStdout = 2;
            AppFixture f = new AppFixture(true).players();
            actions(f);
            f.game.messages().add(Msg.Text.KIND_GAME, "hello");
            assertEquals("hello\n", outBytes.toString(StandardCharsets.UTF_8), "printed as it arrives");
        }
    }

    @Nested
    @DisplayName("pointer")
    class Pointer {
        @Test
        @DisplayName("X buttons: left 1, middle 2, right 3; others none")
        void xButtons() {
            assertAll(
                () -> assertEquals(1, PlayActions.xButton(GLFW_MOUSE_BUTTON_LEFT), "left"),
                () -> assertEquals(2, PlayActions.xButton(GLFW_MOUSE_BUTTON_MIDDLE), "middle"),
                () -> assertEquals(3, PlayActions.xButton(GLFW_MOUSE_BUTTON_RIGHT), "right"),
                () -> assertEquals(0, PlayActions.xButton(GLFW_MOUSE_BUTTON_4), "back: none"));
        }

        @Test
        @DisplayName("buttons act only with pointer control on and not talking, for buttons 1..5")
        void buttons() {
            AppFixture f = new AppFixture(true).players();
            PlayActions a = actions(f);
            InputState input = new InputState(KeyRouter.bindings(null));
            KeyRouter router = new KeyRouter(input, new TalkLine());
            a.button(router, 1, true);
            long off = input.keys();
            a.handle(KeyRouter.POINTER_CONTROL, 0, T0);
            a.button(router, 2, true);
            long thrust = input.keys();
            a.button(router, 2, false);
            a.button(router, 6, true);
            a.button(router, 0, true);
            long outside = input.keys();
            a.talk(true);
            boolean suspended = a.pointer().on();
            a.button(router, 2, true);
            long talking = input.keys();
            a.talk(false);
            assertAll(
                () -> assertEquals(0, off & ControlKey.FIRE_SHOT.mask(), "pointer control off: no fire"),
                () -> assertEquals(ControlKey.THRUST.mask(), thrust & ControlKey.THRUST.mask(), "button 2 thrusts"),
                () -> assertEquals(0, outside & ControlKey.THRUST.mask(), "released; 0 and 6 do nothing"),
                () -> assertFalse(suspended, "the talk line suspends pointer control"),
                () -> assertEquals(0, talking & ControlKey.THRUST.mask(), "no buttons while talking"),
                () -> assertTrue(a.pointer().on(), "back after the talk line"));
        }
    }

    @Nested
    @DisplayName("through ClientFrame")
    class Frame {
        ClientFrame frame(AppFixture f, String rc) {
            return new ClientFrame(f.game, conn, KeyConfig.load(rc, "rc", null), new SceneData(8, 16, 64, 8),
                new RadarBlips(8));
        }

        @Test
        @DisplayName("pointer movement goes out with the frame's INPUT; buttons and the wheel press their functions")
        void pointerSteering() {
            AppFixture f = new AppFixture(true).players();
            ClientFrame cf = frame(f, "pointerButton4: keyFireMissile\n");
            cf.step(T0, 800, 600, 1, ClientFrameTest.M, 256, 100);
            cf.key(GLFW_KEY_KP_ENTER, GLFW_PRESS, 0, T0);
            cf.cursor(420, 300);
            cf.mouseButton(GLFW_MOUSE_BUTTON_RIGHT, GLFW_PRESS, T0);
            cf.step(T0 + 1000, 800, 600, 1, ClientFrameTest.M, 256, 100);
            long shield = conn.lastKeys;
            cf.mouseButton(GLFW_MOUSE_BUTTON_RIGHT, GLFW_RELEASE, T0);
            cf.mouseButton(GLFW_MOUSE_BUTTON_RIGHT, GLFW_REPEAT, T0);
            cf.scroll(1.0, T0);
            cf.scroll(0, T0);
            cf.step(T0 + 2000, 800, 600, 1, ClientFrameTest.M, 256, 100);
            long wheel = conn.lastKeys;
            cf.step(T0 + 3000, 800, 600, 1, ClientFrameTest.M, 256, 100);
            assertAll(
                () -> assertEquals(List.of(0, 20, 0, 0), conn.aims, "the 20-pixel move once, with the next INPUT"),
                () -> assertEquals(ControlKey.SHIELD.mask(), shield & ControlKey.SHIELD.mask(), "button 3: shield"),
                () -> assertEquals(ControlKey.FIRE_MISSILE.mask(), wheel & ControlKey.FIRE_MISSILE.mask(),
                    "wheel up (button 4) taps its function"),
                () -> assertEquals(0, conn.lastKeys & ControlKey.FIRE_MISSILE.mask(), "released by the next frame"));
        }

        @Test
        @DisplayName("the talk key suspends pointer control; focus loss and gain too")
        void suspension() {
            AppFixture f = new AppFixture(true).players();
            ClientFrame cf = frame(f, "");
            cf.key(GLFW_KEY_KP_ENTER, GLFW_PRESS, 0, T0);
            cf.key(GLFW_KEY_M, GLFW_PRESS, 0, T0);
            boolean talking = cf.play().pointer().on();
            cf.key(GLFW_KEY_ESCAPE, GLFW_PRESS, 0, T0);
            boolean after = cf.play().pointer().on();
            cf.focusLost();
            boolean unfocused = cf.play().pointer().on();
            cf.focusGained();
            assertAll(
                () -> assertFalse(talking, "off while the talk line is open"),
                () -> assertTrue(after, "on when it closes"),
                () -> assertFalse(unfocused, "off without focus"),
                () -> assertTrue(cf.play().pointer().on(), "on with focus"));
        }

        @Test
        @DisplayName("a macro key sends its macro")
        void macroKey() {
            AppFixture f = new AppFixture(true).players();
            ClientFrame cf = frame(f, "msg2: gg #sn\n");
            cf.key(GLFW_KEY_F2, GLFW_PRESS, 0, T0);
            assertEquals(List.of("chat:gg Me"), chats(), "F2 = keySendMsg2");
        }
    }
}
