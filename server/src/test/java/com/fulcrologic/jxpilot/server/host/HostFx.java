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
package com.fulcrologic.jxpilot.server.host;

import com.fulcrologic.jxpilot.common.net.Msg;
import com.fulcrologic.jxpilot.common.net.Protocol;
import com.fulcrologic.jxpilot.common.net.ReliableChannel;
import com.fulcrologic.jxpilot.common.net.ReliableType;
import com.fulcrologic.jxpilot.server.TestWorlds;
import com.fulcrologic.jxpilot.server.net.GameHost;
import com.fulcrologic.jxpilot.server.sim.Simulation;
import com.fulcrologic.jxpilot.server.world.Events;
import com.fulcrologic.jxpilot.server.world.World;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/** Fixtures for the {@link SimGameHost} tests: small maps, a decoding client channel, a sessions spy, payloads. */
final class HostFx {
    private HostFx() {}

    /** One tick per C frame at 60 fps, no gravity, no robots unless a test asks for them. */
    static final String BASE = "framesPerSecond: 60\ngravity: 0\nmaxRobots: 0\nmapName: Arena\nmapAuthor: Bob\n";

    /** Three free bases on one row: indices 0, 1, 2 at bx 2, 5, 8, by 2 (row 2 of 5 from the top). */
    static final String[] THREE = {
        "xxxxxxxxxxx",
        "x         x",
        "x _  _  _ x",
        "x         x",
        "xxxxxxxxxxx",
    };

    /** Four free bases: indices 0..3 at bx 2, 4, 6, 8. */
    static final String[] FOUR = {
        "xxxxxxxxxxx",
        "x         x",
        "x _ _ _ _ x",
        "x         x",
        "xxxxxxxxxxx",
    };

    /** Two free bases: indices 0, 1 at bx 2, 5. */
    static final String[] TWO = {
        "xxxxxxxx",
        "x      x",
        "x _  _ x",
        "x      x",
        "xxxxxxxx",
    };

    /** Team play: team 0 (the robot team by default) has bx 2, team 1 bx 4 and 6, team 2 bx 8. */
    static final String[] TEAMS = {
        "xxxxxxxxxxx",
        "x         x",
        "x 0 1 1 2 x",
        "x         x",
        "xxxxxxxxxxx",
    };

    static Simulation sim(String extra, String... rows) {
        return TestWorlds.sim(BASE + extra, rows);
    }

    /** Let the robot manager log in robots (outside the host) until {@code n} are in the game. */
    static void addRobots(Simulation s, int n) {
        boolean was = s.robots.enabled();
        s.robots.setEnabled(true);
        for (int i = 0; i < 100_000 && s.robots.numRobots() < n; i++) {
            s.robots.update();
        }
        s.robots.setEnabled(was);
        if (s.robots.numRobots() != n) {
            throw new IllegalStateException("robots: " + s.robots.numRobots());
        }
    }

    /** Join (asserting success) and send READY. */
    static int enter(SimGameHost h, String name, int team) {
        int id = h.join(name, team, "");
        if (id <= 0) {
            throw new IllegalStateException("join refused: " + id);
        }
        h.clientMessage(id, ReliableType.READY, empty());
        return id;
    }

    static ByteBuffer empty() {
        return ByteBuffer.allocate(0);
    }

    static ByteBuffer chat(String text) {
        Msg.Text t = new Msg.Text();
        t.kind = Msg.Text.KIND_CHAT;
        t.text.append(text);
        ByteBuffer b = ByteBuffer.allocate(1100);
        t.write(b);
        return b.flip();
    }

    static ByteBuffer settings(float power, float turnSpeed, float turnRes, float altPower, float altTurn, float altRes) {
        Msg.Settings s = new Msg.Settings();
        s.power = power;
        s.turnSpeed = turnSpeed;
        s.turnResistance = turnRes;
        s.altPower = altPower;
        s.altTurnSpeed = altTurn;
        s.altTurnResistance = altRes;
        ByteBuffer b = ByteBuffer.allocate(64);
        s.write(b);
        return b.flip();
    }

    /** Records {@link GameHost.Sessions#kick} calls and, like ServerNet, gives the host its leave. */
    static final class Kicks implements GameHost.Sessions {
        final List<String> calls = new ArrayList<>();
        GameHost host;

        Kicks(GameHost host) {
            this.host = host;
            host.attach(this);
        }

        @Override
        public void kick(int playerId, CharSequence reason) {
            calls.add(playerId + " " + reason);
            host.leave(playerId);
        }
    }

    /**
     * A client's end of the reliable channel: the host writes into {@link #out}; {@link #take} moves everything
     * through real RELIABLE bodies into a client channel, acknowledges it, and returns the decoded messages.
     */
    static final class Wire {
        final ReliableChannel out = new ReliableChannel();
        private final ReliableChannel in = new ReliableChannel();
        private final ByteBuffer buf = ByteBuffer.allocate(Protocol.MAX_PACKET);
        private long now = 1_000_000_000L;

