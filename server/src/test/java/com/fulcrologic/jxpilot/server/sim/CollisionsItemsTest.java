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
import com.fulcrologic.jxpilot.server.world.Has;
import com.fulcrologic.jxpilot.server.world.Mods;
import com.fulcrologic.jxpilot.server.world.Obj;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.fulcrologic.jxpilot.server.sim.ItemFx.reseed;
import static com.fulcrologic.jxpilot.server.sim.ItemFx.seedWhere;
import static com.fulcrologic.jxpilot.server.sim.ItemFx.skip;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.FRAME;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.messages;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.obj;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.ready;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.sim;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The item, mine, missile and tank parts of collision.c PlayerCollision / PlayerObjectCollision. */
@DisplayName("Collisions: items, mines, missiles, tanks (collision.c)")
class CollisionsItemsTest {
    static final double EPS = 1e-9;
    static final int ECM = Item.ECM.ordinal();
    static final int MISSILE = Item.MISSILE.ordinal();

    static float rate(float winner, float loser) {
        float t = (10 * 1024) / (Math.abs(loser - winner) + 1024); // C DFLOAT: float arithmetic
        return loser > winner ? 20 - t : t;
    }

    static void check(Simulation s) {
        s.world.grid.rebuild(s.world.objects);
        s.collisions.check();
    }

    static boolean killed(Player p) {
        return (p.status & Obj.KILLED) != 0;
    }

    // ================================================================ items

    @Nested
    @DisplayName("items (OBJ_ITEM)")
    class Items {
        Simulation s = sim(FRAME, 20, 20, "_");
        World w = s.world;
        Player p = ready(s, "A", 0, 200, 200);

        int item(int kind, int count) {
            w.items.max[kind] = 9;
            return s.items.make(200, 200, 10, 0, kind, count, 0);
        }

        @Test
        @DisplayName("an item the ship flies into is picked up, and ship and item share momentum (Delta_mv)")
        void pickedUp() {
            int h = item(ECM, 2);
            float m = p.mass;
            check(s);
            assertAll(
                () -> assertEquals(2, p.items[ECM], "the pack is added"),
                () -> assertEquals(0.0, w.objects.life[h], "the item is used up"),
                () -> assertEquals((0 * m + 10f * 10f) / (m + 10f), p.vx, "inelastic: the item's mass 10 pushes"));
        }

        @Test
        @DisplayName("a shielded ship without shieldedItemPickup only bumps the item, which starts to fall")
        void shieldBump() {
            int h = item(ECM, 1);
            p.used |= Has.SHIELD;
            float m = p.mass;
            double life = w.objects.life[h];
            check(s);
            assertAll(
                () -> assertEquals(0, p.items[ECM], "not picked up"),
                () -> assertEquals(life, w.objects.life[h], "the item lives on"),
                () -> assertEquals(Obj.GRAVITY, w.objects.status[h] & Obj.GRAVITY, "the item now falls"),
                () -> assertEquals((0 * m + 10f * 10f) / (m + 10f), p.vx, "the bump shares momentum"));
        }

        @Test
        @DisplayName("with shieldedItemPickup a shielded ship picks items up")
        void shieldedPickup() {
            int h = item(ECM, 1);
            p.used |= Has.SHIELD;
            w.items.shieldedItemPickup = true;
            check(s);
            assertAll(
                () -> assertEquals(1, p.items[ECM], "picked up"),
                () -> assertEquals(0, w.objects.status[h] & Obj.GRAVITY, "no bump"));
        }

        @Test
        @DisplayName("an item refused for lack of room stays in the world and the ship moves on")
        void refused() {
            w.items.maxOffensiveItems = 0;
            int h = item(MISSILE, 1);
            double life = w.objects.life[h];
            float m = p.mass;
            check(s);
            assertAll(
                () -> assertEquals(0, p.items[MISSILE], "not picked up"),
                () -> assertEquals(life, w.objects.life[h], "the item keeps its life"),
                () -> assertEquals((0 * m + 10f * 10f) / (m + 10f), p.vx, "the ship bounced off it"));
        }

