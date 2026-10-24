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
import com.fulcrologic.jxpilot.common.net.PacketHeader;
import com.fulcrologic.jxpilot.common.net.PacketType;
import com.fulcrologic.jxpilot.common.net.Protocol;
import com.fulcrologic.jxpilot.common.net.Records;
import com.fulcrologic.jxpilot.common.net.SnapshotWriter;
import com.fulcrologic.jxpilot.server.sim.Simulation;
import com.fulcrologic.jxpilot.server.snapshot.SnapshotBuilder;
import com.fulcrologic.jxpilot.server.world.Events;
import com.fulcrologic.jxpilot.server.world.Obj;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;

import static com.fulcrologic.jxpilot.server.host.HostFx.THREE;
import static com.fulcrologic.jxpilot.server.host.HostFx.TEAMS;
import static com.fulcrologic.jxpilot.server.host.HostFx.addRobots;
import static com.fulcrologic.jxpilot.server.host.HostFx.enter;
import static com.fulcrologic.jxpilot.server.host.HostFx.sim;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** SimGameHost reliable state: initialState and drainEvents decoded from a real ReliableChannel. */
@DisplayName("SimGameHost: initial state and events")
class SimGameHostEventsTest {

    static String join(Player p, int flags) {
        return "JOIN " + p.id + " team=" + (p.team < 0 ? 255 : p.team) + " flags=" + flags + " power=" + p.power
            + " ab=0 name=" + p.name + " shape=" + p.ship.wire;
    }

    @Nested
    @DisplayName("initialState")
    class Initial {
        @Test
        @DisplayName("every player in order: JOIN (SELF, PAUSED, ROBOT flags), BASE_OWNER, SCORE; reserved players are not sent")
        void players() {
            Simulation s = sim("maxRobots: 1\nminRobots: 1\n", HostFx.FOUR);
            World w = s.world;
            SimGameHost h = new SimGameHost(s);
            Player ann = w.byId(enter(h, "Ann", 0));
            Player bob = w.byId(enter(h, "Bob", 0));
            addRobots(s, 1);
            Player robot = w.playerAt(2);
            h.join("Res", 0, "");
            s.ships.pause(bob, true);
            ann.score = 12.5f;
            HostFx.Wire wire = new HostFx.Wire();
            h.initialState(bob.id, wire.out);
            List<String> got = wire.take();
            assertEquals(List.of(
                join(ann, 0), "BASE " + ann.homeBase + " 1", "SCORE 1 12.5 lives=-1 status=0",
                join(bob, Msg.PlayerJoin.FLAG_SELF | Msg.PlayerJoin.FLAG_PAUSED), "BASE " + bob.homeBase + " 2",
                "SCORE 2 0.0 lives=-1 status=" + (Msg.Score.STATUS_DEAD | Msg.Score.STATUS_PAUSED),
                join(robot, Msg.PlayerJoin.FLAG_ROBOT), "BASE " + robot.homeBase + " 3", "SCORE 3 0.0 lives=-1 status=0"),
                got, "C Handle_login's sends: each player in game order (a robot's power is its 55)");
        }

        @Test
        @DisplayName("limitedLives sends lives; team play adds TEAM_SCORE for each team with members")
        void livesAndTeams() {
            Simulation s = sim("teamPlay: yes\nlimitedLives: yes\n", TEAMS);
            World w = s.world;
            SimGameHost h = new SimGameHost(s);
            Player ann = w.byId(enter(h, "Ann", 1));
            Player cid = w.byId(enter(h, "Cid", 2));
            ann.life = 2;
            w.teamScore[1] = 5;
            w.teamScore[2] = -3;
            w.teamScore[0] = 99;
            HostFx.Wire wire = new HostFx.Wire();
            h.initialState(cid.id, wire.out);
            assertEquals(List.of(
                join(ann, 0), "BASE " + ann.homeBase + " 1", "SCORE 1 0.0 lives=2 status=0",
                join(cid, Msg.PlayerJoin.FLAG_SELF), "BASE " + cid.homeBase + " 2", "SCORE 2 0.0 lives=" + cid.life + " status=0",
                "TEAM_SCORE 1 5.0", "TEAM_SCORE 2 -3.0"), wire.take(),
                "lives are the players' own; team 0 has no members so its score is not sent");
        }

