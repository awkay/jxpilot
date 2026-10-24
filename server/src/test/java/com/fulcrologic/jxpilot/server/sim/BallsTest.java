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
package com.fulcrologic.jxpilot.server.sim;

import com.fulcrologic.jxpilot.common.Const;
import com.fulcrologic.jxpilot.common.net.Msg;
import com.fulcrologic.jxpilot.common.net.Records;
import com.fulcrologic.jxpilot.server.TestWorlds;
import com.fulcrologic.jxpilot.server.world.Click;
import com.fulcrologic.jxpilot.server.world.Has;
import com.fulcrologic.jxpilot.server.world.ModeState;
import com.fulcrologic.jxpilot.server.world.Obj;
import com.fulcrologic.jxpilot.server.world.Objects;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.fulcrologic.jxpilot.server.sim.ModeFx.ballOf;
import static com.fulcrologic.jxpilot.server.sim.ModeFx.balls;
import static com.fulcrologic.jxpilot.server.sim.ModeFx.ship;
import static com.fulcrologic.jxpilot.server.sim.ModeFx.teamSim;
import static com.fulcrologic.jxpilot.server.sim.ModeFx.tow;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.gameEvents;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.messages;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.obj;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Balls (shot.c Make_treasure_ball/Move_ball/Delete_shot, collision.c, walls.c, play.c Punish_team)")
class BallsTest {
    static final double EPS = 1e-9;

    @Nested
    @DisplayName("makeTreasureBall / init (Make_treasure_ball, Treasure_init)")
    class Make {
        @Test
        @DisplayName("a loose, gravity-free ball rests in every non-empty treasure")
        void init() {
            Simulation s = teamSim("ballMass: 42\nballConnectorLength: 99\n");
            World w = s.world;
            Objects o = w.objects;
            int h = ballOf(w, 0);
            int h2 = ballOf(w, 2);
            ModeState m = w.modes;
            assertAll(
                () -> assertEquals(2, balls(w).size(), "two balls: the empty treasure has none"),
                () -> assertEquals(-1, ballOf(w, 1), "no ball in the empty treasure '^'"),
                () -> assertEquals(Click.fromPixels(52.5f), o.cx[h], "x: the treasure's centre"),
                () -> assertEquals(Click.fromPixels(45f), o.cy[h], "y: 10 px above the treasure's bottom"),
                () -> assertEquals(o.px[h], o.prevX[h], "previous position = position"),
                () -> assertEquals(Balls.BALL_LIFE, o.life[h], EPS, "life: the LONG_MAX stand-in"),
                () -> assertEquals(42f, o.mass[h], "mass ballMass"),
                () -> assertEquals(0f, o.vx[h] + o.vy[h], "at rest"),
                () -> assertEquals(Obj.RECREATE, o.status[h], "RECREATE and no GRAVITY"),
                () -> assertEquals(10, o.plRange[h] + o.plRadius[h] - 10, "radius 10"),
                () -> assertEquals(1, o.team[h], "team: the treasure's team"),
                () -> assertEquals(2, o.team[h2], "the other treasure's team"),
                () -> assertEquals(Obj.NO_ID, o.owner[h], "not connected"),
                () -> assertEquals(Obj.NO_ID, m.ballOwner[h], "never owned"),
                () -> assertEquals(99f, m.ballLength[h], "length ballConnectorLength"),
                () -> assertTrue(m.treasureHave[0] && m.treasureHave[2], "the treasures have their balls"),
                () -> assertFalse(m.treasureHave[1], "the empty one has none"));
        }

        @Test
        @DisplayName("no second ball, none in an empty treasure, none when the pool is full; team 0 outside team play")
        void refusals() {
            Simulation s = teamSim("");
            World w = s.world;
            int again = s.balls.makeTreasureBall(0);
            int empty = s.balls.makeTreasureBall(1);
            Simulation full = teamSim("");
            Objects fo = full.world.objects;
            while (fo.alloc() >= 0) {
                // fill the pool
            }
            full.world.modes.treasureHave[0] = false;
            int none = full.balls.makeTreasureBall(0);
            String[] rows = ModeFx.teamRows();
            Simulation solo = TestWorlds.sim(SimFixture.FPS14, rows);
            int sh = ballOf(solo.world, 0);
            assertAll(
                () -> assertEquals(-1, again, "a treasure that has its ball gets no second one"),
                () -> assertEquals(-1, empty, "an empty treasure gets none"),
                () -> assertEquals(2, balls(w).size(), "still two balls"),
                () -> assertEquals(-1, none, "a full pool makes none"),
                () -> assertFalse(full.world.modes.treasureHave[0], "and the treasure stays without"),
                () -> assertEquals(0, solo.world.objects.team[sh], "outside team play the ball's team is 0 (C)"));
        }
    }

    @Nested
    @DisplayName("connect (PlayerCollision connector)")
    class Connect {
        @Test
        @DisplayName("reaching starts for the nearest loose ball within (int) length; then connects at length")
        void reachAndConnect() {
            Simulation s = teamSim("");
            World w = s.world;
            Objects o = w.objects;
            int h = ballOf(w, 2);
            Player p = ship(s, "P", 1, o.px[h] - 100, o.py[h]);
            p.used |= Has.CONNECTOR;
            s.balls.connect(p);
            int reach1 = p.ball;
            s.balls.connect(p);
            int stillReaching = p.ball;
            boolean ownerWhileNear = o.owner[h] == Obj.NO_ID;
            p.initPixels(o.px[h] - 121, o.py[h]);
            s.balls.connect(p);
            assertAll(
                () -> assertEquals(h, reach1, "the enemy ball 100 px away is reached for"),
                () -> assertEquals(h, stillReaching, "while closer than the length it is still only reached for"),
                () -> assertTrue(ownerWhileNear, "and not yet connected"),
                () -> assertEquals(-1, p.ball, "at >= length the reach ends"),
                () -> assertEquals(p.id, o.owner[h], "the ball is connected to the ship"),
                () -> assertEquals(p.team, o.team[h], "with the ship's team"),
                () -> assertEquals(p.id, w.modes.ballOwner[h], "the ship owns it"),
                () -> assertEquals(121f, w.modes.ballLength[h], "the length is the distance"),
                () -> assertTrue((o.status[h] & Obj.GRAVITY) != 0, "gravity on"),
                () -> assertFalse(w.modes.treasureHave[2], "the treasure lost its ball"),
                () -> assertTrue((p.have & Has.BALL) != 0, "the ship has a ball"),
                () -> assertEquals(Balls.BALL_LIFE, o.life[h], EPS, "a never-owned ball restarts its loose count"));
        }

