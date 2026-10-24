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

import com.fulcrologic.jxpilot.common.Item;
import com.fulcrologic.jxpilot.common.math.Rng;
import com.fulcrologic.jxpilot.common.net.Msg;
import com.fulcrologic.jxpilot.server.TestWorlds;
import com.fulcrologic.jxpilot.common.sim.Has;
import com.fulcrologic.jxpilot.common.sim.Mods;
import com.fulcrologic.jxpilot.common.sim.Obj;
import com.fulcrologic.jxpilot.server.world.Objects;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.Pulses;
import com.fulcrologic.jxpilot.common.sim.Trig;
import com.fulcrologic.jxpilot.server.world.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.fulcrologic.jxpilot.server.sim.SimFixture.FRAME;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.gameEvents;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.messages;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.ready;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lasers: Fire_laser / Fire_general_laser (shot.c:1411-1466) and Laser_pulse_collision with its helpers (laser.c).
 * Maps are 20x20 open blocks (700x700 px) unless stated; one tick is one C frame.
 */
@DisplayName("Lasers (shot.c Fire_laser, laser.c Laser_pulse_collision)")
class LasersTest {
    static final double EPS = 1e-9;
    static final double FUEL0 = 1000 * 256;
    static final int LASER = Item.LASER.ordinal();
    static final int MIRROR = Item.MIRROR.ordinal();
    static final int ARMOR = Item.ARMOR.ordinal();
    static final int KILL = Msg.GameEvent.KILL;

    Simulation s;
    World w;
    Pulses pu;
    Player p;

    void setup(String extra, String bases) {
        s = SimFixture.sim(FRAME + extra, 20, 20, bases);
        w = s.world;
        pu = w.pulses;
        p = ready(s, "P", 0, 100, 600);
        p.dir = 0;
    }

    void setup(String extra) {
        setup(extra, "____");
    }

    /** A pulse of {@code owner} (null: a cannon) that will start at (x1, y1) after its next advance. */
    int pulseAt(Player owner, float x1, float y1, int dir) {
        int i = pu.add();
        pu.owner[i] = owner != null ? owner.id : Obj.NO_ID;
        pu.team[i] = owner != null ? owner.team : 2;
        pu.dir[i] = dir;
        pu.len[i] = Lasers.PULSE_LENGTH;
        pu.life[i] = 4;
        pu.mods[i] = 0;
        pu.refl[i] = false;
        pu.x[i] = x1 - Trig.tcos(dir) * Lasers.PULSE_SPEED;
        pu.y[i] = y1 - Trig.tsin(dir) * Lasers.PULSE_SPEED;
        if (owner != null) {
            owner.numPulses++;
        }
        return i;
    }

    @Nested
    @DisplayName("collide: asteroids (laser.c:375-415, 600-635)")
    class AsteroidHits {
        @Test
        @DisplayName("a pulse ends on the first asteroid it reaches, which loses ASTEROID_FUEL_HIT(ED_LASER_HIT, size) life")
        void hits() {
            setup("");
            w.modeParams.asteroidPoints = 2;
            w.modeParams.asteroidMaxScore = 100;
            int small = s.asteroids.make(270, 300, 1, 0, 0f); // radius (int)(0.8 * 16) = 12
            int big = s.asteroids.make(300, 500, 4, 0, 0f);
            Objects o = w.objects;
            o.vx[small] = o.vy[small] = o.vx[big] = o.vy[big] = 0;
            double score0 = p.score;
            int a = pulseAt(p, 200, 300, 0);
            int b = pulseAt(null, 200, 500, 0);
            int c = pulseAt(p, 200, 650, 0);
            s.lasers.collide();
            double score1 = p.score;
            assertAll(
                () -> assertEquals(0.0, o.life[small], "size 1: 1000 + (-25600 * 1000) / (256 * 25 * 1) clamps to 0"),
                () -> assertEquals(500.0, o.life[big], "size 4: 1000 - 4000 / 8 = 500 (hit by the cannon's pulse)"),
                () -> assertTrue(pu.len[a] > 0 && pu.len[a] < Lasers.PULSE_LENGTH, "P's pulse is cut at the asteroid"),
                () -> assertTrue(pu.len[b] > 0 && pu.len[b] < Lasers.PULSE_LENGTH, "so is the cannon's"),
                () -> assertEquals(Lasers.PULSE_LENGTH, pu.len[c], "a pulse far from every asteroid runs on"),
                () -> assertEquals(score0 + 2, score1, 1e-9,
                    "P's pulse brought an asteroid to 0: asteroidPoints (P's score is <= asteroidMaxScore)"));
        }

        @Test
        @DisplayName("no points above asteroidMaxScore or without asteroidPoints")
        void noPoints() {
            setup("");
            w.modeParams.asteroidPoints = 2;
            w.modeParams.asteroidMaxScore = 100;
            p.score = 101;
            s.asteroids.make(270, 300, 1, 0, 0f);
            pulseAt(p, 200, 300, 0);
            s.lasers.collide();
            double above = p.score;
            w.modeParams.asteroidPoints = 0;
            p.score = 0;
            s.asteroids.make(270, 450, 1, 0, 0f);
            pulseAt(p, 200, 450, 0);
            s.lasers.collide();
            assertAll(
                () -> assertEquals(101.0, above, 1e-9, "score 101 > asteroidMaxScore 100: no points"),
                () -> assertEquals(0.0, p.score, 1e-9, "asteroidPoints 0: no points"));
        }
    }

    @Nested
    @DisplayName("collide: walls and cannons (the scratch object through Move_object)")
    class Walls {
        /** 20x20 blocks with a wall column at block x 10 (px 350..384) above the bottom row, and 'r' cannon at (5, 10). */
        void wallSetup(String extra) {
            String[] rows = SimFixture.open(20, 20, "____");
            for (int r = 0; r < 19; r++) {
                char[] c = rows[r].toCharArray();
                c[10] = 'x';
                rows[r] = new String(c);
            }
            char[] c = rows[9].toCharArray();
            c[5] = 'r';
            rows[9] = new String(c);
            char[] row5 = rows[5].toCharArray();          // block y 14 (py 490..524), block x 13..18
            for (int x = 13; x <= 18; x++) {
                row5[x] = 'x';
            }
            rows[5] = new String(row5);
            s = TestWorlds.sim(FRAME + extra, rows);
            w = s.world;
            pu = w.pulses;
            p = ready(s, "P", 0, 100, 600);
        }

        @Test
        @DisplayName("a wall ends the pulse at the first blocked sample: len = len * i / max")
        void wallStop() {
            wallSetup("");
            // x1 = 300, samples x = 300 + i (i = 0, 5, ...); the sample at 350 stops at the wall face, the one at
            // 355 crashes (Move_object): len = 85 * 55 / 85
            int i = pulseAt(p, 300, 300, 0);
            s.lasers.collide();
            int len = pu.len[i];
            s.lasers.collide();
            assertAll(
                () -> assertEquals(55, len, "cut to 55 px"),
                () -> assertEquals(0, pu.count(), "the cut pulse dies the next frame"));
        }

        @Test
        @DisplayName("a diagonal pulse: the cut is len * i / max with max the larger axis delta")
        void wallStopDiagonal() {
            wallSetup("");
            // dir 16: dx = dy = 85 * tcos(16) = 60.1, max = 60; samples x = 300 + i * 60.1 / 60; i = 50 gives
            // x 350.08, inside block 10
            int i = pulseAt(p, 300, 300, 16);
            s.lasers.collide();
            assertEquals(85 * 50 / 60, pu.len[i], "crashing at i = 50 cuts to 85 * 50 / 60 = 70");
        }

        @Test
        @DisplayName("a pulse up into a wall row is cut the same way")
        void wallStopUp() {
            wallSetup("");
            // dir 32 from y1 = 420: the sample at 490 stops at the face, 495 crashes: 85 * 75 / 85
            int i = pulseAt(p, 500, 420, 32);
            s.lasers.collide();
            assertEquals(75, pu.len[i], "cut to 75 px");
        }

        @Test
        @DisplayName("the scratch object starts at the pulse start (not where the last pulse left it)")
        void scratchStart() {
            wallSetup("");
            // right of the wall column: the path from the origin to (400, 300) would cross the wall
            int i = pulseAt(p, 400, 300, 0);
            s.lasers.collide();
            assertEquals(85, pu.len[i], "full length past the wall");
        }

