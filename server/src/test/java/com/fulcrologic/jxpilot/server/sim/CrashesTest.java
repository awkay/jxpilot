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

import com.fulcrologic.jxpilot.common.net.Msg;
import com.fulcrologic.jxpilot.common.Const;
import com.fulcrologic.jxpilot.common.map.BlockType;
import com.fulcrologic.jxpilot.common.map.Cannon;
import com.fulcrologic.jxpilot.common.math.DebrisBurst;
import com.fulcrologic.jxpilot.common.math.Rng;
import com.fulcrologic.jxpilot.common.net.Records;
import com.fulcrologic.jxpilot.server.world.Events;
import com.fulcrologic.jxpilot.common.sim.Has;
import com.fulcrologic.jxpilot.common.sim.Obj;
import com.fulcrologic.jxpilot.server.world.Objects;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.fulcrologic.jxpilot.server.sim.MoveState.*;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.FPS14;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.FRAME;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.gameEvents;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.killCauses;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.lastBurst;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.live;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.messages;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.obj;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.ready;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Crashes (walls.c Object_crash, Player_crash, Cannon_dies)")
class CrashesTest {
    static final double EPS = 1e-9;

    static MoveState ms(int crash) {
        MoveState m = new MoveState();
        m.crash = crash;
        return m;
    }

    static boolean dead(World w) {
        return w.cannonDeadTime[0] > 0;
    }

    @Nested
    @DisplayName("objectCrashed (Object_crash)")
    class ObjectCrash {
        @Test
        @DisplayName("a crash ends the object's life; wormholes and non-crashes do nothing")
        void lifeEnds() {
            Simulation s = CannonsTest.sim(FRAME, "_");
            World w = s.world;
            int[] kinds = {CRASH_WALL, CRASH_UNIVERSE, CRASH_TARGET, CRASH_TREASURE, CRASH_UNKNOWN};
            List<Double> lives = new ArrayList<>();
            for (int k : kinds) {
                int h = obj(w, Obj.SHOT, Obj.NO_ID, 100, 100, 0, 0, 0.1, 30);
                s.crashes.objectCrashed(h, ms(k));
                lives.add(w.objects.life[h]);
            }
            int worm = obj(w, Obj.SHOT, Obj.NO_ID, 100, 100, 0, 0, 0.1, 30);
            s.crashes.objectCrashed(worm, ms(CRASH_WORMHOLE));
            int none = obj(w, Obj.SHOT, Obj.NO_ID, 100, 100, 0, 0, 0.1, 30);
            s.crashes.objectCrashed(none, ms(NOT_A_CRASH));
            assertAll(
                () -> assertEquals(List.of(0.0, 0.0, 0.0, 0.0, 0.0), lives, "wall/universe/target/treasure/unknown end it"),
                () -> assertEquals(30.0, w.objects.life[worm], EPS, "a wormhole crash leaves it alive"),
                () -> assertEquals(30.0, w.objects.life[none], EPS, "NOT_A_CRASH leaves it alive"),
                () -> assertFalse(dead(w), "no cannon is hurt"));
        }

        @Test
        @DisplayName("an object hitting a cannon kills it and credits the object's owner")
        void intoCannon() {
            Simulation s = CannonsTest.sim(FRAME + "cannonPoints: 10\n", "_");
            World w = s.world;
            Player p = ready(s, "P", 0, 100, 100);
            int h = obj(w, Obj.SHOT, p.id, 700, 680, 0, 0, 0.1, 30);
            MoveState m = ms(CRASH_CANNON);
            m.cannon = 0;
            s.crashes.objectCrashed(h, m);
            assertAll(
                () -> assertEquals(0.0, w.objects.life[h], EPS, "the object dies"),
                () -> assertTrue(dead(w), "the cannon dies"),
                () -> assertEquals(10.0, p.score, EPS, "the owner gets cannonPoints"));
        }
    }

