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
import com.fulcrologic.jxpilot.common.net.Protocol;
import com.fulcrologic.jxpilot.common.net.ReliableType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

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
    @DisplayName("TIMING sets a known player's race progress (check, round); unknown ids and new joins start at 0")
    void timing() {
        ClientGame g = configured();
        g.onReliable(ReliableType.PLAYER_JOIN, payload(join(9, 1, "Robby")));
        Msg.Timing t = new Msg.Timing();
        t.id = 9;
        t.check = 2;
        t.round = 3;
        g.onReliable(ReliableType.TIMING, payload(t));
        int s = g.players().slotOf(9);
        int check = g.players().check(s);
        int round = g.players().round(s);
        t.id = 44;
        g.onReliable(ReliableType.TIMING, payload(t));
        g.onReliable(ReliableType.PLAYER_LEAVE, payload(leaveOf(9)));
        g.onReliable(ReliableType.PLAYER_JOIN, payload(join(9, 1, "Robby")));
        int s2 = g.players().slotOf(9);
        assertAll(
            () -> assertEquals(2, check, "check"),
            () -> assertEquals(3, round, "round"),
            () -> assertEquals(-1, g.players().slotOf(44), "an unknown id is ignored"),
            () -> assertEquals(0, g.players().check(s2) + g.players().round(s2), "a new join starts at 0"));
    }

    static Msg.PlayerLeave leaveOf(int id) {
        Msg.PlayerLeave l = new Msg.PlayerLeave();
        l.id = id;
        return l;
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

    static Msg.GameEvent event(int kind, int a, int b) {
        Msg.GameEvent e = new Msg.GameEvent();
        e.kind = kind;
        e.a = a;
        e.b = b;
        return e;
    }

    /** HUD texts of the live score objects, oldest first. */
    static List<String> hud(ClientGame g) {
        List<String> r = new ArrayList<>();
        ScoreObjects so = g.scoreObjects();
        for (int i = 0; i < ScoreObjects.MAX_SCORE_OBJECTS; i++) {
            int s = so.hudOrder(i);
            if (so.active(s)) {
                r.add(so.hudText(s) + (so.hasPosition(s) ? " @" + so.blockX(s) + "," + so.blockY(s) : ""));
            }
        }
        return r;
    }

    static ClientGame withPlayers() {
        ClientGame g = configured();
        g.onReliable(ReliableType.PLAYER_JOIN, payload(join(7, 0, "me")));
        g.onReliable(ReliableType.PLAYER_JOIN, payload(join(9, 0, "Robby")));
        g.onReliable(ReliableType.SCORE, payload(score(7, 10)));
        g.onReliable(ReliableType.SCORE, payload(score(9, 10)));
        return g;
    }

    @Test
    @DisplayName("own score changes become score objects; others, the join baseline and resets to 0 do not")
    void scoreObjects() {
        ClientGame g = configured();
        g.onReliable(ReliableType.PLAYER_JOIN, payload(join(7, 0, "me")));
        g.onReliable(ReliableType.PLAYER_JOIN, payload(join(9, 0, "it")));
        g.onReliable(ReliableType.SCORE, payload(score(9, 4)));
        g.onReliable(ReliableType.SCORE, payload(score(9, 6)));
        g.onReliable(ReliableType.SCORE, payload(score(7, 150)));
        List<String> afterBaseline = hud(g);
        g.onReliable(ReliableType.SCORE, payload(score(7, 152.5f)));
        g.onReliable(ReliableType.SCORE, payload(score(7, 0)));
        ScoreObjects so = g.scoreObjects();
        int s = so.hudOrder(ScoreObjects.MAX_SCORE_OBJECTS - 1);
        List<String> objects = hud(g);
        String text = so.text(s).toString();
        float points = so.score(s);
        boolean placed = so.hasPosition(s);
        g.onAccepted(7);
        boolean afterNewSession = g.scoreObjects().active(s);
        assertAll(
            () -> assertFalse(afterNewSession, "a new session forgets score objects"),
            () -> assertEquals(List.of(), afterBaseline, "another player's change and the join baseline make none"),
            () -> assertEquals(List.of("2.5"), objects, "one object; the reset to 0 makes none; unknown reason: points only"),
            () -> assertEquals("2.5", text, "game-area text: the points"),
            () -> assertEquals(2.5f, points, "points"),
            () -> assertFalse(placed, "no place without an event"));
    }

    @Test
    @DisplayName("kill events name the other player and place the object at the victim")
    void killReasons() {
        WorldFixture f = new WorldFixture(true);
        ClientGame g = withPlayers();
        f.begin(100).self(7, 0, 100, 100, 0, 0, 0).ship(9, 0, 350, 175, 0, 0, 0);
        f.buf.flip();
        g.onSnapshot(f.buf, WorldFixture.at(100));
        g.frame(WorldFixture.at(100), 0);
        g.onReliable(ReliableType.GAME_EVENT, payload(event(Msg.GameEvent.KILL, 7, 9)));
        g.onReliable(ReliableType.SCORE, payload(score(7, 13)));
        g.frame(WorldFixture.at(100), 0);
        g.onReliable(ReliableType.GAME_EVENT, payload(event(Msg.GameEvent.KILL, 9, 7)));
        g.onReliable(ReliableType.SCORE, payload(score(7, 12)));
        g.frame(WorldFixture.at(100), 0);
        g.onReliable(ReliableType.GAME_EVENT, payload(event(Msg.GameEvent.KILL, 7, 7)));
        g.onReliable(ReliableType.SCORE, payload(score(7, 11)));
        g.frame(WorldFixture.at(100), 0);
        g.onReliable(ReliableType.GAME_EVENT, payload(event(Msg.GameEvent.KILL, Protocol.NO_ID, 7)));
        g.onReliable(ReliableType.SCORE, payload(score(7, 10)));
        g.frame(WorldFixture.at(100), 0);
        g.onReliable(ReliableType.GAME_EVENT, payload(event(Msg.GameEvent.KILL, 9, 4)));
        g.onReliable(ReliableType.SCORE, payload(score(7, 9)));
        assertEquals(List.of("Robby 3.0 @10,5", "Robby -1.0 @2,2", "me -1.0 @2,2", "-1.0", "-1.0"), hud(g),
            "killer: victim's name at the victim; victim: killer's name; self-kill: own name; "
                + "killed by nobody: points only; another pair's kill is no reason");
    }

    @Test
    @DisplayName("crash, target and treasure reasons; a reason does not outlive its batch")
    void otherReasons() {
        WorldFixture f = new WorldFixture(true);
        ClientGame g = withPlayers();
        g.onReliable(ReliableType.GAME_EVENT, payload(event(Msg.GameEvent.CRASH, 7, 0)));
        g.onReliable(ReliableType.SCORE, payload(score(7, 9)));
        g.frame(WorldFixture.at(100), 0);
        g.onReliable(ReliableType.GAME_EVENT, payload(event(Msg.GameEvent.TREASURE_DESTROYED, 7,
            f.map.treasures().size())));
        g.onReliable(ReliableType.SCORE, payload(score(7, 8)));
        g.frame(WorldFixture.at(100), 0);
        g.onReliable(ReliableType.SCORE, payload(score(7, 9)));
        g.frame(WorldFixture.at(100), 0);
        g.onReliable(ReliableType.GAME_EVENT, payload(event(Msg.GameEvent.TARGET_DESTROYED, 7, 0)));
        g.onReliable(ReliableType.SCORE, payload(score(7, 11)));
        g.frame(WorldFixture.at(100), 0);
        g.onReliable(ReliableType.GAME_EVENT, payload(event(Msg.GameEvent.TREASURE_DESTROYED, 7, 0)));
        g.onReliable(ReliableType.SCORE, payload(score(7, 12)));
        g.frame(WorldFixture.at(100), 0);
        g.onReliable(ReliableType.GAME_EVENT, payload(event(Msg.GameEvent.TARGET_DESTROYED, 7, 5)));
        g.onReliable(ReliableType.SCORE, payload(score(7, 13)));
        g.frame(WorldFixture.at(100), 0);
        g.onReliable(ReliableType.GAME_EVENT, payload(event(Msg.GameEvent.CRASH, 9, 0)));
        g.onReliable(ReliableType.GAME_EVENT, payload(event(Msg.GameEvent.TARGET_DESTROYED, 9, 0)));
        g.onReliable(ReliableType.GAME_EVENT, payload(event(Msg.GameEvent.SPAWN, 7, 0)));
        g.onReliable(ReliableType.SCORE, payload(score(7, 14)));
        g.onReliable(ReliableType.GAME_EVENT, payload(event(Msg.GameEvent.CRASH, 7, 0)));
        g.frame(WorldFixture.at(100), 0);
        g.onReliable(ReliableType.SCORE, payload(score(7, 15)));
        String target = "@" + f.map.targets().get(0).bx() + "," + f.map.targets().get(0).by();
        String treasure = "@" + f.map.treasures().get(0).bx() + "," + f.map.treasures().get(0).by();
        f.begin(100).self(7, 0, 100, 100, 0, 0, 0);
        f.buf.flip();
        g.onSnapshot(f.buf, WorldFixture.at(100));
        g.frame(WorldFixture.at(100), 0);
        g.onReliable(ReliableType.GAME_EVENT, payload(event(Msg.GameEvent.CRASH, 7, 0)));
        g.onReliable(ReliableType.SCORE, payload(score(7, 14)));
        assertEquals(List.of("Treasure:  -1.0", "1.0", "Target:  2.0 " + target, "Treasure:  1.0 " + treasure,
                "Target:  1.0", "1.0", "1.0", "[Wall] -1.0 @2,2"), hud(g).subList(1, 9),
            "a treasure index equal to the count: no place; a crash at the visible own ship is placed there");
        assertEquals(List.of("[Wall] -1.0", "Treasure:  -1.0", "1.0", "Target:  2.0 " + target, "Treasure:  1.0 " + treasure,
                "Target:  1.0", "1.0", "1.0"), hud(g).subList(0, 8),
            "crash at an unknown own ship: no place; target/treasure at their block (C's double space); "
                + "a bad index: no place; other players' events and SPAWN are no reason; a reason after the "
                + "SCORE is forgotten at the frame");
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