        @Test
        @DisplayName("an already owned ball keeps its loose count when taken again")
        void ownedBefore() {
            Simulation s = teamSim("");
            World w = s.world;
            Objects o = w.objects;
            int h = ballOf(w, 2);
            Player p = ship(s, "P", 1, o.px[h] - 121, o.py[h]);
            p.used |= Has.CONNECTOR;
            w.modes.ballOwner[h] = 77;
            o.life[h] = Balls.BALL_LIFE - 5;
            p.ball = h;
            s.balls.connect(p);
            assertEquals(Balls.BALL_LIFE - 5, o.life[h], EPS, "life is not restarted for a ball owned before");
        }

        @Test
        @DisplayName("picking: nearest wins, beyond (int) length and connected balls are ignored; own never-owned ball is off limits in team play")
        void picking() {
            Simulation s = teamSim("ballConnectorLength: 120.9\n");
            World w = s.world;
            Objects o = w.objects;
            int own = ballOf(w, 0);
            int enemy = ballOf(w, 2);
            Player p = ship(s, "P", 1, o.px[own] + 30, o.py[own]);
            p.used |= Has.CONNECTOR;
            s.balls.connect(p);
            int ownPicked = p.ball;
            w.modes.ballOwner[own] = 5;
            s.balls.connect(p);
            int ownOwnedPicked = p.ball;
            p.ball = -1;
            int far = obj(w, Obj.BALL, Obj.NO_ID, o.px[enemy] - 120, o.py[enemy], 0, 0, 50, 100);
            w.modes.ballTreasure[far] = 2;
            w.modes.ballOwner[far] = 5;
            int near = obj(w, Obj.BALL, Obj.NO_ID, o.px[enemy] - 60, o.py[enemy], 0, 0, 50, 100);
            w.modes.ballTreasure[near] = 2;
            w.modes.ballOwner[near] = 5;
            int towed = obj(w, Obj.BALL, 99, o.px[enemy] - 10, o.py[enemy], 0, 0, 50, 100);
            w.modes.ballTreasure[towed] = 2;
            Player q = ship(s, "Q", 2, o.px[enemy], o.py[enemy]);
            q.used |= Has.CONNECTOR;
            s.balls.connect(q);
            int nearest = q.ball;
            q.ball = -1;
            o.px[near] = 5000;
            o.px[towed] = 5000;
            s.balls.connect(q);
            int edge = q.ball;
            assertAll(
                () -> assertEquals(-1, ownPicked, "the own team's never-owned ball is not reached for"),
                () -> assertEquals(own, ownOwnedPicked, "once owned by someone it can be"),
                () -> assertEquals(near, nearest,
                    "the nearest loose ball; a connected one and the own team's never-owned one are ignored"),
                () -> assertEquals(-1, edge, "a ball exactly (int) length away is out of reach"));
        }

        @Test
        @DisplayName("no connector, phasing, or a dead, taken or vanished ball ends the reach")
        void reachEnds() {
            Simulation s = teamSim("");
            World w = s.world;
            Objects o = w.objects;
            int h = ballOf(w, 2);
            Player p = ship(s, "P", 1, o.px[h] - 50, o.py[h]);
            p.ball = h;
            s.balls.connect(p);
            int noConnector = p.ball;
            p.used |= Has.CONNECTOR | Has.PHASING_DEVICE;
            p.ball = h;
            s.balls.connect(p);
            int phased = p.ball;
            p.used &= ~Has.PHASING_DEVICE;
            p.ball = h;
            o.life[h] = 0;
            s.balls.connect(p);
            int dead = p.ball;
            o.life[h] = 10;
            o.owner[h] = 99;
            p.ball = h;
            s.balls.connect(p);
            int taken = p.ball;
            o.owner[h] = Obj.NO_ID;
            p.ball = h;
            o.type[h] = Obj.SHOT;
            s.balls.connect(p);
            int notBall = p.ball;
            assertAll(
                () -> assertEquals(-1, noConnector, "connector not in use"),
                () -> assertEquals(-1, phased, "phasing"),
                () -> assertEquals(-1, dead, "a dying ball"),
                () -> assertEquals(-1, taken, "a ball someone else connected"),
                () -> assertEquals(-1, notBall, "a handle that is no longer a ball"));
        }

        @Test
        @DisplayName("outside team play any loose ball can be reached for")
        void noTeams() {
            Simulation s = TestWorlds.sim(SimFixture.FPS14, ModeFx.teamRows());
            World w = s.world;
            Objects o = w.objects;
            int h = ballOf(w, 0);
            Player p = ship(s, "P", 0, o.px[h] + 30, o.py[h]);
            p.used |= Has.CONNECTOR;
            s.balls.connect(p);
            assertEquals(h, p.ball, "the never-owned ball of 'any' team");
        }
    }