        @Test
        @DisplayName("a pulse whose last sample is just short of the wall keeps its length")
        void wallMiss() {
            wallSetup("");
            // x1 = 264: samples up to 349 never enter block x 10
            int i = pulseAt(p, 264, 300, 0);
            s.lasers.collide();
            assertEquals(85, pu.len[i], "full length when no sample is in the wall");
        }

        @Test
        @DisplayName("a player's pulse destroys a cannon in its path; a cannon's pulse passes cannons (no team play)")
        void cannons() {
            wallSetup("");
            // the 'r' cannon in block (5, 10) (px 175..209, y 350..384) sits at the block bottom
            int i = pulseAt(p, 150, 355, 0);
            s.lasers.collide();
            int dead = w.cannonDeadTime[0];
            int len = pu.len[i];
            wallSetup("");
            int j = pulseAt(null, 150, 355, 0);
            s.lasers.collide();
            assertAll(
                () -> assertTrue(dead > 0, "the player's pulse kills the cannon"),
                () -> assertTrue(len < 85, "and stops there"),
                () -> assertEquals(0, w.cannonDeadTime[0], "the cannon pulse (FROMCANNON) leaves it alive"),
                () -> assertEquals(85, pu.len[j], "and goes on at full length"));
        }
    }

    @Nested
    @DisplayName("collide: victims (Laser_pulse_find_victims, Laser_pulse_check_player_hits)")
    class Victims {
        Player v;

        /** P (owner) far away; victim V at (vx, vy); a pulse of P along +x from (300, 300). */
        int shoot(String extra, double vx, double vy) {
            setup(extra);
            v = ready(s, "V", 0, vx, vy);
            return pulseAt(p, 300, 300, 0);
        }

        boolean roasted() {
            return (v.status & Obj.KILLED) != 0;
        }

        @Test
        @DisplayName("the first sample within SHIP_SZ hits; the pulse is cut to that sample")
        void hit() {
            int i = shoot("", 360, 300);
            s.lasers.collide();
            // samples x = 300 + k*5: 345 is 15 <= 16 from the victim (k = 45): len = 85 * 45 / 85
            assertAll(
                () -> assertTrue(roasted(), "the victim is roasted"),
                () -> assertEquals(45, pu.len[i], "the pulse is cut at the hitting sample"));
        }

        @Test
        @DisplayName("a hit on the last sample (i == max) does not cut the pulse")
        void hitAtEnd() {
            int i = shoot("", 400, 300);
            s.lasers.collide();
            assertAll(
                () -> assertTrue(roasted(), "the victim 15 px past the end is hit by the last sample"),
                () -> assertEquals(85, pu.len[i], "the length stays"));
        }

        @Test
        @DisplayName("victims are those within len/2 + SHIP_SZ = 42 + 16 of the midpoint (integer len/2)")
        void midpointRange() {
            // midpoint x 342.5; at 400 the distance is 57.5 <= 58, at 401 it is 58.5 > 58 (the last sample is 16 away)
            shoot("", 400, 300);
            s.lasers.collide();
            boolean in = roasted();
            shoot("", 401, 300);
            s.lasers.collide();
            assertAll(
                () -> assertTrue(in, "57.5 from the midpoint: a victim, hit"),
                () -> assertFalse(roasted(), "58.5 from the midpoint: not a victim, although the end is 16 away"));
        }

        @Test
        @DisplayName("the midpoint range is inclusive: exactly 58 px is a victim")
        void midpointExact() {
            setup("");
            v = ready(s, "V", 0, 400, 300);
            pulseAt(p, 299.5f, 300, 0);                    // midpoint 342, last sample 384.5 (15.5 away)
            s.lasers.collide();
            assertTrue(roasted(), "58 px from the midpoint: a victim, hit");
        }

        @Test
        @DisplayName("upward pulses: the midpoint is along y; the cut is at the hitting sample")
        void vertical() {
            int i = shoot("", 300, 300);
            v.initPixels(300, 360);
            pu.dir[i] = 32;
            pu.x[i] = 300;
            pu.y[i] = 300 - 90;
            s.lasers.collide();
            boolean hit = roasted();
            int len = pu.len[i];
            setup("");
            v = ready(s, "V", 0, 300, 400);
            pulseAt(p, 300, 300, 32);
            s.lasers.collide();
            boolean in = roasted();
            setup("");
            v = ready(s, "V", 0, 300, 401);
            pulseAt(p, 300, 300, 32);
            s.lasers.collide();
            assertAll(
                () -> assertTrue(hit, "the victim above is hit"),
                () -> assertEquals(45, len, "at the sample y 345"),
                () -> assertTrue(in, "57.5 above the midpoint: hit"),
                () -> assertFalse(roasted(), "58.5 above the midpoint: not a victim"));
        }

        @Test
        @DisplayName("a pulse cut at an edge samples only the cut part, toward the edge")
        void clippedVictims() {
            setup("");
            Player a = ready(s, "A", 0, 10, 300);
            int left = pulseAt(p, 60, 300, 64);           // cut to 60: x 60 -> 0; A 15 px from x 25 (i = 35)
            s.lasers.collide();
            int leftLen = pu.len[left];
            boolean aHit = (a.status & Obj.KILLED) != 0;
            setup("");
            Player b = ready(s, "B", 0, 690, 300);
            int right = pulseAt(p, 630, 300, 0);          // cut to 69: x 630 -> 699; B 15 px from x 675 (i = 45)
            s.lasers.collide();
            assertAll(
                () -> assertTrue(aHit, "left: the victim near the edge is hit"),
                () -> assertEquals(60 * 35 / 60, leftLen, "left: cut at i = 35 of max 60"),
                () -> assertTrue((b.status & Obj.KILLED) != 0, "right: the victim near the edge is hit"),
                () -> assertEquals(69 * 45 / 69, pu.len[right], "right: cut at i = 45 of max 69"));
        }

        @Test
        @DisplayName("a diagonal pulse cut at the top or bottom edge keeps its direction for the samples")
        void clippedDiagonal() {
            setup("");
            Player a = ready(s, "A", 0, 320, 10);
            int down = pulseAt(p, 300, 30, 112);          // cut to 42: max 29; (310.2, 19.8) is 13.9 from A (i = 10)
            s.lasers.collide();
            int downLen = pu.len[down];
            boolean aHit = (a.status & Obj.KILLED) != 0;
            setup("");
            Player b = ready(s, "B", 0, 320, 690);
            int up = pulseAt(p, 300, 670, 16);            // cut to (int)(85 * 29 / 60.1) = 41: max 29
            s.lasers.collide();
            assertAll(
                () -> assertTrue(aHit, "bottom: the victim along the cut pulse is hit"),
                () -> assertEquals(42 * 10 / 29, downLen, "bottom: cut at i = 10 of max 29"),
                () -> assertTrue((b.status & Obj.KILLED) != 0, "top: the victim along the cut pulse is hit"),
                () -> assertEquals(41 * 10 / 29, pu.len[up], "top: cut at i = 10 of max 29"));
        }

        @Test
        @DisplayName("with edgeWrap a pulse hits across each edge at the first wrapped sample")
        void wrapHits() {
            int[][] cases = {
                {650, 300, 0, 13, 300},      // x 700 -> 0 at i = 50; 13 px from the victim
                {50, 300, 64, 682, 300},     // x -5 -> 695 at i = 55
                {300, 650, 32, 300, 13},     // y 700 -> 0 at i = 50
                {300, 50, 96, 300, 682}};    // y -5 -> 695 at i = 55
            List<Integer> lens = new java.util.ArrayList<>();
            List<Boolean> hits = new java.util.ArrayList<>();
            for (int[] c : cases) {
                setup("edgeWrap: yes\n");
                v = ready(s, "V", 0, c[3], c[4]);
                int i = pulseAt(p, c[0], c[1], c[2]);
                s.lasers.collide();
                lens.add(pu.len[i]);
                hits.add(roasted());
            }
            assertAll(
                () -> assertEquals(List.of(true, true, true, true), hits, "every victim across an edge is hit"),
                () -> assertEquals(List.of(50, 55, 50, 55), lens, "at the first wrapped sample"));
        }