        @Test
        @DisplayName("events and bursts before the initial state are covered by it: the cursors start at the newest")
        void cursors() {
            Simulation s = sim("", THREE);
            World w = s.world;
            SimGameHost h = new SimGameHost(s);
            Player ann = w.byId(enter(h, "Ann", 0));
            h.tick(1);
            int bob = h.join("Bob", 0, "");
            w.events.message(Msg.Text.KIND_GAME, Obj.NO_ID).append("old news");
            s.debris.make(ann.cx / 64.0, ann.cy / 64.0, 1, 0, ann.id, ann.team, Obj.DEBRIS, 3.5f, Obj.GRAVITY,
                Records.ObjColor.RED, Records.BurstKind.DEBRIS, 8, 10, 10, 0, 127, 20, 30, 5, 20);
            HostFx.Wire wire = new HostFx.Wire();
            h.initialState(bob, wire.out);
            wire.take();
            h.clientMessage(bob, com.fulcrologic.jxpilot.common.net.ReliableType.READY, HostFx.empty());
            w.events.message(Msg.Text.KIND_GAME, Obj.NO_ID).append("news");
            h.drainEvents(bob, wire.out);
            List<String> drained = wire.take();

            ByteBuffer[] parts = {ByteBuffer.allocate(Protocol.MAX_PACKET)};
            parts[0].position(PacketHeader.SIZE);
            new SnapshotBuilder(w).build(w.byId(bob), 0, w.events.burstSeq(), false, parts);
            byte[] ref = Arrays.copyOfRange(parts[0].array(), SimGameHost.SECTIONS_START, parts[0].position());
            SnapshotWriter wr = new SnapshotWriter();
            ByteBuffer b = ByteBuffer.allocate(Protocol.MAX_PACKET);
            PacketHeader.write(b, PacketType.SNAPSHOT, 1, 0);
            wr.begin(b, 1, 0, 0, 0, 0, 0, 0);
            h.fillSnapshot(bob, 0, false, wr);
            byte[] got = Arrays.copyOfRange(b.array(), SimGameHost.SECTIONS_START, b.position());
            assertAll(
                () -> assertEquals("MSG 4 from=65535 news", drained.get(drained.size() - 1), "the newest event is delivered"),
                () -> assertEquals(List.of(), HostFx.only(drained, "MSG 4 from=65535 old"), "the older message is not"),
                () -> assertArrayEquals(ref, got, "the first snapshot has no burst from before the initial state"));
        }
    }

