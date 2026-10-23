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
package com.fulcrologic.jxpilot.client.net;

import com.fulcrologic.jxpilot.client.game.ClientGame;
import com.fulcrologic.jxpilot.client.game.ClientWorld;
import com.fulcrologic.jxpilot.common.net.Protocol;

import java.io.IOException;
import java.util.function.LongUnaryOperator;

/**
 * Headless client: connects, joins, receives the map and snapshots, runs the per-frame client
 * pipeline (receive, world update, input, reliable flush) without a window, and prints a digest.
 * Used by integration tests (with a scripted fake server) and from the command line:
 * <pre>
 *   ClientSim --connect host[:port] [--bind addr[:port]] [--name N] [--team T] [--snapshots N] [--seconds S]
 * </pre>
 */
public final class ClientSim {
    private final ClientConnection connection;
    private final ClientGame game;
    private final String label;
    private LongUnaryOperator keysForFrame = f -> 0L;
    private long frames;
    private long lastNow = Long.MIN_VALUE;

    public ClientSim(Transport transport, String label, String name, int team, int seed) {
        this.label = label;
        this.game = new ClientGame();
        this.connection = new ClientConnection(transport, game, label, seed);
        connection.connect.name.append(name);
        connection.connect.team = team;
    }

    public ClientConnection connection() { return connection; }

    public ClientGame game() { return game; }

    public long frames() { return frames; }

    /** Key mask to send for each frame number (default: nothing pressed). */
    public void setKeys(LongUnaryOperator keysForFrame) {
        this.keysForFrame = keysForFrame;
    }

    /** Start the handshake. */
    public void start(long nowNanos) {
        connection.start(nowNanos);
        lastNow = nowNanos;
    }

    /**
     * One client frame at {@code nowNanos}: receive, update the game, send input, flush reliable data.
     * <p>
     * BEHAVIOR: the frame's elapsed time (0 for the first) ages messages and HUD timers.
     */
    public void step(long nowNanos) {
        double dt = lastNow == Long.MIN_VALUE ? 0 : (nowNanos - lastNow) / 1e9;
        lastNow = nowNanos;
        connection.update(nowNanos);
        game.frame(nowNanos, dt);
        connection.sendInput(keysForFrame.applyAsLong(frames), 0, nowNanos);
        connection.flush(nowNanos);
        frames++;
    }

    /**
     * Run in real time (frames every {@code frameMillis}) until {@code snapshots} snapshots arrived,
     * the connection closed, or {@code maxMillis} passed.
     *
     * @return true when the snapshot count was reached
     */
    public boolean runRealtime(long snapshots, long maxMillis, long frameMillis) throws InterruptedException {
        long t0 = System.nanoTime();
        start(t0);
        while (true) {
            long now = System.nanoTime();
            step(now);
            if (connection.snapshotsReceived() >= snapshots) {
                return true;
            }
            if (connection.state() == ConnectionState.CLOSED || now - t0 > maxMillis * 1_000_000L) {
                return false;
            }
            Thread.sleep(frameMillis);
        }
    }

    /**
     * One-line summary of what the client knows.
     * <p>
     * BEHAVIOR: lists state, player id, map name and size, snapshot count, newest tick, interpolated
     * ships and shots, particles, players and messages.
     */
    public String digest() {
        ClientWorld w = game.world();
        StringBuilder sb = new StringBuilder(160);
        sb.append("state=").append(connection.state())
            .append(" player=").append(connection.playerId())
            .append(" map=").append(game.map() == null ? "-" : game.map().name())
            .append(' ').append(game.map() == null ? 0 : game.map().width())
            .append('x').append(game.map() == null ? 0 : game.map().height())
            .append(" snapshots=").append(connection.snapshotsReceived())
            .append(" tick=").append(w.newestTick())
            .append(" ships=").append(w.shipCount())
            .append(" shots=").append(w.shotCount())
            .append(" particles=").append(w.particles().count())
            .append(" players=").append(game.players().count())
            .append(" messages=").append(game.messages().talkCount() + game.messages().gameCount());
        return sb.toString();
    }

    /** Command-line entry point; exits 0 when the snapshot count was reached. */
    public static void main(String[] args) throws IOException, InterruptedException {
        Endpoint server = null;
        Endpoint bind = null;
        String name = "jxsim";
        int team = Protocol.NO_TEAM;
        long snapshots = 100;
        long seconds = 15;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            String v = i + 1 < args.length ? args[i + 1] : null;
            switch (a) {
                case "--connect" -> { server = Endpoint.parse(v, Protocol.DEFAULT_PORT); i++; }
                case "--bind" -> { bind = Endpoint.parse(v, 0); i++; }
                case "--name" -> { name = v; i++; }
                case "--team" -> { team = Integer.parseInt(v); i++; }
                case "--snapshots" -> { snapshots = Long.parseLong(v); i++; }
                case "--seconds" -> { seconds = Long.parseLong(v); i++; }
                default -> {
                    System.err.println("unknown argument " + a);
                    System.exit(2);
                }
            }
        }
        if (server == null) {
            System.err.println("usage: ClientSim --connect host[:port] [--bind addr[:port]] [--name N] [--team T]"
                + " [--snapshots N] [--seconds S]");
            System.exit(2);
            return;
        }
        boolean ok;
        ClientSim sim;
        try (UdpTransport t = UdpTransport.open(server, bind)) {
            sim = new ClientSim(t, server.toString(), name, team, (int) System.nanoTime());
            ok = sim.runRealtime(snapshots, seconds * 1000, 16);
            sim.connection().disconnect(System.nanoTime());
        }
        System.out.println(sim.digest());
        System.out.println(sim.game().status());
        System.exit(ok ? 0 : 1);
    }
}
