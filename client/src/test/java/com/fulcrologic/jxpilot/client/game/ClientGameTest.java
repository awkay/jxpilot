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
package com.fulcrologic.jxpilot.client.game;

import com.fulcrologic.jxpilot.client.net.ConnectionState;
import com.fulcrologic.jxpilot.common.net.Msg;
import com.fulcrologic.jxpilot.common.net.ReliableType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

class ClientGameTest {
    static ByteBuffer payload(Msg.Payload p) {
        ByteBuffer b = ByteBuffer.allocate(1100);
        p.write(b);
        return b.flip();
    }

    static Msg.PlayerJoin join(int id, int team, String name) {
        Msg.PlayerJoin j = new Msg.PlayerJoin();
        j.id = id;
        j.team = team;
        j.name.append(name);
        return j;
    }

    static Msg.Score score(int id, float s) {
        Msg.Score m = new Msg.Score();
        m.id = id;
        m.score = s;
        return m;
    }

    static ClientGame configured() {
        WorldFixture f = new WorldFixture(true);
        ClientGame g = new ClientGame(16, 16, 256, 16);
        f.welcome.flags = Msg.Welcome.FLAG_TEAM_PLAY;
        g.onAccepted(7);
        g.onWelcome(f.welcome);
        g.onConfig(f.welcome, f.options, f.map);
        return g;
    }

    @Test
    @DisplayName("reliable events apply in arrival order: an event for an unknown player is a no-op")
    void ordering() {
        ClientGame g = configured();
        g.onReliable(ReliableType.SCORE, payload(score(9, 3)));
        g.onReliable(ReliableType.PLAYER_JOIN, payload(join(9, 1, "Robby")));
        g.onReliable(ReliableType.SCORE, payload(score(9, 5)));
        Msg.PlayerLeave leave = new Msg.PlayerLeave();
        leave.id = 9;
        g.onReliable(ReliableType.PLAYER_LEAVE, payload(leave));
        int afterLeave = g.players().count();
        g.onReliable(ReliableType.PLAYER_JOIN, payload(join(9, 1, "Robby")));
        Msg.PlayerTeam t = new Msg.PlayerTeam();
        t.id = 9;
        t.team = 2;
        g.onReliable(ReliableType.PLAYER_TEAM, payload(t));
        Msg.PlayerThrust th = new Msg.PlayerThrust();
        th.id = 9;
        th.power = 30;
        th.afterburners = 5;
        g.onReliable(ReliableType.PLAYER_THRUST, payload(th));
        Msg.TeamScore ts = new Msg.TeamScore();
        ts.team = 2;
        ts.score = 17;
        g.onReliable(ReliableType.TEAM_SCORE, payload(ts));
        Msg.Text m1 = new Msg.Text();
        m1.kind = Msg.Text.KIND_GAME;
        m1.text.append("first");
        Msg.Text m2 = new Msg.Text();
        m2.kind = Msg.Text.KIND_GAME;
        m2.text.append("second");
        g.onReliable(ReliableType.MESSAGE, payload(m1));
        g.onReliable(ReliableType.MESSAGE, payload(m2));
        Msg.BaseOwner bo = new Msg.BaseOwner();
        bo.baseIndex = 0;
        bo.playerId = 9;
        g.onReliable(ReliableType.BASE_OWNER, payload(bo));
        Msg.GameEvent ev = new Msg.GameEvent();
        ev.kind = Msg.GameEvent.KILL;
        ev.a = 9;
        ev.b = 7;
        g.onReliable(ReliableType.GAME_EVENT, payload(ev));
        g.onReliable(200, ByteBuffer.allocate(3));
        int slot = g.players().slotOf(9);
        assertAll(
            () -> assertEquals(0, afterLeave, "leave removes the player"),
            () -> assertEquals(1, g.players().count(), "rejoined once"),
            () -> assertEquals(0f, g.players().score(slot), "the rejoin starts at 0; the early SCORE was a no-op"),
            () -> assertEquals(2, g.players().team(slot), "team change applied after the join"),
            () -> assertEquals(30f, g.players().power(slot), "thrust power"),
            () -> assertEquals(5, g.players().afterburners(slot), "afterburners"),
            () -> assertEquals(17f, g.scoreboard().teamScore(2), "team score"),
            () -> assertEquals("second", g.messages().game(0).text().toString(), "newest message first"),
            () -> assertEquals("first", g.messages().game(1).text().toString(), "older message second"),
            () -> assertEquals(9, g.world().baseOwner(0), "base owner"),
            () -> assertEquals(Msg.GameEvent.KILL, g.events().kind(0), "game event"),
            () -> assertEquals(13, g.reliableCount(), "every message counted, unknown ones too"),
            () -> assertTrue(g.teamPlay(), "team play from WELCOME"));
    }

    @Test
    @DisplayName("an own score change is shown for 100 frames; others are not")
    void scoreMessage() {
        ClientGame g = configured();
        g.onReliable(ReliableType.PLAYER_JOIN, payload(join(7, 0, "me")));
        g.onReliable(ReliableType.PLAYER_JOIN, payload(join(9, 0, "it")));
        g.onReliable(ReliableType.SCORE, payload(score(9, 4)));
        double otherLife = g.scoreMessageLife();
        g.onReliable(ReliableType.SCORE, payload(score(7, 2.5f)));
        double life = g.scoreMessageLife();
        g.frame(WorldFixture.at(100), 1.0);
        double afterSecond = g.scoreMessageLife();
        g.frame(WorldFixture.at(100), 10.0);
        assertAll(
            () -> assertEquals(0.0, otherLife, "another player's score shows nothing"),
            () -> assertEquals(100.0, life, "own change shown for 100 frames"),
            () -> assertEquals(2.5f, g.scoreDelta(), "the change"),
            () -> assertEquals(85.0, afterSecond, 1e-9, "one second at 15 fps ages it 15 frames"),
            () -> assertEquals(0.0, g.scoreMessageLife(), "never below 0"));
    }

    @Test
    @DisplayName("a new session forgets players; losing the connection logs the status")
    void sessionAndStatus() {
        ClientGame g = configured();
        g.onReliable(ReliableType.PLAYER_JOIN, payload(join(9, 0, "it")));
        g.onStateChange(ConnectionState.PLAYING, "Playing");
        g.onStateChange(ConnectionState.CONNECTING, "lost, reconnecting");
        int msgs = g.messages().gameCount();
        g.onAccepted(8);
        g.onStateChange(ConnectionState.CLOSED, "Rejected");
        assertAll(
            () -> assertEquals(1, msgs, "losing a session logs one message"),
            () -> assertEquals("Rejected", g.messages().game(0).text().toString(), "closing logs the reason"),
            () -> assertEquals("Rejected", g.status().toString(), "status line"),
            () -> assertEquals(ConnectionState.CLOSED, g.state(), "state"),
            () -> assertEquals(0, g.players().count(), "players forgotten"),
            () -> assertEquals(8, g.players().selfId(), "self id from the new ACCEPT"),
            () -> assertEquals(8, g.playerId(), "player id"),
            () -> assertEquals(15f, g.gameFps(), "game fps from WELCOME"),
            () -> assertEquals(60, g.simHz(), "sim rate"),
            () -> assertEquals(30, g.snapshotHz(), "snapshot rate"));
    }
}
