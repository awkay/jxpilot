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

import com.fulcrologic.jxpilot.common.net.ControlKey;
import com.fulcrologic.jxpilot.common.net.PacketHeader;
import com.fulcrologic.jxpilot.common.net.PacketType;
import com.fulcrologic.jxpilot.common.net.Protocol;
import com.fulcrologic.jxpilot.common.net.Records;
import com.fulcrologic.jxpilot.common.net.ReliableType;
import com.fulcrologic.jxpilot.common.net.SnapshotWriter;
import com.fulcrologic.jxpilot.server.sim.Simulation;
import com.fulcrologic.jxpilot.server.snapshot.SnapshotBuilder;
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
import static com.fulcrologic.jxpilot.server.host.HostFx.chat;
import static com.fulcrologic.jxpilot.server.host.HostFx.empty;
import static com.fulcrologic.jxpilot.server.host.HostFx.enter;
import static com.fulcrologic.jxpilot.server.host.HostFx.events;
import static com.fulcrologic.jxpilot.server.host.HostFx.settings;
import static com.fulcrologic.jxpilot.server.host.HostFx.sim;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SimGameHost in play: client messages (READY, CHAT, SETTINGS), input queues, ticks, view centre, snapshots. */
@DisplayName("SimGameHost: spawn, messages, input, tick, view, snapshots")
class SimGameHostPlayTest {
    static final long DEC = ControlKey.DECREASE_POWER.mask();
    static final long INC = ControlKey.INCREASE_POWER.mask();

    /** Keys.apply: power *= 0.90 / 1.10 in double, stored as float (C event.c). */
    static float dec(float p, int times) {
        for (int i = 0; i < times; i++) {
            p *= 0.90;
        }
        return p;
    }

    @Nested
    @DisplayName("clientMessage: READY")
    class Ready {
        @Test
        @DisplayName("spawns the player: JOIN, BASE_OWNER, SPAWN, then the welcome message before the round-delay message")
        void spawnsFirst() {
            Simulation s = sim("", THREE);
            World w = s.world;
            SimGameHost h = new SimGameHost(s);
            int ann = h.join("Ann", 0, "");
            long from = w.events.seq();
            h.clientMessage(ann, ReliableType.READY, empty());
            List<String> spawn = events(w, from);
            long from2 = w.events.seq();
            h.clientMessage(ann, ReliableType.READY, empty());
            assertAll(
                () -> assertEquals(List.of("JOIN 1 0 0", "BASE_OWNER 2 1 0", "GAME_EVENT 3 1 2",
                        "MESSAGE 3 -1 0 Welcome to \"Arena\", made by Bob.",
                        "MESSAGE 4 -1 0 Player entered. Delaying 0 seconds until next round."), spawn,
                    "C Handle_login: the player enters, the welcome (first player) precedes the round part"),
                () -> assertTrue(w.byId(ann).spawned, "Ann is in the game"),
                () -> assertEquals(List.of(), events(w, from2), "a second READY does nothing"));
        }

        @Test
        @DisplayName("later humans are announced as entering; in team play with their team")
        void announcesLater() {
            Simulation s = sim("", THREE);
            SimGameHost h = new SimGameHost(s);
            enter(h, "Ann", 0);
            int bob = h.join("Bob", 0, "");
            long from = s.world.events.seq();
            h.clientMessage(bob, ReliableType.READY, empty());

            Simulation t = sim("teamPlay: yes\n", TEAMS);
            SimGameHost ht = new SimGameHost(t);
            enter(ht, "Ann", 1);
            int cid = ht.join("Cid", 2, "");
            long fromT = t.world.events.seq();
            ht.clientMessage(cid, ReliableType.READY, empty());
            assertAll(
                () -> assertEquals(List.of("MESSAGE 3 -1 0 Bob (X) has entered \"Arena\", made by Bob."),
                    HostFx.only(events(s.world, from), "MESSAGE"),
                    "the second player: \"NAME (REALNAME) has entered\" (X: no real name sent), no round message"),
                () -> assertEquals(List.of("MESSAGE 3 -1 0 Cid (X, team 2) has entered \"Arena\", made by Bob."),
                    HostFx.only(events(t.world, fromT), "MESSAGE"), "team play names the team"));
        }