    @Nested
    @DisplayName("detach (Detach_ball)")
    class Detach {
        @Test
        @DisplayName("all: every connected ball goes loose (owner kept), the connector is off, HAS_BALL lost")
        void all() {
            Simulation s = teamSim("");
            World w = s.world;
            Objects o = w.objects;
            int a = ballOf(w, 0);
            int b = ballOf(w, 2);
            Player p = ship(s, "P", 1, 300, 200);
            tow(w, p, a);
            tow(w, p, b);
            p.used |= Has.CONNECTOR;
            p.ball = 5;
            s.balls.detach(p, -1);
            assertAll(
                () -> assertEquals(Obj.NO_ID, o.owner[a], "first ball loose"),
                () -> assertEquals(Obj.NO_ID, o.owner[b], "second ball loose"),
                () -> assertEquals(p.id, w.modes.ballOwner[a], "the owner stays (balls can be thrown)"),
                () -> assertEquals(-1, p.ball, "no reach"),
                () -> assertEquals(0, p.used & Has.CONNECTOR, "connector off"),
                () -> assertEquals(0, p.have & Has.BALL, "no ball any more"));
        }

        @Test
        @DisplayName("one ball: the other stays connected with HAS_BALL; the connector stays unless it was the reached ball")
        void one() {
            Simulation s = teamSim("");
            World w = s.world;
            Objects o = w.objects;
            int a = ballOf(w, 0);
            int b = ballOf(w, 2);
            Player p = ship(s, "P", 1, 300, 200);
            tow(w, p, a);
            tow(w, p, b);
            p.used |= Has.CONNECTOR;
            s.balls.detach(p, a);
            boolean connector = (p.used & Has.CONNECTOR) != 0;
            p.ball = b;
            s.balls.detach(p, b);
            assertAll(
                () -> assertEquals(Obj.NO_ID, o.owner[a], "the dropped ball is loose"),
                () -> assertTrue(connector, "dropping a ball that is not the reached one keeps the connector"),
                () -> assertEquals(Obj.NO_ID, o.owner[b], "then the second goes"),
                () -> assertEquals(0, p.used & Has.CONNECTOR, "dropping the reached ball turns the connector off"),
                () -> assertEquals(0, p.have & Has.BALL, "and with none left HAS_BALL goes"));
        }

        @Test
        @DisplayName("one of two: HAS_BALL kept")
        void keepsHasBall() {
            Simulation s = teamSim("");
            World w = s.world;
            int a = ballOf(w, 0);
            int b = ballOf(w, 2);
            Player p = ship(s, "P", 1, 300, 200);
            tow(w, p, a);
            tow(w, p, b);
            s.balls.detach(p, a);
            assertAll(
                () -> assertEquals(p.id, w.objects.owner[b], "the other ball is still connected"),
                () -> assertTrue((p.have & Has.BALL) != 0, "the ship still has a ball"));
        }
    }

    @Nested
    @DisplayName("move (Move_ball)")
    class Move {
        static Simulation towing(String extra, double dist) {
            Simulation s = teamSim(extra);
            World w = s.world;
            int h = ballOf(w, 2);
            Player p = ship(s, "P", 1, 300, 200);
            tow(w, p, h);
            w.objects.initPixels(h, 300 - dist, 200);
            return s;
        }

        @Test
        @DisplayName("a stretched spring pulls ship and ball together by k*ratio/mass (C floats)")
        void spring() {
            Simulation s = towing("", 130);
            World w = s.world;
            int h = ballOf(w, 2);
            Player p = w.playerAt(0);
            p.mass = 20;
            s.balls.move(h);
            float ratio = (120f - 130f) / 120f;
            float force = 1500f * ratio;
            assertAll(
                () -> assertEquals(force / 20f, p.vx, 1e-6, "the ship accelerates toward the ball (D = (1, 0))"),
                () -> assertEquals(0f, p.vy, "nothing across the connector"),
                () -> assertEquals(-force / 50f, w.objects.vx[h], 1e-6, "the ball the other way by its mass"),
                () -> assertEquals(130f, w.modes.ballLength[h], "the length is stored"));
        }

        @Test
        @DisplayName("damping: velocity along the connector is resisted on both ends")
        void damping() {
            Simulation s = towing("", 120);
            World w = s.world;
            int h = ballOf(w, 2);
            Player p = w.playerAt(0);
            p.mass = 20;
            p.vx = 3;
            w.objects.vx[h] = 1;
            s.balls.move(h);
            float plDamp = -2f * 3f;
            float ballDamp = -2f * -1f;
            float a = plDamp + ballDamp;
            assertAll(
                () -> assertEquals(3f + a / 20f, p.vx, 1e-6, "ship: (force 0 + both dampings) / ship mass"),
                () -> assertEquals(1f - a / 50f, w.objects.vx[h], 1e-6, "ball: the same force the other way"));
        }

        @Test
        @DisplayName("a string connector does nothing while slack; too long or too short a connector snaps")
        void stringAndSnap() {
            Simulation slack = towing("connectorIsString: yes\n", 100);
            int hs = ballOf(slack.world, 2);
            slack.balls.move(hs);
            Simulation taut = towing("connectorIsString: yes\n", 130);
            int ht = ballOf(taut.world, 2);
            taut.balls.move(ht);
            Simulation longer = towing("", 120 * 1.31);
            int hl = ballOf(longer.world, 2);
            longer.balls.move(hl);
            Simulation shorter = towing("", 120 * 0.69);
            int hh = ballOf(shorter.world, 2);
            shorter.balls.move(hh);
            Simulation edge = towing("", 120 * 1.29);
            int he = ballOf(edge.world, 2);
            edge.balls.move(he);
            assertAll(
                () -> assertEquals(0f, slack.world.playerAt(0).vx, "a slack string pulls nothing"),
                () -> assertEquals(120f, slack.world.modes.ballLength[hs], "and its length is not updated"),
                () -> assertTrue(taut.world.playerAt(0).vx < 0, "a taut string pulls"),
                () -> assertEquals(Obj.NO_ID, longer.world.objects.owner[hl], "over 30% too long: detached"),
                () -> assertEquals(Obj.NO_ID, shorter.world.objects.owner[hh], "over 30% too short: detached"),
                () -> assertEquals(edge.world.playerAt(0).id, edge.world.objects.owner[he], "29% too long still holds"));
        }

