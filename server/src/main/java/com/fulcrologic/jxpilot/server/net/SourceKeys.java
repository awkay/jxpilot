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

/**
 * Secret-keyed 64-bit source keys for per-address limits (rate limiting, the per-IP session cap). The
 * secret is random per server, so a remote host cannot choose addresses that collide with a victim's
 * key or limiter slot. An IPv4 address is its own source; an IPv6 source is its /64 (one subscriber's
 * prefix), so hopping through addresses of one /64 does not escape the limits.
 * <p>
 * IPv4 keys are allocation free. IPv6 keys copy the address bytes (one 16-byte array); they are only
 * computed for packets outside a session (CONNECT, strays), where the JDK has already allocated the
 * sender's address object anyway.
 */
final class SourceKeys {
    private static final long V4 = 4;
    private static final long V6 = 6;
    private final long secret;

    SourceKeys(long secret) {
        this.secret = secret;
    }

    /**
     * BEHAVIOR: IPv4 sources key by the whole address, IPv6 by the first 64 bits; every port of a source
     * shares its key; IPv4 and IPv6 keys are separated by family.
     * BEHAVIOR: keys depend on the secret (a different secret gives unrelated keys).
     * BEHAVIOR: an unresolved address keys by its hash.
     */
    long of(SocketAddress from) {
        if (from instanceof InetSocketAddress isa) {
            InetAddress a = isa.getAddress();
            if (a instanceof Inet4Address) {
                return mix(mix(secret ^ (a.hashCode() & 0xFFFFFFFFL)) + V4);
            }
            if (a instanceof Inet6Address) {
                byte[] b = a.getAddress();
                long prefix = 0;
                for (int i = 0; i < 8; i++) {
                    prefix = prefix << 8 | (b[i] & 0xFF);
                }
                return mix(mix(secret ^ prefix) + V6);
            }
        }
        return mix(secret ^ from.hashCode());
    }

    /** BEHAVIOR: SplitMix64's finalizer, a bijection on 64 bits with full avalanche. */
    static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