        /** Team 1 and 2 are filled by robots while Ann (login team 1) is still loading the map. */
        Simulation noBase() {
            return sim("teamPlay: yes\nrestrictRobots: no\nmaxRobots: 3\nminRobots: 3\n", TEAMS);
        }

        @Test
        @DisplayName("no free base: the player is kicked at the next tick (\"no free base\"), once")
        void noBaseKick() {
            Simulation s = noBase();
            World w = s.world;
            SimGameHost h = new SimGameHost(s);
            HostFx.Kicks kicks = new HostFx.Kicks(h);
            int ann = h.join("Ann", 1, "");
            addRobots(s, 3);
            h.clientMessage(ann, ReliableType.READY, empty());
            h.clientMessage(ann, ReliableType.READY, empty());
            List<String> beforeTick = List.copyOf(kicks.calls);
            boolean reservedBeforeTick = w.byId(ann) != null;
            h.tick(1);
            assertAll(
                () -> assertEquals(List.of(), beforeTick, "nothing is dropped while the message is delivered"),
                () -> assertTrue(reservedBeforeTick, "the reservation stays until then"),
                () -> assertEquals(List.of(ann + " no free base"), kicks.calls, "the next tick drops it, once"),
                () -> assertNull(w.byId(ann), "and frees the slot"));
        }

        @Test
        @DisplayName("a pending kick is dropped when the player leaves first, so a join reusing the id is not hit")
        void pendingKickForgotten() {
            Simulation s = noBase();
            SimGameHost h = new SimGameHost(s);
            HostFx.Kicks kicks = new HostFx.Kicks(h);
            int ann = h.join("Ann", 1, "");
            addRobots(s, 3);
            h.clientMessage(ann, ReliableType.READY, empty());
            h.leave(ann);
            int bob = h.join("Bob", 1, "");
            h.tick(1);
            assertAll(
                () -> assertEquals(ann, bob, "Bob reuses Ann's id"),
                () -> assertEquals(List.of(), kicks.calls, "Bob is not kicked for Ann's missing base"),
                () -> assertTrue(s.world.byId(bob).inUse, "Bob keeps his reservation"));
        }
    }

    @Nested
    @DisplayName("deferred kicks")
    class DeferredKicks {
        /** Teams 1 and 2 full of robots, team 0 (2 bases) reserved: Ann and Bob (login team 1) find no base. */
        static final String[] ROWS = {
            "xxxxxxxxxxxxxx",
            "x            x",
            "x 0 0 1 1 2  x",
            "x            x",
            "xxxxxxxxxxxxxx",
        };

        Simulation world() {
            return sim("teamPlay: yes\nrestrictRobots: no\nmaxRobots: 3\nminRobots: 3\n", ROWS);
        }

        @Test
        @DisplayName("several pending kicks all happen; one whose player left is dropped, the others stay")
        void several() {
            String[] rows = {
                "xxxxxxxxxxxxxxx",
                "x             x",
                "x 0 0 0 1 1 2 x",
                "x             x",
                "xxxxxxxxxxxxxxx",
            };
            Simulation s = sim("teamPlay: yes\nrestrictRobots: no\nmaxRobots: 3\nminRobots: 3\n", rows);
            SimGameHost h = new SimGameHost(s);
            HostFx.Kicks kicks = new HostFx.Kicks(h);
            int ann = h.join("Ann", 1, "");
            int bob = h.join("Bob", 1, "");
            int cid = h.join("Cid", 1, "");
            addRobots(s, 3);
            h.clientMessage(ann, ReliableType.READY, empty());
            h.clientMessage(bob, ReliableType.READY, empty());
            h.clientMessage(cid, ReliableType.READY, empty());
            h.leave(bob);
            h.tick(1);
            List<String> first = List.copyOf(kicks.calls);
            boolean annFreed = s.world.byId(ann) == null;
            boolean cidFreed = s.world.byId(cid) == null;
            int dee = h.join("Dee", 1, "");
            h.tick(2);
            assertAll(
                () -> assertEquals(List.of(ann + " no free base", cid + " no free base"), first,
                    "Ann and Cid are kicked in order; Bob, who left, is not kicked"),
                () -> assertEquals(ann, dee, "Dee gets Ann's id"),
                () -> assertEquals(first, kicks.calls, "the next tick kicks nobody: the done kicks are forgotten"),
                () -> assertTrue(annFreed, "Ann's slot was freed"),
                () -> assertTrue(cidFreed, "Cid's slot was freed"));
        }