        @Test
        @DisplayName("items out of reach are not touched")
        void outOfReach() {
            w.items.max[ECM] = 9;
            int h = s.items.make(200 + 16 + 8, 200, 0, 0, ECM, 1, 0);
            check(s);
            assertAll(
                () -> assertEquals(0, p.items[ECM], "SHIP_SZ + ITEM_SIZE/2 = 24 apart is out of range"),
                () -> assertTrue(w.objects.life[h] > 0, "the item stays"));
        }
    }

    // ================================================================ mines

    @Nested
    @DisplayName("mines (OBJ_MINE)")
    class MinesHit {
        Simulation s = sim(FRAME, 20, 20, "_");
        World w = s.world;
        Player p = ready(s, "A", 0, 200, 200);

        int mine(int status) {
            int h = obj(w, Obj.MINE, Obj.NO_ID, 200, 200, 0, 0, 30, 100);
            w.objects.status[h] = status;
            w.objects.mineOwner[h] = Obj.NO_ID;
            w.objects.plRange[h] = 1;
            return h;
        }

        @Test
        @DisplayName("a mine hit is announced (Player_collides_with_mine) and ends the mine")
        void hit() {
            int h = mine(0);
            long seq = w.events.seq();
            check(s);
            assertAll(
                () -> assertEquals(List.of("A hit a mine."), messages(w, seq), "announced"),
                () -> assertEquals(0.0, w.objects.life[h], "the mine's life ends (it explodes on expiry)"));
        }

        @Test
        @DisplayName("a confused mine passes")
        void confused() {
            int h = mine(Obj.CONFUSED);
            long seq = w.events.seq();
            check(s);
            assertAll(
                () -> assertEquals(List.of(), messages(w, seq), "no hit"),
                () -> assertEquals(100.0, w.objects.life[h], "the mine stays"));
        }
    }

    // ================================================================ missiles

    @Nested
    @DisplayName("missiles (Player_collides_with_killing_shot)")
    class MissileHits {
        static final String MULTS = "torpedoKillScoreMult: 0.5\nsmartKillScoreMult: 0.25\nheatKillScoreMult: 0.125\n";
        Simulation s = sim(FRAME + MULTS, 20, 20, "__");
        World w = s.world;
        Player v = ready(s, "V", 0, 200, 200);
        Player k = ready(s, "K", 0, 500, 500);

        int missile(int type, int owner, int mods) {
            int h = obj(w, type, owner, 200, 200, 0, 0, 5, 100);
            w.objects.mods[h] = mods;
            w.objects.plRange[h] = 1;
            return h;
        }

        @Test
        @DisplayName("a shielded ship eats a missile: 120*256 / ((mini+1)*(power+1)) fuel and 2 frames of visibility")
        void eats() {
            v.used |= Has.SHIELD;
            v.score = 100;
            int mods = Mods.withPower(Mods.withMini(0, 1), 2);
            int h = missile(Obj.TORPEDO, k.id, mods);
            double fuel = v.fuel;
            int vis = v.forceVisible;
            long seq = w.events.seq();
            check(s);
            assertAll(
                () -> assertFalse(killed(v), "survives"),
                () -> assertEquals(List.of("V ate a modified mini torpedo from K."), messages(w, seq),
                    "announced with the shooter"),
                () -> assertEquals(fuel - 5120, v.fuel, EPS, "(long)(-30720 / (2 * 3)) = -5120"),
                () -> assertEquals(vis + 2, v.forceVisible, "visible 2 frames more"),
                () -> assertEquals(100.0, v.score, EPS, "no score change"),
                () -> assertEquals(0.0, w.objects.life[h], "the missile is used up"));
        }

        @Test
        @DisplayName("an unowned heat-seeker is eaten without a shooter; a fully shielded ship pays no fuel")
        void unownedFullyShielded() {
            v.used |= Has.BOTH_SHIELDS;
            missile(Obj.HEAT_SHOT, Obj.NO_ID, 0);
            double fuel = v.fuel;
            long seq = w.events.seq();
            check(s);
            assertAll(
                () -> assertEquals(List.of("V ate a heatseeker."), messages(w, seq), "no shooter named"),
                () -> assertEquals(fuel, v.fuel, EPS, "fully shielded: no drain"),
                () -> assertEquals(2, v.forceVisible, "still visible 2 frames"));
        }

