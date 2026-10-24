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

import com.fulcrologic.jxpilot.common.Const;
import com.fulcrologic.jxpilot.common.Item;
import com.fulcrologic.jxpilot.common.math.Rng;
import com.fulcrologic.jxpilot.server.world.Mods;
import com.fulcrologic.jxpilot.server.world.Obj;
import com.fulcrologic.jxpilot.server.world.Objects;
import com.fulcrologic.jxpilot.server.world.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The world side of C's tuners for objects, cannons, wormholes and the game clock (tuner.c, rules.c). */
@DisplayName("Tuning: tuner side effects")
class TunerEffectsTest {
    static int obj(Objects o, int type, int status, int mods, double life) {
        int h = o.alloc();
        o.type[h] = type;
        o.status[h] = status;
        o.mods[h] = mods;
        o.life[h] = life;
        o.mass[h] = 50;
        return h;
    }

    @Test
    @DisplayName("ballMass sets the mass of every ball in flight (tuner_ballmass)")
    void ballMass() {
        TuningTest.Fx f = new TuningTest.Fx("");
        int ball = obj(f.w.objects, Obj.BALL, 0, 0, 10);
        int shot = obj(f.w.objects, Obj.SHOT, 0, 0, 10);
        f.t.tune("ballMass", "7");
        assertAll(
            () -> assertEquals(7f, f.w.objects.mass[ball], "the ball"),
            () -> assertEquals(50f, f.w.objects.mass[shot], "not other objects"));
    }

    @Test
    @DisplayName("mineLife re-randomizes live mines not fired by cannons: (int)(rfrac * L/(mini+1)) (tuner_minelife)")
    void mineLife() {
        TuningTest.Fx f = new TuningTest.Fx("");
        Objects o = f.w.objects;
        int mine = obj(o, Obj.MINE, 0, Mods.withMini(0, 1), 9999);
        int cannonMine = obj(o, Obj.MINE, Obj.FROMCANNON, 0, 9999);
        int missile = obj(o, Obj.SMART_SHOT, 0, 0, 9999);
        f.w.rng.seed(42);
        Rng r = new Rng(42);
        f.t.tune("mineLife", "100");
        int expected = (int) (r.nextFloat() * (100 / 2));
        assertAll(
            () -> assertEquals(expected, (int) o.life[mine], "100 / (mini 1 + 1) = 50, times rfrac"),
            () -> assertEquals(9999.0, o.life[cannonMine], "a cannon's mine keeps its life"),
            () -> assertEquals(9999.0, o.life[missile], "missiles are another tuner"));
    }

    @Test
    @DisplayName("missileLife re-randomizes live missiles with mineLife (C's tuner_missilelife bug), else MISSILE_LIFETIME")
    void missileLife() {
        TuningTest.Fx f = new TuningTest.Fx("mineLife: 1000\n");
        Objects o = f.w.objects;
        int smart = obj(o, Obj.SMART_SHOT, 0, 0, 1);
        int heat = obj(o, Obj.HEAT_SHOT, 0, 0, 1);
        int mine = obj(o, Obj.MINE, 0, 0, 1);
        f.w.rng.seed(7);
        Rng r = new Rng(7);
        f.t.tune("missileLife", "5");
        int e1 = (int) (r.nextFloat() * 1000);
        int e2 = (int) (r.nextFloat() * 1000);
        TuningTest.Fx g = new TuningTest.Fx("");
        int t = obj(g.w.objects, Obj.TORPEDO, 0, 0, 1);
        g.w.rng.seed(3);
        Rng q = new Rng(3);
        g.t.tune("missileLife", "5");
        int fps = g.w.params.fps;
        int def = Integer.remainderUnsigned(q.nextInt(), 64 * fps - 1) + 128 * fps;
        int e3 = (int) (q.nextFloat() * def);
        assertAll(
            () -> assertEquals(e1, (int) o.life[smart], "mineLife 1000, not missileLife 5"),
            () -> assertEquals(e2, (int) o.life[heat], "each missile in order"),
            () -> assertEquals(1.0, o.life[mine], "not mines"),
            () -> assertEquals(e3, (int) g.w.objects.life[t], "without mineLife: MISSILE_LIFETIME drawn per missile"));
    }

