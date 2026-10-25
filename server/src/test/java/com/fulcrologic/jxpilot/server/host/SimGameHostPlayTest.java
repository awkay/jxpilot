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
import com.fulcrologic.jxpilot.common.sim.Obj;
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
        @DisplayName("a tap gives a press and a release in its tick; a held key presses once; no input holds the mask")
        void edges() {
            Simulation s = sim("", THREE);
            World w = s.world;
            SimGameHost h = new SimGameHost(s);
            int ann = enter(h, "Ann", 0);
            Player p = w.byId(ann);
            h.input(ann, 1, 0, DEC, 7);
            h.tick(1);
            float tapped = p.power;
            long afterTap = p.prevKeys;
            h.input(ann, 2, DEC, 0, 0);
            h.tick(2);
            float pressed = p.power;
            h.tick(3);
            float held = p.power;
            h.input(ann, 4, DEC, DEC, 0);
            h.tick(4);
            float tapWhileHeld = p.power;
            assertAll(
                () -> assertEquals(dec(45f, 1), tapped, "a tap alone is one press: power 45 * 0.9"),
                () -> assertEquals(0L, afterTap, "after the tap the key is up again"),
                () -> assertEquals(dec(45f, 2), pressed, "DEC held from tick 2 is a second press"),
                () -> assertEquals(dec(45f, 2), held, "no input for tick 3: DEC is held, no new press"),
                () -> assertEquals(dec(45f, 2), tapWhileHeld, "a tap of a held key gives no extra press"),
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
        @DisplayName("a second input before the tick replaces the first")
        void replaced() {
            Simulation s = sim("", THREE);
            World w = s.world;
            SimGameHost h = new SimGameHost(s);
            int ann = enter(h, "Ann", 0);
            Player p = w.byId(ann);
            h.input(ann, 1, DEC, 0, 0);
            h.input(ann, 1, INC, 0, 0);
            h.tick(1);
            assertAll(
                () -> assertEquals((float) (45f * 1.10), p.power, "only INC is applied"),
                () -> assertEquals(INC, p.prevKeys, "INC is the held mask"));
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

        /**
         * What a fresh builder writes for the viewer: the reference the host's parts must equal (with the PREDICT
         * sections of an own view in its first snapshots: no input yet, rare version 1, the rare block sent).
         */
        static byte[][] reference(World w, Player viewer, long burstFrom, boolean radar) {
            ByteBuffer[] parts = new ByteBuffer[SimGameHost.SNAPSHOT_PARTS];
            for (int i = 0; i < parts.length; i++) {
                parts[i] = ByteBuffer.allocate(Protocol.MAX_PACKET);
                parts[i].position(PacketHeader.SIZE);
            }
            SnapshotBuilder builder = new SnapshotBuilder(w);
            builder.setPredict(true, 0, 1, 0, 0, true);
            int n = builder.build(viewer, 0, burstFrom, radar, parts);
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

        /** The section types of one filled part 0. */
        static List<Integer> types(SimGameHost h, int id) {
            SnapshotWriter wr = new SnapshotWriter();
            ByteBuffer b = netPart(wr, 0);
            h.fillSnapshot(id, 0, false, wr);
            b.flip();
            b.position(PacketHeader.SIZE);
            com.fulcrologic.jxpilot.common.net.SnapshotReader r = new com.fulcrologic.jxpilot.common.net.SnapshotReader();
            r.begin(b);
            List<Integer> t = new java.util.ArrayList<>();
            while (r.nextSection()) {
                t.add(r.type());
            }
            return t;
        }

        static boolean has(List<Integer> types, int type) {
            return types.contains(type);
        }

        @Test
        @DisplayName("PREDICT: the core every snapshot; the rare block for 8 snapshots from a change and every 14th;"
            + " the keys block when the masks differ from the applied input; nothing while watching another ship")
        void predict() {
            Simulation s = sim("", THREE);
            World w = s.world;
            SimGameHost h = new SimGameHost(s);
            int ann = enter(h, "Ann", 0);
            int bob = enter(h, "Bob", 0);
            h.tick(1);
            Player a = w.byId(ann);
            boolean[] core = new boolean[30];
            boolean[] rare = new boolean[30];
            for (int i = 0; i < 30; i++) {
                if (i == 20) {
                    a.power += 1;
                }
                List<Integer> t = types(h, ann);
                core[i] = has(t, com.fulcrologic.jxpilot.common.net.Section.PREDICT);
                rare[i] = has(t, com.fulcrologic.jxpilot.common.net.Section.PREDICT_RARE);
            }
            boolean keysBefore = has(types(h, ann), com.fulcrologic.jxpilot.common.net.Section.PREDICT_KEYS);
            a.lastReceivedKeys = 5;
            boolean keysAfter = has(types(h, ann), com.fulcrologic.jxpilot.common.net.Section.PREDICT_KEYS);
            a.status |= Obj.PAUSE;
            a.lockTagged |= Player.LOCK_PLAYER;
            a.lockPlId = bob;
            w.items.allowViewing = true;
            boolean watching = has(types(h, ann), com.fulcrologic.jxpilot.common.net.Section.PREDICT);
            boolean[] fc = core;
            boolean[] fr = rare;
            assertAll(
                () -> assertTrue(fc[0] && fc[13] && fc[29], "the core in every own snapshot"),
                () -> assertTrue(fr[0] && fr[7], "the rare block in the first 8 snapshots"),
                () -> assertFalse(fr[8] || fr[13], "not after them"),
                () -> assertTrue(fr[14], "but in every 14th"),
                () -> assertTrue(fr[20] && fr[27], "and in the 8 after a change (power)"),
                () -> assertTrue(fr[28] && !fr[29], "then the 14th again (28), not 29"),
                () -> assertFalse(keysBefore, "no keys block while the masks equal the applied keys (none yet)"),
                () -> assertTrue(keysAfter, "a keys block once lastReceivedKeys differs"),
                () -> assertFalse(watching, "no PREDICT while the snapshot shows another ship"));
        }

        /** Part 0's PREDICT, PREDICT_ROUND and PREDICT_RARE fields: kernel, rare version, round flag, round, offset. */
        static int[] predictFields(SimGameHost h, int id) {
            SnapshotWriter wr = new SnapshotWriter();
            ByteBuffer b = netPart(wr, 0);
            h.fillSnapshot(id, 0, false, wr);
            b.flip();
            b.position(PacketHeader.SIZE);
            com.fulcrologic.jxpilot.common.net.SnapshotReader r = new com.fulcrologic.jxpilot.common.net.SnapshotReader();
            r.begin(b);
            int[] f = {-1, -1, -1, -1, Integer.MIN_VALUE, -1};
            while (r.nextSection()) {
                switch (r.type()) {
                    case com.fulcrologic.jxpilot.common.net.Section.PREDICT -> {
                        Records.Predict p = new Records.Predict().wrap(r, 0);
                        f[0] = p.kernelVersion();
                        f[1] = p.rareVersion();
                        f[2] = p.roundDelaySet() ? 1 : 0;
                        f[5] = p.stateTag();
                    }
                    case com.fulcrologic.jxpilot.common.net.Section.PREDICT_ROUND ->
                        f[3] = new Records.PredictRound().wrap(r, 0).roundDelay();
                    case com.fulcrologic.jxpilot.common.net.Section.PREDICT_RARE ->
                        f[4] = new Records.PredictRare().wrap(r, 0).frameOffset();
                    default -> { }
                }
            }
            return f;
        }

        @Test
        @DisplayName("PREDICT's state tag: the tick of the newest KERNEL_STATE queued to the viewer (the initial state's,"
            + " then a drained change's); a quiet drain keeps it")
        void stateTag() {
            Simulation s = sim("", THREE);
            World w = s.world;
            SimGameHost h = new SimGameHost(s);
            int ann = enter(h, "Ann", 0);
            h.tick(1);
            HostFx.Wire wire = new HostFx.Wire();
            h.initialState(ann, wire.out);
            int initial = predictFields(h, ann)[5];
            w.params.wormTime = 100;
            w.worms.addTemp(5, 1, 7, 1);
            h.tick(2);
            h.drainEvents(ann, wire.out);
            int changed = predictFields(h, ann)[5];
            h.tick(3);
            h.drainEvents(ann, wire.out);
            int quiet = predictFields(h, ann)[5];
            List<String> sent = HostFx.only(wire.take(), "KSTATE");
            assertAll(
                () -> assertEquals(1, initial, "the initial state was tagged with tick 1"),
                () -> assertEquals(2, changed, "the wormhole change scanned in tick 2"),
                () -> assertEquals(2, quiet, "nothing new in tick 3: still 2"),
                () -> assertTrue(sent.size() >= 2 && sent.get(sent.size() - 1).startsWith("KSTATE@2 "),
                    "the messages queued: " + sent));
        }

        @Test
        @DisplayName("PREDICT carries the kernel version mod 256 and roundDelay (flag and PREDICT_ROUND when nonzero);"
            + " the rare block carries the frame offset, and a change of it is a new rare version")
        void predictFields() {
            Simulation s = sim("", THREE);
            World w = s.world;
            SimGameHost h = new SimGameHost(s);
            int ann = enter(h, "Ann", 0);
            h.tick(1);
            int[] first = predictFields(h, ann);
            h.kernelVersion(258);
            w.roundDelay = 40;
            int[] round = predictFields(h, ann);
            w.roundDelay = 0;
            w.frames += 3;
            int[] moved = predictFields(h, ann);
            int[] same = predictFields(h, ann);
            assertAll(
                () -> assertEquals(0, first[0], "no kernel config yet: version 0"),
                () -> assertEquals(0, first[2], "roundDelay 0: no flag"),
                () -> assertEquals(-1, first[3], "and no PREDICT_ROUND"),
                () -> assertEquals((int) (w.frames - 3 - 1), first[4], "the offset: frames minus the net tick 1"),
                () -> assertEquals(2, round[0], "kernel version 258 goes out as 2"),
                () -> assertEquals(1, round[2], "roundDelay 40: the flag"),
                () -> assertEquals(40, round[3], "and PREDICT_ROUND with 40"),
                () -> assertEquals(first[4] + 3, moved[4], "frames moved 3 without a tick: the offset follows"),
                () -> assertEquals(first[1] + 1, moved[1], "a new rare version"),
                () -> assertEquals(moved[1], same[1], "unchanged after that"));
        }

        @Test
        @DisplayName("the owner's view is extended ahead of it by its velocity times its input lead: at most 8 ticks,"
            + " at most 160 px, only in the direction of motion")
        void leadMargin() {
            Simulation s = sim("", THREE);
            World w = s.world;
            SimGameHost h = new SimGameHost(s);
            int ann = enter(h, "Ann", 0);
            int bob = enter(h, "Bob", 0);
            h.tick(1);
            Player a = w.byId(ann);
            Player b = w.byId(bob);
            double edge = a.viewWidth / 2.0 + SnapshotBuilder.VIEW_MARGIN;
            double ax = a.cx / 64.0;
            java.util.function.IntFunction<Boolean> seesBobAt = dx -> {
                b.setClicks((int) ((ax + edge + dx) * 64), a.cy);
                return types(h, ann).contains(com.fulcrologic.jxpilot.common.net.Section.SHIP);
            };
            a.vx = 20;
            a.vy = 0;
            boolean without = seesBobAt.apply(60);
            h.inputLead(ann, 4);
            boolean with = seesBobAt.apply(60);
            boolean beyond = seesBobAt.apply(90);
            a.vx = -20;
            boolean behind = seesBobAt.apply(60);
            a.vx = 20;
            h.inputLead(ann, 14);
            boolean capTicks = seesBobAt.apply(150);
            boolean capTicksBeyond = seesBobAt.apply(170);
            a.vx = 40;
            boolean capPx = seesBobAt.apply(155);
            boolean capPxBeyond = seesBobAt.apply(170);
            h.inputLead(ann, -3);
            boolean negative = seesBobAt.apply(60);
            assertAll(
                () -> assertFalse(without, "60 px beyond the view: Bob is culled"),
                () -> assertTrue(with, "4 ticks of lead at 20 px/tick extend it 80 px ahead: Bob is in"),
                () -> assertFalse(beyond, "but not 90 px"),
                () -> assertFalse(behind, "flying away from Bob: the side behind is not extended"),
                () -> assertTrue(capTicks, "14 ticks of lead count as 8: 160 px at 20 px/tick reaches 150"),
                () -> assertFalse(capTicksBeyond, "not 170"),
                () -> assertTrue(capPx, "at 40 px/tick, 8 ticks would be 320 px: capped at 160, reaches 155"),
                () -> assertFalse(capPxBeyond, "not 170"),
                () -> assertFalse(negative, "a negative lead counts as 0"));
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
