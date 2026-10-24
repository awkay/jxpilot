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
package com.fulcrologic.jxpilot.client.app;

import com.fulcrologic.jxpilot.client.net.ClientConnection;
import com.fulcrologic.jxpilot.client.net.ConnectionListener;
import com.fulcrologic.jxpilot.client.net.Endpoint;
import com.fulcrologic.jxpilot.client.net.Transport;
import com.fulcrologic.jxpilot.client.net.UdpTransport;
import com.fulcrologic.jxpilot.client.game.options.ClientOption;
import com.fulcrologic.jxpilot.client.game.options.ClientOptions;
import com.fulcrologic.jxpilot.client.paint.PaintConfig;
import com.fulcrologic.jxpilot.client.paint.PaintStyle;
import com.fulcrologic.jxpilot.client.xp.ConnectTarget;
import com.fulcrologic.jxpilot.client.xp.GameConnection;
import com.fulcrologic.jxpilot.client.xp.NativeGameConnection;
import com.fulcrologic.jxpilot.client.xp.XpConnection;
import com.fulcrologic.jxpilot.common.math.ShipShape;

import java.io.IOException;

/**
 * Opens the {@link GameConnection} for a {@link ConnectTarget} with the player's name, team and ship
 * shape (as {@code client.xp.GameConnections}, plus the shape, which that factory does not take).
 */
public final class GameConnector {
    private GameConnector() {}

    /**
     * Open (not start) a connection over UDP.
     *
     * @param bind  local source address, null for any
     * @param shape the shipShape resource (C {@code Get_shipshape_resource}); null or "" for the default ship
     * @param audio ask an XPilot server for sound events (the native server always sends them)
     */
    public static GameConnection open(ConnectTarget target, Endpoint bind, ConnectionListener listener, String name,
                                      int team, String shape, boolean audio, int seed) throws IOException {
        return open(target, bind, listener, name, team, shape, audio, seed, null, 0, 0);
    }

    /**
     * As above with the XPilot login identity ({@link XpIdentity}); null resolves it from the environment only.
     */
    public static GameConnection open(ConnectTarget target, Endpoint bind, ConnectionListener listener, String name,
                                      int team, String shape, boolean audio, int seed, XpIdentity identity)
        throws IOException {
        return open(target, bind, listener, name, team, shape, audio, seed, identity, 0, 0);
    }

    /**
     * As above with C's {@code clientPortStart}/{@code clientPortEnd}: without {@code bind}, each socket takes the
     * first free local UDP port of that range (C {@code CreateDgramSocket} over the range, for firewalls); a range
     * starting at 0 means any port.
     */
    public static GameConnection open(ConnectTarget target, Endpoint bind, ConnectionListener listener, String name,
                                      int team, String shape, boolean audio, int seed, XpIdentity identity,
                                      int portStart, int portEnd) throws IOException {
        return open(target, bind, listener, name, team, shape, audio, seed, identity, portStart, portEnd,
            PaintConfig.SPARK_COLORS.length);
    }

    /**
     * As above, asking an XPilot server for {@code debrisColors} debris temperature levels ({@link #debrisColors}:
     * as many as {@code sparkColors} names).
     */
    public static GameConnection open(ConnectTarget target, Endpoint bind, ConnectionListener listener, String name,
                                      int team, String shape, boolean audio, int seed, XpIdentity identity,
                                      int portStart, int portEnd, int debrisColors)
        throws IOException {
        Endpoint ep = target.endpoint();
        String label = ep.toString();
        if (target.protocol() == ConnectTarget.Protocol.XPILOT) {
            XpIdentity id = identity != null ? identity : environmentIdentity("", "", "");
            return new XpConnection(port -> openUdp(new Endpoint(ep.host(), port), bind, portStart, portEnd), listener,
                label, xpLogin(name, team, shape, audio, id, debrisColors));
        }
        UdpTransport t = openUdp(ep, bind, portStart, portEnd);
        return new NativeGameConnection(identity != null
            ? nativeConnection(t, label, listener, name, team, shape, identity)
            : nativeConnection(t, label, listener, name, team, shape), t);
    }

    /** C's identity from the user/host/display resources, the environment, the login and the local host name. */
    public static XpIdentity environmentIdentity(String user, String host, String display) {
        return XpIdentity.resolve(user, host, display, System::getenv, System.getProperty("user.name", ""),
            GameConnector::localHostName);
    }