        @Test
        @DisplayName("the kick list is empty after a tick: a later pending kick for a reused id still happens")
        void reusedIdKickedAgain() {
            Simulation s = world();
            SimGameHost h = new SimGameHost(s);
            HostFx.Kicks kicks = new HostFx.Kicks(h);
            int ann = h.join("Ann", 1, "");
            addRobots(s, 3);
            h.clientMessage(ann, ReliableType.READY, empty());
            h.tick(1);
            int cid = h.join("Cid", 1, "");
            h.leave(cid);
            int dee = h.join("Dee", 1, "");
            addRobots(s, 3);
            h.clientMessage(dee, ReliableType.READY, empty());
            h.tick(2);
            assertAll(
                () -> assertEquals(ann, cid, "Cid got Ann's id (and left again without trouble)"),
                () -> assertEquals(ann, dee, "so did Dee"),
                () -> assertEquals(List.of(ann + " no free base", dee + " no free base"), kicks.calls,
                    "Dee, again without a base, is kicked at the next tick"));
        }
    }

    @Nested
    @DisplayName("clientMessage: SETTINGS, CHAT and the rest")
    class Messages {
        @Test
        @DisplayName("SETTINGS before READY are kept and applied at the spawn (after Init_player's defaults)")
        void settingsBeforeSpawn() {
            Simulation s = sim("", THREE);
            World w = s.world;
            SimGameHost h = new SimGameHost(s);
            int ann = h.join("Ann", 0, "");
            h.clientMessage(ann, ReliableType.SETTINGS, settings(30f, 20f, 0.5f, 25f, 15f, 0.25f));
            float beforeSpawn = w.byId(ann).power;
            long from = w.events.seq();
            h.clientMessage(ann, ReliableType.READY, empty());
            Player p = w.byId(ann);
            List<String> ev = events(w, from);
            assertAll(
                () -> assertEquals(0f, beforeSpawn, "nothing is applied to the reserved player"),
                () -> assertEquals(30f, p.power, "power"),
                () -> assertEquals(20f, p.turnSpeed, "turnspeed"),
                () -> assertEquals(0.5f, p.turnResistance, "turnresistance"),
                () -> assertEquals(25f, p.powerS, "alternate power"),
                () -> assertEquals(15f, p.turnSpeedS, "alternate turnspeed"),
                () -> assertEquals(0.25f, p.turnResistanceS, "alternate turnresistance"),
                () -> assertEquals("THRUST 1 0 0", ev.get(ev.size() - 1), "the power change follows the spawn: " + ev));
        }

