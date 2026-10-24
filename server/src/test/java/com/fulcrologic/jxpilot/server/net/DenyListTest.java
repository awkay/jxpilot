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
import java.nio.ByteBuffer;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** C denyHosts (contact.c Set_deny_hosts, Check_address) and serverHost (server.c:163-171). */
class DenyListTest {

    private static boolean denies(String list, String ip) {
        return DenyList.parse(list).denied(Harness.addr(ip, 5));
    }

    @Test
    @DisplayName("parse: hosts, CIDRs and C's dotted masks, split on commas, semicolons and whitespace; junk is skipped")
    void parse() {
        String list = "10.0.0.1, 192.168.0.0/255.255.0.0;172.16.0.0/12\tfd00::1  fd01::/16";
        assertAll(
            () -> assertEquals(5, DenyList.parse(list).size(), "five networks"),
            () -> assertTrue(denies(list, "10.0.0.1") && !denies(list, "10.0.0.2"), "a single host"),
            () -> assertTrue(denies(list, "192.168.77.1") && !denies(list, "192.169.0.1"), "dotted mask /16"),
            () -> assertTrue(denies(list, "172.31.255.255") && !denies(list, "172.32.0.0"), "CIDR /12"),
            () -> assertTrue(denies(list, "fd00::1") && !denies(list, "fd00::2"), "an IPv6 host"),
            () -> assertTrue(denies(list, "fd01:ffff::9") && !denies(list, "fd02::1"), "an IPv6 /16"),
            () -> assertEquals(0, DenyList.parse("example.com, 10.0.0.0/0.0.0.0, 10.0.0.0/255.0.255.0, 10.1/8,"
                + " ::1/255.0.0.0, 1.2.3.4/x, 10.0.0.0/0, ::/0").size(),
                "names, zero masks and /0 (C skips mask 0), a non-contiguous mask, bad literals, a dotted mask on IPv6"),
            () -> assertEquals(0, DenyList.parse("").size(), "empty"),
            () -> assertEquals(0, DenyList.parse(null).size(), "unset"),
            () -> assertEquals(0, DenyList.NONE.size(), "NONE"));
    }

    @Test
    @DisplayName("denied: inside any network; other addresses, families and unresolved sources pass")
    void denied() {
        DenyList list = DenyList.parse("10.1.0.0/16 192.0.2.7");
        assertAll(
            () -> assertTrue(list.denied(Harness.addr("10.1.200.3", 5)), "in the /16"),
            () -> assertTrue(list.denied(Harness.addr("192.0.2.7", 5)), "the host"),
            () -> assertFalse(list.denied(Harness.addr("192.0.2.8", 5)), "next host"),
            () -> assertFalse(list.denied(Harness.addr("::1", 5)), "IPv6"),
            () -> assertFalse(list.denied(InetSocketAddress.createUnresolved("x", 5)), "unresolved"),
            () -> assertFalse(DenyList.NONE.denied(Harness.addr("10.1.0.1", 5)), "empty list"));
    }

    @Test
    @DisplayName("IPv6 prefixes that end inside a byte compare only the prefix bits; v4 and v6 networks mix")
    void ipv6Prefixes() {
        String list = "2001:db8::/33, 10.0.0.0/8, fe80::/10";
        assertAll(
            () -> assertTrue(denies(list, "2001:db8:7fff::1"), "bit 33 clear: inside the /33"),
            () -> assertFalse(denies(list, "2001:db8:8000::1"), "bit 33 set: outside"),
            () -> assertTrue(denies(list, "febf::1") && !denies(list, "fec0::1"), "the /10 ends two bits into byte 1"),
            () -> assertFalse(denies(list, "2001:db9::1"), "outside the full bytes"),
            () -> assertTrue(denies(list, "10.200.0.1"), "the IPv4 network still applies"));
    }

    @Test
    @DisplayName("an IPv4 check allocates nothing")
    void allocationFree() {
        DenyList list = DenyList.parse("10.1.0.0/16, 192.0.2.7, fd00::/8");
        InetSocketAddress from = Harness.addr("198.51.100.1", 4000);
        com.sun.management.ThreadMXBean mx =
            (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        boolean sink = false;
        for (int i = 0; i < 20_000; i++) {
            sink ^= list.denied(from);
        }
        long before = mx.getCurrentThreadAllocatedBytes();
        for (int i = 0; i < 1000; i++) {
            sink ^= list.denied(from);
        }
        long bytes = mx.getCurrentThreadAllocatedBytes() - before;
        assertEquals(0, bytes, "0 bytes over 1000 checks (sink " + sink + ")");
    }

    @Test
    @DisplayName("ServerNet ignores a denied source before anything else: no REJECT VERSION for an old CONNECT")
    void beforeVersionCheck() {
        ServerNetConfig cfg = new ServerNetConfig();
        cfg.denyHosts = DenyList.parse("10.9.9.9");
        Harness h = new Harness(cfg, "mapName: test\n");
        InetSocketAddress bad = Harness.addr("10.9.9.9", 4000);
        InetSocketAddress good = Harness.addr("10.9.9.10", 4000);
        ByteBuffer old = new TestClient(0x01020304).connect("Bad", 255, "");
        old.put(2, (byte) 2);
        ByteBuffer old2 = new TestClient(0x01020305).connect("Good", 255, "");
        old2.put(2, (byte) 2);
        h.in(0, bad, old);
        h.in(0, good, old2);
        assertAll(
            () -> assertEquals(List.of(), h.take(bad), "no reply to the denied source"),
            () -> assertEquals(1, h.take(good).size(), "others still get REJECT VERSION"),
            () -> assertEquals(1, h.net.denied(), "counted"));
    }

    @Test
    @DisplayName("denyHosts set at runtime (/set denyHosts) parses like the option: /0 and zero masks are skipped")
    void runtime() {
        Harness h = new Harness(new ServerNetConfig(), "mapName: test\n");
        InetSocketAddress bad = Harness.addr("10.9.9.9", 4000);
        InetSocketAddress other = Harness.addr("192.0.2.1", 4000);
        h.net.denyHosts("0.0.0.0/0, ::/0, 0.0.0.0/0.0.0.0, 10.9.9.9");
        h.in(0, bad, new TestClient(1).connect("Bad", 255, ""));
        h.in(0, other, new TestClient(2).connect("Other", 255, ""));
        long deniedAfterSet = h.net.denied();
        boolean otherJoined = h.isConnected(0, other);
        int listed = h.cfg.denyHosts.size();
        h.net.denyHosts("");
        InetSocketAddress bad2 = Harness.addr("10.9.9.9", 4001);
        h.in(0, bad2, new TestClient(3).connect("Back", 255, ""));
        assertAll(
            () -> assertEquals(1, deniedAfterSet, "only the listed host is denied: /0 does not deny everyone"),
            () -> assertTrue(otherJoined, "an address outside the list joins"),
            () -> assertEquals(1, listed, "the list holds only the host"),
            () -> assertEquals(0, h.cfg.denyHosts.size(), "an empty value clears the list"),
            () -> assertTrue(h.isConnected(0, bad2), "a cleared list lets the host in"));
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
        assertEquals(1, c.denyHosts.size(), "one network");
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
