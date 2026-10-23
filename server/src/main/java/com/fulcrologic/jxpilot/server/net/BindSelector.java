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

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * One value of the {@code bind} option (see SPEC "Server network binding"). Parsed by
 * {@link #parse(String)}; turned into concrete addresses by {@link BindPlan}.
 */
public sealed interface BindSelector {

    /** Every address of every up interface (IPv6 link-local excluded). The default. */
    record All() implements BindSelector {
        @Override
        public String toString() { return "all"; }
    }

    /** Addresses of loopback interfaces ({@code 127.0.0.1}, {@code ::1}). */
    record Loopback() implements BindSelector {
        @Override
        public String toString() { return "loopback"; }
    }

    /**
     * A single IP literal. A wildcard ({@code 0.0.0.0}, {@code ::}) binds the wildcard socket itself
     * (explicit opt-in, warned about); any other literal must be a local address.
     */
    record Literal(InetAddress address) implements BindSelector {
        public boolean wildcard() { return address.isAnyLocalAddress(); }

        @Override
        public String toString() { return BindTarget.text(address); }
    }

    /** Every local address inside the network; survives interfaces being renumbered. */
    record Network(Cidr cidr) implements BindSelector {
        @Override
        public String toString() { return cidr.toString(); }
    }

    /** Every address of the named interface ({@code utun4}, {@code en0}, {@code tailscale0}). */
    record Interface(String name) implements BindSelector {
        @Override
        public String toString() { return "if:" + name; }
    }

    /** A host name; {@code addresses} is empty until {@link BindPlan#resolveNames} resolves it. */
    record Host(String name, List<InetAddress> addresses) implements BindSelector {
        public Host {
            addresses = List.copyOf(addresses);
        }

        @Override
        public String toString() { return "host:" + name; }
    }

    /**
     * A bare word that is an interface name or a host name; {@link BindPlan#resolveNames} decides at
     * startup.
     */
    record Name(String name) implements BindSelector {
        @Override
        public String toString() { return name; }
    }

    Pattern DOTTED_QUAD = Pattern.compile("[0-9]{1,3}(\\.[0-9]{1,3}){3}");

    /**
     * Parse one selector.
     * <p>
     * BEHAVIOR: {@code all} and {@code loopback} (any case) are keywords.
     * BEHAVIOR: text containing '/' is a CIDR.
     * BEHAVIOR: {@code if:NAME} is an interface and {@code host:NAME} a host name, explicitly.
     * BEHAVIOR: a dotted quad or text containing ':' is an IP literal (a bad one is an error, never a
     * DNS lookup).
     * BEHAVIOR: any other word is a {@link Name}; surrounding whitespace is ignored.
     *
     * @throws IllegalArgumentException for empty text, a bad literal, CIDR or empty prefixed name
     */
    static BindSelector parse(String text) {
        String t = text.strip();
        if (t.isEmpty()) {
            throw new IllegalArgumentException("empty bind selector");
        }
        String lower = t.toLowerCase(java.util.Locale.ROOT);
        if (lower.equals("all")) {
            return new All();
        }
        if (lower.equals("loopback")) {
            return new Loopback();
        }
        if (t.indexOf('/') >= 0) {
            return new Network(Cidr.parse(t));
        }
        if (lower.startsWith("if:")) {
            return new Interface(requireName(t.substring(3), t));
        }
        if (lower.startsWith("host:")) {
            return new Host(requireName(t.substring(5), t), List.of());
        }
        if (DOTTED_QUAD.matcher(t).matches() || t.indexOf(':') >= 0) {
            InetAddress a = parseLiteral(t);
            if (a == null) {
                throw new IllegalArgumentException("bad IP address '" + t + "'");
            }
            return new Literal(a);
        }
        return new Name(t);
    }

    /**
     * Parse every value, as the {@code bind} option gives them.
     * <p>
     * BEHAVIOR: each element may itself be a comma list; blank elements are skipped.
     * BEHAVIOR: no selectors at all means {@code all}.
     */
    static List<BindSelector> parseAll(List<String> values) {
        List<BindSelector> out = new ArrayList<>();
        for (String v : values) {
            for (String part : v.split(",")) {
                if (!part.isBlank()) {
                    out.add(parse(part));
                }
            }
        }
        if (out.isEmpty()) {
            out.add(new All());
        }
        return out;
    }

    /**
     * BEHAVIOR: returns the address for a dotted-quad IPv4 or a ':'-containing IPv6 literal (scope ids
     * allowed), null for anything else; never resolves names.
     */
    static InetAddress parseLiteral(String t) {
        if (!DOTTED_QUAD.matcher(t).matches() && t.indexOf(':') < 0) {
            return null;
        }
        try {
            return InetAddress.ofLiteral(t);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String requireName(String name, String whole) {
        String n = name.strip();
        if (n.isEmpty()) {
            throw new IllegalArgumentException("bind selector '" + whole + "' has no name");
        }
        return n;
    }
}