    @Test
    @DisplayName("the modifier options filter every player's modifiers (filter_mods)")
    void modifiers() {
        TuningTest.Fx f = new TuningTest.Fx("allowNukes: yes\nallowModifiers: yes\n");
        Player p = f.s.ships.join("Ann", 0);
        p.mods = Mods.withMini(Mods.withNuclear(0, 1), 2);
        f.t.tune("allowNukes", "no");
        int afterNukes = p.mods;
        f.t.tune("allowModifiers", "no");
        assertAll(
            () -> assertEquals(0, Mods.nuclear(afterNukes), "nuclear dropped"),
            () -> assertEquals(2, Mods.mini(afterNukes), "mini kept while modifiers are allowed"),
            () -> assertEquals(0, Mods.mini(p.mods), "then dropped"));
    }

    @Test
    @DisplayName("cannonsUseItems refills every cannon's items (rfrac * (initial+1) each), or empties them")
    void cannonsUseItems() {
        TuningTest.Fx f = new TuningTest.Fx("initialMines: 4\ninitialMissiles: 3\n", "xxxxxxx", "x _ _ x", "x  r  x",
            "xxxxxxx");
        f.t.tune("cannonsUseItems", "yes");
        int mines = f.w.cannonItems.item(0, Item.MINE);
        int maskOn = f.w.params.objCannonMask & Obj.ITEM;
        f.t.tune("cannonsUseItems", "no");
        int none = 0;
        for (int i = 0; i < Item.COUNT; i++) {
            none += f.w.cannonItems.item(0, i);
        }
        final int total = none;
        assertAll(
            () -> assertTrue(mines >= 0 && mines <= 4, "0..initial mines: " + mines),
            () -> assertEquals(Obj.ITEM, maskOn, "items crash into cannons (Move_init)"),
            () -> assertEquals(0, total, "off: no items"),
            () -> assertEquals(0, f.w.params.objCannonMask & Obj.ITEM, "and the mask again"));
    }

    @Test
    @DisplayName("teamCannons gives cannons the closest team, or none (tuner_teamcannons)")
    void teamCannons() {
        TuningTest.Fx f = new TuningTest.Fx("teamPlay: yes\nteamCannons: no\n", "xxxxxxxxxx", "x1      2x", "x      r x",
            "xxxxxxxxxx");
        int before = f.w.map.cannons().get(0).team();
        f.t.tune("teamCannons", "yes");
        int on = f.w.map.cannons().get(0).team();
        f.t.tune("teamCannons", "no");
        assertAll(
            () -> assertEquals(Const.TEAM_NOT_SET, before, "no team"),
            () -> assertEquals(2, on, "team 2's base is closest"),
            () -> assertEquals(Const.TEAM_NOT_SET, f.w.map.cannons().get(0).team(), "none again"));
    }

    @Test
    @DisplayName("wormTime: every countdown, or without it temporary wormholes go (C's loop skips the one moved in)")
    void wormTime() {
        TuningTest.Fx f = new TuningTest.Fx("", "xxxxxxxxx", "x _ _   x", "x @ @   x", "x       x", "xxxxxxxxx");
        f.t.tune("wormTime", "2");
        int countdown = f.w.worms.countdown(1);
        f.w.worms.addTemp(6, 1, 6, 3);
        int temps = f.w.worms.tempCount();
        f.t.tune("wormTime", "0");
        assertAll(
            () -> assertEquals(120, countdown, "2 s"),
            () -> assertEquals(2, temps, "a temporary pair"),
            () -> assertEquals(1, f.w.worms.tempCount(), "one removed; the one moved into its index is skipped (C)"),
            () -> assertEquals(Tuning.WORMCOUNT, f.w.worms.countdown(0), "map wormholes: WORMCOUNT"));
    }