        @Test
        @DisplayName("ball and ship on the same spot: no direction, no push; a ball whose ship left is not moved")
        void degenerate() {
            Simulation s = towing("", 0);
            World w = s.world;
            int h = ballOf(w, 2);
            s.balls.move(h);
            Simulation gone = towing("", 130);
            int g = ballOf(gone.world, 2);
            gone.world.objects.owner[g] = 200;
            gone.balls.move(g);
            assertAll(
                () -> assertEquals(Obj.NO_ID, w.objects.owner[h], "length 0 is 100% too short: detached"),
                () -> assertEquals(0f, gone.world.objects.vx[g], "an unknown ship moves nothing"));
        }
    }

    @Nested
    @DisplayName("deleting (Delete_shot, ball case)")
    class Deleting {
        @Test
        @DisplayName("a connected ball detaches; RECREATE explodes (10-20 red debris) and returns its treasure")
        void recreate() {
            Simulation s = teamSim("");
            World w = s.world;
            int h = ballOf(w, 2);
            Player p = ship(s, "P", 1, 300, 200);
            tow(w, p, h);
            long bursts = w.events.burstSeq();
            int r = s.balls.deleting(h);
            var b = SimFixture.lastBurst(w);
            assertAll(
                () -> assertEquals(2, r, "its treasure is returned for a new ball"),
                () -> assertEquals(0, p.have & Has.BALL, "the ship lost it"),
                () -> assertEquals(bursts + 1, w.events.burstSeq(), "one explosion"),
                () -> assertTrue(b.count >= 10 && b.count < 20, "10-19 debris"),
                () -> assertEquals(Records.BurstKind.DEBRIS, w.events.burstKind[(int) ((w.events.burstSeq() - 1) & 4095)],
                    "a debris burst"));
        }

        @Test
        @DisplayName("NOEXPLOSION: no debris; without RECREATE: no new ball; a never-owned ball is always recreated")
        void variants() {
            Simulation s = teamSim("");
            World w = s.world;
            Objects o = w.objects;
            int h = ballOf(w, 2);
            w.modes.ballOwner[h] = 3;
            o.status[h] |= Obj.NOEXPLOSION;
            long bursts = w.events.burstSeq();
            int quiet = s.balls.deleting(h);
            long after = w.events.burstSeq();
            o.status[h] = Obj.GRAVITY;
            int gone = s.balls.deleting(h);
            w.modes.ballOwner[h] = Obj.NO_ID;
            w.modes.treasureHave[2] = true;
            int forced = s.balls.deleting(h);
            assertAll(
                () -> assertEquals(2, quiet, "NOEXPLOSION|RECREATE: recreated"),
                () -> assertEquals(bursts, after, "without an explosion"),
                () -> assertEquals(-1, gone, "an owned ball without RECREATE is not recreated"),
                () -> assertEquals(2, forced, "a never-owned ball is recreated (knocked out of its treasure)"),
                () -> assertFalse(w.modes.treasureHave[2], "and its treasure no longer has it"));
        }

        @Test
        @DisplayName("ships reaching for a deleted loose ball stop; Shots.delete makes the new ball after freeing")
        void throughShots() {
            Simulation s = teamSim("");
            World w = s.world;
            int h = ballOf(w, 2);
            Player p = ship(s, "P", 1, 300, 200);
            p.ball = h;
            w.modes.ballOwner[h] = Obj.NO_ID;
            s.shots.delete(h);
            int fresh = ballOf(w, 2);
            assertAll(
                () -> assertEquals(-1, p.ball, "the reach ends"),
                () -> assertEquals(h, fresh, "the freed slot is reused by the new ball (C order: free, then make)"),
                () -> assertEquals(Obj.RECREATE, w.objects.status[fresh], "a fresh resting ball"),
                () -> assertTrue(w.modes.treasureHave[2], "back in its treasure"));
        }
    }

    @Nested
    @DisplayName("atTreasure (Move_segment TREASURE, balls)")
    class AtTreasure {
        static MoveState hit(int h, int treasure) {
            MoveState ms = new MoveState();
            ms.obj = h;
            ms.type = Obj.BALL;
            ms.treasure = treasure;
            ms.crash = MoveState.CRASH_TREASURE;
            return ms;
        }

        @Test
        @DisplayName("own treasure, not cashed: it rests again (no crash, life restarts, the move continues)")
        void rests() {
            Simulation s = teamSim("");
            World w = s.world;
            int h = ballOf(w, 2);
            w.objects.life[h] = 5;
            MoveState ms = hit(h, 2);
            boolean go = s.balls.atTreasure(ms);
            Player enemy = ship(s, "E", 1, 300, 200);
            w.modes.ballOwner[h] = enemy.id;
            MoveState ms2 = hit(h, 2);
            boolean go2 = s.balls.atTreasure(ms2);
            assertAll(
                () -> assertTrue(go, "unowned: continues"),
                () -> assertEquals(MoveState.NOT_A_CRASH, ms.crash, "no crash"),
                () -> assertEquals(Balls.BALL_LIFE, w.objects.life[h], EPS, "life restarts"),
                () -> assertTrue(go2, "owned by another team: continues"),
                () -> assertEquals(MoveState.NOT_A_CRASH, ms2.crash, "no crash either"));
        }

