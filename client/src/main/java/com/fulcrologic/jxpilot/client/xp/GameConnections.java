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

import com.fulcrologic.jxpilot.client.net.ClientConnection;
import com.fulcrologic.jxpilot.client.net.ConnectionListener;
import com.fulcrologic.jxpilot.client.net.Endpoint;
import com.fulcrologic.jxpilot.client.net.UdpTransport;

import java.io.IOException;

/**
 * Opens a {@link GameConnection} for a {@link ConnectTarget}: the native protocol over one connected
 * UDP socket, or the XPilot protocol, which talks to the contact port first and then to the per-player
 * port the server assigns (one connected UDP socket each). Example:
 * <pre>
 *   ConnectTarget t = ConnectTarget.parse(args.connect(), args.protocol()); // "xp://host:15345"
 *   GameConnection c = GameConnections.open(t, game, "Pilot", Protocol.NO_TEAM, null, seed);
 * </pre>
 */
public final class GameConnections {
    private GameConnections() {}

    /**
     * @param bind local address to send from, null for any
     * @param seed native CONNECT nonce seed (unused by the XPilot protocol)
     */
    public static GameConnection open(ConnectTarget target, ConnectionListener listener, String nick, int team,
                                      Endpoint bind, int seed) throws IOException {
        Endpoint ep = target.endpoint();
        String label = ep.toString();
        if (target.protocol() == ConnectTarget.Protocol.XPILOT) {
            XpConnection.Login login = new XpConnection.Login();
            login.nick = nick;
            login.team = team;
            login.realName = realName();
            return new XpConnection(port -> UdpTransport.open(new Endpoint(ep.host(), port), bind), listener, label,
                login);
        }
        UdpTransport t = UdpTransport.open(ep, bind);
        ClientConnection c = new ClientConnection(t, listener, label, seed);
        c.connect.name.append(nick);
        c.connect.team = team;
        return new NativeGameConnection(c, t);
    }

    /** BEHAVIOR: the user name with only letters, digits, '-', '_' and '.', "jxpilot" when empty. */
    static String realName() {
        String u = System.getProperty("user.name", "");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < u.length() && sb.length() < XpProto.MAX_NAME_LEN - 1; i++) {
            char c = u.charAt(i);
            if (Character.isLetterOrDigit(c) && c < 128 || c == '-' || c == '_' || c == '.') {
                sb.append(c);
            }
        }
        return sb.isEmpty() ? "jxpilot" : sb.toString();
    }
}
