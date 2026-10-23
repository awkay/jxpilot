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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Server network binding (pure)")
class BindingTest {

    static InetAddress ip(String s) {
        return InetAddress.ofLiteral(s);
    }

    static BindTarget t(String ip, String iface) {
        return new BindTarget(ip(ip), iface);
    }

    /** A multi-homed macOS-like host: loopback, LAN, a corporate VPN on utun4, Tailscale, a down interface. */
    static final List<NetIf> HOST = List.of(
        new NetIf("lo0", true, true, List.of(ip("127.0.0.1"), ip("::1"), ip("fe80::1%1"))),
        new NetIf("en0", true, false, List.of(ip("fe80::aede:48ff:fe00:1122%2"), ip("192.168.1.20"),
            ip("2001:db8::20"))),
        new NetIf("utun4", true, false, List.of(ip("10.224.0.16"))),
        new NetIf("tailscale0", true, false, List.of(ip("100.101.1.2"))),
        new NetIf("en5", false, false, List.of(ip("172.16.0.9"))));

    static List<BindSelector> sel(String... s) {
        return BindSelector.parseAll(List.of(s));
    }

    @Nested
    @DisplayName("Cidr")
    class CidrTests {
        @Test
        @DisplayName("parses IPv4/IPv6 networks and matches prefixes bit-exactly")
        void parseAndContain() {
            Cidr v4 = Cidr.parse("10.224.0.0/22");
            Cidr odd = Cidr.parse("10.224.0.16/22");
            Cidr cgnat = Cidr.parse("100.64.0.0/10");
            Cidr v6 = Cidr.parse("2001:db8::/33");
            Cidr all4 = Cidr.parse("0.0.0.0/0");
            Cidr host = Cidr.parse("192.168.1.20/32");
            Cidr mapped = Cidr.parse("::ffff:10.0.0.0/104");
            assertAll(
                () -> assertTrue(v4.contains(ip("10.224.3.255")), "the last address of a /22 is inside"),
                () -> assertFalse(v4.contains(ip("10.224.4.0")), "the next /22 is outside"),
                () -> assertTrue(odd.contains(ip("10.224.0.1")), "host bits in the written network are ignored"),
                () -> assertTrue(cgnat.contains(ip("100.127.255.255")), "a partial-byte prefix covers its range"),
                () -> assertFalse(cgnat.contains(ip("100.128.0.0")), "a partial-byte prefix excludes the next bit"),
                () -> assertTrue(v6.contains(ip("2001:db8:7fff::1")), "IPv6 partial-byte prefix inside"),
                () -> assertFalse(v6.contains(ip("2001:db8:8000::1")), "IPv6 partial-byte prefix outside"),
                () -> assertTrue(all4.contains(ip("8.8.8.8")), "/0 contains every address of its family"),
                () -> assertFalse(all4.contains(ip("::1")), "a network never contains the other family"),
                () -> assertFalse(v6.contains(ip("10.224.0.1")), "an IPv6 network never contains IPv4"),
                () -> assertTrue(host.contains(ip("192.168.1.20")) && !host.contains(ip("192.168.1.21")),
                    "a /32 contains exactly one address"),
                () -> assertEquals(Cidr.parse("10.0.0.0/8"), mapped, "an IPv4-mapped network is the IPv4 network, prefix - 96"),
                () -> assertEquals("10.224.0.0/22", v4.toString(), "prints address/prefix"));
        }

