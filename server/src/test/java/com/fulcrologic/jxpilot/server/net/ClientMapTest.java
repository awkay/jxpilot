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
package com.fulcrologic.jxpilot.server.net;

import com.fulcrologic.jxpilot.common.config.ConfigParser;
import com.fulcrologic.jxpilot.common.config.Opt;
import com.fulcrologic.jxpilot.common.config.OptionOrigin;
import com.fulcrologic.jxpilot.common.config.Options;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The map clients get (netserver.c Packet_map, 225-262). */
class ClientMapTest {
    static final String MAP = "x+-><imkj@()%&zb_\n";

    private static Options opts(String text) {
        Options o = new Options();
        ConfigParser.parse(text, "t", OptionOrigin.COMMAND, o);
        return o;
    }

    @Test
    @DisplayName("all visible (the defaults): only friction becomes filled decor")
    void visible() {
        assertEquals("x+-><imkj@()%&bb_\n", ClientMap.of(MAP, new Options()), "z -> b, the rest unchanged");
    }

    @Test
    @DisplayName("each visibility option blanks its own blocks only")
    void hidden() {
        assertAll(
            () -> assertEquals("x        @()%&bb_\n", ClientMap.of(MAP, opts("gravityVisible: no\n")), "gravity"),
            () -> assertEquals("x+-><imkj   %&bb_\n", ClientMap.of(MAP, opts("wormholeVisible: no\n")), "wormholes"),
            () -> assertEquals("x+-><imkj@() &bb_\n", ClientMap.of(MAP, opts("itemConcentratorVisible: no\n")),
                "item concentrators"),
            () -> assertEquals("x+-><imkj@()% bb_\n", ClientMap.of(MAP, opts("asteroidConcentratorVisible: no\n")),
                "asteroid concentrators"),
            () -> assertEquals("x+-><imkj@()%& b_\n", ClientMap.of(MAP, opts("blockFrictionVisible: no\n")),
                "friction"),
            () -> assertNull(ClientMap.of(null, new Options()), "no map"));
    }

    @Test
    @DisplayName("the config text sent to clients carries the filtered map; the server's own options keep the original")
    void configText() {
        Options o = opts("gravityVisible: no\nmapData: \\multiline: EOF\nx+x\nEOF\n");
        String text = o.toConfigText(ClientMap.of(o.mapData(), o));
        Options back = new Options();
        ConfigParser.parse(text, "t", OptionOrigin.MAP, back);
        assertAll(
            () -> assertEquals("x x\n", back.mapData(), "clients see no gravity block"),
            () -> assertEquals("x+x\n", o.mapData(), "the server keeps it"),
            () -> assertTrue(text.contains("gravityVisible"), "other options are still sent"),
            () -> assertEquals(o.toConfigText(), o.toConfigText(o.mapData()), "the no-argument form sends mapData"),
            () -> assertEquals(false, back.getBool(Opt.GRAVITY_VISIBLE), "and the option itself"));
    }
}
