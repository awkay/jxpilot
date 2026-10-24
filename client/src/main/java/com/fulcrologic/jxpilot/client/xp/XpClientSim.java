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

import com.fulcrologic.jxpilot.client.game.ClientGame;
import com.fulcrologic.jxpilot.client.game.ClientWorld;
import com.fulcrologic.jxpilot.client.net.ConnectionListener;
import com.fulcrologic.jxpilot.client.net.ConnectionState;
import com.fulcrologic.jxpilot.common.config.Options;
import com.fulcrologic.jxpilot.common.map.WorldMap;
import com.fulcrologic.jxpilot.common.net.ControlKey;
import com.fulcrologic.jxpilot.common.net.Msg;
import com.fulcrologic.jxpilot.common.net.Protocol;
import com.fulcrologic.jxpilot.common.net.ReliableType;

import java.nio.ByteBuffer;
import java.util.Locale;

/**
 * Headless client for a real server, normally an original C XPilot 4.5.5 server: joins, prints the
 * map, then every few frames the own ship's position, velocity and fuel, the visible ship count and
 * every message, while a key script thrusts, turns and chats. Ends with protocol counters.
 * <pre>
 *   XpClientSim --connect xp://127.0.0.1:15345 [--protocol xpilot|jxpilot] [--name N] [--team T]
 *               [--seconds S] [--every N] [--no-script] [--chat TEXT]
 * </pre>
 * Script (seconds after the first snapshot): thrust 1-3, turn left 4-5, thrust 5-6, chat at 6.
 * Exit code 0 when it played until the end without malformed packets.
 */
public final class XpClientSim {
    private XpClientSim() {}

    /** Prints messages as they arrive, then passes everything on. */
    static final class Tap implements ConnectionListener {
        final ConnectionListener next;
        final Msg.Text text = new Msg.Text();
        long messages;

        Tap(ConnectionListener next) {
            this.next = next;
        }

        @Override
        public void onAccepted(int playerId) {
            next.onAccepted(playerId);
        }

        @Override
        public void onWelcome(Msg.Welcome w) {
            next.onWelcome(w);
        }

        @Override
        public void onConfig(Msg.Welcome w, Options o, WorldMap m) {
            System.out.printf(Locale.ROOT, "map \"%s\" by %s: %dx%d blocks (%dx%d px), %d fps%s%n", m.name(),
                m.author(), m.width(), m.height(), m.pixelWidth(), m.pixelHeight(), (int) w.gameFps,
                m.edgeWrap() ? ", wrapping" : "");
            next.onConfig(w, o, m);
        }

        @Override
        public void onReliable(int type, ByteBuffer p) {
            if (type == ReliableType.MESSAGE) {
                int pos = p.position();
                text.read(p);
                p.position(pos);
                messages++;
                System.out.println("message: " + text.text);
            }
            next.onReliable(type, p);
        }

        @Override
        public void onSnapshot(ByteBuffer body, long nowNanos) {
            next.onSnapshot(body, nowNanos);
        }

        @Override
        public void onStateChange(ConnectionState s, CharSequence status) {
            System.out.println("state " + s + ": " + status);
            next.onStateChange(s, status);
        }

        @Override
        public void onIdentity(int id, CharSequence realName, CharSequence host) {
            next.onIdentity(id, realName, host);
        }
    }

    public static void main(String[] args) throws Exception {
        String connect = null;
        String protocol = null;
        String name = "Jxsim";
        int team = Protocol.NO_TEAM;
        int seconds = 30;
        int every = 12;
        boolean script = true;
        String chatLine = "hello from jxpilot";
        for (int i = 0; i < args.length; i++) {
            String v = i + 1 < args.length ? args[i + 1] : null;
            switch (args[i]) {
                case "--connect" -> { connect = v; i++; }
                case "--protocol" -> { protocol = v; i++; }
                case "--name" -> { name = v; i++; }
                case "--team" -> { team = Integer.parseInt(v); i++; }
                case "--seconds" -> { seconds = Integer.parseInt(v); i++; }
                case "--every" -> { every = Integer.parseInt(v); i++; }
                case "--chat" -> { chatLine = v; i++; }
                case "--no-script" -> script = false;
                default -> {
                    System.err.println("unknown argument " + args[i]);
                    System.exit(2);
                }
            }
        }
        if (connect == null) {
            System.err.println("usage: XpClientSim --connect xp://host[:port] [--protocol xpilot|jxpilot] [--name N]"
                + " [--team T] [--seconds S] [--every N] [--no-script] [--chat TEXT]");
            System.exit(2);
        }
        ConnectTarget target = ConnectTarget.parse(connect, protocol);
        ClientGame game = new ClientGame();
        Tap tap = new Tap(game);
        System.exit(run(target, game, tap, name, team, seconds, every, script, chatLine) ? 0 : 1);
    }