    @Test
    @DisplayName("gameDuration sets the game-over time from now (tuner_gameduration)")
    void gameDuration() {
        TuningTest.Fx f = new TuningTest.Fx("");
        f.w.wallSeconds = () -> 1000;
        f.t.tune("gameDuration", "2");
        long two = f.w.gameOverTime;
        f.t.tune("gameDuration", "0");
        assertAll(
            () -> assertEquals(1120, two, "now + 2 minutes"),
            () -> assertEquals(1000, f.w.gameOverTime, "0: now"),
            () -> assertEquals(0f, f.w.gameDuration, "the duration"));
    }

    @Test
    @DisplayName("item probabilities: items beyond the new maximum are deleted (Tune_item_probs)")
    void itemProbs() {
        TuningTest.Fx f = new TuningTest.Fx("maxItemDensity: 1\nitemMineProb: 1\n");
        Objects o = f.w.objects;
        int mine = Item.MINE.ordinal();
        for (int i = 0; i < 3; i++) {
            int h = obj(o, Obj.ITEM, 0, 0, 100);
            o.info[h] = mine;
            o.color[h] = com.fulcrologic.jxpilot.common.net.Records.ObjColor.WHITE;
        }
        f.w.itemCount[mine] = 3;
        f.t.tune("maxItemDensity", "0");
        assertAll(
            () -> assertEquals(0, f.w.items.max[mine], "no more mines allowed"),
            () -> assertEquals(0, f.w.itemCount[mine], "all three deleted"),
            () -> assertEquals(0, o.count(), "gone from the world"));
    }

    @Test
    @DisplayName("asteroid concentrator limits are applied to the option values (Tune_asteroid_prob)")
    void asteroidLimits() {
        TuningTest.Fx f = new TuningTest.Fx("");
        f.t.tune("asteroidConcentratorRadius", "0");
        f.t.tune("asteroidConcentratorProb", "5");
        assertAll(
            () -> assertEquals("1", f.get("asteroidConcentratorRadius"), "at least 1"),
            () -> assertEquals("1", f.get("asteroidConcentratorProb"), "at most 1"));
    }

    @Test
    @DisplayName("mineLife 0: each live mine draws MINE_LIFETIME (5000 + randomMT()&255), then rfrac")
    void mineLifeDefault() {
        TuningTest.Fx f = new TuningTest.Fx("mineLife: 50\n");
        int mine = obj(f.w.objects, Obj.MINE, 0, 0, 1);
        f.w.rng.seed(11);
        Rng r = new Rng(11);
        f.t.tune("mineLife", "0");
        int base = 5000 + (r.nextInt() & 255);
        int expected = (int) (r.nextFloat() * base);
        assertEquals(expected, (int) f.w.objects.life[mine], "the default lifetime");
    }

    @Test
    @DisplayName("cannonsUseItems draws each item in C's order: rfrac * (initial + 1) through Cannon_add_item")
    void cannonItemsExact() {
        String extra = "initialMines: 4\ninitialMissiles: 3\ninitialTanks: 2\ninitialFuel: 500\n";
        String[] rows = {"xxxxxxx", "x _ _ x", "x  r  x", "xxxxxxx"};
        TuningTest.Fx f = new TuningTest.Fx(extra, rows);
        TuningTest.Fx g = new TuningTest.Fx(extra, rows);
        f.w.rng.seed(5);
        g.w.rng.seed(5);
        f.t.tune("cannonsUseItems", "yes");
        for (int j = 0; j < Item.COUNT; j++) {
            g.w.cannonItems.set(0, j, 0);
            g.w.cannonItems.addItem(g.w.items, g.w.rng, 0, j, (int) (g.w.rfrac() * (g.w.items.initial[j] + 1)));
        }
        int[] got = new int[Item.COUNT];
        int[] want = new int[Item.COUNT];
        for (int j = 0; j < Item.COUNT; j++) {
            got[j] = f.w.cannonItems.item(0, j);
            want[j] = g.w.cannonItems.item(0, j);
        }
        assertAll(
            () -> assertEquals(java.util.Arrays.toString(want), java.util.Arrays.toString(got), "the same draws"),
            () -> assertTrue(java.util.Arrays.stream(got).sum() > 0, "some items"));
    }

