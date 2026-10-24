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
import com.fulcrologic.jxpilot.server.TestWorlds;
import com.fulcrologic.jxpilot.server.world.CannonItems;
import com.fulcrologic.jxpilot.server.world.Ecms;
import com.fulcrologic.jxpilot.server.world.Has;
import com.fulcrologic.jxpilot.server.world.Mods;
import com.fulcrologic.jxpilot.server.world.Obj;
import com.fulcrologic.jxpilot.server.world.Objects;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.fulcrologic.jxpilot.server.sim.SimFixture.FRAME;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.ready;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ECM: Fire_ecm / Fire_general_ecm (item.c:954-1229) and the ECM / transporter aging of Update_objects
 * (update.c:588-609). Random draws are predicted with a copy of the world RNG seeded alike.
 */
@DisplayName("Ecm (item.c Fire_ecm, Fire_general_ecm; update.c)")
class EcmTest {
    static final double EPS = 1e-9;
    static final double FUEL0 = 1000 * 256;
    static final int ECM = Item.ECM.ordinal();
    static final int LASER = Item.LASER.ordinal();
    static final int SENSOR = Item.SENSOR.ordinal();
    static final int CLOAK = Item.CLOAK.ordinal();
    static final int SEED = 12345;

    Simulation s;
    World w;
    Ecms e;
    Objects o;
    Player p;

    /** 20x20 open blocks, P at (300, 300). */
    void setup(String extra, String bases) {
        s = SimFixture.sim(FRAME + extra, 20, 20, bases);
        w = s.world;
        e = w.ecms;
        o = w.objects;
        p = ready(s, "P", bases.charAt(0) == '_' ? 0 : bases.charAt(0) - '0', 300, 300);
    }

    void setup(String extra) {
        setup(extra, "____");
    }

    /** A pool object of {@code type} owned by {@code owner} (null: nobody) at (x, y). */
    int object(int type, Player owner, double x, double y) {
        int h = o.alloc();
        o.type[h] = type;
        o.owner[h] = owner != null ? owner.id : Obj.NO_ID;
        o.team[h] = owner != null ? owner.team : -1;
        o.initPixels(h, x, y);
        o.life[h] = 100;
        return h;
    }

    boolean confused(int h) {
        return (o.status[h] & Obj.CONFUSED) != 0;
    }

    @Nested
    @DisplayName("fire (Fire_ecm, item.c:1218-1229)")
    class Fire {
        @Test
        @DisplayName("a blast at the ship: radius (int)ECM_DISTANCE, owner, one ECM and ED_ECM paid, counted")
        void fires() {
            setup("");
            p.items[ECM] = 2;
            s.ecm.fire(p);
            assertAll(
                () -> assertEquals(1, e.count(), "one blast"),
                () -> assertEquals(300f, e.x[0], "at the ship x"),
                () -> assertEquals(300f, e.y[0], "at the ship y"),
                () -> assertEquals(400, e.size[0], "radius (int)(VISIBILITY_DISTANCE * 0.4) = 400"),
                () -> assertEquals(p.id, e.owner[0], "owned by the shooter"),
                () -> assertEquals(1, p.items[ECM], "one ECM is used"),
                () -> assertEquals(FUEL0 - 60 * 256, p.fuel, EPS, "ED_ECM = 60 fuel is paid"),
                () -> assertEquals(1, p.ecmCount, "the blast is counted"));
        }

        int blastsWith(java.util.function.Consumer<Player> prep) {
            setup("");
            p.items[ECM] = 2;
            prep.accept(p);
            s.ecm.fire(p);
            return e.count();
        }

        @Test
        @DisplayName("guards (item.c:1222-1226): ECMs, fuel above 60, fewer than 8 expanding blasts, not phased")
        void guards() {
            assertAll(
                () -> assertEquals(0, blastsWith(q -> q.items[ECM] = 0), "no ECM: nothing"),
                () -> assertEquals(1, blastsWith(q -> q.items[ECM] = 1), "one ECM: a blast"),
                () -> assertEquals(0, blastsWith(q -> q.fuel = 60 * 256), "fuel = -ED_ECM: nothing"),
                () -> assertEquals(1, blastsWith(q -> q.fuel = 60 * 256 + 1), "fuel above -ED_ECM: a blast"),
                () -> assertEquals(0, blastsWith(q -> q.ecmCount = 8), "MAX_PLAYER_ECMS expanding: nothing"),
                () -> assertEquals(1, blastsWith(q -> q.ecmCount = 7), "7 expanding: a blast"),
                () -> assertEquals(0, blastsWith(q -> q.used |= Has.PHASING_DEVICE), "phased: nothing"));
        }
    }

    @Nested
    @DisplayName("fireGeneral: the blast list (Fire_general_ecm)")
    class Blast {
        @Test
        @DisplayName("with MAX_TOTAL_ECMS blasts nothing happens at all")
        void full() {
            setup("");
            p.items[ECM] = 2;
            while (e.add() >= 0) {
                // fill
            }
            Player v = ready(s, "V", 0, 350, 300);
            s.ecm.fireGeneral(p, p.team, 300, 300);
            assertAll(
                () -> assertEquals(Ecms.CAPACITY, e.count(), "the list stays full"),
                () -> assertEquals(2, p.items[ECM], "no ECM is used"),
                () -> assertEquals(FUEL0, p.fuel, EPS, "no fuel is paid"),
                () -> assertEquals(0, p.ecmCount, "nothing is counted"),
                () -> assertEquals(0, v.damaged, "nobody is blinded"));
        }