        @Test
        @DisplayName("SETTINGS after READY apply at once; early SETTINGS do not outlive the player's id")
        void settingsAfterSpawn() {
            Simulation s = sim("", THREE);
            World w = s.world;
            SimGameHost h = new SimGameHost(s);
            int ann = enter(h, "Ann", 0);
            long from = w.events.seq();
            h.clientMessage(ann, ReliableType.SETTINGS, settings(50f, 30f, 0.2f, 40f, 10f, 0.1f));
            Player p = w.byId(ann);
            List<String> ev = events(w, from);

            int bob = h.join("Bob", 0, "");
            h.clientMessage(bob, ReliableType.SETTINGS, settings(12f, 30f, 0.2f, 40f, 10f, 0.1f));
            h.leave(bob);
            int again = h.join("Cid", 0, "");
            h.clientMessage(again, ReliableType.READY, empty());
            assertAll(
                () -> assertEquals(50f, p.power, "power is set"),
                () -> assertEquals(30f, p.turnSpeed, "turnspeed is set"),
                () -> assertEquals(0.1f, p.turnResistanceS, "alternate turnresistance is set"),
                () -> assertEquals(List.of("THRUST 1 0 0"), ev, "THRUST tells clients about the new power"),
                () -> assertEquals(bob, again, "Cid reuses Bob's id"),
                () -> assertEquals(45f, w.byId(again).power, "Cid starts with the default power, not Bob's"));
        }

        @Test
        @DisplayName("CHAT from a player in the game goes through Handle_talk; before READY it is dropped")
        void chats() {
            Simulation s = sim("", THREE);
            World w = s.world;
            SimGameHost h = new SimGameHost(s);
            int ann = enter(h, "Ann", 0);
            int bob = h.join("Bob", 0, "");
            long from = w.events.seq();
            h.clientMessage(ann, ReliableType.CHAT, chat("hello"));
            h.clientMessage(bob, ReliableType.CHAT, chat("too early"));
            assertEquals(List.of("MESSAGE 0 1 0 hello [Ann]"), events(w, from),
                "Ann's line goes to everyone; Bob's is dropped");
        }

        @Test
        @DisplayName("SHAPE, unknown types and messages of ids no connection holds do nothing")
        void ignored() {
            Simulation s = sim("", THREE);
            World w = s.world;
            SimGameHost h = new SimGameHost(s);
            int ann = enter(h, "Ann", 0);
            int res = h.join("Res", 0, "");
            long from = w.events.seq();
            h.clientMessage(ann, ReliableType.SHAPE, ByteBuffer.wrap(new byte[] {0, 0}));
            h.clientMessage(ann, 200, empty());
            h.clientMessage(7, ReliableType.READY, empty());
            h.clientMessage(0, ReliableType.READY, empty());
            h.clientMessage(World.MAX_IDS, ReliableType.READY, empty());
            h.clientMessage(-1, ReliableType.CHAT, chat("x"));
            assertAll(
                () -> assertEquals(List.of(), events(w, from), "no event"),
                () -> assertEquals(1, w.numPlayers, "only Ann plays"),
                () -> assertFalse(w.byId(res).spawned, "Res is still reserved"));
        }
    }

    @Nested
    @DisplayName("input and tick")
    class InputAndTick {
        @Test
        @DisplayName("every queued mask is applied in order, so each press counts; the last one stays the input")
        void edges() {
            Simulation s = sim("", THREE);
            World w = s.world;
            SimGameHost h = new SimGameHost(s);
            int ann = enter(h, "Ann", 0);
            Player p = w.byId(ann);
            for (long k : new long[] {DEC, 0, DEC, 0, DEC}) {
                h.input(ann, 1, k, 7);
            }
            h.tick(1);
            float three = p.power;
            h.tick(2);
            float held = p.power;
            h.input(ann, 2, DEC, 0);
            h.tick(3);
            float stillHeld = p.power;
            assertAll(
                () -> assertEquals(dec(45f, 3), three, "three presses in one tick: power 45 * 0.9^3"),
                () -> assertEquals(dec(45f, 3), held, "no new input: the last mask (DEC) is held, no new press"),
                () -> assertEquals(dec(45f, 3), stillHeld, "DEC again after DEC is no press"),
                () -> assertEquals(DEC, p.prevKeys, "the held mask is the player's"));
        }