        @Test
        @DisplayName("a cannon pulse's victims are only the players in the game, last first")
        void cannonVictims() {
            setup("");
            p.initPixels(380, 300);
            Player a = ready(s, "A", 0, 380, 300);
            Player g = ready(s, "G", 0, 380, 300);
            w.removePlayer(g);                              // left the game: its stale order entry must not count
            pulseAt(null, 300, 300, 0);
            s.lasers.collide();
            assertAll(
                () -> assertFalse((p.status & Obj.KILLED) != 0, "P (first in order) is not hit"),
                () -> assertTrue((a.status & Obj.KILLED) != 0, "A (last in order) is hit"),
                () -> assertFalse((g.status & Obj.KILLED) != 0, "the departed G is not hit"));
        }

        @Test
        @DisplayName("SHIP_SZ is inclusive: 16 px from a sample hits, 17 does not")
        void shipSize() {
            shoot("", 360, 316);
            s.lasers.collide();
            boolean at16 = roasted();
            shoot("", 360, 317);
            s.lasers.collide();
            assertAll(
                () -> assertTrue(at16, "16 px from the sample at 360: hit"),
                () -> assertFalse(roasted(), "17 px from every sample: missed"),
                () -> assertEquals(0, v.forceVisible, "a miss does not make the victim visible"));
        }

        @Test
        @DisplayName("only the first victim along the pulse is hit; it is checked last-first among those in reach")
        void firstOnly() {
            setup("");
            Player a = ready(s, "A", 0, 360, 300);
            Player b = ready(s, "B", 0, 380, 300);
            int i = pulseAt(p, 300, 300, 0);
            s.lasers.collide();
            assertAll(
                () -> assertTrue((a.status & Obj.KILLED) != 0, "the nearer victim is hit"),
                () -> assertFalse((b.status & Obj.KILLED) != 0, "the one behind it is not"),
                () -> assertEquals(45, pu.len[i], "the pulse ends at the first"));
        }

        @Test
        @DisplayName("two victims in reach of the same sample: the later in player order is hit")
        void lastFirst() {
            setup("");
            Player a = ready(s, "A", 0, 380, 300);
            Player b = ready(s, "B", 0, 380, 300);
            pulseAt(p, 300, 300, 0);
            s.lasers.collide();
            assertAll(
                () -> assertFalse((a.status & Obj.KILLED) != 0, "the earlier player is not hit"),
                () -> assertTrue((b.status & Obj.KILLED) != 0, "the later player is hit"));
        }

        @Test
        @DisplayName("a victim moving away is dropped by copying the last victim over it (changes the check order)")
        void dropReorders() {
            setup("");
            // A (295, 330) gets farther from the second sample on and is dropped; C then takes A's slot,
            // so B is checked before C when both are 15 px from the sample at 365
            Player a = ready(s, "A", 0, 295, 330);
            Player b = ready(s, "B", 0, 380, 300);
            Player c = ready(s, "C", 0, 380, 300);
            pulseAt(p, 300, 300, 0);
            s.lasers.collide();
            assertAll(
                () -> assertFalse((a.status & Obj.KILLED) != 0, "A is never in reach"),
                () -> assertTrue((b.status & Obj.KILLED) != 0, "B (now after C) is hit"),
                () -> assertFalse((c.status & Obj.KILLED) != 0, "C is not"));
        }

        @Test
        @DisplayName("during the round delay nobody is hit, but the pulse still moves")
        void roundDelay() {
            int i = shoot("", 360, 300);
            w.roundDelay = 5;
            s.lasers.collide();
            assertAll(
                () -> assertFalse(roasted(), "no hit"),
                () -> assertEquals(0, v.forceVisible, "the victim is not touched"),
                () -> assertEquals(85, pu.len[i], "the pulse is not cut"),
                () -> assertEquals(300f, pu.x[i], "the pulse advanced"));
        }

        @Test
        @DisplayName("only PLAYING players without GAME_OVER, KILLED or PAUSE are victims")
        void status() {
            int[] bad = {0, Obj.PLAYING | Obj.GAME_OVER, Obj.PLAYING | Obj.KILLED, Obj.PLAYING | Obj.PAUSE};
            boolean[] hit = new boolean[bad.length];
            for (int k = 0; k < bad.length; k++) {
                shoot("", 360, 300);
                v.status = bad[k];
                s.lasers.collide();
                hit[k] = v.forceVisible != 0;
            }
            shoot("", 360, 300);
            v.status = Obj.PLAYING;
            s.lasers.collide();
            assertAll(
                () -> assertFalse(hit[0], "not playing: skipped"),
                () -> assertFalse(hit[1], "game over: skipped"),
                () -> assertFalse(hit[2], "killed: skipped"),
                () -> assertFalse(hit[3], "paused: skipped"),
                () -> assertTrue(roasted(), "playing: hit"));
        }

        @Test
        @DisplayName("a phased player is no victim")
        void phased() {
            shoot("", 360, 300);
            v.used |= Has.PHASING_DEVICE;
            s.lasers.collide();
            assertAll(
                () -> assertFalse(roasted(), "phased: not hit"),
                () -> assertEquals(0, v.forceVisible, "and not seen"));
        }

        @Test
        @DisplayName("the owner is hit only by its reflected pulse, and never with selfImmunity")
        void owner() {
            setup("");
            p.initPixels(360, 300);
            pulseAt(p, 300, 300, 0);
            s.lasers.collide();
            boolean unreflected = (p.status & Obj.KILLED) != 0;
            setup("");
            p.initPixels(360, 300);
            int i = pulseAt(p, 300, 300, 0);
            pu.refl[i] = true;
            s.lasers.collide();
            boolean reflected = (p.status & Obj.KILLED) != 0;
            setup("selfImmunity: yes\n");
            p.initPixels(360, 300);
            int j = pulseAt(p, 300, 300, 0);
            pu.refl[j] = true;
            s.lasers.collide();
            assertAll(
                () -> assertFalse(unreflected, "an unreflected own pulse passes the owner"),
                () -> assertTrue(reflected, "a reflected own pulse roasts the owner"),
                () -> assertFalse((p.status & Obj.KILLED) != 0, "with selfImmunity even a reflected one passes"));
        }

        @Test
        @DisplayName("with selfImmunity the owner's tanks are no victims; others' tanks and no immunity are")
        void ownTank() {
            setup("selfImmunity: yes\n");
            Player t = ready(s, "T", 0, 360, 300);
            t.tank = true;
            t.lockPlId = p.id;
            pulseAt(p, 300, 300, 0);
            s.lasers.collide();
            boolean ownImmune = (t.status & Obj.KILLED) != 0;
            setup("selfImmunity: yes\n");
            Player q = ready(s, "Q", 0, 50, 50);
            Player t2 = ready(s, "T", 0, 360, 300);
            t2.tank = true;
            t2.lockPlId = q.id;
            pulseAt(p, 300, 300, 0);
            s.lasers.collide();
            boolean other = (t2.status & Obj.KILLED) != 0;
            setup("");
            Player t3 = ready(s, "T", 0, 360, 300);
            t3.tank = true;
            t3.lockPlId = p.id;
            pulseAt(p, 300, 300, 0);
            s.lasers.collide();
            boolean noImmunity = (t3.status & Obj.KILLED) != 0;
            setup("selfImmunity: yes\n");
            Player nt = ready(s, "N", 0, 360, 300);
            nt.lockPlId = p.id;
            pulseAt(p, 300, 300, 0);
            s.lasers.collide();
            assertAll(
                () -> assertFalse(ownImmune, "own tank with selfImmunity: not hit"),
                () -> assertTrue(other, "somebody else's tank: hit"),
                () -> assertTrue(noImmunity, "own tank without selfImmunity: hit"),
                () -> assertTrue((nt.status & Obj.KILLED) != 0, "a non-tank locked on the owner: hit"));
        }

        /** Team-play world with bases of teams 1 and 2; P on team 1. */
        void teams(String extra) {
            s = SimFixture.sim(FRAME + "teamPlay: yes\n" + extra, 20, 20, "1122");
            w = s.world;
            pu = w.pulses;
            p = ready(s, "P", 1, 100, 600);
        }