        @Test
        @DisplayName("a cannon's blast has no owner and charges nobody")
        void cannon() {
            setup("");
            p.items[ECM] = 2;
            s.ecm.fireGeneral(null, 3, 100, 200);
            assertAll(
                () -> assertEquals(Obj.NO_ID, e.owner[0], "no owner"),
                () -> assertEquals(100f, e.x[0], "at the cannon x"),
                () -> assertEquals(200f, e.y[0], "at the cannon y"),
                () -> assertEquals(2, p.items[ECM], "no player's ECM is used"),
                () -> assertEquals(0, p.ecmCount, "no player's count changes"));
        }
    }

    @Nested
    @DisplayName("fireGeneral: smart missiles")
    class Missiles {
        Player q;

        void setup2() {
            setup("");
            q = ready(s, "Q", 0, 500, 500);
        }

        @Test
        @DisplayName("another's missile within range is confused for CONFUSED_TIME and re-targets the shooter's visible lock")
        void lock() {
            setup2();
            int h = object(Obj.SMART_SHOT, q, 300, 450);
            p.lockTagged = Player.LOCK_PLAYER;
            p.lockPlId = q.id;
            int before = w.rng.state();
            s.ecm.fireGeneral(p, p.team, 300, 300);
            assertAll(
                () -> assertTrue(confused(h), "CONFUSED"),
                () -> assertEquals(150f, o.ecmRange[h], "ecm_range is the distance"),
                () -> assertEquals(3, o.counter[h], "count = CONFUSED_TIME"),
                () -> assertEquals(q.id, o.newInfo[h], "new target: the shooter's lock"),
                () -> assertEquals(before, w.rng.state(), "no random number is drawn"));
        }

        @Test
        @DisplayName("without a usable lock the new target is Players[(int)(rfrac() * NumPlayers)]")
        void random() {
            List<Integer> got = new ArrayList<>();
            List<Integer> want = new ArrayList<>();
            List<java.util.function.Consumer<Player>> preps = List.of(
                pl -> pl.lockTagged = 0,
                pl -> { // out of sensor range with limited visibility
                    w.items.limitedVisibility = true;
                    pl.lockDistance = 600;
                    pl.sensorRange = 599;
                },
                pl -> pl.canSee[q.ind] = false,
                pl -> pl.lockPlId = 200);
            int sd = 1;
            while (new Rng(sd).nextFloat() >= 0.5f) {
                sd++;
            }
            int seed = sd;
            for (java.util.function.Consumer<Player> prep : preps) {
                setup2();
                int h = object(Obj.SMART_SHOT, q, 300, 450);
                p.lockTagged = Player.LOCK_PLAYER;
                p.lockPlId = q.id;
                prep.accept(p);
                w.rng.seed(seed);
                s.ecm.fireGeneral(p, p.team, 300, 300);
                got.add(o.newInfo[h]);
                want.add(w.playerAt((int) (new Rng(seed).nextFloat() * 2)).id);
            }
            assertAll(
                () -> assertEquals(List.of(p.id, p.id, p.id, p.id), want, "the roll below 0.5 picks Players[0] = P, not the lock Q"),
                () -> assertEquals(want, got, "unlocked, out of range, unseen, departed lock: the random player"));
        }

        @Test
        @DisplayName("the lock counts when within sensor range (<=) or without limited visibility")
        void lockRange() {
            setup2();
            int h = object(Obj.SMART_SHOT, q, 300, 450);
            p.lockTagged = Player.LOCK_PLAYER;
            p.lockPlId = 200;
            w.items.limitedVisibility = true;
            p.lockDistance = 600;
            p.sensorRange = 600;
            p.lockPlId = q.id;
            s.ecm.fireGeneral(p, p.team, 300, 300);
            int at = o.newInfo[h];
            setup2();
            int h2 = object(Obj.SMART_SHOT, q, 300, 450);
            p.lockTagged = Player.LOCK_PLAYER;
            p.lockPlId = q.id;
            w.items.limitedVisibility = false;
            p.lockDistance = 900;
            p.sensorRange = 100;
            s.ecm.fireGeneral(p, p.team, 300, 300);
            assertAll(
                () -> assertEquals(q.id, at, "lock distance = sensor range: the lock"),
                () -> assertEquals(q.id, o.newInfo[h2], "unlimited visibility: the lock at any distance"));
        }

        @Test
        @DisplayName("a cannon's blast always picks a random new target")
        void cannonRandom() {
            setup2();
            int h = object(Obj.SMART_SHOT, q, 300, 450);
            w.rng.seed(SEED);
            s.ecm.fireGeneral(null, 3, 300, 300);
            assertAll(
                () -> assertTrue(confused(h), "confused"),
                () -> assertEquals(w.playerAt((int) (new Rng(SEED).nextFloat() * 2)).id, o.newInfo[h], "random target"));
        }

