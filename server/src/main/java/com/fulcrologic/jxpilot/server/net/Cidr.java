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

/**
 * An IPv4 or IPv6 network in CIDR notation ({@code 10.224.0.0/22}, {@code fd7a::/48}). Host bits
 * in the written address are ignored, so {@code 10.224.0.16/22} is the same network as
 * {@code 10.224.0.0/22}.
 */
public record Cidr(InetAddress network, int prefix) {

    /**
     * Parse {@code address/prefix}.
     * <p>
     * BEHAVIOR: the address must be an IP literal (no DNS lookup) and the prefix a decimal number in
     * 0..32 for IPv4 or 0..128 for IPv6.
     * BEHAVIOR: an IPv4-mapped IPv6 network ({@code ::ffff:10.0.0.0/104}) is the IPv4 network with
     * prefix - 96 (Java decodes mapped literals as IPv4); a mapped prefix below 96 is rejected.
     *
     * @throws IllegalArgumentException with a message naming the problem
     */
    public static Cidr parse(String text) {
        int slash = text.indexOf('/');
        if (slash < 0) {
            throw new IllegalArgumentException("CIDR '" + text + "' has no /prefix");
        }
        String addr = text.substring(0, slash);
        String bits = text.substring(slash + 1);
        InetAddress a = BindSelector.parseLiteral(addr);
        if (a == null) {
            throw new IllegalArgumentException("CIDR '" + text + "' does not start with an IP address");
        }
        int max = addr.indexOf(':') >= 0 ? 128 : 32;
        int p;
        try {
            p = Integer.parseInt(bits);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("CIDR '" + text + "' has a bad prefix length");
        }
        if (p < 0 || p > max || !bits.equals(Integer.toString(p))) {
            throw new IllegalArgumentException("CIDR '" + text + "' prefix must be 0.." + max);
        }
        if (max == 128 && a instanceof Inet4Address) {
            if (p < 96) {
                throw new IllegalArgumentException("CIDR '" + text + "' maps IPv4 and needs a prefix of 96..128");
            }
            p -= 96;
        }
        return new Cidr(a, p);
    }

    /**
     * BEHAVIOR: true iff {@code a} has the network's family and its first {@code prefix} bits equal
     * the network's. A /0 contains every address of its family.
     */
    public boolean contains(InetAddress a) {
        byte[] n = network.getAddress();
        byte[] x = a.getAddress();
        if (n.length != x.length) {
            return false;
        }
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

    @Override
    public String toString() {
        return network.getHostAddress() + "/" + prefix;
    }
}