        @Test
        @DisplayName("team immunity spares team mates; other teams and no immunity are hit")
        void teamImmune() {
            teams("teamImmunity: yes\n");
            Player mate = ready(s, "M", 1, 360, 300);
            pulseAt(p, 300, 300, 0);
            s.lasers.collide();
            boolean mateHit = (mate.status & Obj.KILLED) != 0;
            teams("teamImmunity: yes\n");
            Player foe = ready(s, "F", 2, 360, 300);
            pulseAt(p, 300, 300, 0);
            s.lasers.collide();
            boolean foeHit = (foe.status & Obj.KILLED) != 0;
            teams("teamImmunity: no\n");
            Player mate2 = ready(s, "M", 1, 360, 300);
            pulseAt(p, 300, 300, 0);
            s.lasers.collide();
            assertAll(
                () -> assertFalse(mateHit, "an immune team mate is not hit"),
                () -> assertTrue(foeHit, "another team is hit"),
                () -> assertTrue((mate2.status & Obj.KILLED) != 0, "without team immunity a team mate is hit"));
        }

        @Test
        @DisplayName("a cannon's pulse spares the cannon's team with team immunity in team play only")
        void cannonTeam() {
            teams("teamImmunity: yes\n");
            Player mate = ready(s, "M", 1, 360, 300);
            int i = pulseAt(null, 300, 300, 0);
            pu.team[i] = 1;
            s.lasers.collide();
            boolean same = (mate.status & Obj.KILLED) != 0;
            teams("teamImmunity: yes\n");
            Player foe = ready(s, "F", 2, 360, 300);
            int j = pulseAt(null, 300, 300, 0);
            pu.team[j] = 1;
            s.lasers.collide();
            boolean other = (foe.status & Obj.KILLED) != 0;
            teams("teamImmunity: no\n");
            Player mate2 = ready(s, "M", 1, 360, 300);
            int k = pulseAt(null, 300, 300, 0);
            pu.team[k] = 1;
            s.lasers.collide();
            boolean noImm = (mate2.status & Obj.KILLED) != 0;
            setup("teamImmunity: yes\n");
            Player any = ready(s, "A", 0, 360, 300);
            int m = pulseAt(null, 300, 300, 0);
            pu.team[m] = any.team;
            s.lasers.collide();
            assertAll(
                () -> assertFalse(same, "the cannon's team is spared"),
                () -> assertTrue(other, "another team is hit"),
                () -> assertTrue(noImm, "without team immunity the cannon's team is hit"),
                () -> assertTrue((any.status & Obj.KILLED) != 0, "outside team play everyone is hit"));
        }
    }

    @Nested
    @DisplayName("collide: hit effects (Laser_pulse_hits_player)")
    class Hits {
        Player v;
        int i;
        long ev;

        /** P owns a pulse along +x from (300, 300) that hits V at (360, 300) at sample 45 (x 345). */
        void hit(String extra, boolean cannon) {
            setup(extra);
            v = ready(s, "V", 0, 360, 300);
            i = pulseAt(cannon ? null : p, 300, 300, 0);
            ev = w.events.seq();
        }

        void hit(String extra) {
            hit(extra, false);
        }

        @Test
        @DisplayName("an unshielded victim is roasted: fuel, KILLED, message, kill event, Rate * laserKillScoreMult")
        void roasted() {
            hit("laserKillScoreMult: 2\n");
            p.score = 100;
            s.lasers.collide();
            // Rate(100, 0) = 10 * 1024 / (100 + 1024); loser 0 is not above the winner (score.c Rate)
            float sc = 10 * 1024 / 1124.0f * 2;
            assertAll(
                () -> assertTrue((v.status & Obj.KILLED) != 0, "the victim is KILLED"),
                () -> assertEquals(FUEL0 - 25600, v.fuel, EPS, "it pays ED_LASER_HIT = 100 fuel"),
                () -> assertEquals(1, v.forceVisible, "it is seen (forceVisible + 1)"),
                () -> assertEquals(List.of("V got roasted alive by P's laser."), messages(w, ev), "the roast message"),
                () -> assertEquals(List.of(KILL + ":" + p.id + ":" + v.id), gameEvents(w, ev), "a KILL by P of V"),
                () -> assertEquals(100 + sc, p.score, 1e-4, "P gains Rate(100, 0) * laserKillScoreMult (float option)"),
                () -> assertEquals(-sc, v.score, 1e-4, "V loses the same"),
                () -> assertEquals(1, p.kills, "P counts a kill"),
                () -> assertEquals(0, v.items[ARMOR], "no armor is taken from a victim without armor"));
        }

        @Test
        @DisplayName("the own reflected pulse: 'How strange!', -Rate(0, score) * laserKillScoreMult * selfKillScoreMult")
        void self() {
            setup("selfKillScoreMult: 0.25\nlaserKillScoreMult: 2\n");
            p.initPixels(360, 300);
            int k = pulseAt(p, 300, 300, 0);
            pu.refl[k] = true;
            long e = w.events.seq();
            s.lasers.collide();
            assertAll(
                () -> assertTrue((p.status & Obj.KILLED) != 0, "the owner is KILLED"),
                () -> assertEquals(List.of("P got roasted alive by P's laser. How strange!"), messages(w, e),
                    "the self-roast message"),
                () -> assertEquals(List.of(KILL + ":" + Obj.NO_ID + ":" + p.id), gameEvents(w, e), "a KILL by nobody"),
                () -> assertEquals(-10 * 2 * 0.25, p.score, 1e-6, "loses Rate(0, 0) * 2 * 0.25 = 5"),
                () -> assertEquals(0, p.kills, "no kill is counted"));
        }

        @Test
        @DisplayName("a cannon's pulse: 'cannonfire', -Rate(CANNON_SCORE, score) / 4; no team score outside team play")
        void cannon() {
            hit("laserKillScoreMult: 2\n", true);
            s.lasers.collide();
            float sc = (20 - 10 * 1024 / (1436.0f + 1024)) / 4;
            assertAll(
                () -> assertTrue((v.status & Obj.KILLED) != 0, "the victim is KILLED"),
                () -> assertEquals(List.of("V got roasted alive by cannonfire."), messages(w, ev), "the cannonfire message"),
                () -> assertEquals(List.of(KILL + ":" + Obj.NO_ID + ":" + v.id), gameEvents(w, ev), "a KILL by nobody"),
                () -> assertEquals(-sc, v.score, 1e-9, "loses Rate(-1436, 0) / 4 (laserKillScoreMult does not apply)"),
                () -> assertEquals(0.0, w.teamScore[2], EPS, "no team score"),
                () -> assertEquals(0, p.kills, "no player counts a kill"));
        }

        @Test
        @DisplayName("in team play a cannon's roast scores for the cannon's team iff the victim is on another team")
        void cannonTeams() {
            s = SimFixture.sim(FRAME + "teamPlay: yes\n", 20, 20, "1122");
            w = s.world;
            pu = w.pulses;
            v = ready(s, "V", 2, 360, 300);
            int k = pulseAt(null, 300, 300, 0);
            pu.team[k] = 1;
            s.lasers.collide();
            double other = w.teamScore[1];
            s = SimFixture.sim(FRAME + "teamPlay: yes\nteamImmunity: no\n", 20, 20, "1122");
            w = s.world;
            pu = w.pulses;
            Player same = ready(s, "S", 1, 360, 300);
            int m = pulseAt(null, 300, 300, 0);
            pu.team[m] = 1;
            s.lasers.collide();
            float sc = (20 - 10 * 1024 / (1436.0f + 1024)) / 4;
            assertAll(
                () -> assertEquals(sc, other, 1e-9, "another team's victim: the cannon's team gains Rate / 4"),
                () -> assertTrue((same.status & Obj.KILLED) != 0, "the cannon's own team is roasted (no team immunity)"),
                () -> assertEquals(-sc, w.teamScore[1], 1e-9, "but its team only loses the victim's points"));
        }

        @Test
        @DisplayName("a shielded victim only pays fuel; a fully shielded (shield + emergency) one pays nothing")
        void shields() {
            hit("");
            v.used |= Has.SHIELD;
            s.lasers.collide();
            boolean killed = (v.status & Obj.KILLED) != 0;
            double fuel = v.fuel;
            List<String> msgs = messages(w, ev);
            hit("");
            v.used |= Has.SHIELD | Has.EMERGENCY_SHIELD;
            s.lasers.collide();
            assertAll(
                () -> assertFalse(killed, "shield: not killed"),
                () -> assertEquals(FUEL0 - 25600, fuel, EPS, "shield: pays ED_LASER_HIT"),
                () -> assertEquals(List.of(), msgs, "shield: no message"),
                () -> assertFalse((v.status & Obj.KILLED) != 0, "both shields: not killed"),
                () -> assertEquals(FUEL0, v.fuel, EPS, "both shields: no fuel"),
                () -> assertEquals(1, v.forceVisible, "both shields: still seen"));
        }