        @Test
        @DisplayName("own treasure brought back by a team member: 5 points, message, quiet recreation")
        void replaced() {
            Simulation s = teamSim("");
            World w = s.world;
            int h = ballOf(w, 2);
            Player mate = ship(s, "Mate", 2, 300, 200);
            w.modes.ballOwner[h] = mate.id;
            long from = w.events.seq();
            MoveState ms = hit(h, 2);
            boolean go = s.balls.atTreasure(ms);
            assertAll(
                () -> assertTrue(go, "the move continues"),
                () -> assertEquals(MoveState.CRASH_TREASURE, ms.crash, "but it is a treasure crash"),
                () -> assertEquals(0.0, w.objects.life[h], EPS, "the ball dies"),
                () -> assertEquals(Obj.NOEXPLOSION | Obj.RECREATE, w.objects.status[h] & (Obj.NOEXPLOSION | Obj.RECREATE),
                    "quietly, to be recreated"),
                () -> assertEquals(5.0, w.teamScore[2], EPS, "5 points (team play: to the team)"),
                () -> assertEquals(List.of(" < Mate (team 2) has replaced the treasure >"), messages(w, from), "message"));
        }

        @Test
        @DisplayName("another treasure: unowned ball dies; enemy ball cashed home destroys the treasure; CTF needs a safe treasure")
        void cashing() {
            Simulation s = teamSim("");
            World w = s.world;
            int h = ballOf(w, 2);
            w.modes.ballOwner[h] = Obj.NO_ID;
            MoveState ms = hit(h, 0);
            boolean unowned = s.balls.atTreasure(ms);
            double unownedLife = w.objects.life[h];
            w.objects.life[h] = Balls.BALL_LIFE - 9;
            Player p = ship(s, "P", 1, 300, 200);
            Player victim = ship(s, "V", 2, 500, 200);
            w.modes.ballOwner[h] = p.id;
            long from = w.events.seq();
            boolean cashed = s.balls.atTreasure(hit(h, 0));
            List<String> msgs = messages(w, from);
            Simulation ctf = teamSim("captureTheFlag: yes\n");
            World cw = ctf.world;
            int ch = ballOf(cw, 2);
            Player cp = ship(ctf, "P", 1, 300, 200);
            ship(ctf, "V", 2, 500, 200);
            cw.modes.ballOwner[ch] = cp.id;
            cw.modes.treasureHave[0] = false;
            long cfrom = cw.events.seq();
            ctf.balls.atTreasure(hit(ch, 0));
            assertAll(
                () -> assertFalse(unowned, "an unowned ball's move ends"),
                () -> assertEquals(0.0, unownedLife, EPS, "and it dies"),
                () -> assertFalse(cashed, "a cashed ball's move ends"),
                () -> assertEquals(" < The ball was loose for 9 frames >", msgs.get(0), "loose frames"),
                () -> assertEquals(1, w.modes.treasureDestroyed[2], "the enemy treasure is destroyed"),
                () -> assertEquals(0, w.objects.status[h] & Obj.RECREATE, "so its ball is not recreated"),
                () -> assertEquals(List.of(" < The ball was loose for 0 frames >",
                    "Your treasure must be safe before you can cash an opponent's!"), messages(cw, cfrom),
                    "CTF: the own treasure must have its ball"),
                () -> assertEquals(0, cw.modes.treasureDestroyed[2], "CTF: nothing destroyed"),
                () -> assertTrue((cw.objects.status[ch] & Obj.RECREATE) != 0, "CTF: the ball is recreated"),
                () -> assertEquals(0.0, victim.score - victim.score, EPS, "(victim exists)"));
        }

        @Test
        @DisplayName("an enemy ball taken to a third team's treasure, or outside team play, just dies")
        void elsewhere() {
            Simulation s = TestWorlds.sim(SimFixture.FPS14, ModeFx.teamRows());
            World w = s.world;
            int h = ballOf(w, 2);
            Player p = ship(s, "P", 0, 300, 200);
            w.modes.ballOwner[h] = p.id;
            long from = w.events.seq();
            boolean go = s.balls.atTreasure(hit(h, 0));
            assertAll(
                () -> assertFalse(go, "the move ends"),
                () -> assertEquals(0.0, w.objects.life[h], EPS, "the ball dies"),
                () -> assertEquals(List.of(), messages(w, from), "no cashing outside team play"),
                () -> assertTrue((w.objects.status[h] & Obj.RECREATE) != 0, "it will be recreated"));
        }
    }

    @Nested
    @DisplayName("punishTeam (Punish_team)")
    class Punish {
        @Test
        @DisplayName("team scores: losers -3*Rate(win, lose) each, cashier 3*por, mates 2*por; counters, event, message")
        void scores() {
            Simulation s = teamSim("treasureKillTeam: yes\n");
            World w = s.world;
            Player p = ship(s, "P", 1, 300, 200);
            Player mate = ship(s, "M", 1, 320, 200);
            Player v1 = ship(s, "V", 2, 500, 200);
            Player v2 = ship(s, "W", 2, 520, 200);
            p.score = 100.7f;
            mate.score = 50.6f;
            v1.score = 10.9f;
            v2.score = 0.2f;
            w.teamScore[1] = 0;
            w.teamScore[2] = 0;
            long from = w.events.seq();
            boolean r = s.balls.punishTeam(p, 2, 0);
            float sc = 3 * Scoring.rate(151, 11);
            float por = (sc * 2) / (2 * 2 + 1);
            assertAll(
                () -> assertTrue(r, "punished"),
                () -> assertEquals(" < P's (1) team has destroyed team 2 treasure >", messages(w, from).get(0), "message"),
                () -> assertEquals("6:" + p.id + ":2", gameEvents(w, from).get(0), "TREASURE_DESTROYED(player, treasure)"),
                () -> assertEquals(1, w.modes.treasureDestroyed[2], "destroyed once"),
                () -> assertEquals(0, w.modes.teamTreasuresLeft[2], "team 2 has none left"),
                () -> assertEquals(1, w.modes.teamTreasuresDestroyed[1], "team 1 destroyed one"),
                () -> assertEquals(-2 * sc, w.teamScore[2], 1e-9, "each loser loses sc (sums truncated to int)"),
                () -> assertEquals(3 * por + 2 * por, w.teamScore[1], 1e-9, "cashier 3*por, mate 2*por"),
                () -> assertTrue((v1.status & Obj.KILLED) != 0 && (v2.status & Obj.KILLED) != 0,
                    "treasureKillTeam kills the losers"),
                () -> assertEquals(0, p.status & Obj.KILLED, "not the winners"),
                () -> assertEquals(1, p.kills, "the cashier gets a kill"));
        }