        @Test
        @DisplayName("range: a missile exactly ECM_DISTANCE away is hit, one pixel farther is not; other types never")
        void range() {
            setup2();
            int at = object(Obj.SMART_SHOT, q, 300, 700 - 0);
            int far = object(Obj.SMART_SHOT, q, 300, 701);
            int shot = object(Obj.SHOT, q, 300, 310);
            s.ecm.fireGeneral(p, p.team, 300, 300);
            assertAll(
                () -> assertTrue(confused(at), "400 px: confused"),
                () -> assertFalse(confused(far), "401 px: untouched"),
                () -> assertFalse(confused(shot), "a plain shot: untouched"),
                () -> assertEquals(0, o.counter[shot], "its counter untouched"),
                () -> assertEquals(100.0, o.life[shot], EPS, "and its life (not treated as a mine)"));
        }

        @Test
        @DisplayName("own missiles: hit iff the target id equals the owner's PLAYER INDEX (C GetInd quirk, kept)")
        void ownQuirk() {
            setup("");
            Player me = ready(s, "M", 0, 300, 300);   // index 1, id 2
            int afterId1 = object(Obj.SMART_SHOT, me, 300, 350);
            o.info[afterId1] = 1;                      // after player id 1 (P), equals M's index 1
            int afterId2 = object(Obj.SMART_SHOT, me, 300, 360);
            o.info[afterId2] = me.id;                  // after itself (id 2)
            s.ecm.fireGeneral(me, me.team, 300, 300);
            assertAll(
                () -> assertEquals(1, s.ecm.ownerIndex(me.id), "M is at index 1"),
                () -> assertEquals(2, me.id, "with id 2"),
                () -> assertTrue(confused(afterId1), "own missile after id 1 (== index): confused"),
                () -> assertFalse(confused(afterId2), "own missile after id 2: ignored"),
                () -> assertEquals(-1, s.ecm.ownerIndex(200), "an unknown id has index -1"));
        }

        @Test
        @DisplayName("own mines are ignored iff owner-immune")
        void ownMines() {
            setup("");
            int immune = object(Obj.MINE, p, 300, 550);
            o.status[immune] |= Obj.OWNERIMMUNE;
            int plain = object(Obj.MINE, p, 300, 560);
            w.rng.seed(SEED);
            s.ecm.fireGeneral(p, p.team, 300, 300);
            assertAll(
                () -> assertEquals(0f, o.ecmRange[immune], "the owner-immune mine is ignored"),
                () -> assertEquals(260f, o.ecmRange[plain], "the other own mine is hit"));
        }

        /** Team play: P (index 0, id 1, team 1), M (index 1, id 2, team 1), F (index 2, id 3, team 2). */
        Player m;
        Player f;

        void teams(String extra) {
            setup("teamPlay: yes\n" + extra, "1122");
            m = ready(s, "M", 1, 500, 500);
            f = ready(s, "F", 2, 600, 600);
        }

        @Test
        @DisplayName("Team_immune gets the owner's INDEX as an id (C quirk, kept): F's missile is spared as if M's")
        void teamImmuneQuirk() {
            teams("teamImmunity: yes\n");
            int h = object(Obj.SMART_SHOT, f, 300, 350);
            s.ecm.fireGeneral(p, p.team, 300, 300);
            boolean spared = !confused(h);
            teams("teamImmunity: no\n");
            int h2 = object(Obj.SMART_SHOT, f, 300, 350);
            s.ecm.fireGeneral(p, p.team, 300, 300);
            assertAll(
                () -> assertEquals(2, s.ecm.ownerIndex(f.id), "F's index 2 is M's id"),
                () -> assertTrue(spared, "with team immunity F's missile is spared (Team_immune(1, 2))"),
                () -> assertTrue(confused(h2), "without team immunity it is confused"));
        }

        @Test
        @DisplayName("in team play another's object of the blast's team is spared; other teams' and ownerless ones are hit")
        void sameTeam() {
            teams("teamImmunity: no\n");
            int mate = object(Obj.SMART_SHOT, m, 300, 350);
            int foe = object(Obj.SMART_SHOT, f, 300, 360);
            int ownerless = object(Obj.SMART_SHOT, null, 300, 370);
            o.team[ownerless] = 1;
            s.ecm.fireGeneral(p, p.team, 300, 300);
            boolean cannonFoe;
            int mate2 = object(Obj.SMART_SHOT, m, 300, 380);
            s.ecm.fireGeneral(null, 1, 300, 300);
            cannonFoe = confused(mate2);
            assertAll(
                () -> assertFalse(confused(mate), "a team mate's missile: spared"),
                () -> assertTrue(confused(foe), "another team's missile: confused"),
                () -> assertTrue(confused(ownerless), "an ownerless missile of the team: confused"),
                () -> assertFalse(cannonFoe, "a cannon blast of team 1 spares team 1's missile"));
        }

        @Test
        @DisplayName("outside team play the team of another's object does not matter")
        void noTeamPlay() {
            setup2();
            int h = object(Obj.SMART_SHOT, q, 300, 350);
            o.team[h] = p.team;
            s.ecm.fireGeneral(p, p.team, 300, 300);
            assertTrue(confused(h), "confused although o.team == team");
        }
    }

    @Nested
    @DisplayName("fireGeneral: mines")
    class MinesEcm {
        Player q;

