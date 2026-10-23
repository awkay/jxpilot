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
package com.fulcrologic.jxpilot.client.xp;

import com.fulcrologic.jxpilot.client.net.Endpoint;

/**
 * Where to connect and with which protocol, from a connect string and an optional protocol flag:
 * {@code xp://host[:port]} is the original XPilot protocol, {@code jx://host[:port]} the native one,
 * and a plain {@code host[:port]} uses the flag ({@code --protocol xpilot|jxpilot}), native by default.
 * The default port is 15345 for both.
 */
public record ConnectTarget(Protocol protocol, Endpoint endpoint) {
    /** Wire protocols. */
    public enum Protocol {
        /** JXPilot's own protocol (docs/PROTOCOL.md). */
        JXPILOT,
        /** The original XPilot 4.5.5 client/server protocol. */
        XPILOT
    }

    public static final String XP_SCHEME = "xp://";
    public static final String JX_SCHEME = "jx://";

    /**
     * BEHAVIOR: a scheme decides the protocol and wins over the flag; without one the flag decides
     * ("xpilot"/"xp" or "jxpilot"/"jx"/"native", any case), null meaning native.
     * BEHAVIOR: throws IllegalArgumentException for an unknown flag value or an unparsable endpoint.
     */
    public static ConnectTarget parse(String target, String protocolFlag) {
        String t = target.trim();
        String lower = t.toLowerCase(java.util.Locale.ROOT);
        Protocol p;
        if (lower.startsWith(XP_SCHEME)) {
            p = Protocol.XPILOT;
            t = t.substring(XP_SCHEME.length());
        } else if (lower.startsWith(JX_SCHEME)) {
            p = Protocol.JXPILOT;
            t = t.substring(JX_SCHEME.length());
        } else {
            p = protocolFlag == null ? Protocol.JXPILOT : parseProtocol(protocolFlag);
        }
        if (t.endsWith("/")) {
            t = t.substring(0, t.length() - 1);
        }
        return new ConnectTarget(p, Endpoint.parse(t, XpProto.SERVER_PORT));
    }

    /** BEHAVIOR: "xpilot"/"xp" or "jxpilot"/"jx"/"native" (any case); anything else throws. */
    public static Protocol parseProtocol(String flag) {
        return switch (flag.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "xpilot", "xp" -> Protocol.XPILOT;
            case "jxpilot", "jx", "native" -> Protocol.JXPILOT;
            default -> throw new IllegalArgumentException("unknown protocol '" + flag + "' (xpilot or jxpilot)");
        };
    }

    @Override
    public String toString() {
        return (protocol == Protocol.XPILOT ? XP_SCHEME : JX_SCHEME) + endpoint;
    }
}
