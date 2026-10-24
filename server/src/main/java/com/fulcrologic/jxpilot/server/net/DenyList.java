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

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * C {@code denyHosts} (contact.c:1195-1260 Set_deny_hosts, Check_address): networks whose packets outside a
 * session the server ignores. (C 4.5.5 only builds the list when an operator changes the option at run time,
 * so a configured list never takes effect there; JXPilot applies it, see DEVIATIONS-server.md.)
 * <p>
 * The networks are precomputed: IPv4 sources are checked allocation free (the address as an int); an IPv6
 * check copies the source address once, like {@link SourceKeys}, and only runs for packets outside a session.
 */
public final class DenyList {
    /** No networks. */
    public static final DenyList NONE = new DenyList(new int[0], new int[0], new byte[0][], new int[0]);

    private final int[] v4Net;
    private final int[] v4Mask;
    private final byte[][] v6Net;
    private final int[] v6Prefix;

    private DenyList(int[] v4Net, int[] v4Mask, byte[][] v6Net, int[] v6Prefix) {
        this.v4Net = v4Net;
        this.v4Mask = v4Mask;
        this.v6Net = v6Net;
        this.v6Prefix = v6Prefix;
    }

    /**
     * Parse a deny list.
     * <p>
     * BEHAVIOR: entries are separated by commas, semicolons or whitespace (C also splits on ':', which would
     * break IPv6 addresses, so ':' is not a separator here).
     * BEHAVIOR: an entry is an IP literal (one host), {@code address/prefix} (CIDR) or, as in C,
     * {@code address/dotted.mask} with a contiguous mask; host names are not resolved.
     * BEHAVIOR: entries that do not parse, a zero mask or prefix (C skips mask 0), and non-contiguous masks are
     * skipped, as C skips what it cannot use.
     */
    public static DenyList parse(String text) {
        List<Cidr> out = new ArrayList<>();
        if (text != null) {
            for (String tok : text.split("[,;\\s]+")) {
                Cidr c = tok.isEmpty() ? null : entry(tok);
                if (c != null && c.prefix() > 0) {
                    out.add(c);
                }
            }
        }
        int n4 = 0;
        for (Cidr c : out) {
            n4 += c.network() instanceof Inet4Address ? 1 : 0;
        }
        int[] net = new int[n4];
        int[] mask = new int[n4];
        byte[][] net6 = new byte[out.size() - n4][];
        int[] prefix6 = new int[out.size() - n4];
        int i4 = 0;
        int i6 = 0;
        for (Cidr c : out) {
            if (c.network() instanceof Inet4Address a) {
                mask[i4] = (int) (0xFFFFFFFFL << (32 - c.prefix()));
                net[i4] = a.hashCode() & mask[i4];
                i4++;
            } else {
                net6[i6] = c.network().getAddress();
                prefix6[i6] = c.prefix();
                i6++;
            }
        }
        return new DenyList(net, mask, net6, prefix6);
    }

    private static Cidr entry(String tok) {
        int slash = tok.indexOf('/');
        String addr = slash < 0 ? tok : tok.substring(0, slash);
        InetAddress a = BindSelector.parseLiteral(addr);
        if (a == null) {
            return null;
        }
        if (slash < 0) {
            return new Cidr(a, a instanceof Inet4Address ? 32 : 128);
        }
        String mask = tok.substring(slash + 1);
        if (mask.indexOf('.') >= 0) {
            InetAddress m = BindSelector.parseLiteral(mask);
            if (!(m instanceof Inet4Address) || !(a instanceof Inet4Address)) {
                return null;
            }
            int bits = m.hashCode();
            int prefix = Integer.bitCount(bits);
            if (prefix == 0 || bits != (int) (0xFFFFFFFFL << (32 - prefix))) {
                return null;
            }
            return new Cidr(a, prefix);
        }
        try {
            return Cidr.parse(tok);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Number of networks. */
    public int size() {
        return v4Net.length + v6Net.length;
    }

    /**
     * BEHAVIOR: true iff {@code from} is an internet address inside one of the networks. IPv4: allocation free.
     */
    public boolean denied(SocketAddress from) {
        if (size() == 0 || !(from instanceof InetSocketAddress isa)) {
            return false;
        }
        InetAddress a = isa.getAddress();
        if (a instanceof Inet4Address) {
            int x = a.hashCode();
            for (int i = 0; i < v4Net.length; i++) {
                if ((x & v4Mask[i]) == v4Net[i]) {
                    return true;
                }
            }
            return false;
        }
        if (a instanceof Inet6Address && v6Net.length > 0) {
            byte[] x = a.getAddress();
            for (int i = 0; i < v6Net.length; i++) {
                if (prefixEquals(v6Net[i], x, v6Prefix[i])) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean prefixEquals(byte[] n, byte[] x, int prefix) {
        int full = prefix / 8;
        for (int i = 0; i < full; i++) {
            if (n[i] != x[i]) {
                return false;
            }
        }
        int rest = prefix % 8;
        if (rest == 0) {
            return true;
        }
        int mask = (0xFF << (8 - rest)) & 0xFF;
        return (n[full] & mask) == (x[full] & mask);
    }
}