        void setup2(boolean reprogram) {
            setup("");
            q = ready(s, "Q", 0, 600, 600);
            w.items.ecmsReprogramMines = reprogram;
        }

        @Test
        @DisplayName("a mine explodes iff (int)(rfrac() * 100) < (int)(10 * (1 - r)) + 5; else confused 2..10 s")
        void explodeOrConfuse() {
            // distance 250, mini 0: perim = MINE_RANGE / 1 = 100, r = (250 - 100) / (400 - 100) = 0.5: explode below 10%
            float roll = new Rng(SEED).nextFloat();
            setup2(false);
            int h = object(Obj.MINE, q, 300, 550);
            w.rng.seed(SEED);
            s.ecm.fireGeneral(p, p.team, 300, 300);
            boolean exploded = o.life[h] == 0;
            assertAll(
                () -> assertEquals((int) (roll * 100) < 10, exploded, "explodes iff the roll is below 10"),
                () -> assertEquals(250f, o.ecmRange[h], "ecm_range is the distance"),
                () -> assertEquals(exploded ? 0 : (4 + 2) * 60, o.counter[h], "confused ((int)(8 * 0.5) + 2) * FPS"),
                () -> assertEquals(!exploded, confused(h), "CONFUSED iff not exploded"));
        }

        /** The first seed whose first roll is in [lo, hi) percent. */
        int seed(int lo, int hi) {
            for (int sd = 1; ; sd++) {
                int r = (int) (new Rng(sd).nextFloat() * 100.0f);
                if (r >= lo && r < hi) {
                    return sd;
                }
            }
        }

        @Test
        @DisplayName("the explosion threshold boundary: roll 9 explodes, roll 10 confuses (r = 0.5)")
        void threshold() {
            setup2(false);
            int h = object(Obj.MINE, q, 300, 550);
            w.rng.seed(seed(9, 10));
            s.ecm.fireGeneral(p, p.team, 300, 300);
            boolean at9 = o.life[h] == 0;
            setup2(false);
            int h2 = object(Obj.MINE, q, 300, 550);
            w.rng.seed(seed(10, 11));
            s.ecm.fireGeneral(p, p.team, 300, 300);
            assertAll(
                () -> assertTrue(at9, "roll 9 < 10: exploded"),
                () -> assertEquals(100.0, o.life[h2], EPS, "roll 10: not exploded"),
                () -> assertEquals(360, o.counter[h2], "confused 6 s"));
        }

        @Test
        @DisplayName("a mine inside its own perimeter always explodes without a roll; mini mines have a smaller one")
        void perimeter() {
            setup2(false);
            int inside = object(Obj.MINE, q, 300, 400);           // 100 = perim: r = 0
            int before = w.rng.state();
            s.ecm.fireGeneral(p, p.team, 300, 300);
            boolean noDraw = before == w.rng.state();
            setup2(false);
            int mini = object(Obj.MINE, q, 300, 375);              // 75 > perim 50 (mini 1): r = 25 / 350
            o.mods[mini] = Mods.withMini(0, 1);
            w.rng.seed(seed(14, 15));                              // threshold (int)(10 * (1 - 0.0714)) + 5 = 14
            s.ecm.fireGeneral(p, p.team, 300, 300);
            boolean mini14 = o.life[mini] == 0;
            int miniCounter = o.counter[mini];
            setup2(false);
            int mini2 = object(Obj.MINE, q, 300, 375);
            o.mods[mini2] = Mods.withMini(0, 1);
            w.rng.seed(seed(13, 14));
            s.ecm.fireGeneral(p, p.team, 300, 300);
            assertAll(
                () -> assertEquals(0.0, o.life[inside], EPS, "at the perimeter: exploded"),
                () -> assertTrue(noDraw, "without a roll"),
                () -> assertFalse(mini14, "mini mine, roll 14: confused (threshold 14)"),
                () -> assertTrue(o.life[mini2] == 0, "mini mine, roll 13: exploded"),
                // ((int)(8 * (1 - 25/350)) + 2) * 60 = (7 + 2) * 60
                () -> assertEquals(9 * 60, miniCounter, "a close mini mine is confused 9 s"));
        }

        @Test
        @DisplayName("ecmsReprogramMines: the closest unconfused mine goes to the shooter iff roll < 100 - (int)(50 r)")
        void reprogram() {
            // mines at 250 (r 0.5) and 340 (r 0.8): the closer one; threshold 100 - 25 = 75
            setup2(true);
            int near = object(Obj.MINE, q, 300, 550);
            int far = object(Obj.MINE, q, 300, 640);
            // rolls: near mine (>= 10: confused), far mine (>= 13: confused), reprogram roll < 75
            int sd = seedFor(new int[] {10, 13, 0}, new int[] {100, 100, 75});
            w.rng.seed(sd);
            s.ecm.fireGeneral(p, 3, 300, 300);
            int nearOwner = o.owner[near];
            int nearTeam = o.team[near];
            int farOwner = o.owner[far];
            setup2(true);
            int near2 = object(Obj.MINE, q, 300, 550);
            w.rng.seed(seedFor(new int[] {10, 75}, new int[] {100, 100}));
            s.ecm.fireGeneral(p, 3, 300, 300);
            assertAll(
                () -> assertEquals(p.id, nearOwner, "roll < 75: the closest mine is the shooter's"),
                () -> assertEquals(3, nearTeam, "and of the blast's team"),
                () -> assertEquals(q.id, farOwner, "the farther mine stays Q's"),
                () -> assertEquals(q.id, o.owner[near2], "roll >= 75: the owner stays"),
                () -> assertEquals(3, o.team[near2], "but the team changes anyway (C's unbraced if, kept)"));
        }

