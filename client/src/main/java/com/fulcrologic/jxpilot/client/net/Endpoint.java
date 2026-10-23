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

/**
 * A host and UDP port parsed from {@code --connect}/{@code --bind} arguments. Pure; resolution
 * happens at the edge ({@link UdpTransport}).
 *
 * @param host host name or IP literal (IPv6 without brackets)
 * @param port UDP port, 0 = any (only meaningful for {@code --bind})
 */
public record Endpoint(String host, int port) {

    /**
     * Parse {@code host}, {@code host:port}, {@code [v6]}, {@code [v6]:port} or a bare IPv6 literal.
     * <p>
     * BEHAVIOR: without a port the default port is used.
     * BEHAVIOR: a bracketed host is IPv6 and may be followed by {@code :port}.
     * BEHAVIOR: an unbracketed value with two or more colons is a bare IPv6 literal (no port).
     * BEHAVIOR: throws IllegalArgumentException for an empty host, an unclosed bracket, junk after the
     * bracket, or a port that is not a number in 0..65535.
     */
    public static Endpoint parse(String s, int defaultPort) {
        if (s == null || s.isEmpty()) {
            throw new IllegalArgumentException("empty address");
        }
        String host;
        String port = null;
        if (s.charAt(0) == '[') {
            int close = s.indexOf(']');
            if (close < 0) {
                throw new IllegalArgumentException("missing ']' in " + s);
            }
            host = s.substring(1, close);
            String rest = s.substring(close + 1);
            if (!rest.isEmpty()) {
                if (rest.charAt(0) != ':') {
                    throw new IllegalArgumentException("unexpected text after ']' in " + s);
                }
                port = rest.substring(1);
            }
        } else {
            int first = s.indexOf(':');
            int last = s.lastIndexOf(':');
            if (first >= 0 && first == last) {
                host = s.substring(0, first);
                port = s.substring(first + 1);
            } else {
                host = s;
            }
        }
        if (host.isEmpty()) {
            throw new IllegalArgumentException("empty host in " + s);
        }
        return new Endpoint(host, port == null ? defaultPort : parsePort(port));
    }

    private static int parsePort(String p) {
        int v;
        try {
            v = Integer.parseInt(p);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("bad port '" + p + "'");
        }
        if (v < 0 || v > 0xFFFF) {
            throw new IllegalArgumentException("port out of range: " + v);
        }
        return v;
    }

    /** BEHAVIOR: {@code host:port}, with IPv6 hosts in brackets. */
    @Override
    public String toString() {
        return host.indexOf(':') >= 0 ? "[" + host + "]:" + port : host + ":" + port;
    }
}