        @Test
        @DisplayName("aimTurn is C's PKT_POINTER_MOVE: applied at once; 0 and unbound ids do nothing")
        void pointerMove() {
            Simulation s = sim("", THREE);
            World w = s.world;
            SimGameHost h = new SimGameHost(s);
            int ann = enter(h, "Ann", 0);
            Player p = w.byId(ann);
            p.turnSpeed = 32;
            p.turnResistance = 0.12f;
            p.turnVel = 0;
            h.input(ann, 1, 0, 20);
            float moved = p.turnVel;
            h.input(ann, 2, 0, 0);
            float none = p.turnVel;
            h.input(63, 3, 0, 20);
            assertAll(
                () -> assertEquals(-10f, moved, "20 px at turnSpeed 32 turns by 10 (Keys.pointerMove)"),
                () -> assertEquals(moved, none, "0: no pointer move"),
                () -> assertEquals(moved, p.turnVel, "an unbound id: nothing"));
        }

        @Test
        @DisplayName("a full queue keeps the oldest 15 and the newest mask")
        void overflow() {
            Simulation s = sim("", THREE);
            World w = s.world;
            SimGameHost h = new SimGameHost(s);
            int ann = enter(h, "Ann", 0);
            Player p = w.byId(ann);
            for (int i = 0; i < SimGameHost.INPUT_QUEUE / 2; i++) {
                h.input(ann, i, DEC, 0);
                h.input(ann, i, 0, 0);
            }
            h.input(ann, 99, INC, 0);
            h.tick(1);
            float expected = (float) (dec(45f, 8) * 1.10);
            assertAll(
                () -> assertEquals(expected, p.power, "8 DEC presses, then INC replaces the 16th entry (a release)"),
                () -> assertEquals(INC, p.prevKeys, "the newest mask is the held one"));
        }

        @Test
        @DisplayName("input before READY and for ids no connection holds is dropped")
        void dropped() {
            Simulation s = sim("", THREE);
            World w = s.world;
            SimGameHost h = new SimGameHost(s);
            int ann = h.join("Ann", 0, "");
            h.input(ann, 1, DEC, 0);
            h.input(5, 1, DEC, 0);
            h.input(0, 1, DEC, 0);
            h.input(-3, 1, DEC, 0);
            h.clientMessage(ann, ReliableType.READY, empty());
            h.tick(1);
            assertAll(
                () -> assertEquals(45f, w.byId(ann).power, "the early DEC never acts"),
                () -> assertEquals(0L, w.byId(ann).prevKeys, "no key is held"));
        }

        @Test
        @DisplayName("without humans the sim does not run: robots off, the world only takes the tick number")
        void idle() {
            Simulation s = sim("maxRobots: 2\nminRobots: 2\n", THREE);
            World w = s.world;
            SimGameHost h = new SimGameHost(s);
            long frames0 = w.frames;
            h.tick(7);
            int tickEmpty = w.tick;
            addRobots(s, 2);
            s.robots.setEnabled(true);
            long frames1 = w.frames;
            h.tick(8);
            assertAll(
                () -> assertEquals(7, tickEmpty, "world.tick = T"),
                () -> assertEquals(frames0, frames1, "no frame ran for an empty game"),
                () -> assertEquals(8, w.tick, "world.tick = T with robots only"),
                () -> assertEquals(frames1, w.frames, "robots alone do not run the game (C NumPlayers > NumRobots)"),
                () -> assertFalse(s.robots.enabled(), "robots are switched off"));
        }