        @Test
        @DisplayName("the reprogram threshold is strict (roll 74 reprograms, 75 does not); equally close mines: the first")
        void reprogramEdges() {
            setup2(true);
            int a = object(Obj.MINE, q, 300, 550);
            int b = object(Obj.MINE, q, 550, 300);
            w.rng.seed(seedFor(new int[] {10, 10, 74}, new int[] {100, 100, 75}));
            s.ecm.fireGeneral(p, 3, 300, 300);
            int aOwner = o.owner[a];
            int bOwner = o.owner[b];
            setup2(true);
            int c = object(Obj.MINE, q, 300, 550);
            w.rng.seed(seedFor(new int[] {10, 75}, new int[] {100, 76}));
            s.ecm.fireGeneral(p, 3, 300, 300);
            assertAll(
                () -> assertEquals(p.id, aOwner, "roll 74: the first of two mines at 250 is reprogrammed"),
                () -> assertEquals(q.id, bOwner, "the second stays Q's"),
                () -> assertEquals(q.id, o.owner[c], "roll 75: not reprogrammed"));
        }

        @Test
        @DisplayName("an already confused mine is never the one reprogrammed; a cannon's blast makes it ownerless")
        void reprogramChoice() {
            setup2(true);
            int confusedNear = object(Obj.MINE, q, 300, 550);
            o.status[confusedNear] |= Obj.CONFUSED;
            int far = object(Obj.MINE, q, 300, 640);
            w.rng.seed(seedFor(new int[] {10, 13, 0}, new int[] {100, 100, 50}));
            s.ecm.fireGeneral(null, 3, 300, 300);
            assertAll(
                () -> assertEquals(q.id, o.owner[confusedNear], "the confused near mine keeps its owner"),
                () -> assertEquals(-1, o.team[confusedNear], "and its team"),
                () -> assertTrue(confused(confusedNear), "it is confused again"),
                () -> assertEquals(Obj.NO_ID, o.owner[far], "the farther one becomes the cannon's (NO_ID)"),
                () -> assertEquals(3, o.team[far], "of the cannon's team"));
        }

        @Test
        @DisplayName("without ecmsReprogramMines no mine changes hands and no roll is made for it")
        void noReprogram() {
            setup2(false);
            int h = object(Obj.MINE, q, 300, 550);
            int sd = seedFor(new int[] {10}, new int[] {100});
            w.rng.seed(sd);
            Rng r = new Rng(sd);
            r.nextFloat();
            s.ecm.fireGeneral(p, 3, 300, 300);
            assertAll(
                () -> assertEquals(q.id, o.owner[h], "the owner stays"),
                () -> assertEquals(q.team, o.team[h], "the team stays"),
                () -> assertEquals(r.state(), w.rng.state(), "only the explosion roll was drawn"));
        }

        /** A seed whose successive rolls (int)(rfrac() * 100) fall in [lo[k], hi[k]). */
        int seedFor(int[] lo, int[] hi) {
            outer:
            for (int sd = 1; ; sd++) {
                Rng r = new Rng(sd);
                for (int k = 0; k < lo.length; k++) {
                    int v = (int) (r.nextFloat() * 100.0f);
                    if (v < lo[k] || v >= hi[k]) {
                        continue outer;
                    }
                }
                return sd;
            }
        }
    }

    @Nested
    @DisplayName("fireGeneral: cannons")
    class Cannons {
        float cx;
        float cy;
        CannonItems ci;

        void cannonSetup(String extra, String bases) {
            String[] rows = SimFixture.open(40, 40, bases);
            char[] r = rows[20].toCharArray();
            r[20] = 'r';
            rows[20] = new String(r);
            s = TestWorlds.sim(FRAME + extra, rows);
            w = s.world;
            e = w.ecms;
            o = w.objects;
            ci = w.cannonItems;
            p = ready(s, "P", bases.charAt(0) == '_' ? 0 : bases.charAt(0) - '0', 100, 100);
            ci.set(0, LASER, 8);
            ci.set(0, Item.SENSOR.ordinal(), 0);
            ci.damaged[0] = 0;
            cx = (float) w.map.cannons().get(0).x();
            cy = (float) w.map.cannons().get(0).y();
        }

        @Test
        @DisplayName("a player's blast: lasers -= (int)(closeness * lasers + 0.5), damaged += (int)(24 * DISTANCE * 0.75^sensors)")
        void damage() {
            cannonSetup("", "__");
            ci.set(0, Item.SENSOR.ordinal(), 2);
            s.ecm.fireGeneral(p, p.team, cx, cy - 100);
            // closeness (400 - 100) / 400 = 0.75: 8 - (int)(6.5) = 2; damaged (int)(24 * 100 * 0.5625) = 1350
            assertAll(
                () -> assertEquals(2, ci.item(0, Item.LASER), "8 lasers lose 6"),
                () -> assertEquals(1350, ci.damaged[0], "damaged by the distance (C uses range, not closeness)"));
        }