        @Test
        @DisplayName("eating a missile with too little fuel drops the shield")
        void dropsShield() {
            v.used |= Has.SHIELD;
            v.fuel = 1000;
            v.tankFuel[0] = 1000;
            missile(Obj.SMART_SHOT, k.id, 0);
            check(s);
            assertAll(
                () -> assertEquals(0.0, v.fuel, EPS, "fuel runs out"),
                () -> assertEquals(0, v.used & Has.SHIELD, "the shield drops"),
                () -> assertFalse(killed(v), "but the ship lives this time"));
        }

        @Test
        @DisplayName("an unshielded ship is killed: torpedo/smart/heat kill score multipliers")
        void kills() {
            List<String> got = new java.util.ArrayList<>();
            List<String> want = new java.util.ArrayList<>();
            int[] types = {Obj.TORPEDO, Obj.SMART_SHOT, Obj.HEAT_SHOT};
            float[] mult = {0.5f, 0.25f, 0.125f};
            String[] names = {"a torpedo", "a smart missile", "a heatseeker"};
            for (int i = 0; i < 3; i++) {
                Simulation s2 = sim(FRAME + MULTS, 20, 20, "__");
                World w2 = s2.world;
                Player v2 = ready(s2, "V", 0, 200, 200);
                Player k2 = ready(s2, "K", 0, 500, 500);
                v2.score = 100;
                k2.score = 50;
                int h = obj(w2, types[i], k2.id, 200, 200, 0, 0, 5, 100);
                w2.objects.plRange[h] = 1;
                long seq = w2.events.seq();
                check(s2);
                float sc = rate(50, 100) * mult[i];
                got.add(killed(v2) + " " + messages(w2, seq) + " " + k2.kills + " " + k2.score + " " + v2.score);
                want.add(true + " " + List.of("V was killed by " + names[i] + " from K.") + " 1 " + (50 + sc) + " "
                    + (100 - sc));
            }
            assertEquals(want, got, "killed, named, a kill and Rate(K, V) * the missile's multiplier");
        }

        @Test
        @DisplayName("a nuclear torpedo is only eaten (as if shielded) when rfrac() >= 0.25, else it kills")
        void nuclearTorpedo() {
            int nuke = Mods.withNuclear(0, Mods.NUCLEAR);
            int eat = seedWhere(r -> r.nextFloat() >= 0.25f);
            int die = seedWhere(r -> r.nextFloat() < 0.25f);
            missile(Obj.TORPEDO, k.id, nuke);
            reseed(w, eat);
            double fuel = v.fuel;
            check(s);
            boolean survived = !killed(v);
            double drained = fuel - v.fuel;
            Simulation s2 = sim(FRAME + MULTS, 20, 20, "__");
            World w2 = s2.world;
            Player v2 = ready(s2, "V", 0, 200, 200);
            Player k2 = ready(s2, "K", 0, 500, 500);
            int h2 = obj(w2, Obj.TORPEDO, k2.id, 200, 200, 0, 0, 5, 100);
            w2.objects.mods[h2] = nuke;
            w2.objects.plRange[h2] = 1;
            Rng m = reseed(w2, die);
            check(s2);
            skip(m, 1);
            assertAll(
                () -> assertTrue(survived, "rfrac >= 0.25: eaten"),
                () -> assertEquals(30720.0, drained, EPS, "drained like a shielded hit"),
                () -> assertTrue(killed(v2), "rfrac < 0.25: killed"),
                () -> assertEquals(m.state(), w2.rng.state(), "one draw"));
        }

        @Test
        @DisplayName("shields or armor skip the nuclear draw")
        void noDrawWhenShielded() {
            v.used |= Has.SHIELD;
            missile(Obj.TORPEDO, k.id, Mods.withNuclear(0, Mods.NUCLEAR));
            Rng m = reseed(w, 3);
            check(s);
            assertEquals(m.state(), w.rng.state(), "no rfrac() when already shielded");
        }
    }

    // ================================================================ ship-ship: items and tanks

    @Nested
    @DisplayName("ship-ship: Item_damage on bounces")
    class Bounces {
        Simulation s = sim(FRAME + "allowPlayerCrashes: no\n", 20, 20, "__");
        World w = s.world;
        Player a = ready(s, "A", 0, 200, 200);
        Player b = ready(s, "B", 0, 210, 200);

