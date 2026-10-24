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
package com.fulcrologic.jxpilot.client.game;

import com.fulcrologic.jxpilot.common.net.ReliableType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The server's MOTD (C client about.c Handle_motd): assembled from MOTD pieces, handed out once. */
class MotdTest {
    private static ByteBuffer piece(int off, int total, String text) {
        byte[] b = text.getBytes(StandardCharsets.ISO_8859_1);
        return ByteBuffer.allocate(8 + b.length).putInt(off).putInt(total).put(b).flip();
    }

    @Test
    @DisplayName("nothing until every piece is in; then the text once")
    void assembled() {
        ClientGame g = new ClientGame();
        String none = g.takeMotd();
        g.onReliable(ReliableType.MOTD, piece(0, 11, "Hello "));
        String partial = g.takeMotd();
        g.onReliable(ReliableType.MOTD, piece(6, 11, "world"));
        String done = g.takeMotd();
        String again = g.takeMotd();
        assertAll(
            () -> assertNull(none, "no MOTD"),
            () -> assertNull(partial, "6 of 11 bytes"),
            () -> assertEquals("Hello world", done, "all 11"),
            () -> assertNull(again, "handed out once"));
    }

    @Test
    @DisplayName("a piece that does not fit is ignored")
    void bad() {
        ClientGame g = new ClientGame();
        g.onReliable(ReliableType.MOTD, piece(5, 3, "xx"));
        assertNull(g.takeMotd(), "ignored");
    }
}
