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
package com.fulcrologic.jxpilot.client.latency;

import com.fulcrologic.jxpilot.common.config.Options;
import com.fulcrologic.jxpilot.common.map.WorldMap;
import com.fulcrologic.jxpilot.common.net.ConfigTransfer;
import com.fulcrologic.jxpilot.server.host.SimGameHost;
import com.fulcrologic.jxpilot.server.host.SimGameHostFactory;
import com.fulcrologic.jxpilot.server.net.ClientMap;
import com.fulcrologic.jxpilot.server.net.Datagrams;
import com.fulcrologic.jxpilot.server.net.Server;
import com.fulcrologic.jxpilot.server.net.ServerNet;
import com.fulcrologic.jxpilot.server.net.ServerNetConfig;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.World;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * The real Java server (options as {@link Server#start} reads them, {@link SimGameHost} with the real
 * simulation and robots, {@link ServerNet}) without sockets or a loop thread: the harness ticks it in virtual
 * time and feeds it datagrams. Seeded, so the same inputs at the same virtual times give the same game.
 */
final class VirtualServer {
    /** Pixels per click (server positions are in clicks, {@code Click.SHIFT} 6). */
    static final double CLICKS_PER_PIXEL = 64;

    final Options options;
    final WorldMap map;
    final SimGameHost host;
    final ServerNet net;
    final int hz;
    final List<CharSequence> log = new ArrayList<>();

    /**
     * @param args server command line ({@code --map FILE} plus options, as for {@code ServerMain})
     * @param seed the world's RNG seed
     */
    VirtualServer(String[] args, int seed, Datagrams out, LongSupplier clock) {
        try {
            options = Server.readOptions(args);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        map = WorldMap.fromOptions(options);
        ConfigTransfer.Blob blob = ConfigTransfer.encode(options.toConfigText(ClientMap.of(options.mapData(), options)));
        host = (SimGameHost) new SimGameHostFactory(() -> seed, log::add).create(options, map);
        // the game clock C uses for gameDuration: virtual, so a run never depends on the wall clock
        host.simulation().world.wallSeconds = () -> clock.getAsLong() / 1_000_000_000L;
        ServerNetConfig cfg = ServerNetConfig.of(options, map);
        hz = cfg.simHz;
        net = new ServerNet(cfg, host, out, log::add, blob);
    }

    World world() {
        return host.simulation().world;
    }

    /** One server frame at {@code now}, as {@code Server}'s loop runs it. */
    void tick(long now) {
        net.tick(now);
    }

    /** The server's state of the player with this id, or null. */
    Player player(int id) {
        return world().byId(id);
    }

    static double px(int clicks) {
        return clicks / CLICKS_PER_PIXEL;
    }
}