        @Test
        @DisplayName("each ship that is not fully shielded gets Item_damage(destroyItemInCollisionProb)")
        void damages() {
            w.items.destroyItemInCollisionProb = 0.5f;
            a.items[ECM] = 4;
            b.items[ECM] = 4;
            b.used |= Has.BOTH_SHIELDS;
            int seed = seedWhere(r -> r.nextFloat() < 0.5f);
            Rng m = reseed(w, seed);
            check(s);
            skip(m, 1);
            assertAll(
                () -> assertEquals(2, a.items[ECM], "A: (int)(4 * 0.5 + 0.5) = 2"),
                () -> assertEquals(4, b.items[ECM], "B, fully shielded, keeps its items"),
                () -> assertEquals(m.state(), w.rng.state(), "one draw, for A's ECMs"));
        }

        @Test
        @DisplayName("the second ship is damaged too when the first is fully shielded")
        void secondDamaged() {
            w.items.destroyItemInCollisionProb = 0.5f;
            a.items[ECM] = 4;
            b.items[ECM] = 4;
            a.used |= Has.BOTH_SHIELDS;
            int seed = seedWhere(r -> r.nextFloat() < 0.5f);
            reseed(w, seed);
            check(s);
            assertAll(
                () -> assertEquals(4, a.items[ECM], "A, fully shielded, keeps its items"),
                () -> assertEquals(2, b.items[ECM], "B loses half"));
        }
    }

    @Nested
    @DisplayName("ship-ship: tank scoring")
    class TankScoring {
        static final String MULTS = "tankKillScoreMult: 0.5\nrunoverKillScoreMult: 0.25\ncrashScoreMult: 0.125\n";
        Simulation s = sim(FRAME + MULTS, 20, 20, "____");
        World w = s.world;

        Player tankOf(Player owner, String name, double x) {
            Player t = ready(s, name, 0, x, 200);
            t.tank = true;
            t.lockPlId = owner.id;
            return t;
        }

        @Test
        @DisplayName("a tank and a ship crash: the tank's owner scores Rate(owner, ship) * tankKillScoreMult")
        void tankFirst() {
            Player o = ready(s, "O", 0, 600, 600);
            Player t = tankOf(o, "T", 200);
            Player v = ready(s, "V", 0, 210, 200);
            o.score = 30;
            v.score = 100;
            check(s);
            float sc = rate(30, 100) * 0.5f;
            assertAll(
                () -> assertTrue(killed(t) && killed(v), "both die"),
                () -> assertEquals(30 + sc, o.score, EPS, "the owner gains"),
                () -> assertEquals(100 - sc, v.score, EPS, "the ship loses the same"),
                () -> assertEquals(0.0, t.score, EPS, "the tank itself scores nothing"));
        }

        @Test
        @DisplayName("the same when the tank is the second of the pair")
        void tankSecond() {
            Player o = ready(s, "O", 0, 600, 600);
            Player v = ready(s, "V", 0, 210, 200);
            Player t = tankOf(o, "T", 200);
            o.score = 30;
            v.score = 100;
            check(s);
            float sc = rate(30, 100) * 0.5f;
            assertAll(
                () -> assertEquals(30 + sc, o.score, EPS, "the owner gains"),
                () -> assertEquals(100 - sc, v.score, EPS, "the ship loses"),
                () -> assertEquals(0.0, t.score, EPS, "the tank scores nothing"));
        }

        @Test
        @DisplayName("two tanks crash: as C, the first tank's owner scores against the second tank")
        void twoTanks() {
            Player o1 = ready(s, "O1", 0, 600, 600);
            Player o2 = ready(s, "O2", 0, 600, 100);
            Player t1 = tankOf(o1, "T1", 200);
            Player t2 = tankOf(o2, "T2", 210);
            o1.score = 30;
            t2.score = 70;
            check(s);
            float sc = rate(30, 70) * 0.5f;
            assertAll(
                () -> assertTrue(killed(t1) && killed(t2), "both tanks die"),
                () -> assertEquals(30 + sc, o1.score, EPS, "the first tank's owner gains (collision.c:378)"),
                () -> assertEquals(70 - sc, t2.score, EPS, "the second tank loses"),
                () -> assertEquals(0.0, o2.score, EPS, "the second tank's owner is not involved"));
        }

