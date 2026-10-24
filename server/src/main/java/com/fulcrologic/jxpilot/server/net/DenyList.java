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
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * C {@code denyHosts} (contact.c:1195-1260 Set_deny_hosts, Check_address): networks whose CONNECTs the server
 * ignores.
 */
public final class DenyList {
    private DenyList() {}

    /**
     * Parse a deny list.
     * <p>
     * BEHAVIOR: entries are separated by commas, semicolons or whitespace (C also splits on ':', which would
     * break IPv6 addresses, so ':' is not a separator here).
     * BEHAVIOR: an entry is an IP literal (one host), {@code address/prefix} (CIDR) or, as in C,
     * {@code address/dotted.mask} with a contiguous mask; host names are not resolved.
     * BEHAVIOR: entries that do not parse, a mask of 0.0.0.0 and non-contiguous masks are skipped, as C skips
     * what it cannot use.
     */
    public static Cidr[] parse(String text) {
        List<Cidr> out = new ArrayList<>();
        if (text == null) {
            return new Cidr[0];
        }
        for (String tok : text.split("[,;\\s]+")) {
            if (tok.isEmpty()) {
                continue;
            }
            Cidr c = entry(tok);
            if (c != null) {
                out.add(c);
            }
        }
        return out.toArray(new Cidr[0]);
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
            int bits = 0;
            for (byte b : m.getAddress()) {
                bits = (bits << 8) | (b & 0xFF);
            }
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

    /** BEHAVIOR: true iff {@code from} is an internet address inside one of the networks. Allocation free. */
    public static boolean denied(Cidr[] list, SocketAddress from) {
        if (list.length == 0 || !(from instanceof InetSocketAddress isa) || isa.getAddress() == null) {
            return false;
        }
        InetAddress a = isa.getAddress();
        for (Cidr c : list) {
            if (c.contains(a)) {
                return true;
            }
        }
        return false;
    }
}