        List<String> take() {
            List<String> got = new ArrayList<>();
            for (int i = 0; i < 100_000; i++) {
                buf.clear();
                if (out.write(buf, now) == 0) {
                    break;
                }
                buf.flip();
                if (in.read(buf, (type, p) -> got.add(render(type, p))) < 0) {
                    throw new AssertionError("malformed RELIABLE body");
                }
                now += 1_000_000L;
                out.onAck(in.ack(), now);
            }
            return got;
        }
    }

    /** One reliable message as a readable line (fields in wire order). */
    static String render(int type, ByteBuffer p) {
        String s = switch (type) {
            case ReliableType.PLAYER_JOIN -> {
                Msg.PlayerJoin m = new Msg.PlayerJoin();
                m.read(p);
                yield "JOIN " + m.id + " team=" + m.team + " flags=" + m.flags + " power=" + m.power + " ab="
                    + m.afterburners + " name=" + m.name + " shape=" + m.shape;
            }
            case ReliableType.PLAYER_LEAVE -> {
                Msg.PlayerLeave m = new Msg.PlayerLeave();
                m.read(p);
                yield "LEAVE " + m.id;
            }
            case ReliableType.PLAYER_TEAM -> {
                Msg.PlayerTeam m = new Msg.PlayerTeam();
                m.read(p);
                yield "TEAM " + m.id + " " + m.team;
            }
            case ReliableType.SCORE -> {
                Msg.Score m = new Msg.Score();
                m.read(p);
                yield "SCORE " + m.id + " " + m.score + " lives=" + m.lives + " status=" + m.status;
            }
            case ReliableType.TEAM_SCORE -> {
                Msg.TeamScore m = new Msg.TeamScore();
                m.read(p);
                yield "TEAM_SCORE " + m.team + " " + m.score;
            }
            case ReliableType.MESSAGE -> {
                Msg.Text m = new Msg.Text();
                m.read(p);
                yield "MSG " + m.kind + " from=" + m.from + " " + m.text;
            }
            case ReliableType.BASE_OWNER -> {
                Msg.BaseOwner m = new Msg.BaseOwner();
                m.read(p);
                yield "BASE " + m.baseIndex + " " + m.playerId;
            }
            case ReliableType.GAME_EVENT -> {
                Msg.GameEvent m = new Msg.GameEvent();
                m.read(p);
                yield "EVENT " + m.kind + " " + m.a + " " + m.b + " " + m.value;
            }
            case ReliableType.PLAYER_THRUST -> {
                Msg.PlayerThrust m = new Msg.PlayerThrust();
                m.read(p);
                yield "THRUST " + m.id + " " + m.power + " ab=" + m.afterburners;
            }
            case ReliableType.SCORE_OBJECT -> {
                Msg.ScoreObject m = new Msg.ScoreObject();
                m.read(p);
                yield "SCORE_OBJECT " + m.score + " " + m.bx + "," + m.by + " " + m.text;
            }
            case ReliableType.SEEK -> {
                Msg.Seek m = new Msg.Seek();
                m.read(p);
                yield "SEEK " + m.programmerId + " " + m.robotId + " " + m.soughtId;
            }
            default -> "type " + type;
        };
        if (p.hasRemaining()) {
            throw new AssertionError(s + ": " + p.remaining() + " bytes left over");
        }
        return s;
    }

    /** Sim events since {@code from} as "KIND a b c" (MESSAGE: "MESSAGE a b c text"). */
    static List<String> events(World w, long from) {
        List<String> out = new ArrayList<>();
        Events e = w.events;
        for (long q = Math.max(from, e.oldestSeq()); q < e.seq(); q++) {
            int i = Events.index(q);
            String k = switch (e.kind[i]) {
                case Events.JOIN -> "JOIN";
                case Events.LEAVE -> "LEAVE";
                case Events.TEAM -> "TEAM";
                case Events.SCORE -> "SCORE";
                case Events.TEAM_SCORE -> "TEAM_SCORE";
                case Events.MESSAGE -> "MESSAGE";
                case Events.BASE_OWNER -> "BASE_OWNER";
                case Events.GAME_EVENT -> "GAME_EVENT";
                case Events.THRUST -> "THRUST";
                case Events.SCORE_OBJECT -> "SCORE_OBJECT";
                default -> "?" + e.kind[i];
            };
            String line = k + " " + e.a[i] + " " + e.b[i] + " " + e.c[i];
            out.add(e.kind[i] == Events.MESSAGE ? line + " " + e.text[i] : line);
        }
        return out;
    }

    /** Only the lines starting with one of {@code prefixes}. */
    static List<String> only(List<String> l, String... prefixes) {
        List<String> out = new ArrayList<>();
        for (String s : l) {
            for (String p : prefixes) {
                if (s.startsWith(p)) {
                    out.add(s);
                    break;
                }
            }
        }
        return out;
    }
}