        @Test
        @DisplayName("a crash with a tank whose owner is gone scores nothing")
        void ownerGone() {
            Player t = ready(s, "T", 0, 200, 200);
            t.tank = true;
            t.lockPlId = 60;
            Player v = ready(s, "V", 0, 210, 200);
            v.score = 100;
            check(s);
            assertAll(
                () -> assertTrue(killed(v), "the ship dies"),
                () -> assertEquals(100.0, v.score, EPS, "no score"));
        }

        @Test
        @DisplayName("a shielded tank runs over a ship: the kill is the tank's, the score its owner's")
        void runOver() {
            Player o = ready(s, "O", 0, 600, 600);
            Player t = tankOf(o, "T", 200);
            Player v = ready(s, "V", 0, 210, 200);
            t.used |= Has.SHIELD;
            o.score = 30;
            v.score = 100;
            long seq = w.events.seq();
            check(s);
            float sc = rate(30, 100) * 0.5f;
            assertAll(
                () -> assertFalse(killed(t), "the tank survives"),
                () -> assertTrue(killed(v), "the ship dies"),
                () -> assertEquals(List.of("T ran over V."), messages(w, seq), "announced"),
                () -> assertEquals(1, t.kills, "the tank gets the kill"),
                () -> assertEquals(30 + sc, o.score, EPS, "the owner gets Rate(owner, ship) * tankKillScoreMult"),
                () -> assertEquals(100 - sc, v.score, EPS, "the ship loses it"));
        }

        @Test
        @DisplayName("a tank running over its own owner scores for itself")
        void runOverOwner() {
            Player o = ready(s, "O", 0, 210, 200);
            Player t = tankOf(o, "T", 200);
            t.used |= Has.SHIELD;
            o.score = 30;
            t.score = 10;
            check(s);
            float sc = rate(10, 30) * 0.5f;
            assertAll(
                () -> assertTrue(killed(o), "the owner dies"),
                () -> assertEquals(10 + sc, t.score, EPS, "the tank gets Rate(tank, owner) * tankKillScoreMult"),
                () -> assertEquals(30 - sc, o.score, EPS, "the owner loses it"));
        }

        @Test
        @DisplayName("a tank whose owner left runs over a ship and scores itself")
        void runOverOwnerGone() {
            Player t = ready(s, "T", 0, 200, 200);
            t.tank = true;
            t.lockPlId = 60;
            t.used |= Has.SHIELD;
            Player v = ready(s, "V", 0, 210, 200);
            t.score = 10;
            v.score = 30;
            check(s);
            float sc = rate(10, 30) * 0.5f;
            assertAll(
                () -> assertEquals(10 + sc, t.score, EPS, "the tank scores with tankKillScoreMult"),
                () -> assertEquals(30 - sc, v.score, EPS, "the ship loses it"));
        }

        @Test
        @DisplayName("a ship running over a tank uses runoverKillScoreMult")
        void shipRunsOverTank() {
            Player o = ready(s, "O", 0, 600, 600);
            Player v = ready(s, "V", 0, 210, 200);
            Player t = tankOf(o, "T", 200);
            v.used |= Has.SHIELD;
            v.score = 100;
            t.score = 40;
            check(s);
            float sc = rate(100, 40) * 0.25f;
            assertAll(
                () -> assertTrue(killed(t), "the tank dies"),
                () -> assertEquals(100 + sc, v.score, EPS, "the ship gains Rate * runoverKillScoreMult"),
                () -> assertEquals(40 - sc, t.score, EPS, "the tank loses it"),
                () -> assertEquals(0.0, o.score, EPS, "the owner is not involved"));
        }

        @Test
        @DisplayName("a tank and its owner in the same pseudo team do not collide")
        void pseudoTeam() {
            Player o = ready(s, "O", 0, 210, 200);
            Player t = tankOf(o, "T", 200);
            t.pseudoTeam = o.pseudoTeam;
            check(s);
            assertAll(
                () -> assertFalse(killed(o), "the owner lives"),
                () -> assertFalse(killed(t), "the tank lives"));
        }
    }
}