        @Test
        @DisplayName("an unshielded victim with armor loses one; with a shield the armor is kept")
        void armor() {
            hit("");
            v.have |= Has.ARMOR;
            v.items[ARMOR] = 2;
            s.lasers.collide();
            boolean killed = (v.status & Obj.KILLED) != 0;
            int armor = v.items[ARMOR];
            double fuel = v.fuel;
            hit("");
            v.have |= Has.ARMOR;
            v.items[ARMOR] = 2;
            v.used |= Has.SHIELD;
            s.lasers.collide();
            assertAll(
                () -> assertFalse(killed, "armor: not killed"),
                () -> assertEquals(1, armor, "armor: one armor is lost"),
                () -> assertEquals(FUEL0 - 25600, fuel, EPS, "armor: the fuel is paid"),
                () -> assertEquals(2, v.items[ARMOR], "shield and armor: the armor is kept"));
        }

        @Test
        @DisplayName("without player killing (KILLING_SHOTS = 0) a hit only makes the victim seen")
        void noKilling() {
            hit("");
            w.params.killingShots = 0;
            s.lasers.collide();
            assertAll(
                () -> assertFalse((v.status & Obj.KILLED) != 0, "not killed"),
                () -> assertEquals(FUEL0, v.fuel, EPS, "no fuel"),
                () -> assertEquals(1, v.forceVisible, "seen"),
                () -> assertEquals(45, pu.len[i], "the pulse still stops at the victim"));
        }

        @Test
        @DisplayName("a stun pulse paralyses a shielding victim for 5 frames and clears shield, laser and shot")
        void stunShield() {
            hit("");
            pu.mods[i] = Mods.withLaser(0, Mods.STUN);
            v.used |= Has.SHIELD | Has.LASER | Has.SHOT;
            s.lasers.collide();
            assertAll(
                () -> assertEquals(5, v.stunned, "stunned += 5"),
                () -> assertEquals(0, v.used & (Has.SHIELD | Has.LASER | Has.SHOT), "shield, laser and shot are off"),
                () -> assertEquals(Has.COMPASS, v.used & Has.COMPASS, "other used bits stay"),
                () -> assertEquals(List.of("V got paralysed by P's stun laser."), messages(w, ev), "the stun message"),
                () -> assertFalse((v.status & Obj.KILLED) != 0, "not killed"),
                () -> assertEquals(FUEL0, v.fuel, EPS, "no fuel"));
        }

        @Test
        @DisplayName("a stun pulse paralyses a thrusting victim and stops the thrust; an idle victim is untouched")
        void stunThrust() {
            hit("");
            pu.mods[i] = Mods.withLaser(0, Mods.STUN);
            v.status |= Obj.THRUSTING;
            s.lasers.collide();
            int thrust = v.status & Obj.THRUSTING;
            int stunned = v.stunned;
            hit("");
            pu.mods[i] = Mods.withLaser(0, Mods.STUN);
            s.lasers.collide();
            assertAll(
                () -> assertEquals(0, thrust, "thrusting is cleared"),
                () -> assertEquals(5, stunned, "the thruster is stunned"),
                () -> assertEquals(0, v.stunned, "an idle victim is not stunned"),
                () -> assertEquals(List.of(), messages(w, ev), "and gets no message"),
                () -> assertFalse((v.status & Obj.KILLED) != 0, "and is not killed"));
        }

        @Test
        @DisplayName("stun messages: a cannon's pulse, and the owner's own ('How strange!')")
        void stunMessages() {
            hit("", true);
            pu.mods[i] = Mods.withLaser(0, Mods.STUN);
            v.status |= Obj.THRUSTING;
            s.lasers.collide();
            List<String> cannon = messages(w, ev);
            setup("");
            p.initPixels(360, 300);
            p.status |= Obj.THRUSTING;
            int k = pulseAt(p, 300, 300, 0);
            pu.refl[k] = true;
            pu.mods[k] = Mods.withLaser(0, Mods.STUN);
            long e = w.events.seq();
            s.lasers.collide();
            assertAll(
                () -> assertEquals(List.of("V got paralysed by a stun laser."), cannon, "cannon stun message"),
                () -> assertEquals(List.of("P got paralysed by P's stun laser. How strange!"), messages(w, e),
                    "self stun message"));
        }

        @Test
        @DisplayName("laserIsStunGun makes every pulse stun iff laser modifiers are not allowed")
        void stunGun() {
            hit("");
            w.items.laserIsStunGun = true;
            w.items.allowLaserModifiers = false;
            v.status |= Obj.THRUSTING;
            s.lasers.collide();
            int stunned = v.stunned;
            boolean killed = (v.status & Obj.KILLED) != 0;
            hit("");
            w.items.laserIsStunGun = true;
            w.items.allowLaserModifiers = true;
            v.status |= Obj.THRUSTING;
            s.lasers.collide();
            assertAll(
                () -> assertEquals(5, stunned, "stun gun without laser modifiers: stunned"),
                () -> assertFalse(killed, "and not roasted"),
                () -> assertEquals(0, v.stunned, "stun gun with laser modifiers: a plain pulse does not stun"),
                () -> assertTrue((v.status & Obj.KILLED) != 0, "it roasts"));
        }

        @Test
        @DisplayName("a blinding pulse: damaged and forceVisible += FPS + 6, the shooter recorded as shover until then")
        void blind() {
            hit("");
            pu.mods[i] = Mods.withLaser(0, Mods.BLIND);
            long now = w.frames;
            s.lasers.collide();
            assertAll(
                () -> assertEquals(66, v.damaged, "damaged += 60 + 6"),
                () -> assertEquals(1 + 66, v.forceVisible, "forceVisible += 1 + 66"),
                () -> assertEquals(p.id, v.shovePusher[0], "P is the shover"),
                () -> assertEquals(now + 66, v.shoveTime[0], "until frame_loops + FPS + 6"),
                () -> assertFalse((v.status & Obj.KILLED) != 0, "not killed"),
                () -> assertEquals(FUEL0, v.fuel, EPS, "no fuel"));
        }

        @Test
        @DisplayName("a cannon's blinding pulse records no shover")
        void blindCannon() {
            hit("", true);
            pu.mods[i] = Mods.withLaser(0, Mods.BLIND);
            s.lasers.collide();
            assertAll(
                () -> assertEquals(66, v.damaged, "damaged"),
                () -> assertEquals(0, v.shoveNext, "no shove record"));
        }

        /** The first seed whose first rfrac() is in [lo, hi). */
        int seed(double lo, double hi) {
            for (int sd = 1; ; sd++) {
                float f = new Rng(sd).nextFloat();
                if (f >= lo && f < hi) {
                    return sd;
                }
            }
        }

        @Test
        @DisplayName("a mirror reflects iff rfrac() * 2 * mirrors >= 1: back along the line, life + mirrors, full length")
        void mirror() {
            hit("");
            v.have |= Has.MIRROR;
            v.items[MIRROR] = 1;
            w.rng.seed(seed(0.5, 1));
            s.lasers.collide();
            // hit at sample x 345: start = 345 - tcos(0) * 0.5 * 5; dir = findDir(360 - 342.5, 0) * 2 - 64 - 0 = -64 = 64
            assertAll(
                () -> assertEquals(342.5f, pu.x[i], "restarts half a sample back"),
                () -> assertEquals(300f, pu.y[i], "on the same line"),
                () -> assertEquals(64, pu.dir[i], "turned back (MOD2(-64, RES))"),
                () -> assertEquals(3 + 1, pu.life[i], "life 3 + 1 mirror"),
                () -> assertEquals(85, pu.len[i], "full length, not cut at the hit"),
                () -> assertTrue(pu.refl[i], "marked reflected"),
                () -> assertFalse((v.status & Obj.KILLED) != 0, "the victim is unhurt"),
                () -> assertEquals(FUEL0, v.fuel, EPS, "and pays nothing"),
                () -> assertEquals(1, v.forceVisible, "but is seen"));
        }