        @Test
        @DisplayName("range: a cannon 400 px away is reached (closeness 0), 401 px is not")
        void range() {
            cannonSetup("", "__");
            s.ecm.fireGeneral(p, p.team, cx, cy - 400);
            int lasersAt = ci.item(0, Item.LASER);
            int damagedAt = ci.damaged[0];
            cannonSetup("", "__");
            s.ecm.fireGeneral(p, p.team, cx, cy - 401);
            assertAll(
                () -> assertEquals(8, lasersAt, "at 400 px: (int)(0 * 8 + 0.5) = 0 lasers lost"),
                () -> assertEquals(9600, damagedAt, "at 400 px: damaged 24 * 400"),
                () -> assertEquals(0, ci.damaged[0], "at 401 px: untouched"));
        }

        @Test
        @DisplayName("outside team play cannons are immune to cannon blasts")
        void cannonBlast() {
            cannonSetup("", "__");
            s.ecm.fireGeneral(null, 3, cx, cy - 100);
            assertAll(
                () -> assertEquals(8, ci.item(0, Item.LASER), "no lasers lost"),
                () -> assertEquals(0, ci.damaged[0], "not damaged"));
        }

        @Test
        @DisplayName("in team play a blast spares cannons of its team, hits others' (cannon blasts too)")
        void teams() {
            cannonSetup("teamPlay: yes\n", "12");
            int team = w.map.cannons().get(0).team();
            s.ecm.fireGeneral(null, team == 1 ? 2 : 1, cx, cy - 100);
            int otherTeam = ci.damaged[0];
            cannonSetup("teamPlay: yes\n", "12");
            s.ecm.fireGeneral(p, team, cx, cy - 100);
            assertAll(
                () -> assertEquals(2400, otherTeam, "another team's cannon blast damages it (24 * 100)"),
                () -> assertEquals(0, ci.damaged[0], "a blast of the cannon's team does not"),
                () -> assertEquals(8, ci.item(0, Item.LASER), "nor takes lasers"));
        }

        @Test
        @DisplayName("a cannon without lasers stays at 0")
        void noLasers() {
            cannonSetup("", "__");
            ci.set(0, LASER, 0);
            s.ecm.fireGeneral(p, p.team, cx, cy);
            assertAll(
                () -> assertEquals(0, ci.item(0, Item.LASER), "no lasers"),
                () -> assertEquals(0, ci.damaged[0], "damaged 24 * 0 at the cannon"));
        }
    }

    @Nested
    @DisplayName("fireGeneral: players")
    class Players {
        Player v;

        void victim(String extra, double x, double y) {
            setup(extra);
            v = ready(s, "V", 0, x, y);
        }

        @Test
        @DisplayName("a player 100 px away (closeness 0.75): seen 18 frames, lasers by closeness, blinded, shove recorded")
        void effects() {
            victim("", 300, 400);
            v.items[LASER] = 4;
            long now = w.frames;
            s.ecm.fireGeneral(p, p.team, 300, 300);
            // damage = 24 * 0.75 = 18; lasers 4 - (int)(3 + 0.5) = 1; blind (int)(18 * 0.75^0) = 18
            assertAll(
                () -> assertEquals(18, v.forceVisible, "forceVisible += (int)(24 * closeness)"),
                () -> assertEquals(1, v.items[LASER], "4 lasers lose (int)(0.75 * 4 + 0.5) = 3"),
                () -> assertEquals(18, v.damaged, "blinded 18 frames"),
                () -> assertEquals(p.id, v.shovePusher[0], "the shooter is recorded as shover"),
                () -> assertEquals(now + 18, v.shoveTime[0], "until frame_loops + duration"),
                () -> assertEquals(0, p.damaged, "the shooter is not affected"),
                () -> assertEquals(0, p.forceVisible, "nor seen"));
        }

        @Test
        @DisplayName("cloaks above one and sensors scale by 0.75 per item")
        void scaling() {
            victim("", 300, 400);
            v.items[CLOAK] = 3;
            v.items[SENSOR] = 2;
            s.ecm.fireGeneral(p, p.team, 300, 300);
            int f3 = v.forceVisible;
            int d2 = v.damaged;
            victim("", 300, 400);
            v.items[CLOAK] = 1;
            s.ecm.fireGeneral(p, p.team, 300, 300);
            assertAll(
                () -> assertEquals(10, f3, "3 cloaks: (int)(18 * 0.75^2) = 10"),
                () -> assertEquals(10, d2, "2 sensors: (int)(18 * 0.75^2) = 10"),
                () -> assertEquals(18, v.forceVisible, "1 cloak: the full 18"));
        }

        @Test
        @DisplayName("a cannon's blast blinds without a shove record")
        void cannonBlind() {
            victim("", 300, 400);
            s.ecm.fireGeneral(null, 3, 300, 300);
            assertAll(
                () -> assertEquals(18, v.damaged, "blinded"),
                () -> assertEquals(0, v.shoveNext, "no shove record"),
                () -> assertEquals(24, p.damaged, "P at the centre is blinded once, 24 frames (no shooter to skip)"));
        }