        @Test
        @DisplayName("with a human the sim runs tick T and robots join and play; rawMode runs an empty game")
        void running() {
            Simulation s = sim("maxRobots: 1\nminRobots: 1\n", THREE);
            World w = s.world;
            SimGameHost h = new SimGameHost(s);
            int ann = enter(h, "Ann", 0);
            long frames0 = w.frames;
            h.tick(41);
            long frames1 = w.frames;
            boolean enabled = s.robots.enabled();
            int t = w.tick;
            for (int i = 0; i < 2 * 60; i++) {
                h.tick(42 + i);
            }
            int robots = s.robots.numRobots();
            h.leave(ann);
            h.tick(500);
            boolean afterLeave = s.robots.enabled();

            com.fulcrologic.jxpilot.common.config.Options o = com.fulcrologic.jxpilot.server.TestWorlds.options(HostFx.BASE, THREE);
            o.force(com.fulcrologic.jxpilot.common.config.Opt.IDLE_RUN, "true"); // command line only
            Simulation r = new Simulation(new World(o, 1));
            SimGameHost hr = new SimGameHost(r);
            long rawFrames = r.world.frames;
            hr.tick(3);
            assertAll(
                () -> assertEquals(frames0 + 1, frames1, "one frame per tick"),
                () -> assertEquals(41, t, "the sim ran tick 41"),
                () -> assertTrue(enabled, "robots are on while a human plays"),
                () -> assertEquals(1, robots, "a robot joined after 2*FPS frames"),
                () -> assertFalse(afterLeave, "robots are off once the human left"),
                () -> assertEquals(rawFrames + 1, r.world.frames, "rawMode runs the game without players"),
                () -> assertTrue(r.robots.enabled(), "and its robots"));
        }

        @Test
        @DisplayName("a connected human the sim removes for pausing too long is kicked; a sim-only player is not")
        void maxPauseTime() {
            Simulation s = sim("maxPauseTime: 1\n", THREE);
            World w = s.world;
            SimGameHost h = new SimGameHost(s);
            HostFx.Kicks kicks = new HostFx.Kicks(h);
            int ann = enter(h, "Ann", 0);
            enter(h, "Bob", 0);
            Player zed = s.ships.join("Zed", 0);
            s.ships.pause(w.byId(ann), true);
            s.ships.pause(zed, true);
            long from = w.events.seq();
            int t = 1;
            while (kicks.calls.isEmpty() && t < 500) {
                h.tick(t++);
            }
            int kickedAt = t - 1;
            h.input(ann, 1, DEC, 0);
            h.tick(t);
            assertAll(
                () -> assertEquals(List.of(ann + " auto-kicked: paused too long"), kicks.calls,
                    "Ann's connection is dropped with the reason; Zed has none"),
                () -> assertTrue(events(w, from).contains("MESSAGE 3 -1 0 Ann was auto-kicked for pausing too long [*Server notice*]"),
                    "the sim announced it"),
                () -> assertTrue(kickedAt > 60, "only after maxPauseTime = 60 frames (" + kickedAt + ")"),
                () -> assertNull(w.byId(ann), "Ann is gone"),
                () -> assertEquals(1, w.numPlayers, "Bob plays on (Zed was removed too)"),
                () -> assertEquals("Bob", w.playerAt(0).name.toString(), "the player left is Bob"));
        }
    }

    @Nested
    @DisplayName("viewX / viewY")
    class View {
        @Test
        @DisplayName("a spawned viewer: its position in clicks / CLICK, exactly")
        void spawned() {
            Simulation s = sim("", THREE);
            SimGameHost h = new SimGameHost(s);
            int ann = enter(h, "Ann", 0);
            Player p = s.world.byId(ann);
            p.cx = 12345;
            p.cy = 6789;
            assertAll(
                () -> assertEquals(12345 / 64.0, h.viewX(ann), "x = 192.890625"),
                () -> assertEquals(6789 / 64.0, h.viewY(ann), "y = 106.078125"));
        }