        @Test
        @DisplayName("rejects malformed CIDRs without DNS")
        void rejects() {
            assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> Cidr.parse("10.0.0.0"), "a prefix is required"),
                () -> assertThrows(IllegalArgumentException.class, () -> Cidr.parse("example.com/8"), "the address must be a literal"),
                () -> assertThrows(IllegalArgumentException.class, () -> Cidr.parse("10.0.0.0/33"), "IPv4 prefixes stop at 32"),
                () -> assertThrows(IllegalArgumentException.class, () -> Cidr.parse("::/129"), "IPv6 prefixes stop at 128"),
                () -> assertThrows(IllegalArgumentException.class, () -> Cidr.parse("10.0.0.0/-1"), "negative prefixes are rejected"),
                () -> assertThrows(IllegalArgumentException.class, () -> Cidr.parse("10.0.0.0/x"), "the prefix must be a number"),
                () -> assertThrows(IllegalArgumentException.class, () -> Cidr.parse("10.0.0.0/+8"), "the prefix must be plain decimal"),
                () -> assertThrows(IllegalArgumentException.class, () -> Cidr.parse("::ffff:10.0.0.0/95"),
                    "a mapped prefix below 96 is rejected"),
                () -> assertEquals(128, Cidr.parse("::1/128").prefix(), "IPv6 accepts 128"),
                () -> assertEquals(32, Cidr.parse("1.2.3.4/32").prefix(), "IPv4 accepts 32"),
                () -> assertEquals(Cidr.parse("0.0.0.0/0"), Cidr.parse("::ffff:0.0.0.0/96"), "a mapped /96 is IPv4 /0"),
                () -> assertTrue(assertThrows(IllegalArgumentException.class, () -> Cidr.parse("/8")).getMessage()
                    .contains("does not start with an IP address"), "a missing address is reported as such"));
        }
    }

    @Nested
    @DisplayName("BindSelector.parse")
    class Parse {
        @Test
        @DisplayName("recognises every selector form")
        void forms() {
            assertAll(
                () -> assertEquals(new BindSelector.All(), BindSelector.parse(" ALL "), "'all' is a keyword, any case, trimmed"),
                () -> assertEquals(new BindSelector.Loopback(), BindSelector.parse("Loopback"), "'loopback' is a keyword"),
                () -> assertEquals(new BindSelector.Network(Cidr.parse("10.224.0.0/22")), BindSelector.parse("10.224.0.0/22"),
                    "a slash makes a CIDR"),
                () -> assertEquals(new BindSelector.Literal(ip("10.224.0.16")), BindSelector.parse("10.224.0.16"),
                    "a dotted quad is a literal"),
                () -> assertEquals(new BindSelector.Literal(ip("::1")), BindSelector.parse("::1"), "colons make an IPv6 literal"),
                () -> assertEquals(new BindSelector.Interface("utun4"), BindSelector.parse("if:utun4"), "if: forces an interface"),
                () -> assertEquals(new BindSelector.Interface("utun4"), BindSelector.parse("IF: utun4"), "the if: prefix is case-insensitive"),
                () -> assertEquals(new BindSelector.Host("vpn.example.com", List.of()), BindSelector.parse("host:vpn.example.com"),
                    "host: forces a host name"),
                () -> assertEquals(new BindSelector.Name("en0"), BindSelector.parse("en0"), "a bare word is a name decided later"),
                () -> assertEquals(new BindSelector.Name("eth0.100"), BindSelector.parse("eth0.100"),
                    "a dotted word that is not a quad is a name, not a literal"));
        }

        @Test
        @DisplayName("wildcards are literals flagged as wildcard")
        void wildcard() {
            BindSelector.Literal any4 = (BindSelector.Literal) BindSelector.parse("0.0.0.0");
            BindSelector.Literal any6 = (BindSelector.Literal) BindSelector.parse("::");
            BindSelector.Literal one = (BindSelector.Literal) BindSelector.parse("127.0.0.1");
            assertAll(
                () -> assertTrue(any4.wildcard(), "0.0.0.0 is a wildcard"),
                () -> assertTrue(any6.wildcard(), ":: is a wildcard"),
                () -> assertFalse(one.wildcard(), "a specific address is not"));
        }

        @Test
        @DisplayName("rejects bad text with a message")
        void errors() {
            assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> BindSelector.parse("  "), "empty is rejected"),
                () -> assertThrows(IllegalArgumentException.class, () -> BindSelector.parse("999.1.1.1"), "a bad quad is rejected"),
                () -> assertThrows(IllegalArgumentException.class, () -> BindSelector.parse("1:2:3:z::"), "a bad IPv6 literal is rejected"),
                () -> assertThrows(IllegalArgumentException.class, () -> BindSelector.parse("if:"), "if: needs a name"),
                () -> assertThrows(IllegalArgumentException.class, () -> BindSelector.parse("host: "), "host: needs a name"),
                () -> assertThrows(IllegalArgumentException.class, () -> BindSelector.parse("10.0.0.0/99"), "a bad CIDR is rejected"),
                () -> assertThrows(IllegalArgumentException.class, () -> BindSelector.parse("/8"),
                    "a leading slash still means CIDR (and is rejected)"));
        }

        @Test
        @DisplayName("parseAll splits comma lists, skips blanks, defaults to all")
        void parseAll() {
            assertAll(
                () -> assertEquals(List.of(new BindSelector.Loopback(), new BindSelector.Name("utun4"),
                        new BindSelector.Literal(ip("::1"))), sel("loopback, utun4", "", "::1,"),
                    "elements and comma parts are kept in order; blanks are skipped"),
                () -> assertEquals(List.of(new BindSelector.All()), BindSelector.parseAll(List.of()), "nothing means all"),
                () -> assertEquals(List.of(new BindSelector.All()), sel(" , "), "only blanks means all"));
        }

        @Test
        @DisplayName("parseLiteral never resolves names")
        void parseLiteral() {
            assertAll(
                () -> assertEquals(ip("10.1.2.3"), BindSelector.parseLiteral("10.1.2.3"), "dotted quad"),
                () -> assertEquals(ip("fe80::1%1"), BindSelector.parseLiteral("fe80::1%1"), "scoped IPv6"),
                () -> assertNull(BindSelector.parseLiteral("10"), "a short numeric form is not accepted as IPv4"),
                () -> assertNull(BindSelector.parseLiteral("localhost"), "names are not literals"),
                () -> assertNull(BindSelector.parseLiteral("::g"), "bad IPv6 is null"),
                () -> assertNull(BindSelector.parseLiteral("300.1.1.1"), "an out-of-range quad is null"));
        }

        @Test
        @DisplayName("selectors print in their written form")
        void printing() {
            assertEquals("[all, loopback, 10.0.0.1, 10.0.0.0/8, if:en0, host:h, en1]",
                sel("all", "loopback", "10.0.0.1", "10.0.0.0/8", "if:en0", "host:h", "en1").toString(),
                "each selector prints as the operator wrote it, with if:/host: made explicit");
        }
    }

    @Nested
    @DisplayName("BindPlan.resolveNames")
    class ResolveNames {
        final Resolver dns = h -> switch (h) {
            case "localhost" -> List.of(ip("127.0.0.1"), ip("::1"));
            case "vpn.example.com" -> List.of(ip("10.224.0.16"));
            default -> List.of();
        };

        @Test
        @DisplayName("names become interfaces, hosts, or pending interfaces")
        void names() {
            List<String> warnings = new ArrayList<>();
            List<BindSelector> out = BindPlan.resolveNames(
                sel("utun4", "localhost", "utun9", "host:vpn.example.com", "host:nope.invalid", "loopback"), HOST, dns, warnings);
            assertAll(
                () -> assertEquals(new BindSelector.Interface("utun4"), out.get(0), "a current interface name becomes an interface"),
                () -> assertEquals(new BindSelector.Host("localhost", List.of(ip("127.0.0.1"), ip("::1"))), out.get(1),
                    "a resolvable non-interface name becomes a host with its addresses"),
                () -> assertEquals(new BindSelector.Interface("utun9"), out.get(2),
                    "an unknown, unresolvable name becomes a (future) interface"),
                () -> assertEquals(new BindSelector.Host("vpn.example.com", List.of(ip("10.224.0.16"))), out.get(3),
                    "an explicit host is resolved"),
                () -> assertEquals(new BindSelector.Host("nope.invalid", List.of()), out.get(4),
                    "an unresolvable explicit host keeps no addresses"),
                () -> assertEquals(new BindSelector.Loopback(), out.get(5), "other selectors pass through"),
                () -> assertEquals(1, warnings.size(), "exactly the ambiguous unknown name is warned about"),
                () -> assertTrue(warnings.get(0).contains("'utun9'"), "the warning names it"));
        }
    }

    @Nested
    @DisplayName("BindPlan.choose")
    class Choose {
        @Test
        @DisplayName("all: every address of up interfaces except IPv6 link-local, IPv4 first")
        void all() {
            BindPlan.Choice c = BindPlan.choose(sel("all"), HOST);
            assertAll(
                () -> assertEquals(List.of(t("10.224.0.16", "utun4"), t("100.101.1.2", "tailscale0"), t("127.0.0.1", "lo0"),
                    t("192.168.1.20", "en0"), t("::1", "lo0"), t("2001:db8::20", "en0")), c.targets(),
                    "up interfaces only, link-local excluded, sorted IPv4 then IPv6 by bytes"),
                () -> assertEquals(List.of(), c.unmatched(), "all matched"),
                () -> assertEquals(List.of(), c.warnings(), "no warnings"));
        }

        @Test
        @DisplayName("loopback, interface, CIDR, literal and host selectors")
        void kinds() {
            assertAll(
                () -> assertEquals(List.of(t("127.0.0.1", "lo0"), t("::1", "lo0")), BindPlan.choose(sel("loopback"), HOST).targets(),
                    "loopback excludes its link-local address"),
                () -> assertEquals(List.of(t("192.168.1.20", "en0"), t("2001:db8::20", "en0")),
                    BindPlan.choose(sel("if:en0"), HOST).targets(), "an interface gives its non-link-local addresses"),
                () -> assertEquals(List.of(t("10.224.0.16", "utun4"), t("100.101.1.2", "tailscale0")),
                    BindPlan.choose(sel("10.224.0.0/22", "100.64.0.0/10"), HOST).targets(), "CIDRs pick addresses inside them"),
                () -> assertEquals(List.of(t("fe80::1%1", "lo0"), t("fe80::aede:48ff:fe00:1122%2", "en0")),
                    BindPlan.choose(sel("fe80::/10"), HOST).targets(), "a CIDR covering link-local takes link-local"),
                () -> assertEquals(List.of(t("10.224.0.16", "utun4")), BindPlan.choose(sel("10.224.0.16"), HOST).targets(),
                    "a literal takes the local address, labelled with its interface"),
                () -> assertEquals(List.of(t("127.0.0.1", "lo0")),
                    BindPlan.choose(List.of(new BindSelector.Host("h", List.of(ip("127.0.0.1"), ip("8.8.8.8")))), HOST).targets(),
                    "a host takes only its local addresses"),
                () -> assertEquals(List.of(), BindPlan.choose(sel("172.16.0.9"), HOST).targets(),
                    "addresses on a down interface are never chosen"));
        }

        @Test
        @DisplayName("overlapping selectors give each address once; unmatched selectors are reported")
        void overlapAndUnmatched() {
            List<BindSelector> s = List.of(new BindSelector.Interface("utun4"), BindSelector.parse("10.0.0.0/8"),
                new BindSelector.Interface("utun9"), BindSelector.parse("10.9.9.9"));
            BindPlan.Choice c = BindPlan.choose(s, HOST);
            assertAll(
                () -> assertEquals(List.of(t("10.224.0.16", "utun4")), c.targets(), "an address matched twice appears once"),
                () -> assertEquals(List.of(s.get(2), s.get(3)), c.unmatched(), "selectors matching nothing are listed in order"));
        }

        @Test
        @DisplayName("link-local addresses on two interfaces are both bound; the first interface labels a duplicate")
        void scopesAndDuplicates() {
            List<NetIf> twoLinks = List.of(
                new NetIf("en0", true, false, List.of(ip("fe80::1%1"), ip("10.0.0.5"))),
                new NetIf("en1", true, false, List.of(ip("fe80::1%2"), ip("10.0.0.5"))),
                new NetIf("lo1", true, true, List.of(ip("10.9.9.9"))),
                new NetIf("en2", true, false, List.of(ip("127.0.0.7"))));
            assertAll(
                () -> assertEquals(List.of(t("fe80::1%1", "en0"), t("fe80::1%2", "en1")),
                    BindPlan.choose(sel("fe80::/10"), twoLinks).targets(), "same link-local address, two scopes: two targets"),
                () -> assertEquals(List.of(t("10.0.0.5", "en0")), BindPlan.choose(sel("10.0.0.0/24"), twoLinks).targets(),
                    "one address on two interfaces is bound once, labelled with the first interface"),
                () -> assertEquals(List.of(t("10.9.9.9", "lo1"), t("127.0.0.7", "en2")),
                    BindPlan.choose(sel("loopback"), twoLinks).targets(),
                    "loopback takes addresses of a loopback interface and loopback addresses on any interface"));
        }

        @Test
        @DisplayName("0.0.0.0 together with :: binds only the dual-stack ::")
        void bothWildcards() {
            BindPlan.Choice both = BindPlan.choose(sel("0.0.0.0", "::"), HOST);
            BindPlan.Choice v4 = BindPlan.choose(sel("0.0.0.0"), HOST);
            assertAll(
                () -> assertEquals(List.of(t("::", "*")), both.targets(), "only :: is bound"),
                () -> assertTrue(both.warnings().get(both.warnings().size() - 1).contains("dual-stack"), "with a warning saying why"),
                () -> assertEquals(List.of(t("0.0.0.0", "*")), v4.targets(), "0.0.0.0 alone is kept"));
        }

        @Test
        @DisplayName("wildcards bind themselves, exclusively, with warnings")
        void wildcards() {
            BindPlan.Choice alone = BindPlan.choose(sel("::"), HOST);
            BindPlan.Choice mixed = BindPlan.choose(sel("if:utun4", "0.0.0.0", "0.0.0.0"), HOST);
            assertAll(
                () -> assertEquals(List.of(t("::", "*")), alone.targets(), "a wildcard binds the wildcard address"),
                () -> assertEquals(1, alone.warnings().size(), "a wildcard is warned about once"),
                () -> assertTrue(alone.warnings().get(0).contains("different source address"), "the warning explains the risk"),
                () -> assertEquals(List.of(t("0.0.0.0", "*")), mixed.targets(),
                    "a wildcard replaces specific addresses, and duplicates collapse"),
                () -> assertTrue(mixed.warnings().get(mixed.warnings().size() - 1).contains("10.224.0.16"),
                    "the replaced specific addresses are named"),
                () -> assertEquals(List.of(), mixed.unmatched(), "a wildcard never counts as unmatched"),
                () -> assertFalse(BindPlan.matches(BindSelector.parse("0.0.0.0"), HOST.get(0), ip("0.0.0.0")),
                    "a wildcard never matches an interface address"),
                () -> assertFalse(BindPlan.matches(new BindSelector.Name("lo0"), HOST.get(0), ip("127.0.0.1")),
                    "an unresolved name matches nothing"));
        }
    }

    @Nested
    @DisplayName("BindPlan.diff")
    class DiffTests {
        @Test
        @DisplayName("opens new addresses, closes vanished ones, relabels a renumbered interface in place")
        void diff() {
            List<BindTarget> before = List.of(t("127.0.0.1", "lo0"), t("10.224.0.16", "utun4"), t("10.224.0.9", "utun4"));
            List<BindTarget> vpnMoved = List.of(t("127.0.0.1", "lo0"), t("10.224.0.16", "utun5"), t("10.224.0.17", "utun5"));
            BindPlan.Diff d = BindPlan.diff(before, vpnMoved);
            BindPlan.Diff scoped = BindPlan.diff(List.of(t("fe80::1%1", "lo0")), List.of(t("fe80::1%1", "lo0"), t("fe80::1%2", "en0")));
            assertAll(
                () -> assertEquals(List.of(t("10.224.0.17", "utun5")), d.open(), "addresses not bound yet are opened"),
                () -> assertEquals(List.of(t("10.224.0.9", "utun4")), d.close(), "addresses no longer wanted are closed"),
                () -> assertEquals(List.of(t("10.224.0.16", "utun5")), d.relabel(),
                    "an address that moved from utun4 to utun5 keeps its channel and is only relabelled"),
                () -> assertEquals(List.of(t("fe80::1%2", "en0")), scoped.open(), "the same link-local address on another scope is new"),
                () -> assertEquals(List.of(), scoped.close(), "and the first scope stays"),
                () -> assertFalse(d.isEmpty(), "a change is not empty"),
                () -> assertFalse(new BindPlan.Diff(List.of(), List.of(), List.of(t("::1", "lo0"))).isEmpty(),
                    "a relabel alone is a change"),
                () -> assertTrue(BindPlan.diff(before, List.copyOf(before)).isEmpty(), "equal sets give an empty diff"),
                () -> assertTrue(BindPlan.diff(List.of(), List.of(t("::1", "lo0"))).close().isEmpty()
                    && !BindPlan.diff(List.of(), List.of(t("::1", "lo0"))).isEmpty(), "an open alone is a change"),
                () -> assertFalse(BindPlan.diff(before, List.of()).isEmpty(), "a close alone is a change"));
        }
    }

    @Test
    @DisplayName("BindPlan.needsRescan: only literals without bindWait never rescan")
    void needsRescan() {
        assertAll(
            () -> assertFalse(BindPlan.needsRescan(sel("127.0.0.1", "::1"), false), "literals alone never change"),
            () -> assertTrue(BindPlan.needsRescan(sel("127.0.0.1", "::1"), true), "literals with bindWait wait for their address"),
            () -> assertTrue(BindPlan.needsRescan(sel("127.0.0.1", "if:utun4"), false), "an interface selector follows changes"),
            () -> assertTrue(BindPlan.needsRescan(sel("10.0.0.0/8"), false), "a CIDR follows changes"),
            () -> assertTrue(BindPlan.needsRescan(sel("all"), false), "all follows changes"),
            () -> assertTrue(BindPlan.needsRescan(List.of(), true), "bindWait alone rescans"));
    }

    @Test
    @DisplayName("AddressKey: the address plus its IPv6 scope")
    void addressKey() {
        assertAll(
            () -> assertEquals(0, BindPlan.AddressKey.of(ip("10.0.0.1")).scope(), "IPv4 has scope 0"),
            () -> assertEquals(2, BindPlan.AddressKey.of(ip("fe80::1%2")).scope(), "a scoped IPv6 address keeps its scope"),
            () -> assertFalse(BindPlan.AddressKey.of(ip("fe80::1%1")).equals(BindPlan.AddressKey.of(ip("fe80::1%2"))),
                "one link-local address on two interfaces gives two keys"),
            () -> assertEquals(BindPlan.AddressKey.of(ip("10.0.0.1")), BindPlan.AddressKey.of(ip("10.0.0.1")), "equal addresses, equal keys"));
    }

    @Nested
    @DisplayName("BindPlan.decide")
    class Decide {
        final BindPlan.Choice ok = new BindPlan.Choice(List.of(t("127.0.0.1", "lo0")), List.of(), List.of());
        final BindPlan.Choice partial = new BindPlan.Choice(List.of(t("127.0.0.1", "lo0")),
            List.of(new BindSelector.Interface("utun9")), List.of());
        final BindPlan.Choice none = new BindPlan.Choice(List.of(), List.of(new BindSelector.Interface("utun9")), List.of());

        @Test
        @DisplayName("fails, waits or proceeds per bindWait")
        void decide() {
            assertAll(
                () -> assertEquals(BindPlan.Startup.PROCEED, BindPlan.decide(ok, false), "everything matched proceeds"),
                () -> assertEquals(BindPlan.Startup.FAIL, BindPlan.decide(partial, false),
                    "any unmatched selector fails without bindWait"),
                () -> assertEquals(BindPlan.Startup.FAIL, BindPlan.decide(none, false), "nothing matched fails without bindWait"),
                () -> assertEquals(BindPlan.Startup.PROCEED, BindPlan.decide(partial, true),
                    "with bindWait, a partial match proceeds"),
                () -> assertEquals(BindPlan.Startup.WAIT, BindPlan.decide(none, true), "with bindWait, nothing matched waits"),
                () -> assertTrue(BindPlan.failureMessage(partial).contains("if:utun9"), "the failure names the selector"),
                () -> assertTrue(BindPlan.failureMessage(partial).contains("bindWait"), "the failure suggests bindWait"));
        }
    }

    @Test
    @DisplayName("BindTarget.describe formats address:port (interface)")
    void describe() {
        assertAll(
            () -> assertEquals("10.224.0.16:15345 (utun4)", t("10.224.0.16", "utun4").describe(15345), "IPv4 plain"),
            () -> assertEquals("[::1]:15345 (lo0)", t("::1", "lo0").describe(15345), "IPv6 in brackets"),
            () -> assertEquals("[fe80::1]:7 (lo0)", t("fe80::1%1", "lo0").describe(7), "the scope suffix is dropped"),
            () -> assertEquals("::", BindTarget.text(ip("::")), "all zeros compress to ::"),
            () -> assertEquals("2001:db8::1:0:0:1", BindTarget.text(ip("2001:db8:0:0:1:0:0:1")),
                "the first of two equal zero runs is compressed"),
            () -> assertEquals("2001:0:0:1::1", BindTarget.text(ip("2001:0:0:1:0:0:0:1")), "the longest zero run is compressed"),
            () -> assertEquals("2001:db8:0:1:1:1:1:1", BindTarget.text(ip("2001:db8:0:1:1:1:1:1")),
                "a single zero group is not compressed"),
            () -> assertEquals("1::", BindTarget.text(ip("1::")), "a trailing run compresses to ::"),
            () -> assertEquals("abcd::ef", BindTarget.text(ip("ABCD:0:0:0:0:0:0:00EF")), "lower-case hex without leading zeros"));
    }

    @Test
    @DisplayName("NetIf and Host copy their lists")
    void immutable() {
        List<InetAddress> l = new ArrayList<>(List.of(ip("10.0.0.1")));
        NetIf n = new NetIf("x", true, false, l);
        BindSelector.Host h = new BindSelector.Host("h", l);
        l.clear();
        assertAll(
            () -> assertEquals(List.of(ip("10.0.0.1")), n.addresses(), "NetIf keeps its own copy"),
            () -> assertEquals(List.of(ip("10.0.0.1")), h.addresses(), "Host keeps its own copy"));
    }
}