    @Nested
    @DisplayName("drainEvents")
    class Drain {
        @Test
        @DisplayName("maps every event kind: ids NO_ID -> 0xFFFF, teams -> 0xFF; messages and score objects by receiver")
        void kinds() {
            Simulation s = sim("", THREE);
            World w = s.world;
            SimGameHost h = new SimGameHost(s);
            Player ann = w.byId(enter(h, "Ann", 0));
            Player bob = w.byId(enter(h, "Bob", 0));
            h.join("Res", 0, "");
            HostFx.Wire a = new HostFx.Wire();
            HostFx.Wire b = new HostFx.Wire();
            h.initialState(ann.id, a.out);
            h.initialState(bob.id, b.out);
            a.take();
            b.take();
            Events e = w.events;
            e.add(Events.JOIN, 2, 0, 0, 0f);
            e.add(Events.JOIN, 3, 0, 0, 0f);
            e.add(Events.JOIN, 9, 0, 0, 0f);
            e.add(Events.LEAVE, 7, 0, 0, 0f);
            e.add(Events.TEAM, 2, 3, 0, 0f);
            e.add(Events.TEAM, 2, -1, 0, 0f);
            e.add(Events.SCORE, 1, 3, 2, 12.5f);
            e.add(Events.TEAM_SCORE, 1, 0, 0, 7.5f);
            e.message(Msg.Text.KIND_GAME, Obj.NO_ID).append("all");
            e.playerMessage(Msg.Text.KIND_PRIVATE, 2, 1).append("to ann");
            e.playerMessage(Msg.Text.KIND_PRIVATE, 1, 2).append("to bob");
            e.add(Events.BASE_OWNER, 2, Obj.NO_ID, 0, 0f);
            e.add(Events.BASE_OWNER, 1, 2, 0, 0f);
            e.add(Events.GAME_EVENT, Msg.GameEvent.KILL, Obj.NO_ID, 2, 3f);
            e.add(Events.GAME_EVENT, Msg.GameEvent.SPAWN, 1, 0, 0f);
            e.add(Events.THRUST, 1, 2, 0, 40f);
            e.text[e.add(Events.SCORE_OBJECT, 1, 3, 4, -2f)].append("kill");
            e.text[e.add(Events.SCORE_OBJECT, 2, 5, 6, 1f)].append("bob's");
            e.add(Events.SEEK, 1, 2, Obj.NO_ID, 0f);
            e.add(99, 1, 1, 1, 1f);
            h.drainEvents(ann.id, a.out);
            List<String> annGot = a.take();
            h.drainEvents(ann.id, a.out);
            List<String> again = a.take();
            h.drainEvents(bob.id, b.out);
            List<String> bobGot = b.take();
            assertAll(
                () -> assertEquals(List.of(
                    join(bob, 0), "LEAVE 7", "TEAM 2 3", "TEAM 2 255", "SCORE 1 12.5 lives=3 status=2", "TEAM_SCORE 1 7.5",
                    "MSG 4 from=65535 all", "MSG 2 from=2 to ann", "BASE 2 65535", "BASE 1 2", "EVENT 1 65535 2 3.0",
                    "EVENT 3 1 0 0.0", "THRUST 1 40.0 ab=2", "SCORE_OBJECT -2.0 3,4 kill", "SEEK 1 2 65535"), annGot,
                    "JOIN as the player is now (reserved and unknown ids skipped); Bob's private line and score object skipped"),
                () -> assertEquals(List.of(), again, "the cursor moved past everything"),
                () -> assertEquals(List.of("MSG 4 from=65535 all", "MSG 2 from=1 to bob", "SCORE_OBJECT 1.0 5,6 bob's",
                    "SEEK 1 2 65535"), HostFx.only(bobGot, "MSG", "SCORE_OBJECT", "SEEK"),
                    "Bob gets his own private line and score object; SEEK goes to everyone (C Send_seek)"),
                () -> assertEquals(join(bob, Msg.PlayerJoin.FLAG_SELF), bobGot.get(0), "Bob's own JOIN has SELF"));
        }

        @Test
        @DisplayName("a JOIN whose player left before the drain is skipped (the LEAVE follows)")
        void joinOfGonePlayer() {
            Simulation s = sim("", THREE);
            World w = s.world;
            SimGameHost h = new SimGameHost(s);
            int ann = enter(h, "Ann", 0);
            HostFx.Wire a = new HostFx.Wire();
            h.initialState(ann, a.out);
            a.take();
            int bob = enter(h, "Bob", 0);
            int base = w.byId(bob).homeBase;
            h.leave(bob);
            h.drainEvents(ann, a.out);
            assertEquals(List.of("BASE " + base + " 2", "EVENT 3 2 " + base + " 0.0",
                    "MSG 3 from=65535 Bob (X) has entered \"Arena\", made by Bob.", "BASE " + base + " 65535", "LEAVE 2"),
                a.take(), "no PLAYER_JOIN for Bob, who is gone");
        }