        @Test
        @DisplayName("a mirror hit on an upward pulse sends it back down (dir = 2 * 32 - 64 - 32)")
        void mirrorUp() {
            setup("");
            v = ready(s, "V", 0, 300, 360);
            i = pulseAt(p, 300, 300, 32);
            v.have |= Has.MIRROR;
            v.items[MIRROR] = 1;
            w.rng.seed(seed(0.5, 1));
            s.lasers.collide();
            assertAll(
                () -> assertEquals(342.5f, pu.y[i], "restarts half a sample below y 345"),
                () -> assertEquals(300f, pu.x[i], 1e-4f, "on the same x (tcos(32) is 0 up to table rounding)"),
                () -> assertEquals(96, pu.dir[i], "dir 2 * 32 - 64 - 32 = -32 = 96 (down)"));
        }

        @Test
        @DisplayName("an off-centre mirror hit turns the pulse about the line to the victim")
        void mirrorAngle() {
            setup("");
            v = ready(s, "V", 0, 360, 310);
            i = pulseAt(p, 300, 300, 0);
            v.have |= Has.MIRROR;
            v.items[MIRROR] = 1;
            w.rng.seed(seed(0.5, 1));
            s.lasers.collide();
            // first sample within 16: x 350 (14.1 px); start 347.5; dir = findDir(12.5, 10) * 2 - 64
            int fd = (int) com.fulcrologic.jxpilot.common.math.XMath.findDir(12.5f, 10f);
            assertAll(
                () -> assertEquals(347.5f, pu.x[i], "restarts half a sample before x 350"),
                () -> assertEquals(13, fd, "the line to the victim has direction 13 (38.7 deg)"),
                () -> assertEquals(90, pu.dir[i], "dir = 2 * 13 - 64 - 0 = -38, mod RES = 90"));
        }

        @Test
        @DisplayName("a mirror roll below 1 / (2 * mirrors) lets the pulse through; more mirrors reflect more")
        void mirrorMiss() {
            hit("");
            v.have |= Has.MIRROR;
            v.items[MIRROR] = 1;
            int sd = seed(0.25, 0.5);
            float f = new Rng(sd).nextFloat();
            w.rng.seed(sd);
            s.lasers.collide();
            boolean killed = (v.status & Obj.KILLED) != 0;
            int seed2 = sd;
            float f2 = new Rng(seed2).nextFloat();
            hit("");
            v.have |= Has.MIRROR;
            v.items[MIRROR] = 2;
            w.rng.seed(seed2);
            s.lasers.collide();
            assertAll(
                () -> assertTrue(f >= 0.25f && f < 0.5f, "the roll is in [0.25, 0.5)"),
                () -> assertTrue(killed, "one mirror, roll * 2 < 1: roasted"),
                () -> assertEquals(f, f2, "the same roll again"),
                () -> assertTrue(pu.refl[i], "two mirrors, roll * 4 >= 1: reflected"),
                () -> assertEquals(3 + 2, pu.life[i], "life + 2 mirrors"));
        }

        @Test
        @DisplayName("a mirror without HAS_MIRROR does nothing (no roll)")
        void mirrorNotHad() {
            hit("");
            v.items[MIRROR] = 3;
            int before = w.rng.state();
            s.lasers.collide();
            assertAll(
                () -> assertTrue((v.status & Obj.KILLED) != 0, "roasted"),
                () -> assertEquals(before, w.rng.state(), "no random number is drawn"));
        }
    }

    @Nested
    @DisplayName("pulseLife (PULSE_LIFE)")
    class PulseLife {
        @Test
        @DisplayName("4.5 + lasers / 4 with integer division (serverconst.h:238-239)")
        void formula() {
            assertAll(
                () -> assertEquals(4.5, Lasers.pulseLife(0), EPS, "no lasers: PULSE_MIN_LIFE"),
                () -> assertEquals(4.5, Lasers.pulseLife(3), EPS, "3 lasers: 3/4 = 0"),
                () -> assertEquals(5.5, Lasers.pulseLife(4), EPS, "4 lasers: one more frame"),
                () -> assertEquals(6.5, Lasers.pulseLife(9), EPS, "9 lasers: 9/4 = 2"),
                () -> assertEquals(85, Lasers.PULSE_LENGTH, "PULSE_LENGTH = PULSE_SPEED - PULSE_SAMPLE_DISTANCE"));
        }
    }

    @Nested
    @DisplayName("fire (Fire_laser)")
    class Fire {
        void arm(int lasers) {
            p.items[LASER] = lasers;
            p.used |= Has.LASER;
        }

        @Test
        @DisplayName("a pulse at the gun one frame ahead, moved one PULSE_SPEED back, costing ED_LASER")
        void fires() {
            setup("");
            arm(2);
            p.vx = 3;
            p.vy = -2;
            s.lasers.fire(p);
            // gun x = 100 + 15 + 3 = 118, y = 600 + 0 - 2 = 598; start = gun - 90 * (tcos 0, tsin 0) (shot.c:1460-1461)
            assertAll(
                () -> assertEquals(1, pu.count(), "one pulse is made"),
                () -> assertEquals(118f - 90f, pu.x[0], "x = px + gun x + vx - PULSE_SPEED"),
                () -> assertEquals(598f, pu.y[0], "y = py + gun y + vy"),
                () -> assertEquals(0, pu.dir[0], "along the heading"),
                () -> assertEquals(85, pu.len[0], "PULSE_LENGTH long"),
                () -> assertEquals(4, pu.life[0], "life (int)(4.5 + 2/4) = 4"),
                () -> assertEquals(p.id, pu.owner[0], "owned by the shooter"),
                () -> assertEquals(p.team, pu.team[0], "of the shooter's team"),
                () -> assertFalse(pu.refl[0], "not reflected"),
                () -> assertEquals(1, p.numPulses, "the shooter has one pulse in flight"),
                () -> assertEquals(FUEL0 - 2560, p.fuel, EPS, "costs ED_LASER = 10 fuel"));
        }

        @Test
        @DisplayName("the shooter's modifiers go with the pulse; the life grows with every 4 lasers")
        void modsAndLife() {
            setup("");
            arm(8);
            p.mods = Mods.withLaser(0, Mods.BLIND);
            s.lasers.fire(p);
            assertAll(
                () -> assertEquals(Mods.withLaser(0, Mods.BLIND), pu.mods[0], "the pulse carries the mods"),
                () -> assertEquals(6, pu.life[0], "life (int)(4.5 + 8/4) = 6"));
        }

        @Test
        @DisplayName("fires iff the lasers outnumber the pulses in flight")
        void pulseLimit() {
            setup("");
            arm(2);
            p.numPulses = 2;
            s.lasers.fire(p);
            int atLimit = pu.count();
            p.numPulses = 1;
            s.lasers.fire(p);
            assertAll(
                () -> assertEquals(0, atLimit, "2 lasers and 2 pulses in flight: no pulse"),
                () -> assertEquals(1, pu.count(), "2 lasers and 1 pulse in flight: a pulse"));
        }

        @Test
        @DisplayName("fires iff slower than PULSE_SPEED - PULSE_SAMPLE_DISTANCE (85)")
        void speedLimit() {
            setup("");
            arm(4);
            p.velocity = 85f;
            s.lasers.fire(p);
            int atLimit = pu.count();
            p.velocity = Math.nextDown(85f);
            s.lasers.fire(p);
            assertAll(
                () -> assertEquals(0, atLimit, "at speed 85: no pulse"),
                () -> assertEquals(1, pu.count(), "just below 85: a pulse"));
        }

        @Test
        @DisplayName("with 10 fuel or less the laser goes off; above it fires and stays on")
        void fuel() {
            setup("");
            arm(4);
            p.fuel = 2560;
            s.lasers.fire(p);
            int low = pu.count();
            int lowUsed = p.used & Has.LASER;
            double lowFuel = p.fuel;
            p.used |= Has.LASER;
            p.fuel = 2561;
            s.lasers.fire(p);
            assertAll(
                () -> assertEquals(0, low, "fuel 2560 (= -ED_LASER): no pulse"),
                () -> assertEquals(0, lowUsed, "fuel 2560: HAS_LASER is cleared from used"),
                () -> assertEquals(2560.0, lowFuel, EPS, "fuel 2560: nothing is paid"),
                () -> assertEquals(1, pu.count(), "fuel 2561: a pulse"),
                () -> assertEquals(Has.LASER, p.used & Has.LASER, "fuel 2561: the laser stays in use"),
                () -> assertEquals(1.0, p.fuel, EPS, "fuel 2561: pays 2560"));
        }