        @Test
        @DisplayName("a player who left the game is not affected; its id has no player index")
        void departed() {
            victim("", 300, 400);
            Player g = ready(s, "G", 0, 300, 350);
            w.removePlayer(g);
            s.ecm.fireGeneral(p, p.team, 300, 300);
            assertAll(
                () -> assertEquals(0, g.damaged, "the departed G is not blinded"),
                () -> assertEquals(18, v.damaged, "V is"),
                () -> assertEquals(-1, s.ecm.ownerIndex(g.id), "G's id has index -1"));
        }

        @Test
        @DisplayName("range: exactly 400 px is reached with closeness 0, 401 px is not")
        void range() {
            victim("", 300, 700);
            v.items[LASER] = 4;
            s.ecm.fireGeneral(p, p.team, 300, 300);
            int lasers = v.items[LASER];
            int shoves = v.shoveNext;
            victim("", 300, 701);
            s.ecm.fireGeneral(p, p.team, 300, 300);
            assertAll(
                () -> assertEquals(4, lasers, "at 400 px: no lasers lost"),
                () -> assertEquals(1, shoves, "at 400 px: reached (a 0-frame shove record)"),
                () -> assertEquals(0, v.shoveNext, "at 401 px: untouched"));
        }

        @Test
        @DisplayName("skipped: phased, not playing, paused, game over; team mates in team play")
        void skipped() {
            int[] statuses = {0, Obj.PLAYING | Obj.PAUSE, Obj.PLAYING | Obj.GAME_OVER};
            List<Integer> got = new ArrayList<>();
            for (int st : statuses) {
                victim("", 300, 400);
                v.status = st;
                s.ecm.fireGeneral(p, p.team, 300, 300);
                got.add(v.damaged);
            }
            victim("", 300, 400);
            v.used |= Has.PHASING_DEVICE;
            s.ecm.fireGeneral(p, p.team, 300, 300);
            got.add(v.damaged);
            victim("", 300, 400);
            v.status |= Obj.KILLED;
            s.ecm.fireGeneral(p, p.team, 300, 300);
            int killed = v.damaged;
            setup("teamPlay: yes\n", "1122");
            Player mate = ready(s, "M", 1, 300, 400);
            Player foe = ready(s, "F", 2, 300, 400);
            s.ecm.fireGeneral(p, p.team, 300, 300);
            assertAll(
                () -> assertEquals(List.of(0, 0, 0, 0), got, "not playing, paused, game over, phased: not blinded"),
                () -> assertEquals(18, killed, "a KILLED but playing player is blinded (C checks only PLAYING|GAME_OVER|PAUSE)"),
                () -> assertEquals(0, mate.damaged, "a team mate: spared"),
                () -> assertEquals(18, foe.damaged, "another team: blinded"));
        }

        @Test
        @DisplayName("the ball hook gets each reached ball carrier and the closeness")
        void balls() {
            victim("", 300, 400);
            List<String> calls = new ArrayList<>();
            s.ecm.ballDetacher = (pl, range) -> calls.add(pl.name + ":" + range);
            v.have |= Has.BALL;
            Player n = ready(s, "N", 0, 300, 450);
            s.ecm.fireGeneral(p, p.team, 300, 300);
            Ecm bare = new Ecm(w, s);
            v.have |= Has.BALL;
            bare.fireGeneral(p, p.team, 300, 300);
            assertAll(
                () -> assertEquals(List.of("V:0.75"), calls, "only V (has a ball) is passed, with closeness 0.75"),
                () -> assertEquals(15 + 15, n.forceVisible, "N without a ball (150 px: (int)(24 * 0.625)) is hit by both"),
                () -> assertEquals(36, v.forceVisible, "without a hook nothing breaks"));
        }

        void robot(String extra) {
            victim(extra, 300, 400);
            v.robot = true;
            w.items.ecmsReprogramRobots = true;
        }

        @Test
        @DisplayName("ecmsReprogramRobots: a robot is reprogrammed on the shooter's visible lock instead of blinded")
        void reprogram() {
            
            robot("");
            Player q = ready(s, "Q", 0, 600, 600);
            List<String> calls = new ArrayList<>();
            s.ecm.robotProgrammer = (rb, id) -> calls.add(rb.name.toString() + "->" + id);
            p.lockTagged = Player.LOCK_PLAYER;
            p.lockPlId = q.id;
            v.items[LASER] = 4;
            long seq0 = w.events.seq();
            s.ecm.fireGeneral(p, p.team, 300, 300);
            String seek = "";
            for (long e = seq0; e < w.events.seq(); e++) {
                int i = com.fulcrologic.jxpilot.server.world.Events.index(e);
                if (w.events.kind[i] == com.fulcrologic.jxpilot.server.world.Events.SEEK) {
                    seek += w.events.a[i] + " " + w.events.b[i] + " " + w.events.c[i];
                }
            }
            String seekSeen = seek;
            assertAll(
                () -> assertEquals(p.id + " " + v.id + " " + q.id, seekSeen,
                    "a SEEK event: programmer, robot, sought (C Send_seek to everyone, item.c:1207)"),
                () -> assertEquals(List.of("V->" + q.id), calls, "V is programmed to hunt Q"),
                () -> assertEquals(0, v.damaged, "and not blinded"),
                () -> assertEquals(18, v.forceVisible, "but seen"),
                () -> assertEquals(1, v.items[LASER], "and loses lasers"));
        }

