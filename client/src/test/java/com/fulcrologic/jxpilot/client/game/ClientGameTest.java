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
    @DisplayName("SEEK logs C's \"has programmed ... to seek ...\" game message; unknown players log nothing")
    void seek() {
        ClientGame g = configured();
        g.onReliable(ReliableType.PLAYER_JOIN, payload(join(9, 1, "Robby")));
        g.onReliable(ReliableType.PLAYER_JOIN, payload(join(10, 1, "Ann")));
        g.onReliable(ReliableType.PLAYER_JOIN, payload(join(11, 2, "Bob")));
        Msg.Seek m = new Msg.Seek();
        m.programmerId = 10;
        m.robotId = 9;
        m.soughtId = 11;
        g.onReliable(ReliableType.SEEK, payload(m));
        int before = g.messages().gameCount();
        m.soughtId = 42;
        g.onReliable(ReliableType.SEEK, payload(m));
        int after = g.messages().gameCount();
        assertAll(
            () -> assertEquals("Ann has programmed Robby to seek Bob.", g.messages().game(0).text().toString(),
                "the message (client.c:1153)"),
            () -> assertEquals(before, after, "an unknown sought player logs nothing"));
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

    static Msg.ScoreObject scoreObject(float points, int bx, int by, String label) {
        Msg.ScoreObject o = new Msg.ScoreObject();
        o.score = points;
        o.bx = bx;
        o.by = by;
        o.text.append(label);
        return o;
    }

    static Msg.GameEvent event(int kind, int a, int b, float value) {
        Msg.GameEvent e = event(kind, a, b);
        e.value = value;
        return e;
    }

    @Test
    @DisplayName("SCORE_OBJECT makes a score object with its points, label and block; SCORE never does")
    void scoreObjects() {
        ClientGame g = configured();
        g.onReliable(ReliableType.PLAYER_JOIN, payload(join(7, 0, "me")));
        g.onReliable(ReliableType.PLAYER_JOIN, payload(join(9, 0, "Robby")));
        g.onReliable(ReliableType.SCORE, payload(score(7, 150)));
        g.onReliable(ReliableType.SCORE, payload(score(7, 152.5f)));
        g.onReliable(ReliableType.SCORE, payload(score(9, 6)));
        List<String> afterScores = hud(g);
        float ownScore = g.players().score(g.players().slotOf(7));
        g.onReliable(ReliableType.GAME_EVENT, payload(event(Msg.GameEvent.KILL, 7, 9)));
        g.onReliable(ReliableType.SCORE_OBJECT, payload(scoreObject(3, 10, 5, "Robby")));
        g.onReliable(ReliableType.SCORE, payload(score(7, 155.5f)));
        g.onReliable(ReliableType.SCORE_OBJECT, payload(scoreObject(-1, 2, 300, "[Wall]")));
        g.onReliable(ReliableType.SCORE_OBJECT, payload(scoreObject(4, 1, 2, "")));
        ScoreObjects so = g.scoreObjects();
        int last = so.hudOrder(ScoreObjects.MAX_SCORE_OBJECTS - 1);
        List<String> objects = hud(g);
        String text = so.text(last).toString();
        String hudText = so.hudText(last).toString();
        float points = so.score(last);
        List<Integer> place = List.of(so.blockX(last), so.blockY(last));
        boolean placed = so.hasPosition(last);
        g.onAccepted(7);
        boolean afterNewSession = g.scoreObjects().active(last);
        assertAll(
            () -> assertEquals(List.of(), afterScores, "own and other SCOREs make no object (no delta heuristic)"),
            () -> assertEquals(152.5f, ownScore, "SCORE still sets the score"),
            () -> assertEquals(List.of("Robby 3.0 @10,5", "[Wall] -1.0 @2,300", " @1,2"), objects,
                "one object per SCORE_OBJECT in arrival order: HUD text \"label points\" at the sent block"),
            () -> assertEquals("", hudText, "an empty label makes no HUD text (C Handle_score_object)"),
            () -> assertEquals("4.0", text, "the game-area text is still the points"),
            () -> assertEquals(4f, points, "points"),
            () -> assertEquals(List.of(1, 2), place, "block"),
            () -> assertTrue(placed, "SCORE_OBJECT always has a place"),
            () -> assertFalse(afterNewSession, "a new session forgets score objects"));
    }

    @Test
    @DisplayName("GAME_EVENT crash sub-types and shove kills reach the ring unchanged and make no score object")
    void crashSubTypes() {
        ClientGame g = configured();
        g.onReliable(ReliableType.PLAYER_JOIN, payload(join(7, 0, "me")));
        g.onReliable(ReliableType.GAME_EVENT, payload(event(Msg.GameEvent.CRASH, 7, Protocol.NO_ID,
            Msg.GameEvent.CRASH_WALL)));
        g.onReliable(ReliableType.GAME_EVENT, payload(event(Msg.GameEvent.CRASH, 7, 9, Msg.GameEvent.CRASH_SHIPS)));
        g.onReliable(ReliableType.GAME_EVENT, payload(event(Msg.GameEvent.CRASH, 7, Protocol.NO_ID,
            Msg.GameEvent.CRASH_UNKNOWN)));
        g.onReliable(ReliableType.GAME_EVENT, payload(event(Msg.GameEvent.KILL, 9, 7, Msg.GameEvent.CRASH_WALL)));
        GameEvents ev = g.events();
        List<String> held = new ArrayList<>();
        for (int i = 0; i < ev.size(); i++) {
            held.add(ev.kind(i) + " " + ev.a(i) + " " + ev.b(i) + " " + ev.value(i));
        }
        int crash = Msg.GameEvent.CRASH;
        assertAll(
            () -> assertEquals(List.of(crash + " 7 65535 1.0", crash + " 7 9 6.0", crash + " 7 65535 7.0",
                    Msg.GameEvent.KILL + " 9 7 1.0"), held,
                "wall crash (b NO_ID), two-ship crash (b = other ship), unknown crash, shove kill by the pusher "
                    + "with the wall sub-type, in order"),
            () -> assertEquals(List.of(), hud(g), "events alone make no score objects"));
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