        /** Pulses made by firing from (px, py) heading {@code dir} on a fresh non-wrapping world. */
        int firedAt(int dir, double px, double py) {
            setup("");
            arm(4);
            p.initPixels(px, py);
            p.dir = dir;
            s.lasers.fire(p);
            return pu.count();
        }

        @Test
        @DisplayName("without wrapping the gun point must be inside [0, width) x [0, height)")
        void insideWorld() {
            // gun offsets: dir 0 (+15, 0), dir 32 (0, +15), dir 64 (-15, 0), dir 96 (0, -15)
            assertAll(
                () -> assertEquals(1, firedAt(0, 684, 300), "gun x 699: fires"),
                () -> assertEquals(0, firedAt(0, 685, 300), "gun x 700 (= width): no pulse"),
                () -> assertEquals(1, firedAt(64, 15, 300), "gun x 0: fires"),
                () -> assertEquals(0, firedAt(64, 14, 300), "gun x -1: no pulse"),
                () -> assertEquals(1, firedAt(32, 300, 684), "gun y 699: fires"),
                () -> assertEquals(0, firedAt(32, 300, 685), "gun y 700 (= height): no pulse"),
                () -> assertEquals(1, firedAt(96, 300, 15), "gun y 0: fires"),
                () -> assertEquals(0, firedAt(96, 300, 14), "gun y -1: no pulse"));
        }

        @Test
        @DisplayName("an out-of-world gun point costs nothing")
        void outsideCostsNothing() {
            firedAt(0, 685, 300);
            assertAll(
                () -> assertEquals(FUEL0, p.fuel, EPS, "no fuel is paid"),
                () -> assertEquals(0, p.numPulses, "no pulse is counted"));
        }

        @Test
        @DisplayName("with edgeWrap the gun point wraps into the world (WRAP_XPIXEL / WRAP_YPIXEL)")
        void wraps() {
            setup("edgeWrap: yes\n");
            arm(4);
            p.initPixels(690, 300);
            s.lasers.fire(p);
            float x = pu.x[0];
            setup("edgeWrap: yes\n");
            arm(4);
            p.initPixels(300, 690);
            p.dir = 32;
            s.lasers.fire(p);
            assertAll(
                () -> assertEquals(705f - 700f - 90f, x, "gun x 705 wraps to 5, start 5 - 90"),
                () -> assertEquals(705f - 700f - 90f, pu.y[0], "gun y 705 wraps to 5, start 5 - 90"));
        }
    }

    @Nested
    @DisplayName("fireGeneral (Fire_general_laser)")
    class FireGeneral {
        @Test
        @DisplayName("a cannon's pulse: no owner, the cannon's team, life (int)PULSE_LIFE(CANNON_PULSES = 1)")
        void cannon() {
            setup("");
            s.lasers.fireGeneral(null, 3, 100, 200, 32, 0);
            assertAll(
                () -> assertEquals(1, pu.count(), "one pulse"),
                () -> assertEquals(Obj.NO_ID, pu.owner[0], "no owner"),
                () -> assertEquals(3, pu.team[0], "the cannon's team"),
                () -> assertEquals(4, pu.life[0], "life (int)(4.5 + 1/4) = 4"),
                () -> assertEquals(100f - 90 * Trig.tcos(32), pu.x[0], "x one PULSE_SPEED back along dir 32"),
                () -> assertEquals(110f, pu.y[0], "y = 200 - 90 * tsin(32) = 110"),
                () -> assertEquals(FUEL0, p.fuel, EPS, "no player pays"));
        }

        @Test
        @DisplayName("with MAX_TOTAL_PULSES in flight the player still pays but gets no pulse")
        void full() {
            setup("");
            p.items[LASER] = 4;
            while (pu.add() >= 0) {
                // fill
            }
            s.lasers.fireGeneral(p, p.team, 100, 200, 0, 0);
            assertAll(
                () -> assertEquals(Pulses.CAPACITY, pu.count(), "the list stays full"),
                () -> assertEquals(FUEL0 - 2560, p.fuel, EPS, "ED_LASER is paid before the list check (shot.c:1440)"),
                () -> assertEquals(0, p.numPulses, "no pulse is counted"));
        }
    }

    @Nested
    @DisplayName("destroy / removeOwnedBy (Laser_pulse_destroy_one, Delete_player)")
    class Destroy {
        @Test
        @DisplayName("destroying a player's pulse drops its count; a cannon's or a departed owner's touches nobody")
        void destroy() {
            setup("");
            Player q = ready(s, "Q", 0, 300, 300);
            int a = pulseAt(p, 10, 10, 0);
            pulseAt(null, 20, 20, 0);
            pulseAt(q, 30, 30, 0);
            s.lasers.destroy(a);
            int afterPlayer = p.numPulses;
            int moved = pu.owner[0];
            s.lasers.destroy(1);
            int qAfterCannon = q.numPulses;
            pu.owner[0] = 200;
            s.lasers.destroy(0);
            assertAll(
                () -> assertEquals(0, afterPlayer, "the owner's pulse count drops to 0"),
                () -> assertEquals(q.id, moved, "the last pulse fills the slot"),
                () -> assertEquals(1, qAfterCannon, "a cannon pulse changes no player's count"),
                () -> assertEquals(0, pu.count(), "an unknown owner id is skipped and the pulse goes"));
        }

        @Test
        @DisplayName("removeOwnedBy removes every pulse of the player, keeps the others and zeroes the count")
        void removeOwned() {
            setup("");
            Player q = ready(s, "Q", 0, 300, 300);
            pulseAt(p, 10, 10, 0);
            pulseAt(p, 11, 10, 0);
            pulseAt(q, 30, 30, 0);
            pulseAt(p, 12, 10, 0);
            s.lasers.removeOwnedBy(p);
            assertAll(
                () -> assertEquals(1, pu.count(), "one pulse is left"),
                () -> assertEquals(q.id, pu.owner[0], "it is Q's"),
                () -> assertEquals(30f - 90f, pu.x[0], "with Q's position"),
                () -> assertEquals(0, p.numPulses, "P has no pulses"),
                () -> assertEquals(1, q.numPulses, "Q keeps its count"));
        }
    }

    @Nested
    @DisplayName("collide: life, motion and edges (Laser_pulse_collision)")
    class Motion {
        @Test
        @DisplayName("a pulse advances PULSE_SPEED along its direction and loses a frame of life")
        void advances() {
            setup("");
            int i = pulseAt(p, 300, 300, 32);
            s.lasers.collide();
            assertAll(
                () -> assertEquals(1, pu.count(), "the pulse lives on"),
                () -> assertEquals(300f - 90 * Trig.tcos(32) + 90 * Trig.tcos(32), pu.x[i], "x += tcos(dir) * 90"),
                () -> assertEquals(300f, pu.y[i], "y += tsin(dir) * 90 (210 + 90)"),
                () -> assertEquals(3, pu.life[i], "life 4 - 1"),
                () -> assertEquals(85, pu.len[i], "full length in open space"));
        }

        @Test
        @DisplayName("a pulse dies iff its life goes below 0 or it is shorter than PULSE_LENGTH")
        void ends() {
            setup("");
            int dying = pulseAt(p, 300, 100, 0);
            pu.life[dying] = 0;
            int last = pulseAt(p, 300, 200, 0);
            pu.life[last] = 1;
            int shortP = pulseAt(p, 300, 300, 0);
            pu.len[shortP] = 84;
            int full = pulseAt(p, 300, 400, 0);
            s.lasers.collide();
            assertAll(
                () -> assertEquals(2, pu.count(), "the life 0 and the 84-long pulses are gone"),
                // last first: slot 2 dies (slot 3 moves in), slot 0 dies (that pulse moves on into slot 0)
                () -> assertEquals(List.of(400f, 200f), List.of(pu.y[0], pu.y[1]),
                    "the full and the life 1 pulse are left, the last moved into each freed slot"),
                () -> assertEquals(0, pu.life[1], "the life 1 pulse has life 0 now"),
                () -> assertEquals(2, p.numPulses, "the owner's count follows"));
        }