        @Test
        @DisplayName("before the spawn: the first base of the login team (any base without one); no bases: the world centre")
        void beforeSpawn() {
            Simulation s = sim("", THREE);
            SimGameHost h = new SimGameHost(s);
            int ann = h.join("Ann", 0, "");
            Simulation t = sim("teamPlay: yes\n", TEAMS);
            SimGameHost ht = new SimGameHost(t);
            int cid = ht.join("Cid", 2, "");
            Simulation e = sim("", "xxxxxxxxxxx", "x         x", "x         x", "x         x", "xxxxxxxxxxx");
            SimGameHost he = new SimGameHost(e);
            assertAll(
                () -> assertEquals(87.5, h.viewX(ann), "base 0 at block x 2: (2 + 0.5) * 35"),
                () -> assertEquals(87.5, h.viewY(ann), "block y 2 (row 2 of 5, from the bottom)"),
                () -> assertEquals(297.5, ht.viewX(cid), "team 2's base at block x 8"),
                () -> assertEquals(87.5, ht.viewY(cid), "its y"),
                () -> assertEquals(87.5, ht.viewX(9), "an unknown viewer: the first base"),
                () -> assertEquals(87.5, h.viewX(-1), "an invalid id: the first base"),
                () -> assertEquals(192.5, he.viewX(1), "no bases: half of 11 * 35"),
                () -> assertEquals(87.5, he.viewY(1), "no bases: half of 5 * 35"));
        }
    }

    @Nested
    @DisplayName("fillSnapshot")
    class Snapshots {
        /** The network layer's part: packet header and snapshot header written, as ServerNet does. */
        static ByteBuffer netPart(SnapshotWriter wr, int part) {
            ByteBuffer b = ByteBuffer.allocate(Protocol.MAX_PACKET);
            PacketHeader.write(b, PacketType.SNAPSHOT, 77, 0);
            wr.begin(b, 5, 0, part, 0, 0, 100, 100);
            return b;
        }

        static byte[] sections(ByteBuffer b) {
            return Arrays.copyOfRange(b.array(), SimGameHost.SECTIONS_START, b.position());
        }

        /** What a fresh builder writes for the viewer: the reference the host's parts must equal. */
        static byte[][] reference(World w, Player viewer, long burstFrom, boolean radar) {
            ByteBuffer[] parts = new ByteBuffer[SimGameHost.SNAPSHOT_PARTS];
            for (int i = 0; i < parts.length; i++) {
                parts[i] = ByteBuffer.allocate(Protocol.MAX_PACKET);
                parts[i].position(PacketHeader.SIZE);
            }
            int n = new SnapshotBuilder(w).build(viewer, 0, burstFrom, radar, parts);
            byte[][] out = new byte[n][];
            for (int i = 0; i < n; i++) {
                out[i] = sections(parts[i]);
            }
            return out;
        }

        static void burst(Simulation s, Player near) {
            s.debris.make(near.cx / 64.0, near.cy / 64.0, 1, 0, near.id, near.team, Obj.DEBRIS, 3.5f, Obj.GRAVITY,
                Records.ObjColor.RED, Records.BurstKind.DEBRIS, 8, 10, 10, 0, 127, 20, 30, 5, 20);
        }

        @Test
        @DisplayName("one part: the builder's sections byte for byte after the header; radar as asked")
        void onePart() {
            Simulation s = sim("", THREE);
            World w = s.world;
            SimGameHost h = new SimGameHost(s);
            int ann = enter(h, "Ann", 0);
            enter(h, "Bob", 0);
            h.tick(1);
            Player p = w.byId(ann);
            SnapshotWriter wr = new SnapshotWriter();
            ByteBuffer b = netPart(wr, 0);
            byte[][] ref = reference(w, p, w.events.burstSeq(), true);
            boolean more = h.fillSnapshot(ann, 0, true, wr);
            ByteBuffer noRadar = netPart(wr, 0);
            byte[][] refNoRadar = reference(w, p, w.events.burstSeq(), false);
            h.fillSnapshot(ann, 0, false, wr);
            assertAll(
                () -> assertEquals(1, ref.length, "everything fits one part"),
                () -> assertTrue(ref[0].length > 0, "the snapshot has sections"),
                () -> assertArrayEquals(ref[0], sections(b), "part 0 carries exactly the builder's sections"),
                () -> assertFalse(more, "no more parts"),
                () -> assertArrayEquals(refNoRadar[0], sections(noRadar), "without radar: the builder's radar-less sections"),
                () -> assertTrue(refNoRadar[0].length < ref[0].length, "the radar section is the difference"));
        }

