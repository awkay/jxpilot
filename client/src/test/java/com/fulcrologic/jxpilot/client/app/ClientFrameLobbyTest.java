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

import com.fulcrologic.jxpilot.client.game.hud.HudFrame;
import com.fulcrologic.jxpilot.client.game.hud.TextMetrics;
import com.fulcrologic.jxpilot.client.game.input.InputState;
import com.fulcrologic.jxpilot.client.net.LobbyClient;
import com.fulcrologic.jxpilot.client.net.LobbyWire;
import com.fulcrologic.jxpilot.client.paint.SceneData;
import com.fulcrologic.jxpilot.common.lobby.LobbyEntry;
import com.fulcrologic.jxpilot.common.net.LobbyMsg;
import com.fulcrologic.jxpilot.common.net.PacketHeader;
import com.fulcrologic.jxpilot.common.net.PacketType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.util.List;

import static com.fulcrologic.jxpilot.client.app.AppFixture.T0;
import static com.fulcrologic.jxpilot.client.net.LobbyWire.entry;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_END;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_HOME;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_DOWN;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_UP;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_SHIFT;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_UP;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;
import static org.lwjgl.glfw.GLFW.GLFW_RELEASE;
import static org.lwjgl.glfw.GLFW.GLFW_REPEAT;

/** The overlay's key handling in {@link ClientFrame}: what the keys of a player in the lobby do. */
@DisplayName("ClientFrame in the lobby")
class ClientFrameLobbyTest {
    record Mono() implements TextMetrics {
        public int ascent() { return 10; }

        public int descent() { return 3; }

        public int width(CharSequence s, int len) { return 7 * len; }
    }

    static final Mono M = new Mono();
    static final long COOKIE = 77;
    static final int NONCE = 5;

    final FakeConnection conn = new FakeConnection();
    final InputState input = new InputState(KeyRouter.bindings(null));
    final LobbyWire wire = new LobbyWire();
    final LobbyClient lobby = new LobbyClient(wire);
    final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
    ClientFrame frame;
    long now = T0;

    @BeforeEach
    void setUp() {
        AppFixture f = new AppFixture(true).players();
        frame = new ClientFrame(f.game, conn, input, new SceneData(8, 16, 64, 8), new RadarBlips(8));
        frame.setErrorStream(new PrintStream(errBytes));
    }

    void deliver(byte[] datagram) {
        ByteBuffer b = ByteBuffer.wrap(datagram);
        PacketHeader h = new PacketHeader();
        h.read(b);
        lobby.onPacket(h.type(), b, now);
    }

    /** The lobby has greeted and listed these maps (sorted by the client: active first, then by name). */
    void inLobby(LobbyEntry... maps) {
        lobby.begin(now, NONCE);
        deliver(LobbyWire.info(NONCE, COOKIE, "friends"));
        for (byte[] p : LobbyWire.list(1, List.of(maps), LobbyMsg.MAX_PAGE_BODY)) {
            deliver(p);
        }
        wire.sent.clear();
        conn.lobby = lobby;
        step();
    }

    void step() {
        now += 16_000_000L;
        frame.step(now, 800, 600, 1, M, 256, 100);
    }

    List<byte[]> selects() {
        return wire.sentOf(PacketType.LOBBY_SELECT);
    }