        @Test
        @DisplayName("the scratch OBJ_PULSE object is left as a dead DEBRIS object")
        void scratch() {
            setup("");
            pulseAt(p, 300, 300, 0);
            s.lasers.collide();
            int h = w.objects.active[0];
            assertAll(
                () -> assertEquals(1, w.objects.count(), "one object was allocated"),
                () -> assertEquals(Obj.DEBRIS, w.objects.type[h], "it ends as OBJ_DEBRIS"),
                () -> assertEquals(0.0, w.objects.life[h], EPS, "with life 0"));
        }

        @Test
        @DisplayName("with no free object every pulse is destroyed")
        void noObject() {
            setup("");
            pulseAt(p, 300, 300, 0);
            pulseAt(null, 300, 400, 0);
            pulseAt(p, 300, 500, 0);
            while (w.objects.alloc() >= 0) {
                // fill the pool
            }
            s.lasers.collide();
            assertAll(
                () -> assertEquals(0, pu.count(), "no pulse is left"),
                () -> assertEquals(0, p.numPulses, "the owner's count is 0"));
        }

        @Test
        @DisplayName("with edgeWrap the start wraps across each edge and the length is kept")
        void wraps() {
            setup("edgeWrap: yes\n");
            int right = pulseAt(p, 740, 100, 0);
            int left = pulseAt(p, -40, 200, 64);
            int top = pulseAt(p, 300, 740, 32);
            int bottom = pulseAt(p, 400, -40, 96);
            s.lasers.collide();
            assertAll(
                () -> assertEquals(40f, pu.x[right], "x 740 wraps to 40"),
                () -> assertEquals(660f, pu.x[left], "x -40 wraps to 660"),
                () -> assertEquals(40f, pu.y[top], "y 740 wraps to 40"),
                () -> assertEquals(660f, pu.y[bottom], "y -40 wraps to 660"),
                () -> assertEquals(List.of(85, 85, 85, 85), List.of(pu.len[0], pu.len[1], pu.len[2], pu.len[3]),
                    "no pulse is cut"));
        }

        @Test
        @DisplayName("with edgeWrap x/y exactly at width/height wrap to 0; just inside stay")
        void wrapBoundary() {
            setup("edgeWrap: yes\n");
            int atW = pulseAt(p, 700, 100, 0);
            int inW = pulseAt(p, 699, 200, 0);
            int atH = pulseAt(p, 300, 700, 32);
            int inH = pulseAt(p, 400, 699, 32);
            int zeroX = pulseAt(p, 0, 300, 64);
            int zeroY = pulseAt(p, 500, 0, 96);
            s.lasers.collide();
            assertAll(
                () -> assertEquals(0f, pu.x[atW], "x 700 wraps to 0"),
                () -> assertEquals(699f, pu.x[inW], "x 699 stays"),
                () -> assertEquals(0f, pu.y[atH], "y 700 wraps to 0"),
                () -> assertEquals(699f, pu.y[inH], "y 699 stays"),
                () -> assertEquals(0f, pu.x[zeroX], "x 0 stays"),
                () -> assertEquals(0f, pu.y[zeroY], "y 0 stays"));
        }

        @Test
        @DisplayName("without edgeWrap a start outside the world gets length 0 (and dies the next frame)")
        void startOutside() {
            setup("");
            pulseAt(p, 740, 100, 0);
            pulseAt(p, -40, 200, 64);
            pulseAt(p, 300, 740, 32);
            pulseAt(p, 400, -40, 96);
            int inside = pulseAt(p, 300, 300, 0);
            s.lasers.collide();
            List<Integer> lens = List.of(pu.len[0], pu.len[1], pu.len[2], pu.len[3], pu.len[inside]);
            s.lasers.collide();
            assertAll(
                () -> assertEquals(List.of(0, 0, 0, 0, 85), lens, "x >= width, x < 0, y >= height, y < 0 give length 0"),
                () -> assertEquals(1, pu.count(), "the next frame only the inside pulse is left"));
        }

        @Test
        @DisplayName("without edgeWrap starts exactly at 0 or width-1 / height-1 are inside")
        void startBoundary() {
            setup("");
            int x0 = pulseAt(p, 0, 100, 32);
            int y0 = pulseAt(p, 100, 0, 0);
            int xMax = pulseAt(p, 699, 300, 32);
            int yMax = pulseAt(p, 300, 699, 0);
            int xW = pulseAt(p, 700, 400, 64);    // heading back inside: still outside
            int yH = pulseAt(p, 400, 700, 96);
            s.lasers.collide();
            assertAll(
                () -> assertEquals(85, pu.len[x0], "x 0 is inside"),
                () -> assertEquals(85, pu.len[y0], "y 0 is inside"),
                () -> assertEquals(0, pu.len[xW], "x 700 is outside"),
                () -> assertEquals(0, pu.len[yH], "y 700 is outside"),
                // dir 32 from x 699: inside; its end y 385 is fine
                () -> assertEquals(85, pu.len[xMax], "x 699 is inside"),
                // dir 0 from (300, 699): inside
                () -> assertEquals(85, pu.len[yMax], "y 699 is inside"));
        }

        @Test
        @DisplayName("without edgeWrap an end beyond an edge cuts the length there (laser.c:501-526)")
        void clipped() {
            setup("");
            // len = (int)(len * (edge - x1) / (x2 - x1)), edges 0 and width-1 / height-1
            int right = pulseAt(p, 630, 100, 0);   // x2 = 715 >= 700: (int)(85 * 69 / 85) = 69
            int rightAt = pulseAt(p, 615, 200, 0); // x2 = 700 >= 700: (int)(85 * 84 / 85) = 84
            int left = pulseAt(p, 60, 300, 64);    // x2 = -25 < 0: (int)(85 * -60 / -85) = 60
            int top = pulseAt(p, 300, 630, 32);    // y2 = 715 > 700: 69
            int topAt = pulseAt(p, 400, 615, 32);  // y2 = 700 is not > 700: kept (C uses > here)
            int bottom = pulseAt(p, 500, 60, 96);  // y2 = -25 < 0: 60
            int lzero = pulseAt(p, 699, 500, 0);   // x2 = 784: (int)(85 * 0 / 85) = 0
            s.lasers.collide();
            assertAll(
                () -> assertEquals(69, pu.len[right], "the right edge cuts at width - 1"),
                () -> assertEquals(84, pu.len[rightAt], "an end exactly at width is cut too"),
                () -> assertEquals(60, pu.len[left], "the left edge cuts at 0"),
                () -> assertEquals(69, pu.len[top], "the top edge cuts at height - 1"),
                () -> assertEquals(85, pu.len[topAt], "an end exactly at height is not cut"),
                () -> assertEquals(60, pu.len[bottom], "the bottom edge cuts at 0"),
                () -> assertEquals(0, pu.len[lzero], "a cut to nothing leaves length 0"));
        }

        @Test
        @DisplayName("a diagonal pulse cut by an edge: the formula on the cut coordinate")
        void diagonal() {
            setup("");
            // dir 16 (45 deg) from (650, 300): x2 = 650 + 85 c > 700
            int i = pulseAt(p, 650, 300, 16);
            float c = Trig.tcos(16);
            float x1 = (650f - c * 90) + c * 90;
            float x2 = x1 + c * 85;
            int expect = (int) (85 * (700 - 1 - x1) / (x2 - x1));
            // dir 112 (-45 deg) from (300, 30): y2 = 30 - 85 s < 0
            int j = pulseAt(p, 300, 30, 112);
            float sn = Trig.tsin(112);
            float y1 = (30f - sn * 90) + sn * 90;
            float y2 = y1 + sn * 85;
            int expectY = (int) (85 * (0 - y1) / (y2 - y1));
            s.lasers.collide();
            assertAll(
                () -> assertEquals(69, expect, "the derived x cut is (int)(85 * 49 / 60.1) = 69"),
                () -> assertEquals(expect, pu.len[i], "the x cut uses the float C formula"),
                () -> assertEquals(42, expectY, "the derived y cut is (int)(85 * 30 / 60.1) = 42"),
                () -> assertEquals(expectY, pu.len[j], "the y cut uses the float C formula"));
        }
    }
}