    @Nested
    @DisplayName("cannonDies (Cannon_dies)")
    class CannonDies {
        @Test
        @DisplayName("dead for cannonDeadTime seconds of frames, block SPACE, 20-39 debris out of its face")
        void explosion() {
            Simulation s = CannonsTest.sim(FRAME, "_");
            World w = s.world;
            Cannon c = w.map.cannons().get(0);
            w.rng.seed(6);
            Rng ref = new Rng(6);
            int num = 20 + (int) (ref.nextFloat() * 20);
            s.crashes.cannonDies(0, Obj.NO_ID);
            DebrisBurst d = lastBurst(w);
            int i = Events.burstIndex(w.events.burstSeq() - 1);
            Objects o = w.objects;
            int h0 = live(w).get(0);
            Simulation s14 = CannonsTest.sim(FPS14, "_");
            s14.crashes.cannonDies(0, Obj.NO_ID);
            assertAll(
                () -> assertEquals(72.0 * 60, w.cannonDeadTime[0], EPS, "dead 72 s * 60 fps frames"),
                () -> assertEquals(72.0 * 14, s14.world.cannonDeadTime[0], EPS, "dead 72 s * 14 fps frames at 14 fps"),
                () -> assertEquals(BlockType.SPACE, w.map.block(c.bx(), c.by()), "its block becomes SPACE"),
                () -> assertEquals(num, d.count, "20 + (int)(rfrac * 20) debris"),
                () -> assertEquals((float) CannonsTest.CX, d.x, "from (int) cannon x"),
                () -> assertEquals((float) CannonsTest.CY, d.y, "from (int) cannon y"),
                () -> assertEquals(0.0f, d.vx, "at rest"),
                // (int)(32 - 25.6) = 6 .. (int)(32 + 25.6) = 57
                () -> assertEquals(6, d.minDir, "from dir - RES*0.2"),
                () -> assertEquals(51, d.dirSpan, "to dir + RES*0.2"),
                () -> assertEquals(20.0f, d.minSpeed, "speed from 20"),
                () -> assertEquals(50.0f, d.maxSpeed, "to 50"),
                () -> assertEquals(8, d.minLife, "life from 8"),
                () -> assertEquals(60, d.maxLife, "to 68, capped at shotLife 60"),
                () -> assertEquals(Records.BurstKind.DEBRIS, w.events.burstKind[i], "a DEBRIS burst"),
                () -> assertEquals(Obj.NO_ID, w.events.burstOwner[i], "unowned"),
                () -> assertEquals(Obj.DEBRIS, o.type[h0], "debris"),
                () -> assertEquals(4.5, o.mass[h0], EPS, "mass 4.5"),
                () -> assertEquals(6, o.plRange[h0], "radius 6"),
                () -> assertEquals(c.team(), o.team[h0], "of the cannon's team"),
                () -> assertEquals(Obj.NO_ID, o.owner[h0], "owned by nobody"));
        }

        @Test
        @DisplayName("a dead cannon dies only once; index -1 is ignored")
        void once() {
            Simulation s = CannonsTest.sim(FRAME + "cannonPoints: 10\n", "_");
            World w = s.world;
            Player p = ready(s, "P", 0, 100, 100);
            w.cannonDeadTime[0] = 5;
            s.crashes.cannonDies(0, p.id);
            s.crashes.cannonDies(-1, p.id);
            assertAll(
                () -> assertEquals(5.0, w.cannonDeadTime[0], EPS, "the dead time is not reset"),
                () -> assertEquals(0, w.events.burstSeq(), "no second explosion"),
                () -> assertEquals(0.0, p.score, EPS, "no points"));
        }