    int selectedId() {
        return LobbyWire.select(selects().get(selects().size() - 1)).mapId;
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
    @DisplayName("showing the overlay")
    class Showing {
        @Test
        @DisplayName("while the connection is in the lobby the help overlay holds the map list; it goes when the lobby ends")
        void showsAndHides() {
            inLobby(entry(0, "alpha", false, 0), entry(1, "beta", true, 2));
            boolean active = frame.lobbyActive();
            boolean title = hasText(frame.helpHud(), "friends: choose a map");
            boolean beta = hasText(frame.helpHud(), "beta");
            conn.lobby = null;
            step();
            assertAll(
                () -> assertTrue(active, "lobby overlay active"),
                () -> assertTrue(title, "the lobby's name is the heading"),
                () -> assertTrue(beta, "the maps are listed"),
                () -> assertFalse(frame.lobbyActive(), "not active after the lobby ended"),
                () -> assertFalse(hasText(frame.helpHud(), "beta"), "the list is gone"));
        }

        @Test
        @DisplayName("the panel shows the active map first and the list's version bumps are picked up")
        void sortedFromClient() {
            inLobby(entry(0, "alpha", false, 0), entry(1, "beta", true, 2));
            assertAll(
                () -> assertEquals("beta", frame.lobbyPanel().current().name(), "active beta is row 0"),
                () -> assertEquals(2, frame.lobbyPanel().rows(), "two rows"));
        }

        @Test
        @DisplayName("without a lobby the overlay is not active and the key help works as before")
        void notInLobby() {
            step();
            assertFalse(frame.lobbyActive(), "not active");
        }
    }

    @Nested
    @DisplayName("keys")
    class Keys {
        @Test
        @DisplayName("Up and Down move the cursor (also on key repeat), Home and End jump, PageUp/PageDown page; release does nothing")
        void navigation() {
            inLobby(entry(0, "a", false, 0), entry(1, "b", false, 0), entry(2, "c", false, 0), entry(3, "d", false, 0));
            frame.key(GLFW_KEY_DOWN, GLFW_PRESS, 0, now);
            frame.key(GLFW_KEY_DOWN, GLFW_REPEAT, 0, now);
            int two = frame.lobbyPanel().cursor();
            frame.key(GLFW_KEY_UP, GLFW_PRESS, 0, now);
            int one = frame.lobbyPanel().cursor();
            frame.key(GLFW_KEY_UP, GLFW_RELEASE, 0, now);
            int stillOne = frame.lobbyPanel().cursor();
            frame.key(GLFW_KEY_END, GLFW_PRESS, 0, now);
            int end = frame.lobbyPanel().cursor();
            frame.key(GLFW_KEY_HOME, GLFW_PRESS, 0, now);
            int home = frame.lobbyPanel().cursor();
            frame.key(GLFW_KEY_PAGE_DOWN, GLFW_PRESS, 0, now);
            int page = frame.lobbyPanel().cursor();
            frame.key(GLFW_KEY_PAGE_UP, GLFW_PRESS, 0, now);
            assertAll(
                () -> assertEquals(2, two, "press and repeat of Down: two rows"),
                () -> assertEquals(1, one, "Up"),
                () -> assertEquals(1, stillOne, "a release is not a move"),
                () -> assertEquals(3, end, "End"),
                () -> assertEquals(0, home, "Home"),
                () -> assertEquals(3, page, "PageDown goes down a page (clamped to the last row)"),
                () -> assertEquals(0, frame.lobbyPanel().cursor(), "PageUp goes back up"));
        }

        @Test
        @DisplayName("Enter chooses the map under the cursor: SELECT goes out with that map's id")
        void enter() {
            inLobby(entry(10, "alpha", false, 0), entry(11, "beta", false, 0), entry(12, "gamma", false, 0));
            frame.key(GLFW_KEY_DOWN, GLFW_PRESS, 0, now);
            frame.key(GLFW_KEY_ENTER, GLFW_PRESS, 0, now);
            assertAll(
                () -> assertEquals(1, selects().size(), "one SELECT"),
                () -> assertEquals(11, selectedId(), "for beta, the second row"),
                () -> assertEquals(COOKIE, LobbyWire.select(selects().get(0)).cookie, "with the lobby's cookie"));
        }

        @Test
        @DisplayName("keypad Enter chooses too; holding Enter (repeat) and releasing it choose nothing more")
        void keypadEnterAndRepeat() {
            inLobby(entry(10, "alpha", false, 0), entry(11, "beta", false, 0));
            frame.key(GLFW_KEY_KP_ENTER, GLFW_REPEAT, 0, now);
            frame.key(GLFW_KEY_KP_ENTER, GLFW_RELEASE, 0, now);
            int none = selects().size();
            frame.key(GLFW_KEY_KP_ENTER, GLFW_PRESS, 0, now);
            assertAll(
                () -> assertEquals(0, none, "repeat and release choose nothing"),
                () -> assertEquals(1, selects().size(), "the press chooses"));
        }

        @Test
        @DisplayName("a shortcut character chooses its row at once: '0' the first map, '1' the second")
        void shortcuts() {
            inLobby(entry(100, "m00", false, 0), entry(101, "m01", false, 0), entry(102, "m02", false, 0));
            frame.character('1');
            assertAll(
                () -> assertEquals(1, selects().size(), "one SELECT"),
                () -> assertEquals(101, selectedId(), "'1' is the second row"),
                () -> assertEquals(1, frame.lobbyPanel().cursor(), "and the cursor is on it"));
        }

        @Test
        @DisplayName("a character that is no shortcut, or past the end of the list, chooses nothing")
        void noShortcut() {
            inLobby(entry(0, "a", false, 0), entry(1, "b", false, 0));
            frame.character('z');
            frame.character('?');
            frame.character(' ');
            assertEquals(0, selects().size(), "nothing was chosen");
        }

        @Test
        @DisplayName("shortcut 'a' of a long list chooses its row (the 11th)")
        void letterShortcut() {
            LobbyEntry[] maps = new LobbyEntry[15];
            for (int i = 0; i < maps.length; i++) {
                maps[i] = entry(200 + i, String.format("m%02d", i), false, 0);
            }
            inLobby(maps);
            frame.character('a');
            assertEquals(210, selectedId(), "row 10");
        }

        @Test
        @DisplayName("Escape quits from the lobby, and the game keys are not live there")
        void escapeAndGameKeys() {
            inLobby(entry(0, "a", false, 0));
            long idle = input.keys();
            frame.key(GLFW_KEY_RIGHT_SHIFT, GLFW_PRESS, 0, now);
            long held = input.keys();
            boolean quitBefore = frame.quitRequested();
            frame.key(GLFW_KEY_ESCAPE, GLFW_PRESS, 0, now);
            assertAll(
                () -> assertEquals(idle, held, "thrust is not pressed by a key in the lobby"),
                () -> assertFalse(quitBefore, "no quit yet"),
                () -> assertTrue(frame.quitRequested(), "Escape quits"));
        }

        @Test
        @DisplayName("outside the lobby Escape is an ordinary game key and characters go to the game, not the lobby")
        void outsideLobby() {
            step();
            frame.key(GLFW_KEY_ESCAPE, GLFW_PRESS, 0, now);
            frame.character('1');
            assertAll(
                () -> assertFalse(frame.quitRequested(), "Escape does not quit during play (C's keySwapSettings)"),
                () -> assertEquals(0, selects().size(), "no lobby choice"));
        }

        @Test
        @DisplayName("a choice made before the greeting is answered does nothing")
        void beforeGreeting() {
            lobby.begin(now, NONCE);
            conn.lobby = lobby;
            step();
            frame.key(GLFW_KEY_ENTER, GLFW_PRESS, 0, now);
            frame.character('0');
            assertEquals(0, selects().size(), "no SELECT without a cookie");
        }
    }

    @Nested
    @DisplayName("--lobby-map")
    class Pick {
        @Test
        @DisplayName("the named map (any case) is chosen by itself once the list is there, and only once")
        void picks() {
            frame.setLobbyPick("BETA");
            conn.lobby = lobby;
            lobby.begin(now, NONCE);
            step();
            int beforeList = selects().size();
            deliver(LobbyWire.info(NONCE, COOKIE, "friends"));
            for (byte[] p : LobbyWire.list(1, List.of(entry(3, "alpha", false, 0), entry(4, "beta", false, 0)), 1000)) {
                deliver(p);
            }
            wire.sent.clear();
            step();
            int chosen = selects().size();
            int id = selectedId();
            step();
            step();
            assertAll(
                () -> assertEquals(0, beforeList, "nothing chosen before the list"),
                () -> assertEquals(1, chosen, "chosen at the first frame with the list"),
                () -> assertEquals(4, id, "beta's id"),
                () -> assertEquals(1, selects().size(), "not chosen again"));
        }

        @Test
        @DisplayName("a name the lobby does not offer is reported to the error stream, once, and the player chooses")
        void unknown() {
            frame.setLobbyPick("nowhere");
            inLobby(entry(0, "alpha", false, 0));
            step();
            step();
            String err = errBytes.toString();
            assertAll(
                () -> assertEquals(0, selects().size(), "nothing chosen"),
                () -> assertTrue(err.contains("no map named 'nowhere'"), "reported: " + err),
                () -> assertEquals(1, err.split("\n").length, "once"));
        }
    }
}
