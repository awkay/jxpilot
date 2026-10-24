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

import com.fulcrologic.jxpilot.common.config.Opt;
import com.fulcrologic.jxpilot.common.config.Options;
import com.fulcrologic.jxpilot.common.map.WorldMap;
import com.fulcrologic.jxpilot.common.net.Msg;
import com.fulcrologic.jxpilot.common.net.Protocol;

/** Tunables of {@link ServerNet}. Plain mutable holder; set fields before constructing ServerNet. */
public final class ServerNetConfig {
    /**
     * Simulation ticks per second: the map's framesPerSecond (one tick per C frame, XPilot default 14).
     * Everything else here is in time units, so it holds at any tick rate.
     */
    public int simHz = 14;
    /** A snapshot every this many ticks. */
    public int snapshotDivisor = Protocol.DEFAULT_SNAPSHOT_DIVISOR;
    /** RADAR in every this many snapshots. */
    public int radarDivisor = Protocol.DEFAULT_RADAR_DIVISOR;
    /** Connection pool size (each preallocates a reliable channel, about 70 KB). */
    public int maxConnections = 32;
    /** Sessions per source (IPv4 address or IPv6 /64); 0 or less is unlimited. Beyond it: REJECT FULL. */
    public int maxPerIp = 4;
    /**
     * Drop a verified peer after this long without a packet, or when its oldest unacknowledged reliable
     * message has waited this long.
     */
    public long timeoutNanos = Protocol.TIMEOUT_MS * 1_000_000L;
    /** Drop a peer whose reliable overflow queue holds more messages than this (it is not acking). */
    public int maxOverflow = 256;
    /** First ACCEPT resend interval for an unverified session; doubles with each resend. */
    public long acceptResendNanos = Protocol.CONNECT_RESEND_MS * 1_000_000L;
    /**
     * ACCEPT resends for an unverified session. It is dropped one doubled interval after the last, so
     * with 3 resends at 250 ms it lives 250 * (1 + 2 + 4 + 8) = 3.75 s and gets 4 ACCEPTs.
     */
    public int acceptResends = 3;
    /** WELCOME flags ({@link Msg.Welcome} FLAG_*). */
    public int welcomeFlags;
    /** Original XPilot frames per second, for WELCOME. */
    public float gameFps = 14;
    /** Shown to clients in WELCOME. */
    public String serverName = "JXPilot";
    public double worldWidth = 3500;
    public double worldHeight = 3500;
    public boolean edgeWrap;
    /** Per source (IPv4 address or IPv6 /64), for packets outside a session: sustained packets per second. */
    public double ratePerSecond = 400;
    /** Per source, outside a session: burst size in packets. */
    public double rateBurst = 800;
    /** Per session (one connected client): sustained packets per second. */
    public double sessionRatePerSecond = 400;
    /** Per session: burst size in packets. */
    public double sessionRateBurst = 800;
    /** Extra per-source cost, in packets, of a CONNECT that would create a session. */
    public double connectCost = 100;
    /**
     * Reliable datagrams per peer per second (bounds map-transfer bandwidth: 480 x 1.2 KB = 576 KB/s);
     * spread over the ticks by {@link #reliablePacketsPerTick()}.
     */
    public int reliablePacketsPerSecond = 480;
    /** Reliable window slots kept free for game messages while the map streams. */
    public int configReserveSlots = 4;
    /** C {@code denyHosts}: CONNECTs from these networks are ignored ({@link DenyList}). */
    public Cidr[] denyHosts = new Cidr[0];

    /** BEHAVIOR: reliable datagrams per peer per tick: reliablePacketsPerSecond / simHz rounded up, at least 1. */
    public int reliablePacketsPerTick() {
        return Math.max(1, (reliablePacketsPerSecond + simHz - 1) / simHz);
    }

    /**
     * Settings for a loaded map and the server's options.
     * <p>
     * BEHAVIOR: WELCOME flags mirror the map's teamPlay, edgeWrap and timing; world size is the map's
     * pixel size; the tick rate simHz and gameFps are framesPerSecond (simHz clamped to 1..255, it is a
     * u8 in WELCOME); the server name includes the map name.
     * BEHAVIOR: snapshotDivisor (clamped to 1..simHz), radarDivisor (at least 1), the per-source and
     * per-session packet rates and bursts (at least 1), maxClientsPerIP and denyHosts come from the options.
     */
    public static ServerNetConfig of(Options options, WorldMap map) {
        ServerNetConfig c = new ServerNetConfig();
        c.welcomeFlags = (map.teamPlay() ? Msg.Welcome.FLAG_TEAM_PLAY : 0)
            | (map.edgeWrap() ? Msg.Welcome.FLAG_EDGE_WRAP : 0)
            | (map.timing() ? Msg.Welcome.FLAG_TIMING : 0);
        c.gameFps = options.framesPerSecond();
        c.simHz = Math.clamp(options.framesPerSecond(), 1, 255);
        c.serverName = "JXPilot: " + map.name();
        c.worldWidth = map.pixelWidth();
        c.worldHeight = map.pixelHeight();
        c.edgeWrap = map.edgeWrap();
        c.snapshotDivisor = Math.clamp(options.getInt(Opt.SNAPSHOT_DIVISOR), 1, c.simHz);
        c.radarDivisor = Math.max(1, options.getInt(Opt.RADAR_DIVISOR));
        c.ratePerSecond = Math.max(1, options.getDouble(Opt.IP_PACKET_RATE));
        c.rateBurst = Math.max(1, options.getDouble(Opt.IP_PACKET_BURST));
        c.sessionRatePerSecond = Math.max(1, options.getDouble(Opt.SESSION_PACKET_RATE));
        c.sessionRateBurst = Math.max(1, options.getDouble(Opt.SESSION_PACKET_BURST));
        c.maxPerIp = options.getInt(Opt.MAX_CLIENTS_PER_IP);
        c.denyHosts = DenyList.parse(options.getString(Opt.DENY_HOSTS));
        return c;
    }
}