        @Test
        @DisplayName("own team's treasure: nothing; nobody of the losing team in the game: Rate/2 only")
        void noPunish() {
            Simulation s = teamSim("");
            World w = s.world;
            Player p = ship(s, "P", 1, 300, 200);
            long from = w.events.seq();
            boolean own = s.balls.punishTeam(p, 0, 0);
            List<String> ownMsgs = messages(w, from);
            Player v = ship(s, "V", 2, 500, 200);
            v.status |= Obj.GAME_OVER;
            boolean nobody = s.balls.punishTeam(p, 2, 0);
            assertAll(
                () -> assertFalse(own, "an own-team ball punishes nobody"),
                () -> assertEquals(List.of(), ownMsgs, "silently"),
                () -> assertFalse(nobody, "no live loser: not punished"),
                () -> assertEquals(0, w.modes.treasureDestroyed[2], "nothing destroyed"),
                () -> assertEquals(Scoring.rate(0, Scoring.CANNON_SCORE) / 2, w.teamScore[1], 1e-9,
                    "the cashier gets Rate(score, CANNON_SCORE)/2"),
                () -> assertEquals(0, v.status & Obj.KILLED, "no kill"));
        }

        @Test
        @DisplayName("paused-out and waiting players with no score are ignored")
        void ignored() {
            Simulation s = teamSim("");
            Player p = ship(s, "P", 1, 300, 200);
            Player paused = ship(s, "Q", 2, 500, 200);
            paused.status |= Obj.PAUSE;
            paused.count = 0;
            Player waiting = ship(s, "W", 2, 520, 200);
            waiting.status |= Obj.GAME_OVER;
            waiting.mychar = 'W';
            Player counting = ship(s, "C", 2, 540, 200);
            counting.status |= Obj.PAUSE;
            counting.count = 5;
            assertAll(
                () -> assertTrue(Balls.ignored(paused), "paused with the count run out"),
                () -> assertTrue(Balls.ignored(waiting), "waiting with score 0"),
                () -> assertFalse(Balls.ignored(counting), "paused but still counting"),
                () -> assertFalse(Balls.ignored(p), "playing"),
                () -> assertTrue(s.balls.punishTeam(p, 2, 0),
                    "a paused player still counting down is a loser who is in the game (not GAME_OVER)"));
        }
    }

    @Nested
    @DisplayName("playerHit (Player_collides_with_ball)")
    class PlayerHit {
        @Test
        @DisplayName("unshielded: 50 fuel and (treasureCollisionDestroys) the ball; survives with fuel unless it may kill")
        void survives() {
            Simulation s = teamSim("");
            World w = s.world;
            int h = ballOf(w, 2);
            Player p = ship(s, "P", 1, w.objects.px[h] - 20, w.objects.py[h]);
            double fuel = p.fuel;
            s.balls.playerHit(p, h, Const.SHIP_SZ + 10);
            assertAll(
                () -> assertEquals(fuel - 50 * 256, p.fuel, EPS, "50 fuel"),
                () -> assertEquals(0.0, w.objects.life[h], EPS, "the ball is destroyed"),
                () -> assertEquals(0, p.status & Obj.KILLED, "the ship survives"),
                () -> assertTrue(p.vx < 0, "and is pushed away from the ball"));
        }

        @Test
        @DisplayName("fully shielded: no fuel, ball kept; treasureCollisionDestroys off keeps the ball")
        void shielded() {
            Simulation s = teamSim("treasureCollisionMayKill: yes\n");
            World w = s.world;
            int h = ballOf(w, 2);
            Player p = ship(s, "P", 1, w.objects.px[h] - 20, w.objects.py[h]);
            p.used |= Has.SHIELD | Has.EMERGENCY_SHIELD;
            double fuel = p.fuel;
            s.balls.playerHit(p, h, Const.SHIP_SZ + 10);
            Simulation keep = teamSim("treasureCollisionDestroys: no\n");
            int kh = ballOf(keep.world, 2);
            Player kp = ship(keep, "P", 1, 300, 200);
            keep.balls.playerHit(kp, kh, Const.SHIP_SZ + 10);
            assertAll(
                () -> assertEquals(fuel, p.fuel, EPS, "no fuel lost"),
                () -> assertEquals(Balls.BALL_LIFE, w.objects.life[h], EPS, "the ball is kept"),
                () -> assertEquals(0, p.status & Obj.KILLED, "shielded ships survive even when balls may kill"),
                () -> assertEquals(Balls.BALL_LIFE, keep.world.objects.life[kh], EPS, "treasureCollisionDestroys off"));
        }