        @Test
        @DisplayName("points: cannonPoints to a killer at or below cannonMaxScore; none without killer or points")
        void points() {
            Simulation s = CannonsTest.sim(FRAME + "cannonPoints: 10\n", "____");
            Player a = ready(s, "A", 0, 100, 100);
            Player b = ready(s, "B", 0, 100, 100);
            b.score = 100;
            Player c = ready(s, "C", 0, 100, 100);
            c.score = 100.5f;
            s.crashes.cannonDies(0, a.id);
            s.world.cannonDeadTime[0] = 0;
            s.crashes.cannonDies(0, b.id);
            s.world.cannonDeadTime[0] = 0;
            s.crashes.cannonDies(0, c.id);
            s.world.cannonDeadTime[0] = 0;
            s.crashes.cannonDies(0, Obj.NO_ID);
            Simulation z = CannonsTest.sim(FRAME + "cannonPoints: 0\n", "_");
            Player d = ready(z, "D", 0, 100, 100);
            d.scoreDirty = false;
            z.crashes.cannonDies(0, d.id);
            assertAll(
                () -> assertEquals(10.0, a.score, EPS, "a killer at score 0 gets cannonPoints"),
                () -> assertEquals(110.0, b.score, EPS, "a killer exactly at cannonMaxScore still gets them"),
                () -> assertEquals(100.5, c.score, EPS, "a killer above cannonMaxScore gets nothing"),
                () -> assertTrue(dead(z.world), "the cannon dies without points"),
                () -> assertEquals(0.0, d.score, EPS, "cannonPoints 0 gives nothing"),
                () -> assertFalse(d.scoreDirty, "and does not touch the score at all"));
        }

        @Test
        @DisplayName("team play: with teamCannons the cannon's team loses the points; team mates get none")
        void teams() {
            Simulation s = CannonsTest.sim(FRAME + "cannonPoints: 10\nteamPlay: yes\nreserveRobotTeam: no\nteamCannons: yes\n", "01");
            World w = s.world;
            int ct = w.map.cannons().get(0).team();
            Player mate = ready(s, "M", ct, 100, 100);
            Player foe = ready(s, "F", 1 - ct, 100, 100);
            s.crashes.cannonDies(0, mate.id);
            double mateScore = mate.score;
            double teamAfterMate = w.teamScore[ct];
            w.cannonDeadTime[0] = 0;
            s.crashes.cannonDies(0, foe.id);
            Simulation n = CannonsTest.sim(FRAME + "cannonPoints: 10\nteamPlay: yes\nreserveRobotTeam: no\n", "01");
            int nt = n.world.map.cannons().get(0).team();
            Player q = ready(n, "Q", 0, 100, 100);
            n.crashes.cannonDies(0, q.id);
            assertAll(
                () -> assertEquals(0.0, mateScore, EPS, "a team mate of the cannon gets no points"),
                () -> assertEquals(-10.0, teamAfterMate, EPS, "the cannon's team loses cannonPoints"),
                () -> assertEquals(10.0, foe.score, EPS, "an enemy gets cannonPoints"),
                () -> assertEquals(-20.0, w.teamScore[ct], EPS, "the cannon's team loses them again"),
                () -> assertEquals(10.0, w.teamScore[1 - ct], EPS, "the enemy's team gains through the player's score"),
                () -> assertEquals(Const.TEAM_NOT_SET, nt, "without teamCannons cannons have no team"),
                () -> assertEquals(10.0, q.score, EPS, "and anyone gets the points"),
                () -> assertEquals(10.0, n.world.teamScore[0], EPS, "no team loses points without teamCannons"));
        }
    }

    @Nested
    @DisplayName("playerCrashed (Player_crash)")
    class PlayerCrash {
        Simulation s;
        World w;
        Player a;

        void setup(String extra) {
            s = CannonsTest.sim(FRAME + extra, "____");
            w = s.world;
            a = ready(s, "A", 0, 100, 100);
        }

        String crash(int kind, int pt) {
            a.status = Obj.PLAYING;
            a.score = 0;
            w.cannonDeadTime[0] = 0;
            long seq = w.events.seq();
            MoveState m = ms(kind);
            m.cannon = 0;
            s.crashes.playerCrashed(a, m, pt);
            List<String> l = messages(w, seq);
            return l.isEmpty() ? null : l.get(0);
        }