        @Test
        @DisplayName("a viewer whose cursor fell out of the ring: PLAYER_LEAVE for announced ids gone, then the full state")
        void resync() {
            Simulation s = sim("", THREE);
            World w = s.world;
            SimGameHost h = new SimGameHost(s);
            Player ann = w.byId(enter(h, "Ann", 0));
            int bob = enter(h, "Bob", 0);
            int cid = enter(h, "Cid", 0);
            HostFx.Wire a = new HostFx.Wire();
            h.initialState(ann.id, a.out);
            a.take();
            h.leave(bob);
            h.leave(cid);
            int res = h.join("Res", 0, "");
            for (int i = 0; i < Events.CAPACITY + 10; i++) {
                w.events.add(Events.TEAM_SCORE, 1, 0, 0, i);
            }
            h.drainEvents(ann.id, a.out);
            List<String> got = a.take();
            h.drainEvents(ann.id, a.out);
            List<String> after = a.take();
            assertAll(
                () -> assertEquals(bob, res, "Res holds Bob's id but is not in the game"),
                () -> assertEquals(List.of("LEAVE 2", "LEAVE 3", join(ann, Msg.PlayerJoin.FLAG_SELF), "BASE " + ann.homeBase + " 1",
                    "SCORE 1 0.0 lives=-1 status=0", "ALLIANCE 1 ' '"), got,
                    "leaves for Bob and Cid, then the state (every alliance mark); no lost event is replayed"),
                () -> assertEquals(List.of(), after, "the cursor continues from the newest event"));
        }

        @Test
        @DisplayName("resync sends no PLAYER_LEAVE for an id whose leave was already delivered")
        void resyncAfterLeave() {
            Simulation s = sim("", THREE);
            World w = s.world;
            SimGameHost h = new SimGameHost(s);
            Player ann = w.byId(enter(h, "Ann", 0));
            int bob = enter(h, "Bob", 0);
            HostFx.Wire a = new HostFx.Wire();
            h.initialState(ann.id, a.out);
            h.leave(bob);
            h.drainEvents(ann.id, a.out);
            a.take();
            for (int i = 0; i < Events.CAPACITY + 1; i++) {
                w.events.add(Events.TEAM_SCORE, 1, 0, 0, i);
            }
            h.drainEvents(ann.id, a.out);
            assertEquals(List.of(join(ann, Msg.PlayerJoin.FLAG_SELF), "BASE " + ann.homeBase + " 1", "SCORE 1 0.0 lives=-1 status=0",
                    "ALLIANCE 1 ' '"),
                a.take(), "only the state: Bob was already announced as gone");
        }

        @Test
        @DisplayName("a viewer id reused by a new join starts with nothing announced")
        void reusedViewer() {
            Simulation s = sim("", THREE);
            World w = s.world;
            SimGameHost h = new SimGameHost(s);
            int ann = enter(h, "Ann", 0);
            int bob = enter(h, "Bob", 0);
            HostFx.Wire a = new HostFx.Wire();
            h.initialState(ann, a.out);
            h.leave(bob);
            h.leave(ann);
            int cid = enter(h, "Cid", 0);
            HostFx.Wire c = new HostFx.Wire();
            h.initialState(cid, c.out);
            c.take();
            for (int i = 0; i < Events.CAPACITY + 1; i++) {
                w.events.add(Events.TEAM_SCORE, 1, 0, 0, i);
            }
            h.drainEvents(cid, c.out);
            Player cp = w.byId(cid);
            assertAll(
                () -> assertEquals(ann, cid, "Cid has Ann's old viewer id"),
                () -> assertEquals(List.of(join(cp, Msg.PlayerJoin.FLAG_SELF), "BASE " + cp.homeBase + " " + cid,
                    "SCORE " + cid + " 0.0 lives=-1 status=0", "ALLIANCE " + cid + " ' '"), c.take(),
                    "no PLAYER_LEAVE for Bob, whom Cid never saw"));
        }

        @Test
        @DisplayName("exactly CAPACITY events behind is still readable (no resync)")
        void boundary() {
            Simulation s = sim("", THREE);
            World w = s.world;
            SimGameHost h = new SimGameHost(s);
            int ann = enter(h, "Ann", 0);
            HostFx.Wire a = new HostFx.Wire();
            h.initialState(ann, a.out);
            a.take();
            for (int i = 0; i < Events.CAPACITY; i++) {
                w.events.add(Events.TEAM_SCORE, 1, 0, 0, i);
            }
            h.drainEvents(ann, a.out);
            List<String> got = a.take();
            assertAll(
                () -> assertEquals(Events.CAPACITY, got.size(), "every event is delivered"),
                () -> assertEquals("TEAM_SCORE 1 0.0", got.get(0), "from the oldest"),
                () -> assertEquals("TEAM_SCORE 1 1023.0", got.get(got.size() - 1), "to the newest"));
        }
    }
}
