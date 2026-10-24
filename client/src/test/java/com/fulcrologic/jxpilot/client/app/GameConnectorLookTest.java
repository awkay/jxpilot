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

import com.fulcrologic.jxpilot.client.game.options.ClientOption;
import com.fulcrologic.jxpilot.client.game.options.ClientOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("GameConnector: debris colours from sparkColors")
class GameConnectorLookTest {
    @Test
    @DisplayName("the XPilot login asks for as many debris colours as sparkColors names")
    void debrisColors() {
        ClientOptions o = new ClientOptions();
        int def = GameConnector.debrisColors(o);
        o.apply(ClientOption.SPARK_COLORS, "1,2");
        int two = GameConnector.debrisColors(o);
        o.apply(ClientOption.SPARK_COLORS, "5 6");
        o.apply(ClientOption.MAX_COLORS, "4");
        int four = GameConnector.debrisColors(o);
        assertAll(
            () -> assertEquals(4, def, "C's default 5,6,7,3"),
            () -> assertEquals(2, two, "two colours"),
            () -> assertEquals(1, four, "with 4 colours 5 and 6 are dropped: the red default"),
            () -> assertEquals(2, GameConnector.xpLogin("A", 1, "", false, "u", 2).debrisColors, "the login carries it"),
            () -> assertEquals(4, GameConnector.xpLogin("A", 1, "", false, "u").debrisColors, "default login: 4"));
    }
}