        @Test
        @DisplayName("robots are blinded without ecmsReprogramRobots or by a cannon's blast")
        void robotBlinded() {
            robot("");
            w.items.ecmsReprogramRobots = false;
            List<String> calls = new ArrayList<>();
            s.ecm.robotProgrammer = (rb, id) -> calls.add(rb.name.toString());
            s.ecm.fireGeneral(p, p.team, 300, 300);
            int noOption = v.damaged;
            robot("");
            s.ecm.robotProgrammer = (rb, id) -> calls.add(rb.name.toString());
            s.ecm.fireGeneral(null, 3, 300, 300);
            assertAll(
                () -> assertEquals(18, noOption, "without the option: blinded"),
                () -> assertEquals(18, v.damaged, "by a cannon: blinded"),
                () -> assertEquals(List.of(), calls, "never programmed"));
        }

        @Test
        @DisplayName("no reprogramming without a lock, out of range (<, limited visibility), unseen, on itself, or no hook")
        void noReprogram() {
            List<java.util.function.Consumer<Player>> preps = List.of(
                pl -> pl.lockTagged = 0,
                pl -> {
                    w.items.limitedVisibility = true;
                    pl.lockDistance = 500;
                    pl.sensorRange = 500;
                },
                pl -> pl.canSee[pl.lockPlId == v.id ? 0 : w.byId(pl.lockPlId).slot] = false,
                pl -> pl.lockPlId = v.id,
                pl -> pl.lockPlId = 200);
            List<Integer> calls = new ArrayList<>();
            List<Integer> damaged = new ArrayList<>();
            for (java.util.function.Consumer<Player> prep : preps) {
                robot("");
                Player q = ready(s, "Q", 0, 600, 600);
                s.ecm.robotProgrammer = (rb, id) -> calls.add(id);
                p.lockTagged = Player.LOCK_PLAYER;
                p.lockPlId = q.id;
                prep.accept(p);
                s.ecm.fireGeneral(p, p.team, 300, 300);
                damaged.add(v.damaged);
            }
            robot("");
            Player q = ready(s, "Q", 0, 600, 600);
            s.ecm.robotProgrammer = (rb, id) -> calls.add(-id);
            p.lockTagged = Player.LOCK_PLAYER;
            p.lockPlId = q.id;
            w.items.limitedVisibility = true;
            p.lockDistance = 499;
            p.sensorRange = 500;
            s.ecm.fireGeneral(p, p.team, 300, 300);
            robot("");
            s.ecm.robotProgrammer = null;
            p.lockTagged = Player.LOCK_PLAYER;
            p.lockPlId = ready(s, "Q", 0, 600, 600).id;
            s.ecm.fireGeneral(p, p.team, 300, 300);
            assertAll(
                () -> assertEquals(List.of(-q.id), calls, "only the in-range (499 < 500) case programs"),
                () -> assertEquals(List.of(0, 0, 0, 0, 0), damaged, "robots are never blinded with the option"),
                () -> assertEquals(0, v.damaged, "no hook: nothing"));
        }
    }

    @Nested
    @DisplayName("update (update.c:588-609)")
    class Update {
        @Test
        @DisplayName("a blast halves each frame and ends at 0 (400 lasts 9 frames), then the owner's count drops")
        void ages() {
            setup("");
            p.items[ECM] = 1;
            s.ecm.fire(p);
            List<Integer> sizes = new ArrayList<>();
            for (int k = 0; k < 8; k++) {
                s.ecm.update();
                sizes.add(e.size[0]);
            }
            int countBefore = p.ecmCount;
            s.ecm.update();
            assertAll(
                () -> assertEquals(List.of(200, 100, 50, 25, 12, 6, 3, 1), sizes, "sizes halve"),
                () -> assertEquals(1, countBefore, "still counted at size 1"),
                () -> assertEquals(0, e.count(), "the 9th halving ends it"),
                () -> assertEquals(0, p.ecmCount, "the owner's count drops"));
        }

        @Test
        @DisplayName("ending blasts: the moved-in last one is checked too; a cannon's or departed owner's touch nobody")
        void endTogether() {
            setup("");
            p.ecmCount = 2;
            int[] owners = {p.id, Obj.NO_ID, 200, p.id};
            int[] sizes = {1, 1, 1, 4};
            for (int k = 0; k < 4; k++) {
                int i = e.add();
                e.owner[i] = owners[k];
                e.size[i] = sizes[k];
            }
            s.ecm.update();
            assertAll(
                () -> assertEquals(1, e.count(), "three blasts end, one is left"),
                () -> assertEquals(2, e.size[0], "the size-4 blast is left at 2"),
                () -> assertEquals(1, p.ecmCount, "P's count drops once"));
        }

        @Test
        @DisplayName("transporter beams count down and end at 0; the moved-in last one is checked too")
        void transporters() {
            setup("");
            int[] counts = {1, 1, 3};
            for (int c : counts) {
                e.transCount[e.addTransporter()] = c;
            }
            s.ecm.update();
            assertAll(
                () -> assertEquals(1, e.transporters(), "two beams end"),
                () -> assertEquals(2, e.transCount[0], "the 3-frame beam has 2 left"));
        }
    }
}