        @Test
        @DisplayName("each crash kind has its C message; hull point 0 crashes head first")
        void messagesPerKind() {
            setup("");
            assertAll(
                () -> assertEquals("A crashed against a wall.", crash(CRASH_WALL, 1), "wall"),
                () -> assertEquals("A smashed against a wall.", crash(CRASH_WALL_SPEED, 1), "too fast"),
                () -> assertEquals("A smacked against a wall.", crash(CRASH_WALL_NO_FUEL, 1), "no fuel"),
                () -> assertEquals("A was trashed against a wall.", crash(CRASH_WALL_ANGLE, 1), "bad angle"),
                () -> assertEquals("A smashed against a target.", crash(CRASH_TARGET, 1), "target"),
                () -> assertEquals("A smashed against a treasure.", crash(CRASH_TREASURE, 1), "treasure"),
                () -> assertEquals("A smashed against a cannon.", crash(CRASH_CANNON, 1), "cannon"),
                () -> assertEquals("A left the known universe.", crash(CRASH_UNIVERSE, 1), "universe"),
                () -> assertEquals("A slammed into a programming error.", crash(CRASH_UNKNOWN, 1), "unknown"),
                () -> assertEquals("A crashed head first against a wall.", crash(CRASH_WALL, 0), "head first"),
                () -> assertEquals("A left the known universe head first.", crash(CRASH_UNIVERSE, 0),
                    "head first goes after the universe"));
        }

        @Test
        @DisplayName("without shovers the ship dies and loses Rate(WALL_SCORE, score)")
        void wallScore() {
            setup("");
            a.score = 0;
            long seq = w.events.seq();
            s.crashes.playerCrashed(a, ms(CRASH_WALL), 2);
            assertAll(
                () -> assertTrue((a.status & Obj.KILLED) != 0, "the ship is killed"),
                () -> assertEquals(-10.0f * 1024 / (2000 + 1024), a.score, EPS, "loses Rate(2000, 0)"),
                () -> assertEquals(List.of("2:" + a.id + ":-1"), gameEvents(w, seq), "emits CRASH"));
        }

        @Test
        @DisplayName("the CRASH event carries the C crash kind and comes before its score popup (S12 / client order)")
        void crashKindAndOrder() {
            setup("");
            long seq = w.events.seq();
            s.crashes.playerCrashed(a, ms(CRASH_WALL_SPEED), 2);
            int first = com.fulcrologic.jxpilot.server.world.Events.index(seq + 1);
            List<String> ev = ShipFx.events(w, seq);
            assertAll(
                () -> assertEquals(com.fulcrologic.jxpilot.server.world.Events.GAME_EVENT, w.events.kind[first],
                    "after the message comes the crash event"),
                () -> assertEquals((float) Msg.GameEvent.CRASH_WALL, w.events.value[first], "value = CRASH_WALL"),
                () -> assertTrue(ev.get(2).startsWith("SCORE_OBJECT " + a.id + " "), "then the score popup: " + ev),
                () -> assertTrue(ev.get(2).endsWith(" [Wall]"), "labelled like C's hudmsg"),
                () -> assertEquals(List.of(Msg.GameEvent.CRASH_WALL, Msg.GameEvent.CRASH_WALL, Msg.GameEvent.CRASH_WALL,
                        Msg.GameEvent.CRASH_WALL, Msg.GameEvent.CRASH_TARGET, Msg.GameEvent.CRASH_TREASURE,
                        Msg.GameEvent.CRASH_CANNON, Msg.GameEvent.CRASH_UNIVERSE, Msg.GameEvent.CRASH_UNKNOWN),
                    List.of(Crashes.crashKind(CRASH_WALL), Crashes.crashKind(CRASH_WALL_SPEED),
                        Crashes.crashKind(CRASH_WALL_NO_FUEL), Crashes.crashKind(CRASH_WALL_ANGLE),
                        Crashes.crashKind(CRASH_TARGET), Crashes.crashKind(CRASH_TREASURE),
                        Crashes.crashKind(CRASH_CANNON), Crashes.crashKind(CRASH_UNIVERSE),
                        Crashes.crashKind(CRASH_UNKNOWN)), "each C crash kind maps to its sub-type"),
                () -> assertEquals(List.of("[Wall]", "[Target]", "[Treasure]", "[Cannon]", "[Universe]", "[Bug]"),
                    List.of(Crashes.hud(CRASH_WALL_ANGLE), Crashes.hud(CRASH_TARGET), Crashes.hud(CRASH_TREASURE),
                        Crashes.hud(CRASH_CANNON), Crashes.hud(CRASH_UNIVERSE), Crashes.hud(CRASH_UNKNOWN)),
                    "C hudmsg labels"));
        }