        @Test
        @DisplayName("treasureCollisionMayKill: armor takes the hit; else death by an unowned, own or enemy ball")
        void kills() {
            Simulation s = teamSim("treasureCollisionMayKill: yes\nballKillScoreMult: 2\nunownedKillScoreMult: 0.5\n");
            World w = s.world;
            int h = ballOf(w, 2);
            Player armored = ship(s, "A", 1, 300, 200);
            armored.have |= Has.ARMOR;
            armored.items[com.fulcrologic.jxpilot.common.Item.ARMOR.ordinal()] = 2;
            s.balls.playerHit(armored, h, Const.SHIP_SZ + 10);
            Player p = ship(s, "P", 1, 300, 200);
            long from = w.events.seq();
            s.balls.playerHit(p, h, Const.SHIP_SZ + 10);
            List<String> unowned = messages(w, from);
            double unownedTeam = w.teamScore[1];
            Player killer = ship(s, "K", 2, 400, 200);
            w.modes.ballOwner[h] = killer.id;
            Player v = ship(s, "V", 1, 300, 200);
            long from2 = w.events.seq();
            s.balls.playerHit(v, h, Const.SHIP_SZ + 10);
            List<String> owned = messages(w, from2);
            List<String> ev = gameEvents(w, from2);
            w.modes.ballOwner[h] = v.id;
            long from3 = w.events.seq();
            s.balls.playerHit(v, h, Const.SHIP_SZ + 10);
            List<String> self = messages(w, from3);
            assertAll(
                () -> assertEquals(0, armored.status & Obj.KILLED, "armor saves the ship"),
                () -> assertEquals(1, armored.items[com.fulcrologic.jxpilot.common.Item.ARMOR.ordinal()], "one armor used"),
                () -> assertTrue((p.status & Obj.KILLED) != 0, "an unarmored ship dies"),
                () -> assertEquals(List.of("P was killed by a ball."), unowned, "unowned ball message"),
                () -> assertEquals(-Scoring.rate(0, 0) * 2 * 0.5, unownedTeam, 1e-6, "Rate(0)*ballKill*unowned mult"),
                () -> assertEquals(List.of("V was killed by a ball owned by K."), owned, "owned ball message"),
                () -> assertEquals("1:" + killer.id + ":" + v.id, ev.get(0), "KILL(killer, victim) first"),
                () -> assertEquals(1, killer.kills, "the owner gets the kill"),
                () -> assertEquals(List.of("V was killed by a ball owned by V.  How strange!"), self, "own ball"));
        }
    }

    @Nested
    @DisplayName("collide (BallCollision)")
    class Collide {
        @Test
        @DisplayName("ball-ball: repel when gentle, both die when violent; shots are absorbed; only with ballCollisions")
        void objects() {
            Simulation s = teamSim("ballCollisions: yes\nmaxObjectWallBounceSpeed: 20\n");
            World w = s.world;
            Objects o = w.objects;
            int a = obj(w, Obj.BALL, Obj.NO_ID, 300, 150, 1, 0, 50, 100);
            w.modes.ballTreasure[a] = 1;
            int b = obj(w, Obj.BALL, Obj.NO_ID, 315, 150, -1, 0, 50, 100);
            w.modes.ballTreasure[b] = 1;
            int shot = obj(w, Obj.SHOT, Obj.NO_ID, 400, 150, 10, 0, 1, 10);
            o.plRadius[shot] = 0;
            int ball2 = obj(w, Obj.BALL, Obj.NO_ID, 395, 150, 0, 0, 50, 100);
            w.modes.ballTreasure[ball2] = 1;
            o.plRadius[a] = o.plRadius[b] = o.plRadius[ball2] = 10;
            w.grid.rebuild(o);
            s.balls.collide();
            float shotVx = o.vx[shot];
            Simulation v = teamSim("ballCollisions: yes\nmaxObjectWallBounceSpeed: 20\n");
            World vw = v.world;
            int c = obj(vw, Obj.BALL, Obj.NO_ID, 300, 150, 11, 0, 50, 100);
            vw.modes.ballTreasure[c] = 1;
            int d = obj(vw, Obj.BALL, Obj.NO_ID, 315, 150, -11, 0, 50, 100);
            vw.modes.ballTreasure[d] = 1;
            vw.objects.plRadius[c] = vw.objects.plRadius[d] = 10;
            vw.grid.rebuild(vw.objects);
            v.balls.collide();
            Simulation off = teamSim("");
            int e = obj(off.world, Obj.BALL, Obj.NO_ID, 300, 150, 0, 0, 50, 100);
            off.world.modes.ballTreasure[e] = 1;
            int f = obj(off.world, Obj.SHOT, Obj.NO_ID, 300, 150, 0, 0, 1, 10);
            off.world.grid.rebuild(off.world.objects);
            off.balls.collide();
            assertAll(
                () -> assertTrue(o.vx[a] < 1 && o.vx[b] > -1, "gentle balls repel"),
                () -> assertEquals(100.0, o.life[a], EPS, "and survive"),
                () -> assertEquals(0.0, o.life[shot], EPS, "a shot hitting a ball dies"),
                () -> assertEquals(10f * 1 / 51f, shotVx, 1e-6, "inelastic: (0*50 + 10*1)/51"),
                () -> assertEquals(0.0, vw.objects.life[c] + vw.objects.life[d], EPS, "violent: both die"),
                () -> assertEquals(10.0, off.world.objects.life[f], EPS, "without ballCollisions nothing happens"));
        }