    @Test
    @DisplayName("Tune_item_probs deletes only the excess: two of three mines over a maximum of one, other kinds kept")
    void excessOnly() {
        TuningTest.Fx f = new TuningTest.Fx("");
        Objects o = f.w.objects;
        int mine = Item.MINE.ordinal();
        int tank = Item.TANK.ordinal();
        for (int i = 0; i < 3; i++) {
            int h = obj(o, Obj.ITEM, 0, 0, 100);
            o.info[h] = mine;
            o.color[h] = com.fulcrologic.jxpilot.common.net.Records.ObjColor.WHITE;
        }
        int t = obj(o, Obj.ITEM, 0, 0, 100);
        o.info[t] = tank;
        f.w.itemCount[mine] = 3;
        f.w.itemCount[tank] = 1;
        f.w.items.max[mine] = 1;
        f.w.items.max[tank] = 1;
        f.t.deleteExcessItems();
        assertAll(
            () -> assertEquals(1, f.w.itemCount[mine], "one mine left"),
            () -> assertEquals(1, f.w.itemCount[tank], "the tank stays"),
            () -> assertEquals(2, o.count(), "two items in the world"));
    }

    @Test
    @DisplayName("raceLaps in race mode restarts a finished game (tuner_racelaps)")
    void raceLaps() {
        TuningTest.Fx f = new TuningTest.Fx("timing: yes\n", "xxxxxxx", "x _ _ x", "x A B x", "xxxxxxx");
        f.s.ships.join("Ann", 0);
        f.w.gameDuration = -1;
        f.t.tune("raceLaps", "4");
        TuningTest.Fx g = new TuningTest.Fx("");
        g.w.gameDuration = -1;
        g.t.tune("raceLaps", "4");
        assertAll(
            () -> assertEquals(4, f.w.modeParams.raceLaps, "the laps"),
            () -> assertEquals(0f, f.w.gameDuration, "the game restarts"),
            () -> assertEquals(-1f, g.w.gameDuration, "outside race mode nothing restarts"));
    }

    @Test
    @DisplayName("options kept by other classes are re-read: the robots' real name, wreckage")
    void reloads() {
        TuningTest.Fx f = new TuningTest.Fx("maxRobots: 1\nminRobots: 1\n");
        java.util.List<String> log = new java.util.ArrayList<>();
        f.s.robots.log = l -> log.add(l.toString());
        f.t.tune("robotRealName", "Bender");
        HostFx.addRobots(f.s, 1);
        f.t.tune("useWreckage", "no");
        Player p = f.s.ships.join("Ann", 0);
        int before = f.w.objects.count();
        f.s.wreckage.ship(p);
        assertAll(
            () -> assertTrue(log.get(0).contains(", Bender)"), "the log line uses the new name: " + log),
            () -> assertEquals(before, f.w.objects.count(), "no wreckage after useWreckage off"));
    }

    @Test
    @DisplayName("the modifier tuner also computes the rules (Set_world_rules)")
    void modifierRules() {
        TuningTest.Fx f = new TuningTest.Fx("");
        f.t.tune("allowAlliances", "no");
        f.t.tune("allowNukes", "yes");
        assertTrue(!f.s.alliances.enabled, "alliances follow allowAlliances again");
    }

    static final class Doubles {
        double[] d = new double[2];
    }

    @Test
    @DisplayName("copyChanges copies changed double array elements only")
    void doubles() {
        Doubles before = new Doubles();
        Doubles after = new Doubles();
        Doubles live = new Doubles();
        after.d[0] = 2;
        live.d[1] = 3;
        Tuning.copyChanges(before, after, live);
        assertAll(
            () -> assertEquals(2.0, live.d[0], "changed"),
            () -> assertEquals(3.0, live.d[1], "kept"));
    }
}