        @Test
        @DisplayName("wormholes start warping; NOT_A_CRASH does nothing")
        void wormholeAndNothing() {
            setup("");
            MoveState m = ms(CRASH_WORMHOLE);
            m.wormhole = 3;
            long seq = w.events.seq();
            s.crashes.playerCrashed(a, m, 0);
            int st = a.status;
            int hit = a.wormHoleHit;
            List<String> msgs = messages(w, seq);
            a.status = Obj.PLAYING;
            s.crashes.playerCrashed(a, ms(NOT_A_CRASH), 0);
            assertAll(
                () -> assertTrue((st & Obj.WARPING) != 0, "WARPING is set"),
                () -> assertEquals(0, st & Obj.KILLED, "not killed"),
                () -> assertEquals(3, hit, "remembers the wormhole"),
                () -> assertEquals(List.of(), msgs, "no message"),
                () -> assertEquals(Obj.PLAYING, a.status, "NOT_A_CRASH leaves the ship alone"),
                () -> assertEquals(0.0, a.score, EPS, "and its score"));
        }

        @Test
        @DisplayName("a cannon crash destroys the cannon; a fully shielded ship survives and gets the points")
        void cannon() {
            setup("cannonPoints: 10\n");
            a.used |= Has.BOTH_SHIELDS;
            MoveState m = ms(CRASH_CANNON);
            m.cannon = 0;
            long seq = w.events.seq();
            s.crashes.playerCrashed(a, m, 0);
            boolean deadCannon = dead(w);
            int st = a.status;
            float sc = a.score;
            List<String> msgs = messages(w, seq);
            w.cannonDeadTime[0] = 0;
            a.used = Has.SHIELD;
            s.crashes.playerCrashed(a, m, 1);
            assertAll(
                () -> assertTrue(deadCannon, "the cannon dies"),
                () -> assertEquals(0, st & Obj.KILLED, "a fully shielded ship survives"),
                () -> assertEquals(10.0, sc, EPS, "and gets cannonPoints"),
                () -> assertEquals(List.of(), msgs, "no crash message"),
                () -> assertTrue((a.status & Obj.KILLED) != 0, "a merely shielded ship dies"),
                () -> assertTrue(dead(w), "and still destroys the cannon"),
                () -> assertEquals(10 - CollisionsTest.rate(2000, 10), a.score, EPS,
                    "loses Rate(WALL_SCORE) and gets no cannon points"));
        }

        @Test
        @DisplayName("shovers of the last 20 frames share the credit; the last gets the kill")
        void shoves() {
            setup("");
            Player b = ready(s, "B", 0, 300, 300);
            Player c = ready(s, "C", 0, 300, 300);
            b.score = 100;
            c.score = 40;
            w.frames = 100;
            a.shovePusher[0] = b.id;
            a.shoveTime[0] = 90;
            a.shovePusher[1] = b.id;
            a.shoveTime[1] = 80;
            a.shovePusher[2] = c.id;
            a.shoveTime[2] = 85;
            a.shovePusher[3] = Obj.NO_ID;
            long seq = w.events.seq();
            s.crashes.playerCrashed(a, ms(CRASH_WALL), 1);
            // walls.c Player_crash: average = (int)((2*100 + 40) / 3) = 80
            assertAll(
                () -> assertEquals(List.of("A crashed against a wall with help from B and C."), messages(w, seq),
                    "names the shovers in record order"),
                () -> assertEquals(100 + 2 * CollisionsTest.rate(100, 0) * 0.5f / 3, b.score, EPS,
                    "B gets 2/3 of Rate(B, A) * shoveKillScoreMult"),
                () -> assertEquals(40 + CollisionsTest.rate(40, 0) * 0.5f / 3, c.score, EPS,
                    "C gets 1/3 of Rate(C, A) * shoveKillScoreMult"),
                () -> assertEquals(-CollisionsTest.rate(80, 0) * 0.5f, a.score, EPS,
                    "A loses Rate(average pusher score, A) * shoveKillScoreMult"),
                () -> assertEquals(1, c.kills, "the last shover gets the kill"),
                () -> assertEquals(0, b.kills, "the others do not"),
                () -> assertEquals(List.of("1:" + c.id + ":" + a.id), gameEvents(w, seq), "emits KILL(C, A)"),
                () -> assertTrue((a.status & Obj.KILLED) != 0, "the ship dies"));
        }

