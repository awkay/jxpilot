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

import com.fulcrologic.jxpilot.common.config.Options;
import com.fulcrologic.jxpilot.common.map.WorldMap;
import com.fulcrologic.jxpilot.common.net.Msg;
import com.fulcrologic.jxpilot.common.net.Protocol;

/** Tunables of {@link ServerNet}. Plain mutable holder; set fields before constructing ServerNet. */
public final class ServerNetConfig {
    /** Simulation ticks per second. */
    public int simHz = 60;
    /** A snapshot every this many ticks. */
    public int snapshotDivisor = Protocol.DEFAULT_SNAPSHOT_DIVISOR;
    /** RADAR in every this many snapshots. */
    public int radarDivisor = Protocol.DEFAULT_RADAR_DIVISOR;
    /** Connection pool size (each preallocates a reliable channel, about 70 KB). */
    public int maxConnections = 32;
    /** Drop a peer after this long without a packet. */
    public long timeoutNanos = Protocol.TIMEOUT_MS * 1_000_000L;
    /** WELCOME flags ({@link Msg.Welcome} FLAG_*). */
    public int welcomeFlags;
    /** Original XPilot frames per second, for WELCOME. */
    public float gameFps = 14;
    /** Shown to clients in WELCOME. */
    public String serverName = "JXPilot";
    public double worldWidth = 3500;
    public double worldHeight = 3500;
    public boolean edgeWrap;
    /** Per source IP: sustained packets per second. */
    public double ratePerSecond = 400;
    /** Per source IP: burst size in packets. */
    public double rateBurst = 800;
    /** Extra cost, in packets, of a CONNECT that would create a session. */
    public double connectCost = 100;
    /** Reliable datagrams per peer per tick (bounds map-transfer bandwidth: 8 x 1.2 KB x 60 Hz). */
    public int maxReliablePacketsPerTick = 8;
    /** Reliable window slots kept free for game messages while the map streams. */
    public int configReserveSlots = 4;

    /**
     * Settings for a loaded map.
     * <p>
     * BEHAVIOR: WELCOME flags mirror the map's teamPlay, edgeWrap and timing; world size is the map's
     * pixel size; gameFps is framesPerSecond; the server name includes the map name.
     */
    public static ServerNetConfig of(Options options, WorldMap map) {
        ServerNetConfig c = new ServerNetConfig();
        c.welcomeFlags = (map.teamPlay() ? Msg.Welcome.FLAG_TEAM_PLAY : 0)
            | (map.edgeWrap() ? Msg.Welcome.FLAG_EDGE_WRAP : 0)
            | (map.timing() ? Msg.Welcome.FLAG_TIMING : 0);
        c.gameFps = options.framesPerSecond();
        c.serverName = "JXPilot: " + map.name();
        c.worldWidth = map.pixelWidth();
        c.worldHeight = map.pixelHeight();
        c.edgeWrap = map.edgeWrap();
        return c;
    }
}
