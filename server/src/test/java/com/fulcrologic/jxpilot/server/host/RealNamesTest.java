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
import com.fulcrologic.jxpilot.server.sim.Simulation;
import com.fulcrologic.jxpilot.server.world.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static com.fulcrologic.jxpilot.server.host.HostFx.THREE;
import static com.fulcrologic.jxpilot.server.host.HostFx.empty;
import static com.fulcrologic.jxpilot.server.host.HostFx.events;
import static com.fulcrologic.jxpilot.server.host.HostFx.sim;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Real and host names (C realname/hostname): from the client, robots and tanks; shown and matched as C. */
@DisplayName("real and host names")
class RealNamesTest {
    /** The PLAYER_JOIN messages of a viewer's initial state, as "name=real@host". */
    static List<String> joins(SimGameHost h, int viewer) {
        ReliableChannel out = new ReliableChannel();
        h.initialState(viewer, out);
        ReliableChannel in = new ReliableChannel();
        ByteBuffer buf = ByteBuffer.allocate(Protocol.MAX_PACKET);
        List<String> got = new ArrayList<>();
        long now = 1_000_000_000L;
        for (int i = 0; i < 1000; i++) {
            buf.clear();
            if (out.write(buf, now) == 0) {
                break;
            }
            buf.flip();
            in.read(buf, (type, p) -> {
                if (type == ReliableType.PLAYER_JOIN) {
                    Msg.PlayerJoin j = new Msg.PlayerJoin();
                    j.read(p);
                    got.add(j.name + "=" + j.realName + "@" + j.hostName);
                }
            });
            now += 1_000_000L;
            out.onAck(in.ack(), now);
        }
        return got;
    }

    static int enter(SimGameHost h, String name, String real, String host) {
        int id = h.join(name, 0, "");
        h.identity(id, real, host, "10.0.0.9");
        h.clientMessage(id, ReliableType.READY, empty());
        return id;
    }

    @Test
    @DisplayName("the client's names are cleaned as C; none sent gives X and xxx.xxx; the welcome text shows the real name")
    void identity() {
        Simulation s = sim("", THREE);
        SimGameHost h = new SimGameHost(s);
        int ann = enter(h, "Ann", "tony kéy", "-box..local");
        long from = s.world.events.seq();
        int bob = h.join("Bob", 0, "");
        h.clientMessage(bob, ReliableType.READY, empty());
        Player a = s.world.byId(ann);
        Player b = s.world.byId(bob);
        assertAll(
            () -> assertEquals("tonyxkxy", a.realName.toString(), "a space and a Latin-1 letter become x"),
            () -> assertEquals("xxx.xxx", a.hostName.toString(), "a host not starting alphanumeric"),
            () -> assertEquals("10.0.0.9", a.address.toString(), "the address"),
            () -> assertEquals("X@xxx.xxx", b.realName + "@" + b.hostName, "a client that sends no names"),
            () -> assertEquals(List.of("MESSAGE 3 -1 0 Bob (X) has entered \"Arena\", made by Bob."),
                HostFx.only(events(s.world, from), "MESSAGE"), "netserver.c:1108-1112"));
    }

    @Test
    @DisplayName("PLAYER_JOIN carries every player's real and host names")
    void join() {
        Simulation s = sim("", THREE);
        SimGameHost h = new SimGameHost(s);
        int ann = enter(h, "Ann", "tony", "box.local");
        int bob = enter(h, "Bob", "", "");
        assertEquals(List.of("Ann=tony@box.local", "Bob=X@xxx.xxx"), joins(h, bob), "both joins");
    }

    @Test
    @DisplayName("robots and tanks take robotRealName/robotHostName and tankRealName/tankHostName as set")
    void robotsAndTanks() {
        Simulation s = sim("maxRobots: 1\nminRobots: 1\nrobotRealName: droid\nrobotHostName: bots.example\ntankRealName: can\n"
            + "tankHostName: tanks.example\n", THREE);
        HostFx.addRobots(s, 1);
        Player r = s.world.playerAt(0);
        Player owner = s.ships.join("Ann", 0);
        s.tanks.add(owner, 50 * 256);
        s.tanks.detach(owner);
        Player tank = null;
        for (int k = 0; k < s.world.numPlayers; k++) {
            if (s.world.playerAt(k).tank) {
                tank = s.world.playerAt(k);
            }
        }
        Player t = tank;
        assertAll(
            () -> assertEquals("droid@bots.example", r.realName + "@" + r.hostName, "robot.c:784-785"),
            () -> assertEquals("can@tanks.example", t.realName + "@" + t.hostName, "ship.c:439-440"));
    }

    @Test
    @DisplayName("a chat line addressed to part of a real name reaches that player (netserver.c:2745-2749)")
    void talk() {
        Simulation s = sim("", THREE);
        Player ann = s.ships.join("Ann", 0);
        ann.realName.append("tony");
        Player bob = s.ships.join("Bob", 0);
        Talk t = new Talk(s.world);
        List<String> ev = TalkTest.say(s.world, t, bob, "ton:hello");
        assertEquals(List.of("MESSAGE " + Msg.Text.KIND_PRIVATE + " " + bob.id + " " + ann.id + " hello [Bob]:[Ann]",
            "MESSAGE " + Msg.Text.KIND_PRIVATE + " " + bob.id + " " + bob.id + " hello [Bob]:[Ann]"), ev,
            "to Ann and back to the sender");
    }
}