        @Test
        @DisplayName("balls resting in a treasure or connected to a phased ship do not collide; sparks need ballSparkCollisions")
        void skips() {
            Simulation s = teamSim("ballCollisions: yes\nballSparkCollisions: no\n");
            World w = s.world;
            Objects o = w.objects;
            int rest = ballOf(w, 0);
            int shot = obj(w, Obj.SHOT, Obj.NO_ID, o.px[rest], o.py[rest], 0, 0, 1, 10);
            int ball = obj(w, Obj.BALL, Obj.NO_ID, 300, 150, 0, 0, 50, 100);
            w.modes.ballTreasure[ball] = 1;
            int spark = obj(w, Obj.SPARK, Obj.NO_ID, 300, 150, 0, 0, 1, 10);
            int item = obj(w, Obj.ITEM, Obj.NO_ID, 300, 150, 0, 0, 1, 10);
            Player ph = ship(s, "P", 1, 500, 200);
            ph.used |= Has.PHASING_DEVICE;
            int phased = obj(w, Obj.BALL, ph.id, 600, 150, 0, 0, 50, 100);
            w.modes.ballTreasure[phased] = 1;
            int shot2 = obj(w, Obj.SHOT, Obj.NO_ID, 600, 150, 0, 0, 1, 10);
            w.grid.rebuild(o);
            s.balls.collide();
            Simulation sp = teamSim("ballCollisions: yes\n");
            int b2 = obj(sp.world, Obj.BALL, Obj.NO_ID, 300, 150, 0, 0, 50, 100);
            sp.world.modes.ballTreasure[b2] = 1;
            sp.world.objects.plRadius[b2] = 10;
            int spark2 = obj(sp.world, Obj.SPARK, Obj.NO_ID, 300, 150, 0, 0, 1, 10);
            sp.world.grid.rebuild(sp.world.objects);
            sp.balls.collide();
            assertAll(
                () -> assertEquals(10.0, o.life[shot], EPS, "a ball resting in its treasure absorbs nothing"),
                () -> assertEquals(10.0, o.life[spark], EPS, "sparks pass without ballSparkCollisions"),
                () -> assertEquals(10.0, o.life[item], EPS, "items never collide with balls here"),
                () -> assertEquals(10.0, o.life[shot2], EPS, "a phased ball absorbs nothing"),
                () -> assertEquals(0.0, sp.world.objects.life[spark2], EPS, "with ballSparkCollisions a spark is absorbed"));
        }
    }

    @Nested
    @DisplayName("resetRound (Reset_all_players, team play)")
    class Reset {
        @Test
        @DisplayName("every ball goes without exploding, every treasure gets a fresh one, counters start over")
        void reset() {
            Simulation s = teamSim("");
            World w = s.world;
            Objects o = w.objects;
            int h = ballOf(w, 2);
            Player p = ship(s, "P", 1, 300, 200);
            tow(w, p, h);
            o.initPixels(h, 400, 200);
            w.modes.treasureDestroyed[2] = 3;
            w.modes.teamTreasuresLeft[2] = 0;
            w.modes.teamTreasuresDestroyed[1] = 1;
            long bursts = w.events.burstSeq();
            s.balls.resetRound();
            int fresh = ballOf(w, 2);
            assertAll(
                () -> assertEquals(2, balls(w).size(), "two balls again"),
                () -> assertEquals(bursts, w.events.burstSeq(), "no explosion"),
                () -> assertEquals(Click.fromPixels(18.5f * 35), o.cx[fresh], "the ball is back in its treasure"),
                () -> assertEquals(0, w.modes.treasureDestroyed[2], "destroyed count cleared"),
                () -> assertEquals(1, w.modes.teamTreasuresLeft[2], "team 2 has its treasure again"),
                () -> assertEquals(0, w.modes.teamTreasuresDestroyed[1], "team 1 destroyed none"),
                () -> assertEquals(-1, p.ball, "nobody reaches for a removed ball"));
        }
    }

    @Nested
    @DisplayName("ship hooks (Player_death_reset, Pause_player, Delete_player, warp)")
    class ShipHooks {
        @Test
        @DisplayName("death and pause drop the ball; leaving deletes a towed ball and unowns the others")
        void deathPauseLeave() {
            Simulation s = teamSim("");
            World w = s.world;
            Objects o = w.objects;
            int a = ballOf(w, 0);
            int b = ballOf(w, 2);
            Player p = ship(s, "P", 1, 300, 200);
            tow(w, p, b);
            s.ships.deathReset(p);
            int afterDeath = o.owner[b];
            tow(w, p, b);
            p.count = 0;
            s.ships.pause(p, true);
            int afterPause = o.owner[b];
            Player q = ship(s, "Q", 2, 400, 200);
            tow(w, q, b);
            w.modes.ballOwner[a] = q.id;
            o.initPixels(b, 600, 200);
            s.ships.leave(q);
            int fresh = ballOf(w, 2);
            assertAll(
                () -> assertEquals(Obj.NO_ID, afterDeath, "death drops the ball"),
                () -> assertEquals(Obj.NO_ID, afterPause, "pausing drops the ball"),
                () -> assertEquals(Obj.NO_ID, w.modes.ballOwner[a], "the leaver's balls lose their owner"),
                () -> assertEquals(Obj.RECREATE, o.status[fresh], "the towed ball was deleted and recreated"),
                () -> assertEquals(Click.fromPixels(18.5f * 35), o.cx[fresh], "back in its treasure"));
        }

        @Test
        @DisplayName("a warp takes the towed balls along by the same offset; a reach ends")
        void warp() {
            String[] rows = ModeFx.teamRows();
            rows[3] = "  @       ^     @   ";
            Simulation s = TestWorlds.sim(ModeFx.TEAM, rows);
            World w = s.world;
            Objects o = w.objects;
            int b = ballOf(w, 2);
            Player p = ship(s, "P", 1, 2.5 * 35, 4.5 * 35);
            tow(w, p, b);
            o.initPixels(b, 2.5 * 35 + 40, 4.5 * 35);
            p.used |= Has.CONNECTOR;
            p.ball = 0;
            p.wormHoleHit = 0;
            p.status |= Obj.WARPING;
            s.ships.warp(p);
            assertAll(
                () -> assertEquals(16.5 * 35, p.px, 1, "the ship came out of the other wormhole"),
                () -> assertEquals(p.px + 40, o.px[b], 1, "the ball keeps its offset"),
                () -> assertEquals(o.px[b], o.prevX[b], "and its previous position is reset"),
                () -> assertEquals(-1, p.ball, "the reach ends"));
        }
    }
}