        @Test
        @DisplayName("the KILL is tagged as a shove; robots go to war with the pusher at (int)(rfrac * pushers), drawn"
            + " after the scores (walls.c:2195-2197)")
        void shoveWarTarget() {
            for (int want = 0; want < 2; want++) {
                setup("");
                Player b = ready(s, "B", 0, 300, 300);
                Player c = ready(s, "C", 0, 300, 300);
                w.frames = 100;
                a.shovePusher[0] = b.id;
                a.shoveTime[0] = 90;
                a.shovePusher[1] = c.id;
                a.shoveTime[1] = 95;
                int seed = 1;
                while ((int) (new Rng(seed).nextFloat() * 2) != want) {
                    seed++;
                }
                w.rng.seed(seed);
                long seq = w.events.seq();
                s.crashes.playerCrashed(a, ms(CRASH_WALL), 1);
                Rng ref = new Rng(seed);
                ref.nextFloat();
                int warOn = want == 0 ? b.id : c.id;
                String label = "pusher " + want;
                List<String> kills = killCauses(w, seq);
                float next = (float) w.rfrac();
                float expectedNext = ref.nextFloat();
                assertAll(
                    () -> assertEquals(List.of(c.id + ":" + a.id + ":" + Events.KILL_SHOVE + ":" + warOn), kills,
                        label + " is the war target; the last pusher keeps the kill"),
                    () -> assertEquals(expectedNext, next, label + ": exactly one rfrac was drawn"));
            }
        }

        @Test
        @DisplayName("without shovers no rfrac is drawn for a war target")
        void noShoversNoDraw() {
            setup("");
            w.rng.seed(5);
            s.crashes.playerCrashed(a, ms(CRASH_WALL), 1);
            assertEquals(new Rng(5).nextFloat(), w.rfrac(), "the next rfrac is still the seed's first");
        }

        @Test
        @DisplayName("three shovers are listed with commas and 'and'")
        void threeShovers() {
            setup("");
            Player b = ready(s, "B", 0, 300, 300);
            Player c = ready(s, "C", 0, 300, 300);
            Player d = ready(s, "D", 0, 300, 300);
            w.frames = 50;
            int[] ids = {b.id, c.id, d.id, b.id};
            for (int i = 0; i < 4; i++) {
                a.shovePusher[i] = ids[i];
                a.shoveTime[i] = 45;
            }
            long seq = w.events.seq();
            s.crashes.playerCrashed(a, ms(CRASH_WALL), 1);
            assertAll(
                () -> assertEquals(List.of("A crashed against a wall with help from B, C and D."), messages(w, seq),
                    "B, C and D"),
                () -> assertEquals(1, d.kills, "D (last listed) gets the kill"),
                () -> assertEquals(2 * CollisionsTest.rate(0, 0) * 0.5 / 4, b.score, EPS, "B shoved twice of four"));
        }

        @Test
        @DisplayName("shoves older than 20 frames and shovers who left do not count")
        void shoveWindow() {
            setup("");
            Player b = ready(s, "B", 0, 300, 300);
            Player c = ready(s, "C", 0, 300, 300);
            w.frames = 100;
            a.shovePusher[0] = b.id;
            a.shoveTime[0] = 80;
            a.shovePusher[1] = c.id;
            a.shoveTime[1] = 79;
            a.shovePusher[2] = 77;
            a.shoveTime[2] = 99;
            long seq = w.events.seq();
            s.crashes.playerCrashed(a, ms(CRASH_WALL), 1);
            assertAll(
                () -> assertEquals(List.of("A crashed against a wall with help from B."), messages(w, seq),
                    "a shove exactly 20 frames old counts, 21 does not, departed ids are skipped"),
                () -> assertEquals(1, b.kills, "the only shover gets the kill"),
                () -> assertEquals(0.0, c.score, EPS, "the stale shover gets nothing"),
                () -> assertEquals(1, s.crashes.collectPushers(a), "one pusher counted"));
        }
    }
}