    /** C {@code sock_get_local_hostname}: the local host's name, "localhost" when it cannot be found. */
    static String localHostName() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName(); // C gethostname(), not a reverse lookup
        } catch (IOException | RuntimeException e) {
            return "localhost";
        }
    }

    /** A UDP socket to {@code to}: from {@code bind}, else from the first free port of the range, else any port. */
    static UdpTransport openUdp(Endpoint to, Endpoint bind, int portStart, int portEnd) throws IOException {
        if (bind != null || portStart <= 0) {
            return UdpTransport.open(to, bind);
        }
        IOException last = null;
        for (int p : portRange(portStart, portEnd)) {
            try {
                return UdpTransport.open(to, new Endpoint("0.0.0.0", p));
            } catch (IOException e) {
                last = e;
            }
        }
        throw new IOException("no free UDP port in clientPortStart..clientPortEnd " + portStart + ".." + portEnd
            + (last != null ? ": " + last.getMessage() : ""));
    }

    /**
     * C's client port range.
     * <p>
     * BEHAVIOR: start..end inclusive; an end below the start (C's default 0) means the start only; ports are
     * limited to 1..65535.
     */
    static int[] portRange(int start, int end) {
        int a = Math.max(1, Math.min(65535, start));
        int b = Math.max(a, Math.min(65535, end));
        int[] r = new int[b - a + 1];
        for (int i = 0; i < r.length; i++) {
            r[i] = a + i;
        }
        return r;
    }

    /**
     * The XPilot login.
     * <p>
     * BEHAVIOR: nick, team and shape as given (null is ""; XpConnection converts it when sending); debris colours = the client's spark colours (temperature
     * levels, as the C client asks) and spark probability 1 (every particle every frame, so particles can be
     * tracked across frames; the painter does C's sparkle); real name, host and display from {@code id}; audio
     * (PKT_REQUEST_AUDIO) as given.
     */
    static XpConnection.Login xpLogin(String name, int team, String shape, boolean audio, XpIdentity id) {
        return xpLogin(name, team, shape, audio, id, PaintConfig.SPARK_COLORS.length);
    }

    /** {@link #xpLogin(String, int, String, boolean, XpIdentity)} with {@code debrisColors} temperature levels. */
    static XpConnection.Login xpLogin(String name, int team, String shape, boolean audio, XpIdentity id,
                                      int debrisColors) {
        XpConnection.Login login = new XpConnection.Login();
        login.audio = audio;
        login.nick = name;
        login.team = team;
        login.shape = shape == null ? "" : shape;
        login.debrisColors = debrisColors;
        login.sparkProb = 1.0;
        login.realName = id.realName();
        login.host = id.host();
        login.display = id.display();
        return login;
    }

    /**
     * C {@code num_spark_colors}, what the XPilot login asks for as {@code debris_colors}: the number of colours
     * {@code sparkColors} names under {@code maxColors} ({@link LookOptions#sparkColors}).
     */
    public static int debrisColors(ClientOptions options) {
        return LookOptions.sparkColors(options.text(ClientOption.SPARK_COLORS),
            LookOptions.maxColors(options.integer(ClientOption.MAX_COLORS)), new int[PaintStyle.MAX_SPARK_COLORS]);
    }

    /**
     * BEHAVIOR: a native connection whose CONNECT carries the name, team and shape; the shape is sent as C
     * {@code Send_shape} does: converted without limits ({@link ShipShape#convert}, so an empty or invalid
     * shape is the default ship) and written canonically ({@link ShipShape#toWireString}).
     */
    static ClientConnection nativeConnection(Transport t, String label, ConnectionListener listener, String name,
                                             int team, String shape, XpIdentity id) { // ... and id's real name and host
        ClientConnection c = nativeConnection(t, label, listener, name, team, shape);
        c.connect.realName.setLength(0);
        c.connect.realName.append(id.realName());
        c.connect.hostName.setLength(0);
        c.connect.hostName.append(id.host());
        return c;
    }

    /** As above with {@code GameConnections}' real name and host. */
    static ClientConnection nativeConnection(Transport t, String label, ConnectionListener listener, String name,
                                             int team, String shape) {
        ClientConnection c = new ClientConnection(t, listener, label, (int) System.nanoTime());
        c.connect.name.append(name);
        c.connect.team = team;
        c.connect.shape.append(ShipShape.convert(shape).toWireString());
        c.connect.realName.append(com.fulcrologic.jxpilot.client.xp.GameConnections.realName());
        c.connect.hostName.append(com.fulcrologic.jxpilot.client.xp.GameConnections.hostName());
        return c;
    }
}
