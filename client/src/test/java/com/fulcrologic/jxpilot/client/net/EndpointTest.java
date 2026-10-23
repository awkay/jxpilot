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
package com.fulcrologic.jxpilot.client.net;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EndpointTest {
    @Test
    @DisplayName("parses host, host:port, [v6], [v6]:port and bare IPv6")
    void parse() {
        assertAll(
            () -> assertEquals(new Endpoint("example.org", 15345), Endpoint.parse("example.org", 15345), "default port"),
            () -> assertEquals(new Endpoint("10.0.0.1", 4000), Endpoint.parse("10.0.0.1:4000", 15345), "host:port"),
            () -> assertEquals(new Endpoint("::1", 15345), Endpoint.parse("[::1]", 15345), "bracketed v6"),
            () -> assertEquals(new Endpoint("fe80::1", 7), Endpoint.parse("[fe80::1]:7", 15345), "bracketed v6 with port"),
            () -> assertEquals(new Endpoint("::1", 15345), Endpoint.parse("::1", 15345), "bare v6 has no port"),
            () -> assertEquals(new Endpoint("h", 0), Endpoint.parse("h:0", 1), "port 0"),
            () -> assertEquals(new Endpoint("h", 65535), Endpoint.parse("h:65535", 1), "port 65535"));
    }

    @Test
    @DisplayName("rejects malformed addresses")
    void errors() {
        assertAll(
            () -> assertThrows(IllegalArgumentException.class, () -> Endpoint.parse("", 1), "empty"),
            () -> assertThrows(IllegalArgumentException.class, () -> Endpoint.parse(null, 1), "null"),
            () -> assertThrows(IllegalArgumentException.class, () -> Endpoint.parse("[::1", 1), "unclosed bracket"),
            () -> assertThrows(IllegalArgumentException.class, () -> Endpoint.parse("[::1]x", 1), "junk after ]"),
            () -> assertThrows(IllegalArgumentException.class, () -> Endpoint.parse("[]:5", 1), "empty v6 host"),
            () -> assertThrows(IllegalArgumentException.class, () -> Endpoint.parse(":5", 1), "empty host"),
            () -> assertThrows(IllegalArgumentException.class, () -> Endpoint.parse("h:x", 1), "non-numeric port"),
            () -> assertThrows(IllegalArgumentException.class, () -> Endpoint.parse("h:65536", 1), "port too big"),
            () -> assertThrows(IllegalArgumentException.class, () -> Endpoint.parse("h:-1", 1), "negative port"));
    }

    @Test
    @DisplayName("prints IPv6 in brackets")
    void print() {
        assertAll(
            () -> assertEquals("[::1]:5", new Endpoint("::1", 5).toString(), "v6"),
            () -> assertEquals("h:5", new Endpoint("h", 5).toString(), "v4/host"));
    }
}