        @Test
        @DisplayName("several parts: part p copies the builder's part p and says whether more follow")
        void multiPart() {
            Simulation s = sim("", THREE);
            World w = s.world;
            SimGameHost h = new SimGameHost(s);
            int ann = enter(h, "Ann", 0);
            int bob = enter(h, "Bob", 0);
            h.tick(1);
            Player a = w.byId(ann);
            Player b = w.byId(bob);
            for (int i = 0; i < 400; i++) {
                s.shots.fire(b, b.team, false, (float) (a.cx / 64.0 - 100 + (i % 20) * 10), (float) (a.cy / 64.0 + (i / 20)), Obj.SHOT, i % 128, 0);
                b.shots = 0;
            }
            byte[][] ref = reference(w, a, w.events.burstSeq(), false);
            SnapshotWriter wr = new SnapshotWriter();
            byte[][] got = new byte[ref.length][];
            boolean[] more = new boolean[ref.length];
            for (int part = 0; part < ref.length; part++) {
                ByteBuffer buf = netPart(wr, part);
                more[part] = h.fillSnapshot(ann, part, false, wr);
                got[part] = sections(buf);
            }
            ByteBuffer beyond = netPart(wr, ref.length);
            int beyondStart = beyond.position();
            boolean beyondMore = h.fillSnapshot(ann, ref.length, false, wr);
            assertAll(
                () -> assertTrue(ref.length >= 2 && ref.length <= SimGameHost.SNAPSHOT_PARTS,
                    "400 shots need several parts (" + ref.length + ")"),
                () -> assertArrayEquals(ref[0], got[0], "part 0"),
                () -> assertArrayEquals(ref[1], got[1], "part 1"),
                () -> assertArrayEquals(ref[ref.length - 1], got[ref.length - 1], "the last part"),
                () -> assertTrue(more[0], "more follow part 0"),
                () -> assertFalse(more[ref.length - 1], "none follow the last part"),
                () -> assertFalse(beyondMore, "a part beyond the count is empty"),
                () -> assertEquals(beyondStart, beyond.position(), "and writes nothing"));
        }

        @Test
        @DisplayName("bursts since the viewer's cursor are sent once: the cursor moves to the newest")
        void burstCursor() {
            Simulation s = sim("", THREE);
            World w = s.world;
            SimGameHost h = new SimGameHost(s);
            int ann = enter(h, "Ann", 0);
            h.tick(1);
            Player p = w.byId(ann);
            long before = w.events.burstSeq();
            burst(s, p);
            SnapshotWriter wr = new SnapshotWriter();
            byte[][] withBurst = reference(w, p, before, false);
            ByteBuffer first = netPart(wr, 0);
            h.fillSnapshot(ann, 0, false, wr);
            byte[][] without = reference(w, p, w.events.burstSeq(), false);
            ByteBuffer second = netPart(wr, 0);
            h.fillSnapshot(ann, 0, false, wr);
            assertAll(
                () -> assertTrue(withBurst[0].length > without[0].length, "the burst adds a BURST section"),
                () -> assertArrayEquals(withBurst[0], sections(first), "the first snapshot carries the burst"),
                () -> assertArrayEquals(without[0], sections(second), "the next one does not"));
        }

        @Test
        @DisplayName("a viewer not in the game (reserved, unknown) gets no sections")
        void notSpawned() {
            Simulation s = sim("", THREE);
            SimGameHost h = new SimGameHost(s);
            int res = h.join("Res", 0, "");
            SnapshotWriter wr = new SnapshotWriter();
            ByteBuffer b = netPart(wr, 0);
            int start = b.position();
            boolean reserved = h.fillSnapshot(res, 0, true, wr);
            boolean unknown = h.fillSnapshot(9, 0, true, wr);
            assertAll(
                () -> assertFalse(reserved, "no more parts for a reserved viewer"),
                () -> assertFalse(unknown, "nor for an unknown one"),
                () -> assertEquals(start, b.position(), "nothing is written"));
        }
    }
}
