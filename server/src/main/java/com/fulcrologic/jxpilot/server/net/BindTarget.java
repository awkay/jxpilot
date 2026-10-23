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

import java.net.Inet6Address;
import java.net.InetAddress;

/**
 * A concrete local address to open one channel on, with the interface it belongs to (for logs).
 *
 * @param address       local address; a wildcard only for an explicit {@code 0.0.0.0}/{@code ::} bind
 * @param interfaceName OS interface name, or {@code *} for a wildcard
 */
public record BindTarget(InetAddress address, String interfaceName) {

    /**
     * BEHAVIOR: formats {@code addr:port (iface)}, with IPv6 addresses in brackets and without their
     * scope suffix (the interface name is shown anyway).
     */
    public String describe(int port) {
        String host = address.getHostAddress();
        int pct = host.indexOf('%');
        if (pct >= 0) {
            host = host.substring(0, pct);
        }
        String hp = address instanceof Inet6Address ? "[" + host + "]:" + port : host + ":" + port;
        return hp + " (" + interfaceName + ")";
    }
}
