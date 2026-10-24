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
import com.fulcrologic.jxpilot.common.config.OptionOrigin;
import com.fulcrologic.jxpilot.common.config.Options;
import com.fulcrologic.jxpilot.common.map.WorldMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** C denyHosts (contact.c Set_deny_hosts, Check_address) and serverHost (server.c:163-171). */
class DenyListTest {

    private static List<String> parsed(String text) {
        return Arrays.stream(DenyList.parse(text)).map(c -> c.network().getHostAddress() + "/" + c.prefix()).toList();
    }

    @Test
    @DisplayName("parse: hosts, CIDRs and C's dotted masks, split on commas, semicolons and whitespace; junk is skipped")
    void parse() {
        assertAll(
            () -> assertEquals(List.of("10.0.0.1/32", "192.168.0.0/16", "172.16.0.0/12", "fd00:0:0:0:0:0:0:1/128",
                "fd00:0:0:0:0:0:0:0/8"),
                parsed("10.0.0.1, 192.168.0.0/255.255.0.0;172.16.0.0/12\tfd00::1  fd00::/8"), "every form"),
            () -> assertEquals(List.of(), parsed("example.com, 10.0.0.0/0.0.0.0, 10.0.0.0/255.0.255.0, 10.1/8, ::1/255.0.0.0, 1.2.3.4/x"),
                "names, a zero mask, a non-contiguous mask, a bad literal, a dotted mask on IPv6, a bad prefix"),
            () -> assertEquals(List.of(), parsed(""), "empty"),
            () -> assertEquals(List.of(), parsed(null), "unset"));
    }

    @Test
    @DisplayName("denied: inside any network; other addresses and families pass")
    void denied() {
        Cidr[] list = DenyList.parse("10.1.0.0/16 192.0.2.7");
        assertAll(
            () -> assertTrue(DenyList.denied(list, Harness.addr("10.1.200.3", 5)), "in the /16"),
            () -> assertTrue(DenyList.denied(list, Harness.addr("192.0.2.7", 5)), "the host"),
            () -> assertFalse(DenyList.denied(list, Harness.addr("192.0.2.8", 5)), "next host"),
            () -> assertFalse(DenyList.denied(list, Harness.addr("::1", 5)), "IPv6"),
            () -> assertFalse(DenyList.denied(list, InetSocketAddress.createUnresolved("x", 5)), "unresolved"),
            () -> assertFalse(DenyList.denied(new Cidr[0], Harness.addr("10.1.0.1", 5)), "empty list"));
    }

    @Test
    @DisplayName("ServerNet ignores a CONNECT from a denied source (no reply, counted); others still join")
    void serverNet() {
        ServerNetConfig cfg = new ServerNetConfig();
        cfg.denyHosts = DenyList.parse("10.9.9.9");
        Harness h = new Harness(cfg, "mapName: test\n");
        InetSocketAddress bad = Harness.addr("10.9.9.9", 4000);
        InetSocketAddress good = Harness.addr("10.9.9.10", 4000);
        h.in(0, bad, new TestClient(1).connect("Bad", 255, ""));
        h.in(0, good, new TestClient(2).connect("Good", 255, ""));
        assertAll(
            () -> assertEquals(List.of(), h.take(bad), "nothing goes back to the denied source"),
            () -> assertFalse(h.isConnected(0, bad), "no session"),
            () -> assertEquals(1, h.net.denied(), "counted"),
            () -> assertTrue(h.isConnected(0, good), "the other source joins"));
    }

    @Test
    @DisplayName("ServerNetConfig.of reads denyHosts")
    void config() {
        Options o = new Options();
        ConfigParser.parse("denyHosts: 10.0.0.0/8\n", "t", OptionOrigin.DEFAULTS, o);
        ConfigParser.parse("mapData: \\multiline: EOF\n_ \nEOF\nmapWidth: 2\nmapHeight: 1\n", "t", OptionOrigin.MAP, o);
        ServerNetConfig c = ServerNetConfig.of(o, WorldMap.fromOptions(o));
        assertEquals(1, c.denyHosts.length, "one network");
    }

    @Test
    @DisplayName("bindList: bind when set, else serverHost, else bind's default")
    void bindList() {
        Options none = new Options();
        Options host = new Options();
        ConfigParser.parse("serverHost:  localhost \n", "t", OptionOrigin.DEFAULTS, host);
        Options both = new Options();
        ConfigParser.parse("serverHost: localhost\nbind: loopback,10.0.0.0/8\n", "t", OptionOrigin.DEFAULTS, both);
        Options blank = new Options();
        blank.force(com.fulcrologic.jxpilot.common.config.Opt.SERVER_HOST, " ");
        assertAll(
            () -> assertEquals(List.of("all"), ServerArgs.bindList(none), "default"),
            () -> assertEquals(List.of("localhost"), ServerArgs.bindList(host), "serverHost, trimmed"),
            () -> assertEquals(List.of("loopback", "10.0.0.0/8"), ServerArgs.bindList(both), "bind wins"),
            () -> assertEquals(List.of("all"), ServerArgs.bindList(blank), "a blank serverHost is ignored"));
    }
}