    @SuppressWarnings("checkstyle:ParameterNumber")
    static boolean run(ConnectTarget target, ClientGame game, Tap tap, String name, int team, int seconds,
                       int every, boolean script, String chatLine) throws Exception {
        long thrust = ControlKey.THRUST.mask();
        long left = ControlKey.TURN_LEFT.mask();
        try (GameConnection conn = GameConnections.open(target, tap, name, team, null, (int) System.nanoTime())) {
            long t0 = System.nanoTime();
            long last = t0;
            long firstSnap = -1;
            long printed = -1;
            boolean chatted = false;
            float startX = 0;
            float startY = 0;
            float maxDist = 0;
            conn.start(t0);
            while (System.nanoTime() - t0 < seconds * 1_000_000_000L && conn.state() != ConnectionState.CLOSED) {
                long now = System.nanoTime();
                conn.update(now);
                game.frame(now, (now - last) / 1e9);
                last = now;
                ClientWorld w = game.world();
                long keys = 0;
                if (conn.snapshotsReceived() > 0 && w.hasSelf()) {
                    if (firstSnap < 0) {
                        firstSnap = now;
                        startX = w.selfX();
                        startY = w.selfY();
                    }
                    double t = (now - firstSnap) / 1e9;
                    if (script) {
                        if (t >= 1 && t < 3 || t >= 5 && t < 6) {
                            keys |= thrust;
                        }
                        if (t >= 4 && t < 5) {
                            keys |= left;
                        }
                        if (t >= 6 && !chatted) {
                            chatted = conn.sendChat(chatLine, now);
                            System.out.println("sent chat: " + chatLine + " (" + chatted + ")");
                        }
                    }
                    float dx = w.selfX() - startX;
                    float dy = w.selfY() - startY;
                    maxDist = Math.max(maxDist, (float) Math.sqrt(dx * dx + dy * dy));
                    long snaps = conn.snapshotsReceived();
                    if (snaps / every != printed) {
                        printed = snaps / every;
                        System.out.printf(Locale.ROOT,
                            "t=%5.2f snap=%d tick=%d pos=(%.0f,%.0f) vel=(%.1f,%.1f) heading=%.2f fuel=%.0f/%.0f"
                                + " ships=%d shots=%d particles=%d radar=%d players=%d keys=%s%n",
                            t, snaps, w.newestTick(), w.selfX(), w.selfY(), w.selfVx(), w.selfVy(),
                            w.selfHeading(), w.selfFuel(), w.selfFuelMax(), w.shipCount(), w.shotCount(),
                            w.particles().count(), w.radarCount(), game.players().count(),
                            keys == 0 ? "-" : ((keys & thrust) != 0 ? "thrust" : "") + ((keys & left) != 0 ? "left" : ""));
                    }
                }
                conn.sendInput(keys, 0, now);
                conn.flush(now);
                Thread.sleep(16);
            }
            boolean played = conn.state() == ConnectionState.PLAYING;
            conn.disconnect(System.nanoTime());
            System.out.printf(Locale.ROOT, "summary: protocol=%s played=%s snapshots=%d packetsIn=%d packetsOut=%d"
                    + " malformed=%d messages=%d players=%d maxDistanceMoved=%.0f%n",
                conn.protocol(), played, conn.snapshotsReceived(), conn.packetsReceived(), conn.packetsSent(),
                conn.malformed(), tap.messages, game.players().count(), maxDist);
            if (conn instanceof XpConnection xp) {
                System.out.printf(Locale.ROOT, "xpilot: version=0x%x loginPort=%d unknown=%d stale=%d badStream=%d"
                        + " ignoredReliable=%d culled=%d badIndex=%d worldMalformed=%d%n",
                    xp.version(), xp.loginPort(), xp.unknownPackets(), xp.staleFrames(), xp.badStream(),
                    xp.reliableDecoder().ignored(), xp.translator().culled(), xp.translator().badIndex(),
                    game.world().malformedSnapshots());
            }
            return played && conn.malformed() == 0;
        }
    }
}
