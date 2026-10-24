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
import com.fulcrologic.jxpilot.common.map.BlockType;
import com.fulcrologic.jxpilot.common.math.Rng;
import com.fulcrologic.jxpilot.common.math.ShipShape;
import com.fulcrologic.jxpilot.common.math.XMath;
import com.fulcrologic.jxpilot.common.net.Records;
import com.fulcrologic.jxpilot.server.TestWorlds;
import com.fulcrologic.jxpilot.server.world.Click;
import com.fulcrologic.jxpilot.server.world.Events;
import com.fulcrologic.jxpilot.server.world.Has;
import com.fulcrologic.jxpilot.server.world.Mods;
import com.fulcrologic.jxpilot.server.world.Obj;
import com.fulcrologic.jxpilot.server.world.Objects;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.ShipGeom;
import com.fulcrologic.jxpilot.server.world.Trig;
import com.fulcrologic.jxpilot.server.world.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.fulcrologic.jxpilot.server.sim.SimFixture.FPS14;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.FRAME;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.messages;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.obj;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.ready;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.sim;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Missiles: the missile cases of Fire_general_shot, Fire_shot and Move_smart_shot (shot.c). */
@DisplayName("Missiles (shot.c)")
class MissilesTest {
    static final double EPS = 1e-9;
    static final double FUEL0 = 1000 * 256;
    static final int MISSILE = Item.MISSILE.ordinal();
    static final int NUKE = Mods.withNuclear(0, Mods.NUCLEAR);
    static final int FULLNUKE = Mods.withNuclear(0, Mods.NUCLEAR | Mods.FULLNUCLEAR);
    /** ED_SMART_SHOT = -30 fuel units. */
    static final double SMART_FUEL = 30 * 256;
    /** The default triangle with two missile racks at (0, +-4). */
    static final String RACK_SHIP = "(SH: 15,0 -9,8 -9,-8)(MG: 15,0)(EN: -9,0)(MR: 0,4 0,-4)";

    Simulation s;
    World w;
    Objects o;
    Player p;
    Player q;

    void setup(String extra) {
        s = sim(FRAME + extra, 20, 20, "___");
        w = s.world;
        o = w.objects;
        p = ready(s, "P", 0, 200, 300);
        q = ready(s, "Q", 0, 500, 300);
        p.dir = 0;
        p.items[MISSILE] = 5;
        w.items.missileLife = 500;
        w.items.allowSmartMissiles = true;
        w.items.allowHeatSeekers = true;
        w.items.allowTorpedoes = true;
        w.items.nukeMinSmarts = 3;
    }

    /** Lock P on Q (visible, in range). */
    void lock() {
        p.lockTagged = Player.LOCK_PLAYER;
        p.lockPlId = q.id;
        p.lockDistance = 300;
        p.sensorRange = 1000;
    }

    int receiverOfLast() {
        return w.events.c[Events.index(w.events.seq() - 1)];
    }

    @Nested
    @DisplayName("key / fireShot (KEY_FIRE_*, Fire_shot)")
    class Key {
        @Test
        @DisplayName("a key fires along the heading from the ship iff the player has missiles")
        void keyNeedsMissiles() {
            setup("");
            p.dir = 32;
            p.items[MISSILE] = 0;
            s.missiles.key(p, Obj.TORPEDO);
            int none = o.count();
            p.items[MISSILE] = 1;
            s.missiles.key(p, Obj.TORPEDO);
            int h = o.active[0];
            assertAll(
                () -> assertEquals(0, none, "no missiles: nothing"),
                () -> assertEquals(1, o.count(), "one missile: a torpedo"),
                () -> assertEquals(32, o.missileDir[h], "along the heading"),
                () -> assertEquals(Click.fromPixels(200 + p.ship.rackX[0][32]), o.cx[h], "from the rack of the ship"),
                () -> assertEquals(0, p.items[MISSILE], "using the missile"),
                () -> assertEquals(0, p.shots, "missiles do not count as shots"));
        }

        @Test
        @DisplayName("nothing at the shot limit, with shields or while phased")
        void refused() {
            setup("");
            p.shots = p.shotMax;
            s.missiles.fireShot(p, Obj.TORPEDO, 0);
            int atMax = o.count();
            p.shots = p.shotMax - 1;
            p.used |= Has.SHIELD;
            s.missiles.fireShot(p, Obj.TORPEDO, 0);
            int shielded = o.count();
            p.used = Has.PHASING_DEVICE;
            s.missiles.fireShot(p, Obj.TORPEDO, 0);
            int phased = o.count();
            p.used = 0;
            s.missiles.fireShot(p, Obj.TORPEDO, 0);
            assertAll(
                () -> assertEquals(0, atMax, "at shotMax: nothing"),
                () -> assertEquals(0, shielded, "shielded: nothing"),
                () -> assertEquals(0, phased, "phased: nothing"),
                () -> assertEquals(1, o.count(), "below the limit, unshielded: fires"));
        }
    }

    @Nested
    @DisplayName("fire (Fire_general_shot missile cases)")
    class Fire {
        @Test
        @DisplayName("a torpedo: mass 5, no lock, fuse 8 + 2, range 45, a decaying 20 px/frame push, recoil, fuel")
        void torpedo() {
            setup("");
            p.vx = 1;
            p.vy = 2;
            float m = p.mass;
            long m0 = w.events.seq();
            s.missiles.fire(p, 4, false, p.px, p.py, Obj.TORPEDO, 0, 0, -1);
            int h = o.active[0];
            assertAll(
                () -> assertEquals(Obj.TORPEDO, o.type[h], "a torpedo"),
                () -> assertEquals(500.0, o.life[h], EPS, "life missileLife"),
                // fuse = 8 + (int)(2*SHIP_SZ/21 + 1) = 8 + 2 (shot.c:681, 734)
                () -> assertEquals(490.0, o.fuseLife[h], EPS, "fuse 8 + (int)(32/21 + 1) = 10"),
                () -> assertEquals(5f, o.mass[h], "MISSILE_MASS"),
                () -> assertEquals(-1, o.info[h], "no lock (the torpedo frame counter starts below 0)"),
                () -> assertEquals(0, o.counter[h], "count 0"),
                () -> assertEquals(p.id, o.owner[h], "owned by the shooter"),
                () -> assertEquals(4, o.team[h], "of the given team"),
                () -> assertEquals(Records.ObjColor.WHITE, o.color[h], "white"),
                () -> assertEquals(0f, o.turnSpeed[h], "a torpedo does not turn"),
                () -> assertEquals(65f, o.maxSpeed[h], "max speed SPEED_LIMIT"),
                () -> assertEquals(Click.fromPixels(200 + 15f), o.cx[h], "from the rack (the main gun of the default ship)"),
                () -> assertEquals(Click.fromPixels(300), o.cy[h], "at the ship's y"),
                // vel = mv + ship v + tcos(dir) * speed; mv = 20 * tcos(0) / 1 (shot.c:997-1033)
                () -> assertEquals(20f + 1f + 21f, o.vx[h], "vx = spread push 20 + ship 1 + shotSpeed 21"),
                () -> assertEquals(2f, o.vy[h], "vy = ship vy"),
                () -> assertEquals(-20f / 6, o.accX[h], "the push decays over MINI_TORPEDO_SPREAD_TIME = 6"),
                () -> assertEquals(6, o.spreadLeft[h], "for 6 frames"),
                () -> assertEquals(Obj.GRAVITY, o.status[h], "GRAVITY"),
                () -> assertEquals(0, o.missileDir[h], "flies along dir"),
                () -> assertEquals(45, o.plRange[h], "pl_range (int)TORPEDO_RANGE = 45"),
                () -> assertEquals(15, o.plRadius[h], "pl_radius MISSILE_LEN"),
                () -> assertEquals(FUEL0 - SMART_FUEL, p.fuel, EPS, "costs ED_SMART_SHOT = 30 fuel units"),
                () -> assertEquals(4, p.items[MISSILE], "and a missile"),
                () -> assertEquals(1f - ((42f - 1f) * 5f) / m, p.vx, "recoil (shot.v - v) * 5 / mass"),
                () -> assertEquals(2f, p.vy, "no sideways recoil"),
                () -> assertEquals(List.of(), messages(w, m0), "a single missile is not announced"));
        }

        @Test
        @DisplayName("a smart missile locks on the player's visible lock: max 22, turn 2.6, fuse 2, range 4")
        void smart() {
            setup("");
            lock();
            s.missiles.fire(p, 0, false, p.px, p.py, Obj.SMART_SHOT, 0, 0, -1);
            int h = o.active[0];
            assertAll(
                () -> assertEquals(Obj.SMART_SHOT, o.type[h], "a smart missile"),
                () -> assertEquals(q.id, o.info[h], "locked on the player's lock"),
                () -> assertEquals(22f, o.maxSpeed[h], "SMART_SHOT_MAX_SPEED"),
                () -> assertEquals(2.6f, o.turnSpeed[h], "SMART_TURNSPEED"),
                () -> assertEquals(498.0, o.fuseLife[h], EPS, "fuse (int)(32/21 + 1) = 2"),
                () -> assertEquals(4, o.plRange[h], "MISSILE_RANGE"),
                () -> assertEquals(21f, o.vx[h], "shotSpeed along dir, no push"),
                () -> assertEquals(0f, o.accX[h], "no acceleration"),
                () -> assertEquals(FUEL0 - SMART_FUEL, p.fuel, EPS, "30 fuel units"));
        }

        @Test
        @DisplayName("a smart missile needs a lock on a visible player within sensor range (with limitedVisibility)")
        void smartNeedsLock() {
            setup("");
            s.missiles.fire(p, 0, false, p.px, p.py, Obj.SMART_SHOT, 0, 0, -1);
            int noLock = o.count();
            lock();
            p.canSee[q.slot] = false;
            s.missiles.fire(p, 0, false, p.px, p.py, Obj.SMART_SHOT, 0, 0, -1);
            int unseen = o.count();
            p.canSee[q.slot] = true;
            w.items.limitedVisibility = true;
            p.lockDistance = 1001;
            s.missiles.fire(p, 0, false, p.px, p.py, Obj.SMART_SHOT, 0, 0, -1);
            int outOfRange = o.count();
            p.lockDistance = 1000;
            s.missiles.fire(p, 0, false, p.px, p.py, Obj.SMART_SHOT, 0, 0, -1);
            int atRange = o.count();
            w.items.limitedVisibility = false;
            p.lockDistance = 5000;
            s.missiles.fire(p, 0, false, p.px, p.py, Obj.SMART_SHOT, 0, 0, -1);
            int unlimited = o.count();
            p.lockPlId = 999;
            s.missiles.fire(p, 0, false, p.px, p.py, Obj.SMART_SHOT, 0, 0, -1);
            assertAll(
                () -> assertEquals(0, noLock, "no lock: nothing"),
                () -> assertEquals(0, unseen, "an invisible lock: nothing"),
                () -> assertEquals(0, outOfRange, "beyond sensor range with limitedVisibility: nothing"),
                () -> assertEquals(1, atRange, "at sensor range: fires"),
                () -> assertEquals(2, unlimited, "without limitedVisibility the distance does not matter"),
                () -> assertEquals(2, o.count(), "a lock on a player who is gone: nothing"),
                () -> assertEquals(3, p.items[MISSILE], "only the two fired missiles were used"),
                () -> assertEquals(FUEL0 - 2 * SMART_FUEL, p.fuel, EPS, "and paid for"));
        }

        @Test
        @DisplayName("a heat-seeker: no lock, 1.7 times faster with turn 4.42 and max 37.4, fuse 1")
        void heat() {
            setup("");
            s.missiles.fire(p, 0, false, p.px, p.py, Obj.HEAT_SHOT, 0, 0, -1);
            int h = o.active[0];
            assertAll(
                () -> assertEquals(Obj.HEAT_SHOT, o.type[h], "a heat-seeker"),
                () -> assertEquals(-1, o.info[h], "locked on nobody (HEAT_LOCK is not defined)"),
                () -> assertEquals((float) (22.0 * 1.7), o.maxSpeed[h], "max SMART_SHOT_MAX_SPEED * HEAT_SPEED_FACT"),
                () -> assertEquals((float) (2.6 * 1.7), o.turnSpeed[h], "turn SMART_TURNSPEED * HEAT_SPEED_FACT"),
                () -> assertEquals((float) (21f * 1.7), o.vx[h], "speed shotSpeed * 1.7"),
                () -> assertEquals(499.0, o.fuseLife[h], EPS, "fuse (int)(32/35.7 + 1) = 1"));
        }

        @Test
        @DisplayName("disallowed smart missiles and heat-seekers become torpedoes when allowed, else nothing")
        void disallowed() {
            setup("");
            lock();
            w.items.allowSmartMissiles = false;
            w.items.allowHeatSeekers = false;
            s.missiles.fire(p, 0, false, p.px, p.py, Obj.SMART_SHOT, 0, 0, -1);
            s.missiles.fire(p, 0, false, p.px, p.py, Obj.HEAT_SHOT, 0, 0, -1);
            int t1 = o.type[o.active[0]];
            int t2 = o.type[o.active[1]];
            w.items.allowTorpedoes = false;
            s.missiles.fire(p, 0, false, p.px, p.py, Obj.SMART_SHOT, 0, 0, -1);
            s.missiles.fire(p, 0, false, p.px, p.py, Obj.HEAT_SHOT, 0, 0, -1);
            int none = o.count();
            w.items.allowSmartMissiles = true;
            s.missiles.fire(p, 0, false, p.px, p.py, Obj.SMART_SHOT, 0, 0, -1);
            w.items.allowHeatSeekers = true;
            s.missiles.fire(p, 0, false, p.px, p.py, Obj.HEAT_SHOT, 0, 0, -1);
            s.missiles.fire(p, 0, false, p.px, p.py, Obj.TORPEDO, 0, 0, -1);
            s.missiles.fire(p, 0, false, p.px, p.py, Obj.SHOT, 0, 0, -1);
            assertAll(
                () -> assertEquals(Obj.TORPEDO, t1, "a disallowed smart missile is a torpedo"),
                () -> assertEquals(Obj.TORPEDO, t2, "a disallowed heat-seeker is a torpedo"),
                () -> assertEquals(2, none, "without torpedoes either: nothing"),
                () -> assertEquals(Obj.SMART_SHOT, o.type[o.active[2]], "allowed smart missiles stay smart"),
                () -> assertEquals(Obj.HEAT_SHOT, o.type[o.active[3]], "allowed heat-seekers stay heat-seekers"),
                () -> assertEquals(Obj.TORPEDO, o.type[o.active[4]], "torpedoes fire even when torpedoes are disallowed"),
                () -> assertEquals(5, o.count(), "a plain shot is not a missile"),
                () -> assertEquals(0, p.items[MISSILE], "five missiles were used"));
        }

        @Test
        @DisplayName("life: missileLife, else MISSILE_LIFETIME; a killed player's (int)(rfrac*FPS); cannons 8 + rand%24")
        void lives() {
            setup("");
            w.items.missileLife = 0;
            w.rng.seed(7);
            Rng ref = new Rng(7);
            int lifetime = Integer.remainderUnsigned(ref.nextInt(), 64 * 60 - 1) + 128 * 60;
            s.missiles.fire(p, 0, false, p.px, p.py, Obj.TORPEDO, 0, 0, -1);
            p.status |= Obj.KILLED;
            int killed = (int) (ref.nextFloat() * 60);
            s.missiles.fire(p, 0, false, p.px, p.py, Obj.TORPEDO, 0, 0, -1);
            int cannon = 8 + Integer.remainderUnsigned(ref.nextInt(), 24);
            s.missiles.fire(null, 2, true, 100, 100, Obj.SMART_SHOT, 32, 0, q.id);
            w.items.missileLife = 77;
            s.missiles.fire(null, 2, false, 100, 100, Obj.HEAT_SHOT, 32, 0, -1);
            int c = o.active[2];
            assertAll(
                () -> assertEquals((double) lifetime, o.life[o.active[0]], EPS, "randomMT() % (64*FPS - 1) + 128*FPS"),
                () -> assertEquals((double) killed, o.life[o.active[1]], EPS, "a killed player's missile: (int)(rfrac * FPS)"),
                () -> assertEquals((double) cannon, o.life[c], EPS, "a cannon missile lives CANNON_SHOT_LIFE"),
                () -> assertEquals(Obj.GRAVITY | Obj.FROMCANNON, o.status[c], "FROMCANNON"),
                () -> assertEquals(q.id, o.info[c], "a cannon's smart missile locks on the given target"),
                () -> assertEquals(Obj.NO_ID, o.owner[c], "unowned"),
                () -> assertEquals(Click.fromPixels(100), o.cx[c], "from the given point, no rack"),
                () -> assertEquals(21f, o.vy[c], "straight along dir 32"),
                () -> assertEquals(77.0, o.life[o.active[3]], EPS, "an unowned non-cannon missile lives missileLife"),
                () -> assertEquals(3, p.items[MISSILE], "unowned missiles use no player's missiles"));
        }

        @Test
        @DisplayName("nothing without missiles or without room in the pool for every mini")
        void noMissilesNoRoom() {
            setup("");
            p.items[MISSILE] = 0;
            s.missiles.fire(p, 0, false, p.px, p.py, Obj.TORPEDO, 0, 0, -1);
            int none = o.count();
            double fuel = p.fuel;
            p.items[MISSILE] = 5;
            while (o.count() < o.capacity - 3) {
                o.alloc();
            }
            s.missiles.fire(p, 0, false, p.px, p.py, Obj.TORPEDO, 0, Mods.withMini(0, 3), -1);
            int noRoom = o.count();
            o.free(o.active[0]);
            s.missiles.fire(p, 0, false, p.px, p.py, Obj.TORPEDO, 0, Mods.withMini(0, 3), -1);
            int fits = o.count();
            s.missiles.fire(null, 0, false, 100, 100, Obj.TORPEDO, 0, 0, -1);
            assertAll(
                () -> assertEquals(0, none, "no missiles: nothing"),
                () -> assertEquals(FUEL0, fuel, EPS, "and no fuel spent"),
                () -> assertEquals(o.capacity - 3, noRoom, "count + mini >= capacity: nothing"),
                () -> assertEquals(o.capacity, fits, "count + mini = capacity - 1: all four minis"),
                () -> assertEquals(o.capacity, o.count(), "a full pool: nothing"));
        }

        @Test
        @DisplayName("a nuke uses nukeMinSmarts missiles (FULLNUCLEAR all), is that heavy, has range 150 and is announced")
        void nuclear() {
            setup("");
            lock();
            long m0 = w.events.seq();
            s.missiles.fire(p, 0, false, p.px, p.py, Obj.TORPEDO, 0, NUKE, -1);
            int h = o.active[0];
            List<String> msg = messages(w, m0);
            int to = receiverOfLast();
            p.items[MISSILE] = 5;
            s.missiles.fire(p, 0, false, p.px, p.py, Obj.SMART_SHOT, 0, FULLNUKE, -1);
            int hf = o.active[1];
            s.missiles.fire(null, 0, false, 100, 100, Obj.HEAT_SHOT, 0, NUKE, -1);
            assertAll(
                () -> assertEquals(15f, o.mass[h], "3 * MISSILE_MASS"),
                () -> assertEquals(150, o.plRange[h], "a nuclear torpedo: (int)NUKE_RANGE"),
                () -> assertEquals(List.of("P has launched a nuclear torpedo!"), msg, "announced"),
                () -> assertEquals(0, to, "to everyone"),
                () -> assertEquals(25f, o.mass[hf], "full nuclear uses all 5"),
                () -> assertEquals(4, o.plRange[hf], "a nuclear smart missile keeps MISSILE_RANGE"),
                () -> assertEquals(0, p.items[MISSILE], "all missiles used"),
                () -> assertEquals(FUEL0 - 8 * SMART_FUEL, p.fuel, EPS, "each missile used costs ED_SMART_SHOT"),
                () -> assertEquals(15f, o.mass[o.active[2]], "an unowned nuke weighs nukeMinSmarts missiles"));
        }

        @Test
        @DisplayName("too few missiles for a nuke, or nukeMinSmarts 0, or too little fuel")
        void refusedNukesAndFuel() {
            setup("");
            p.items[MISSILE] = 2;
            long m0 = w.events.seq();
            s.missiles.fire(p, 0, false, p.px, p.py, Obj.TORPEDO, 0, NUKE, -1);
            List<String> few = messages(w, m0);
            int to = receiverOfLast();
            int afterFew = o.count();
            w.items.nukeMinSmarts = 0;
            s.missiles.fire(p, 0, false, p.px, p.py, Obj.TORPEDO, 0, FULLNUKE, -1);
            int noNukes = o.active[0];
            p.items[MISSILE] = 5;
            p.fuel = SMART_FUEL - 1;
            long m1 = w.events.seq();
            s.missiles.fire(p, 0, false, p.px, p.py, Obj.TORPEDO, 0, 0, -1);
            List<String> plain = messages(w, m1);
            // (long)(5 * 0.9 / 0.1f * -51.2) = -2303; (7680 + 2303) >> 8 = 38
            p.fuel = SMART_FUEL + 2303 - 1;
            long m2 = w.events.seq();
            s.missiles.fire(p, 0, false, p.px, p.py, Obj.TORPEDO, 0, Mods.withMini(Obj.MOD_CLUSTER, 1), -1);
            List<String> cluster = messages(w, m2);
            int refused = o.count();
            p.fuel = SMART_FUEL + 2303;
            s.missiles.fire(p, 0, false, p.px, p.py, Obj.TORPEDO, 0, Obj.MOD_CLUSTER, -1);
            assertAll(
                () -> assertEquals(List.of("You need at least 3 missiles to fire a nuclear torpedo!"), few, "C's message"),
                () -> assertEquals(p.id, to, "to the shooter only"),
                () -> assertEquals(0, afterFew, "nothing is fired"),
                () -> assertEquals(Mods.withNuclear(0, Mods.FULLNUCLEAR), o.mods[noNukes],
                    "nukeMinSmarts 0 clears NUCLEAR (FULLNUCLEAR alone does nothing)"),
                () -> assertEquals(5f, o.mass[noNukes], "a plain missile's mass"),
                () -> assertEquals(List.of("You need at least 30 fuel units to fire a torpedo!"), plain, "ED_SMART_SHOT >> 8"),
                () -> assertEquals(List.of("You need at least 38 fuel units to fire some mini cluster torpedoes!"), cluster,
                    "the cluster drain is added; a launch of minis says \"some\""),
                () -> assertEquals(1, refused, "the refused shots made nothing"),
                () -> assertEquals(2, o.count(), "exactly the drain fires"),
                () -> assertEquals(0.0, p.fuel, EPS, "spending all of it"));
        }

        @Test
        @DisplayName("velocity needs cluster, spread needs mini")
        void modFilters() {
            setup("");
            s.missiles.fire(p, 0, false, p.px, p.py, Obj.TORPEDO, 0, Mods.withSpread(Mods.withVelocity(0, 2), 2), -1);
            int kept = Mods.withSpread(Mods.withMini(Mods.withVelocity(Obj.MOD_CLUSTER, 2), 1), 2);
            s.missiles.fire(p, 0, false, p.px, p.py, Obj.TORPEDO, 0, kept, -1);
            assertAll(
                () -> assertEquals(0, o.mods[o.active[0]], "velocity and spread are dropped"),
                () -> assertEquals(kept, o.mods[o.active[1]], "with cluster and mini they stay"));
        }

        @Test
        @DisplayName("power raises speed and max speed by 25% and turn speed by 75% per level")
        void power() {
            setup("");
            lock();
            s.missiles.fire(p, 0, false, p.px, p.py, Obj.SMART_SHOT, 0, Mods.withPower(0, 2), -1);
            int h = o.active[0];
            assertAll(
                () -> assertEquals((float) (21f * 1.5), o.vx[h], "speed 21 * (1 + 2 * 0.25)"),
                () -> assertEquals((float) (22f * 1.5), o.maxSpeed[h], "max 22 * 1.5"),
                () -> assertEquals((float) (2.6f * 2.5), o.turnSpeed[h], "turn 2.6 * (1 + 2 * 0.75)"),
                () -> assertEquals(498.0, o.fuseLife[h], EPS, "fuse (int)(32/31.5 + 1) = 2"));
        }

        @Test
        @DisplayName("four mini torpedoes from one rack: 90-degree fan of pushes, all flying along dir, sharing life and mass")
        void miniTorpedoes() {
            setup("");
            p.dir = 16;
            int mods = Mods.withSpread(Mods.withMini(0, 3), 1);
            float m = p.mass;
            long m0 = w.events.seq();
            s.missiles.fire(p, 0, false, p.px, p.py, Obj.TORPEDO, 16, mods, -1);
            // on_this_rack = 4; angle_r = (3 - 2r) / 9; * 90/360 * 128 = 32 -> (int) 10, 3, -3, -10 (shot.c:1010-1016)
            int[] off = {10, 3, -3, -10};
            float[] evx = new float[4];
            float[] eax = new float[4];
            for (int r = 0; r < 4; r++) {
                int ld = Trig.mod(16 + off[r]);
                float mvx = 20 * Trig.tcos(ld) / 2.0f;
                eax[r] = -mvx / 6;
                evx[r] = (float) (mvx + 0.0 + Trig.tcos(16) * 21f);
            }
            float vx = 0;
            for (int i = 0; i < 4; i++) {
                int h = o.active[i];
                vx -= ((o.vx[h] - vx) * o.mass[h]) / m;
            }
            float recoil = vx;
            assertAll(
                () -> assertEquals(4, o.count(), "mini 3: four torpedoes"),
                () -> assertEquals(evx[0], o.vx[o.active[0]], "first pushed 10 units left of dir, spread 2 halves the push"),
                () -> assertEquals(evx[1], o.vx[o.active[1]], "second 3 units left"),
                () -> assertEquals(evx[2], o.vx[o.active[2]], "third 3 units right"),
                () -> assertEquals(evx[3], o.vx[o.active[3]], "fourth 10 units right"),
                () -> assertEquals(eax[3], o.accX[o.active[3]], "the push decays over 6 frames"),
                () -> assertEquals(16, o.missileDir[o.active[0]], "all fly along dir"),
                () -> assertEquals(125.0, o.life[o.active[1]], EPS, "life 500 / 4"),
                () -> assertEquals(1.25f, o.mass[o.active[2]], "mass 5 / 4"),
                () -> assertEquals(11, o.plRange[o.active[3]], "range 45 / 4"),
                () -> assertEquals(115.0, o.fuseLife[o.active[0]], EPS, "fuse life = life - 10"),
                () -> assertEquals(4, p.items[MISSILE], "one missile is used"),
                () -> assertEquals(FUEL0 - SMART_FUEL, p.fuel, EPS, "for one missile's fuel"),
                () -> assertEquals(recoil, p.vx, "the ship recoils from each mini in turn"),
                () -> assertEquals(List.of(), messages(w, m0), "minis are not announced"));
        }

        @Test
        @DisplayName("mini smart missiles fan out over 45 degrees / (spread + 1) in flight direction")
        void miniSmart() {
            setup("");
            lock();
            int mods = Mods.withSpread(Mods.withMini(0, 3), 1);
            s.missiles.fire(p, 0, false, p.px, p.py, Obj.SMART_SHOT, 0, mods, -1);
            // angle_r * (45/360 * 128 / 2) = angle_r * 8: 2.67, 0.89, -0.89, -2.67 -> 2, 0, 0, -2
            assertAll(
                () -> assertEquals(2, o.missileDir[o.active[0]], "first 2 left"),
                () -> assertEquals(0, o.missileDir[o.active[1]], "second straight ((int)0.89)"),
                () -> assertEquals(0, o.missileDir[o.active[2]], "third straight ((int)-0.89)"),
                () -> assertEquals(126, o.missileDir[o.active[3]], "fourth 2 right"),
                () -> assertEquals(Trig.tcos(2) * 21f, o.vx[o.active[0]], 1e-6, "flying along its own direction"),
                () -> assertEquals(0f, o.accX[o.active[0]], "no push"),
                () -> assertEquals(q.id, o.info[o.active[3]], "all locked on the target"));
        }

        @Test
        @DisplayName("two racks: minis alternate racks, spread by rack position, and the start rack cycles per salvo")
        void racks() {
            setup("");
            p.ship = new ShipGeom(ShipShape.parseOrDefault(RACK_SHIP));
            p.dir = 0;
            lock();
            int mods = Mods.withMini(0, 2);
            s.missiles.fire(p, 0, false, p.px, p.py, Obj.SMART_SHOT, 0, mods, -1);
            int rack1 = p.missileRack;
            int a = o.active[0];
            int b = o.active[1];
            int c = o.active[2];
            s.missiles.fire(p, 0, false, p.px, p.py, Obj.SMART_SHOT, 0, Mods.withMini(0, 0), -1);
            int d = o.active[3];
            // shot.c:948-975: 3 minis on 2 racks: 1 from rack 0 (side 4), then 2 from rack 1 (side -4).
            // angle = (n-1-2r)/(3(n-1)) + 2*side/(3*16); * 45/360*128 = 16 (C floats)
            int da = (int) ((float) (0.0f + (float) (2 * 4) / (float) (3 * 16)) * 16.0);
            int db = (int) ((float) ((float) (1.0 / 3.0) + (float) (2 * -4) / (float) (3 * 16)) * 16.0);
            int dc = (int) ((float) ((float) (-1.0 / 3.0) + (float) (2 * -4) / (float) (3 * 16)) * 16.0);
            assertAll(
                () -> assertEquals(2, p.ship.rackX.length, "the test ship has two racks"),
                () -> assertEquals(Click.fromPixels(200 + 0), o.cx[a], "first from rack 0 x"),
                () -> assertEquals(Click.fromPixels(300 + 4), o.cy[a], "first from rack 0 (left, +4)"),
                () -> assertEquals(Click.fromPixels(300 - 4), o.cy[b], "second from rack 1 (right, -4)"),
                () -> assertEquals(Click.fromPixels(300 - 4), o.cy[c], "third from rack 1 too"),
                () -> assertEquals(2, da, "rack 0 alone: 2*4/48 * 16 = 2.67 -> 2 left"),
                () -> assertEquals(2, db, "rack 1 first of two: (1/3 - 1/6) * 16 -> 2"),
                () -> assertEquals(-8, dc, "rack 1 second of two: (-1/3 - 1/6) * 16 = -8"),
                () -> assertEquals(Trig.mod(da), o.missileDir[a], "first turned by its rack side"),
                () -> assertEquals(Trig.mod(db), o.missileDir[b], "second: +1/3 - 1/4 of the fan"),
                () -> assertEquals(Trig.mod(dc), o.missileDir[c], "third: -1/3 - 1/4 of the fan"),
                () -> assertEquals(1, rack1, "the next salvo starts at rack 1"),
                () -> assertEquals(Click.fromPixels(300 - 4), o.cy[d], "the next single missile comes from rack 1"),
                () -> assertEquals(0, p.missileRack, "and the start rack wraps to 0"));
        }

        @Test
        @DisplayName("a rack position outside a flat world makes no missile but the missile and fuel are spent (C)")
        void outside() {
            setup("");
            p.initPixels(5, 300);
            p.dir = 64;
            s.missiles.fire(p, 0, false, p.px, p.py, Obj.TORPEDO, 64, 0, -1);
            Simulation ws = sim(FRAME + "edgeWrap: yes\n", 20, 20, "_");
            Player wp = ready(ws, "W", 0, 5, 300);
            wp.items[MISSILE] = 1;
            wp.dir = 64;
            ws.missiles.fire(wp, 0, false, wp.px, wp.py, Obj.TORPEDO, 64, 0, -1);
            Objects wo = ws.world.objects;
            assertAll(
                () -> assertEquals(0, o.count(), "x = 5 - 15 is outside: nothing"),
                () -> assertEquals(4, p.items[MISSILE], "the missile is used"),
                () -> assertEquals(FUEL0 - SMART_FUEL, p.fuel, EPS, "the fuel is spent"),
                () -> assertEquals(1, wo.count(), "a wrapping map wraps it"),
                () -> assertEquals(Click.fromPixels(690), wo.cx[wo.active[0]], "to 700 - 10"));
        }
    }

    @Nested
    @DisplayName("move: torpedoes (Move_smart_shot)")
    class MoveTorpedo {
        int torp(int mods, int info, int spread) {
            int h = obj(w, Obj.TORPEDO, p.id, 100, 100, 1, 2, 5, 100);
            o.mods[h] = mods;
            o.info[h] = info;
            o.missileDir[h] = 32;
            o.spreadLeft[h] = spread;
            o.accX[h] = -3;
            o.accY[h] = 0.5f;
            return h;
        }

        @Test
        @DisplayName("accelerates by TORPEDO_ACC = 18*22/(FPS*2*FPS) along its direction for 2*FPS frames")
        void acceleration() {
            setup("");
            int a = torp(0, 0, 5);
            int last = torp(0, 119, 5);
            int done = torp(0, 120, 5);
            s.missiles.move(a);
            s.missiles.move(last);
            s.missiles.move(done);
            float acc = (float) (18.0 * 22.0 / (60 * 120));
            assertAll(
                () -> assertEquals(2f + acc * Trig.tsin(32), o.vy[a], "frame 0: vy += 0.055 * tsin(32)"),
                () -> assertEquals(1f + acc * Trig.tcos(32), o.vx[a], 1e-6, "vx += 0.055 * tcos(32) (= 0)"),
                () -> assertEquals(1, o.info[a], "the frame counter counts up"),
                () -> assertEquals(2f + acc, o.vy[last], "frame 119 still accelerates"),
                () -> assertEquals(2f, o.vy[done], "frame 120 (TORPEDO_SPEED_TIME) does not"),
                () -> assertEquals(121, o.info[done], "the counter keeps counting"),
                () -> assertEquals(4, o.spreadLeft[a], "the spread time counts down"),
                () -> assertEquals(-3f, o.accX[a], "while spread time is left the push decays"));
        }

        @Test
        @DisplayName("a nuke accelerates 5 times harder; power scales by 1 + power/4; the spread push stops at 0")
        void nukePowerSpread() {
            setup("");
            int n = torp(NUKE, 10, 5);
            int pw = torp(Mods.withPower(0, 1), 10, 5);
            int sp = torp(0, 10, 0);
            int sp1 = torp(0, 10, 1);
            s.missiles.move(n);
            s.missiles.move(pw);
            s.missiles.move(sp);
            s.missiles.move(sp1);
            float acc = (float) (18.0 * 22.0 / (60 * 120));
            assertAll(
                () -> assertEquals(2f + (float) (5 * (18.0 * 22.0 / (60 * 120))), o.vy[n], "NUKE_ACC = 5 * TORPEDO_ACC"),
                () -> assertEquals(2f + (float) (acc * 1.25), o.vy[pw], "power 1: * 1.25"),
                () -> assertEquals(0f, o.accX[sp], "spread time 0: the push stops (x)"),
                () -> assertEquals(0f, o.accY[sp], "and y"),
                () -> assertEquals(-1, o.spreadLeft[sp], "spread time post-decremented"),
                () -> assertEquals(-3f, o.accX[sp1], "spread time 1: still pushing"));
        }

        @Test
        @DisplayName("at 14 fps the acceleration is 18*22/(14*28) for 28 frames")
        void fps14() {
            Simulation s14 = sim(FPS14, 20, 20, "_");
            World w14 = s14.world;
            int h = obj(w14, Obj.TORPEDO, Obj.NO_ID, 100, 100, 0, 0, 5, 100);
            w14.objects.info[h] = 27;
            s14.missiles.move(h);
            float v27 = w14.objects.vx[h];
            s14.missiles.move(h);
            assertAll(
                () -> assertEquals((float) (18.0 * 22.0 / (14 * 28)), v27, "frame 27 accelerates by 1.0102"),
                () -> assertEquals(v27, w14.objects.vx[h], "frame 28 does not"));
        }
    }

    @Nested
    @DisplayName("move: smart steering (Move_smart_shot)")
    class Steering {
        /** A smart missile at (x, y) flying along dir at speed, locked on Q. */
        int smart(double x, double y, int dir, double speed) {
            int h = obj(w, Obj.SMART_SHOT, p.id, x, y, Trig.tcos(dir) * speed, Trig.tsin(dir) * speed, 5, 100);
            o.info[h] = q.id;
            o.missileDir[h] = dir;
            o.turnSpeed[h] = 2.6f;
            o.maxSpeed[h] = 22;
            return h;
        }

        /** Put Q in direction theta from the missile at (x, y), distance 200. */
        void target(int theta, double x, double y) {
            q.initPixels(x + 200 * Trig.tcos(theta), y + 200 * Trig.tsin(theta));
            q.vx = 0;
            q.vy = 0;
        }

        int turned(int dir, int theta) {
            setup("");
            int h = smart(350, 350, dir, 10);
            target(theta, 350, 350);
            s.missiles.move(h);
            return o.missileDir[h];
        }

        @Test
        @DisplayName("turns by at most (u_byte)turnspeed = 2 per frame, toward the aim, C's RES/2 offset included")
        void turning() {
            assertAll(
                () -> assertEquals(2, turned(0, 0), "aim straight ahead: angle -64, turns +2 (C's oscillation)"),
                () -> assertEquals(2, turned(0, 10), "aim 10 left: +2"),
                () -> assertEquals(126, turned(0, 120), "aim 8 right: angle 56, -2"),
                () -> assertEquals(1, turned(0, 63), "aim 63 left: angle -1, turns only 1"),
                () -> assertEquals(0, turned(0, 64), "aim straight behind: angle 0, no turn"),
                () -> assertEquals(127, turned(0, 65), "aim 63 right: angle 1, -1"),
                () -> assertEquals(1, turned(127, 0), "aim 1 left of 127: angle -63, 127 + 2 wraps to 1 (u_byte, MOD2)"),
                () -> assertEquals(34, turned(32, 32), "aim along 32: angle -64, +2"));
        }

        @Test
        @DisplayName("speeds up by SMART_SHOT_ACC up to max speed; power scales acc and turn")
        void speed() {
            setup("");
            int h = smart(350, 350, 0, 10);
            target(0, 350, 350);
            s.missiles.move(h);
            float v1 = (float) Math.hypot(o.vx[h], o.vy[h]);
            int fast = smart(350, 350, 0, 22);
            s.missiles.move(fast);
            int pw = smart(350, 350, 0, 10);
            o.mods[pw] = Mods.withPower(0, 2);
            o.turnSpeed[pw] = (float) (2.6f * 2.5);
            s.missiles.move(pw);
            int slow = smart(350, 350, 0, 0.5);
            s.missiles.move(slow);
            assertAll(
                () -> assertEquals(Trig.tcos(2) * 10.6f, o.vx[h], "vx = tcos(new dir) * (10 + 0.6)"),
                () -> assertEquals(Trig.tsin(2) * 10.6f, o.vy[h], "vy = tsin(new dir) * 10.6"),
                () -> assertEquals(10.6, v1, 1e-3, "speed 10.6"),
                () -> assertEquals(Trig.tcos(2) * 22f, o.vx[fast], "at max speed 22 no more acceleration"),
                () -> assertEquals(6, o.missileDir[pw], "power 2 turns (u_byte)6.5 = 6"),
                () -> assertEquals(Trig.tcos(6) * (float) (10f + (float) (0.6f * 1.5)), o.vx[pw], "acc 0.6 * 1.5"),
                () -> assertEquals(Trig.tcos(2) * 1.6f, o.vx[slow], "a speed below 1 counts as 1"));
        }

        @Test
        @DisplayName("leads a moving target by its velocity * range / speed")
        void leads() {
            setup("");
            int h = smart(350, 350, 0, 10);
            q.initPixels(550, 350);
            q.vy = -12;
            // lead = vy * range / speed = -12 * 200 / 10 = -240: findDir(200, -240) = 110 (shot.c:1757-1761)
            int theta = (int) XMath.findDir(200f, -240f);
            int still = smart(350, 350, 0, 10);
            q.vy = 0;
            s.missiles.move(still);
            q.vy = -12;
            s.missiles.move(h);
            assertAll(
                () -> assertEquals(110, theta, "the lead point (200, -240) is at dir 110"),
                () -> assertEquals(126, o.missileDir[h], "aim 110: angle 46, turns -2"),
                () -> assertEquals(2, o.missileDir[still], "the same target standing still: angle -64, +2"));
        }

        @Test
        @DisplayName("a missile whose target left does nothing")
        void noTarget() {
            setup("");
            int h = smart(350, 350, 0, 10);
            o.info[h] = 999;
            s.missiles.move(h);
            assertAll(
                () -> assertEquals(0, o.missileDir[h], "no turn"),
                () -> assertEquals(10f, o.vx[h], "no acceleration"));
        }
    }

    @Nested
    @DisplayName("move: walls (look-ahead and neighbour choice)")
    class Walls {
        /** A 20x20 map with walls at the given blocks (C coordinates: y up). */
        Simulation walled(String extra, int... blocks) {
            String[] rows = SimFixture.open(20, 20, "__");
            for (int i = 0; i < blocks.length; i += 2) {
                int row = 19 - blocks[i + 1];
                char[] c = rows[row].toCharArray();
                c[blocks[i]] = 'x';
                rows[row] = new String(c);
            }
            return TestWorlds.sim(FRAME + extra, rows);
        }

        void use(Simulation sm) {
            s = sm;
            w = s.world;
            o = w.objects;
            p = ready(s, "P", 0, 100, 100);
            q = ready(s, "Q", 0, 100, 600);
        }

        int smart(double x, double y, int dir, double speed) {
            int h = obj(w, Obj.SMART_SHOT, p.id, x, y, Trig.tcos(dir) * speed, Trig.tsin(dir) * speed, 5, 100);
            o.info[h] = q.id;
            o.missileDir[h] = dir;
            o.turnSpeed[h] = 2.6f;
            o.maxSpeed[h] = 22;
            return h;
        }

        @Test
        @DisplayName("a wall within the look-ahead slows by acc * 4 when the target is farther than the wall")
        void lookAhead() {
            // missile in block (5, 10) flying right at 10: look-ahead steps of 70 px reach blocks 7, 9, 11, 13
            use(walled("", 9, 10));
            q.initPixels(192 + 400, 367);
            int h = smart(192, 367, 0, 10);
            s.missiles.move(h);
            float far = (float) Math.hypot(o.vx[h], o.vy[h]);
            q.initPixels(192 + 17, 367);
            int near = smart(192, 367, 0, 10);
            s.missiles.move(near);
            float close = (float) Math.hypot(o.vx[near], o.vy[near]);
            q.initPixels(192 + 400, 367);
            int min = smart(192, 367, 0, 4.75);
            s.missiles.move(min);
            int above = smart(192, 367, 0, 4.8);
            s.missiles.move(above);
            assertAll(
                // shot.c:1795-1799: range 400 > (4-3) * 17 -> 10 - 0.6 * 4 = 7.6, then + 0.6
                () -> assertEquals(8.2f, far, 1e-4, "wall 2 steps ahead, target beyond: 10 - 2.4 + 0.6"),
                () -> assertEquals(10.6f, close, 1e-4, "target exactly (4-3)*17 = 17 px away: no slowdown"),
                () -> assertEquals(5.35f, (float) Math.hypot(o.vx[min], o.vy[min]), 1e-4,
                    "below SMART_SHOT_MIN_SPEED 4.8 no slowdown"),
                () -> assertEquals(3.0f, (float) Math.hypot(o.vx[above], o.vy[above]), 1e-4,
                    "4.8f is above the double 4.8 (as in C): slows to 4.8 - 2.4 + 0.6"));
        }

        @Test
        @DisplayName("look-ahead blocks wrap on wrapping maps and stop at the edge of flat maps")
        void lookAheadEdges() {
            use(walled("", 0, 10));
            q.initPixels(400, 367);
            int flat = smart(640, 367, 0, 10);
            s.missiles.move(flat);
            float flatSpeed = (float) Math.hypot(o.vx[flat], o.vy[flat]);
            use(walled("edgeWrap: yes\n", 0, 10));
            q.initPixels(400, 367);
            int wrap = smart(640, 367, 0, 10);
            s.missiles.move(wrap);
            assertAll(
                () -> assertEquals(10.6f, flatSpeed, 1e-4,
                    "flat map: the look-ahead leaves the world and finds no wall"),
                () -> assertEquals(8.2f, (float) Math.hypot(o.vx[wrap], o.vy[wrap]), 1e-4,
                    "wrapping map: the wall at block 0 across the seam slows it"));
        }

        @Test
        @DisplayName("a wall next to the missile: it steers to the free neighbour (C's swapped Wrap_findDir arguments)")
        void neighbour() {
            use(walled("", 6, 10));
            q.initPixels(192 + 400, 367);
            w.rng.seed(2);
            int h = smart(192, 367, 0, 10);
            s.missiles.move(h);
            // j = -2 (cells (1,-1), (0,-1), (-1,-1) all free, k = 3 > freemax 2) wins: neighbour 6 = (0, -1).
            // theta = findDir((10 - 1) * 35 - (367 + 0), 5 * 35 - (192 - 20)) = findDir(-52, 3) = 62
            int theta = (int) XMath.findDir(-52f, 3f);
            assertAll(
                () -> assertEquals(62, theta, "the avoidance aim is 62"),
                () -> assertEquals(2, o.missileDir[h], "62 - 0 - 64 = -2: turns +2"));
        }

        @Test
        @DisplayName("free all around: the target aim is kept; a blocked heading with a free side takes the side")
        void openOrSide() {
            use(walled("", 6, 10, 6, 11, 6, 9, 5, 9));
            q.initPixels(192 + 400, 367);
            w.rng.seed(2);
            int h = smart(192, 367, 0, 10);
            s.missiles.move(h);
            // dir 0, i = 8. Free: (0,1), (-1,1), (-1,0), (-1,-1). j=2 cells (-1,1),(0,1),(1,1)wall: k = 2 -> angle 10;
            // j=1..-2 each have a wall at si=0 (k < 0). Winner j=2: neighbour 2 = (0, 1).
            // theta = findDir((10 + 1) * 35 - 367, 5 * 35 - (192 - 20)) = findDir(18, 3) = 3
            int theta = (int) XMath.findDir((10 + 1) * 35 - 367f, 5 * 35 - (192 - 20f));
            assertAll(
                () -> assertEquals(3, theta, "the aim at the free neighbour (0, 1) is 3"),
                () -> assertEquals(2, o.missileDir[h], "3 - 0 - 64 = -61: turns +2"));
        }

        @Test
        @DisplayName("the blocks missiles steer around: walls, fuel, cannons, targets and treasures")
        void obstacles() {
            java.util.Set<Integer> obstacles = new java.util.TreeSet<>();
            for (int b = 0; b < BlockType.COUNT; b++) {
                if (Missiles.isObstacle(b)) {
                    obstacles.add(b);
                }
            }
            assertEquals(java.util.Set.of((int) BlockType.FILLED, (int) BlockType.REC_LU, (int) BlockType.REC_LD,
                    (int) BlockType.REC_RU, (int) BlockType.REC_RD, (int) BlockType.FUEL, (int) BlockType.CANNON,
                    (int) BlockType.TREASURE, (int) BlockType.TARGET), obstacles,
                "exactly C's TARGET, TREASURE, FUEL, FILLED, REC_*, CANNON (shot.c:1789-1797)");
        }

        /** The first seed from 1 whose first rfrac is below (or not below) 0.5. */
        int seed(boolean below) {
            for (int k = 1; ; k++) {
                if ((new Rng(k).nextFloat() < 0.5f) == below) {
                    return k;
                }
            }
        }

        @Test
        @DisplayName("a tie at j = -1 is taken iff rfrac < 0.5, else the j = 1 neighbour stays")
        void tieBreak() {
            int[] dirs = new int[2];
            for (int t = 0; t < 2; t++) {
                use(walled("", 6, 10, 4, 9));
                q.initPixels(400, 600);
                w.rng.seed(seed(t == 0));
                // missile_dir 0 but flying left: only the aims through the 2 * vel terms differ
                int h = obj(w, Obj.SMART_SHOT, p.id, 191, 375, -10, 0, 5, 100);
                o.info[h] = q.id;
                o.turnSpeed[h] = 2.6f;
                o.maxSpeed[h] = 22;
                s.missiles.move(h);
                dirs[t] = o.missileDir[h];
            }
            // j=2 k=3 -> 10; j=1 k=2 tie -> 9; j=0 wall; j=-1 k=2 tie -> 7 iff rfrac < 0.5; j=-2 k=2 tie ignored.
            // neighbour 7 (1,-1): findDir(9*35 - 375, 6*35 - (191 + 20)) = findDir(-60, -1) = 64 -> angle 0
            // neighbour 1 (1, 1): findDir(11*35 - 375, -1) = findDir(10, -1) = 125 -> angle 61 -> -2
            assertAll(
                () -> assertEquals(64, (int) XMath.findDir(-60f, -1f), "the aim at (1,-1) is 64"),
                () -> assertEquals(125, (int) XMath.findDir(10f, -1f), "the aim at (1,1) is 125"),
                () -> assertEquals(0, dirs[0], "rfrac < 0.5: neighbour (1,-1), angle 0, no turn"),
                () -> assertEquals(126, dirs[1], "rfrac >= 0.5: neighbour (1,1), turns -2"));
        }
    }

    @Nested
    @DisplayName("move: heat-seekers")
    class Heat {
        int heat(double x, double y, int target) {
            int h = obj(w, Obj.HEAT_SHOT, Obj.NO_ID, x, y, 10, 0, 5, 100);
            o.info[h] = target;
            o.missileDir[h] = 0;
            o.turnSpeed[h] = 4.42f;
            o.maxSpeed[h] = 37.4f;
            return h;
        }

        @Test
        @DisplayName("a thrusting target sets the error: 0 within 500, 8 within 1000, else 16 (from its engine)")
        void thrustingTarget() {
            Simulation big = sim(FRAME, 60, 20, "__");
            s = big;
            w = s.world;
            o = w.objects;
            p = ready(s, "P", 0, 100, 300);
            q = ready(s, "Q", 0, 500, 300);
            q.status |= Obj.THRUSTING;
            q.dir = 0;
            int close = heat(500 - 9 - 499, 300, q.id);
            int mid = heat(500 - 9 + 999, 300, q.id);
            int wide = heat(500 - 9 + 1000, 300, q.id);
            int edge = heat(500 - 9 - 500, 300, q.id);
            s.missiles.move(close);
            s.missiles.move(mid);
            s.missiles.move(wide);
            s.missiles.move(edge);
            assertAll(
                () -> assertEquals(0, o.counter[close], "499 px from the engine: HEAT_CLOSE_ERROR"),
                () -> assertEquals(8, o.counter[edge], "500 px: HEAT_MID_ERROR"),
                () -> assertEquals(8, o.counter[mid], "999 px: HEAT_MID_ERROR"),
                () -> assertEquals(16, o.counter[wide], "1000 px: HEAT_WIDE_ERROR"),
                () -> assertEquals(q.id, o.info[wide], "keeps its target"));
        }

        @Test
        @DisplayName("a quiet target counts the error up and keeps the target until a timeout")
        void quietTarget() {
            setup("");
            int h = heat(300, 300, q.id);
            o.counter[h] = 120;
            s.missiles.move(h);
            int c1 = o.counter[h];
            int still = o.info[h];
            s.missiles.move(h);
            assertAll(
                () -> assertEquals(121, c1, "count 120 -> 121 (not > HEAT_CLOSE_TIMEOUT + 0 before the increment)"),
                () -> assertEquals(q.id, still, "keeps the target at 121 (no thrusting player to switch to)"),
                () -> assertEquals(122, o.counter[h], "and keeps counting"),
                () -> assertEquals(q.id, o.info[h], "no thrusting player: the old target stays"));
        }

        @Test
        @DisplayName("after a timeout it switches to the thrusting player with the lowest scaled engine distance")
        void search() {
            setup("");
            Player r = ready(s, "R", 0, 400, 500);
            int h = heat(300, 300, q.id);
            o.counter[h] = 120;
            r.status |= Obj.THRUSTING;
            r.dir = 0;
            w.rng.seed(1);
            s.missiles.move(h);
            assertAll(
                () -> assertEquals(r.id, o.info[h], "count 121 > 120 within 500 px: R (thrusting) is the new target"),
                () -> assertEquals(0, o.counter[h], "R's engine is 216 px away: error 0"));
        }

        @Test
        @DisplayName("without a target it searches at once; afterburners scale the distance as C does")
        void afterburners() {
            setup("");
            Player r = ready(s, "R", 0, 300, 400);
            q.initPixels(300, 650);
            q.status |= Obj.THRUSTING;
            r.status |= Obj.THRUSTING;
            q.dir = 32;
            r.dir = 32;
            r.items[Item.AFTERBURNER.ordinal()] = 4;
            r.have |= Has.AFTERBURNER;
            int h = heat(300, 300, -1);
            s.missiles.move(h);
            int withHave = o.info[h];
            r.have &= ~Has.AFTERBURNER;
            int h2 = heat(300, 300, -1);
            s.missiles.move(h2);
            int idle = heat(300, 300, -1);
            q.status &= ~Obj.THRUSTING;
            r.status &= ~Obj.THRUSTING;
            s.missiles.move(idle);
            // R: engine 100 - 9 = 91 px; * (16 - 4)/16 = 68.25; with HAS_AFTERBURNER * 12 = 819. Q: 341 px.
            assertAll(
                () -> assertEquals(q.id, withHave, "R with HAS_AFTERBURNER scales to 819 > Q's 341: Q"),
                () -> assertEquals(r.id, o.info[h2], "R's items alone scale to 68 < 341: R"),
                () -> assertEquals(-1, o.info[idle], "nobody thrusting: no target"),
                () -> assertEquals(8 * 60 + 16 + 1, o.counter[idle], "count = HEAT_WIDE_TIMEOUT + HEAT_WIDE_ERROR + 1"),
                () -> assertEquals(10f, o.vx[idle], "and it does not steer"));
        }

        @Test
        @DisplayName("heat-seekers err by (int)(rfrac * 4 * count) and accelerate by 0.6 * 1.7")
        void error() {
            setup("");
            q.status |= Obj.THRUSTING;
            q.dir = 0;
            q.initPixels(500, 300);
            int h = heat(300, 300, q.id);
            s.missiles.move(h);
            float v = (float) Math.hypot(o.vx[h], o.vy[h]);
            assertAll(
                () -> assertEquals(0, o.counter[h], "close and thrusting: error 0"),
                () -> assertEquals(4, o.missileDir[h], "aim straight ahead: turns (u_byte)4.42 = 4"),
                () -> assertEquals((float) (10 + (float) (0.6 * 1.7)), v, 1e-4, "speed + SMART_SHOT_ACC * HEAT_SPEED_FACT"));
        }
    }

    @Nested
    @DisplayName("move: confused smart missiles")
    class Confused {
        int smart(int counter) {
            int h = obj(w, Obj.SMART_SHOT, p.id, 300, 300, 10, 0, 5, 100);
            o.info[h] = q.id;
            o.newInfo[h] = p.id;
            o.status[h] = Obj.CONFUSED;
            o.counter[h] = counter;
            o.turnSpeed[h] = 2.6f;
            o.maxSpeed[h] = 22;
            return h;
        }

        @Test
        @DisplayName("every 10th frame (or at count 3) a random player is targeted and the count goes down")
        void random() {
            setup("");
            w.frames = 20;
            w.rng.seed(5);
            Rng ref = new Rng(5);
            int pick = w.playerAt((int) (ref.nextFloat() * w.numPlayers)).id;
            int h = smart(2);
            s.missiles.move(h);
            w.frames = 21;
            int at3 = smart(3);
            s.missiles.move(at3);
            int between = smart(2);
            s.missiles.move(between);
            assertAll(
                () -> assertEquals(pick, o.info[h], "frame 20: a random player"),
                () -> assertEquals(1, o.counter[h], "the count goes down"),
                () -> assertEquals(Obj.CONFUSED, o.status[h], "still confused"),
                () -> assertEquals(2, o.counter[at3], "count CONFUSED_TIME updates at once"),
                () -> assertEquals(2, o.counter[between], "frame 21 with count 2: no update"),
                () -> assertEquals(q.id, o.info[between], "the target is kept"));
        }

        @Test
        @DisplayName("at count 0 confusion ends and the ECM target is taken with 50-100% chance by ECM distance")
        void relock() {
            setup("");
            w.frames = 30;
            int center = smart(0);
            o.ecmRange[center] = 0;
            s.missiles.move(center);
            // edge: r = 0 -> lock iff (int)(rfrac * 100) <= 50
            w.rng.seed(8);
            Rng ref = new Rng(8);
            boolean lockEdge = (int) (ref.nextFloat() * 100) <= 50;
            int edge = smart(0);
            o.ecmRange[edge] = (float) Ecm.ECM_DISTANCE;
            s.missiles.move(edge);
            assertAll(
                () -> assertEquals(0, o.status[center], "confusion ends"),
                () -> assertEquals(p.id, o.info[center], "at the ECM centre it always re-locks on new_info"),
                () -> assertEquals(lockEdge ? p.id : q.id, o.info[edge], "at ECM_DISTANCE: 50% by (int)(rfrac*100) <= 50"));
        }
    }

    // ================================================================ edge cases found by mutation testing

    /** A 20x20 (or w x 20) map with walls at the given blocks (C coordinates: y up). */
    static Simulation walledMap(String extra, int width, int... blocks) {
        String[] rows = SimFixture.open(width, 20, "__");
        for (int i = 0; i < blocks.length; i += 2) {
            int row = 19 - blocks[i + 1];
            char[] c = rows[row].toCharArray();
            c[blocks[i]] = 'x';
            rows[row] = new String(c);
        }
        return TestWorlds.sim(FRAME + extra, rows);
    }

    void use(Simulation sm) {
        s = sm;
        w = s.world;
        o = w.objects;
        p = ready(s, "P", 0, 100, 100);
        q = ready(s, "Q", 0, 100, 600);
    }

    /** A smart missile at (x, y) with missile_dir {@code dir} and velocity (vx, vy), locked on Q. */
    int smartAt(double x, double y, int dir, double vx, double vy) {
        int h = obj(w, Obj.SMART_SHOT, p.id, x, y, vx, vy, 5, 100);
        o.info[h] = q.id;
        o.missileDir[h] = dir;
        o.turnSpeed[h] = 2.6f;
        o.maxSpeed[h] = 22;
        return h;
    }

    float speedAfterMove(int h) {
        s.missiles.move(h);
        return (float) Math.hypot(o.vx[h], o.vy[h]);
    }

    /** The first seed from 1 whose first (int)(rfrac * scale) satisfies the test. */
    static int seedWhere(int scale, java.util.function.IntPredicate test) {
        for (int k = 1; ; k++) {
            if (test.test((int) (new Rng(k).nextFloat() * scale))) {
                return k;
            }
        }
    }

    @Nested
    @DisplayName("look-ahead: depth, target distance, vertical and edges")
    class LookAheadEdges {
        @Test
        @DisplayName("four steps deep: a wall at the fifth step is not seen; the wall step decides the target distance")
        void depthAndDistance() {
            use(walledMap("", 20, 15, 10));
            q.initPixels(192 + 400, 367);
            float fifth = speedAfterMove(smartAt(192, 367, 0, 10, 0));
            use(walledMap("", 20, 9, 10));
            q.initPixels(192 + 50, 367);
            float near50 = speedAfterMove(smartAt(192, 367, 0, 10, 0));
            assertAll(
                () -> assertEquals(10.6f, fifth, 1e-4, "SMART_SHOT_LOOK_AH = 4 steps: block 15 is beyond them"),
                () -> assertEquals(8.2f, near50, 1e-4, "wall at step 2: a target 50 px away (> (4-3)*17) still slows it"));
        }

        @Test
        @DisplayName("vertical look-ahead follows vy")
        void vertical() {
            use(walledMap("", 20, 5, 14));
            q.initPixels(192, 100);
            float up = speedAfterMove(smartAt(192, 367, 32, 0, 10));
            assertEquals(8.2f, up, 1e-4, "flying up at 10: steps reach rows 12 and 14; the wall at (5, 14) slows it");
        }

        @Test
        @DisplayName("wrapping maps wrap the look-ahead blocks on all four sides")
        void wrapSides() {
            String wrap = "edgeWrap: yes\n";
            use(walledMap(wrap, 20, 0, 10));
            q.initPixels(400, 367);
            float leftZero = speedAfterMove(smartAt(100, 367, 64, -10, 0));
            use(walledMap(wrap, 20, 19, 10));
            q.initPixels(400, 367);
            float leftNeg = speedAfterMove(smartAt(30, 367, 64, -10, 0));
            use(walledMap(wrap, 20, 5, 0));
            q.initPixels(400, 400);
            float downZero = speedAfterMove(smartAt(192, 100, 96, 0, -10));
            use(walledMap(wrap, 20, 5, 19));
            q.initPixels(400, 400);
            float downNeg = speedAfterMove(smartAt(192, 30, 96, 0, -10));
            use(walledMap(wrap, 20, 5, 0));
            q.initPixels(400, 400);
            float upTop = speedAfterMove(smartAt(192, 630, 32, 0, 10));
            assertAll(
                () -> assertEquals(8.2f, leftZero, 1e-4, "x 100 - 70 = 30: block 0 itself (no wrap) has the wall"),
                () -> assertEquals(8.2f, leftNeg, 1e-4, "x 30 - 70 = -40: block -1 wraps to 19"),
                () -> assertEquals(8.2f, downZero, 1e-4, "y 100 - 70 = 30: block row 0"),
                () -> assertEquals(8.2f, downNeg, 1e-4, "y 30 - 70: row -1 wraps to 19"),
                () -> assertEquals(8.2f, upTop, 1e-4, "y 630 + 70 = 700: row 20 wraps to 0"));
        }

        @Test
        @DisplayName("flat maps: block 0 is inside, block 20 (width/height) ends the look-ahead")
        void flatSides() {
            use(walledMap("", 20, 0, 10));
            q.initPixels(400, 367);
            float left = speedAfterMove(smartAt(52, 367, 64, -10, 0));
            // block (20, 10) would be read as (0, 11) by the row-major grid
            use(walledMap("", 20, 0, 11));
            q.initPixels(400, 367);
            float right = speedAfterMove(smartAt(640, 367, 0, 10, 0));
            use(walledMap("", 20, 5, 0));
            q.initPixels(400, 400);
            float down = speedAfterMove(smartAt(192, 52, 96, 0, -10));
            use(walledMap("", 20));
            q.initPixels(400, 400);
            float up = speedAfterMove(smartAt(192, 650, 32, 0, 10));
            assertAll(
                () -> assertEquals(8.2f, left, 1e-4, "x 52 - 70 = -18 truncates to block 0, a wall"),
                () -> assertEquals(10.6f, right, 1e-4, "x 710: block 20 is outside, no wall"),
                () -> assertEquals(8.2f, down, 1e-4, "y -18: row 0, a wall"),
                () -> assertEquals(10.6f, up, 1e-4, "y 720: row 20 is outside"));
        }
    }

    @Nested
    @DisplayName("neighbour choice: heading and velocity terms")
    class NeighbourMore {
        @Test
        @DisplayName("the neighbour ring starts at the missile's heading octant; the aim uses pos + 2*vy and pos - 2*vx")
        void heading() {
            use(walledMap("", 20, 5, 11));
            q.initPixels(592, 367);
            int h = smartAt(192, 367, 32, 0, 10);
            s.missiles.move(h);
            // i = 32*8/128 + 8 = 10; j=-2 cells (1,1),(1,0),(1,-1) all free, k = 3: neighbour 0 = (1,0).
            // theta = findDir(10*35 - (367 + 2*10), 6*35 - (192 - 0)) = findDir(-37, 18) = 54; 54-32-64 = -42: +2
            assertAll(
                () -> assertEquals(54, (int) XMath.findDir(-37f, 18f), "the aim at (1, 0)"),
                () -> assertEquals(34, o.missileDir[h], "turns +2 from 32"));
        }

        @Test
        @DisplayName("j = -2 with three free cells beats the earlier choices")
        void lastWins() {
            use(walledMap("", 20, 6, 10));
            q.initPixels(400, 600);
            w.rng.seed(seedWhere(2, v -> v == 0));
            int h = smartAt(191, 375, 0, -10, 0);
            s.missiles.move(h);
            // neighbour 6 = (0,-1): findDir(9*35 - 375, 5*35 - (191 + 20)) = findDir(-60, -36) = 75: angle 11, -2
            assertAll(
                () -> assertEquals(75, (int) XMath.findDir(-60f, -36f), "the aim at (0, -1)"),
                () -> assertEquals(126, o.missileDir[h], "turns -2"));
        }
    }

    @Nested
    @DisplayName("turning and leading: sensitive cases")
    class TurnMore {
        int turn(int dir, double qx, double qy, float qvx, float qvy, double vx, double vy) {
            setup("");
            int h = smartAt(350, 350, dir, vx, vy);
            q.initPixels(qx, qy);
            q.vx = qvx;
            q.vy = qvy;
            s.missiles.move(h);
            return o.missileDir[h];
        }

        @Test
        @DisplayName("an aim just clockwise of the heading (below it in RES) turns clockwise")
        void clockwise() {
            setup("");
            int h = smartAt(350, 350, 10, 10 * Trig.tcos(10), 10 * Trig.tsin(10));
            q.initPixels(350 + 200 * Trig.tcos(8), 350 + 200 * Trig.tsin(8));
            int theta = (int) XMath.findDir((float) (q.px - 350), (float) (q.py - 350));
            s.missiles.move(h);
            assertAll(
                () -> assertTrue(theta < 10 && theta > 4, "the aim is a little clockwise of 10"),
                () -> assertEquals(8, o.missileDir[h], "theta + RES - 10 - 64 > 0: turns -2"));
        }

        @Test
        @DisplayName("leads on both axes: small and moderate leads near the 'behind' aim change the turn")
        void leads() {
            assertAll(
                // lead y = 0.1 * 200/10 = 2: findDir(-200, 2) = 63 -> angle -1 -> +1
                () -> assertEquals(1, turn(0, 150, 350, 0, 0.1f, 10, 0), "y lead 2: aim 63, turns 1"),
                // lead y = 40: findDir(-200, 40) = 59 -> angle -5 -> +2
                () -> assertEquals(2, turn(0, 150, 350, 0, 2f, 10, 0), "y lead 40: aim 59, turns 2"),
                // lead x = -2: findDir(-2, -200) = 95 -> 95 - 32 - 64 = -1 -> +1
                () -> assertEquals(33, turn(32, 350, 150, -0.1f, 0, 0, 10), "x lead -2: aim 95, turns 1"),
                // lead x = -40: findDir(-40, -200) = 91 -> -5 -> +2
                () -> assertEquals(34, turn(32, 350, 150, -2f, 0, 0, 10), "x lead -40: aim 91, turns 2"),
                () -> assertEquals(0, turn(0, 150, 350, 0, 0, 10, 0), "no lead: aim 64 straight behind, no turn"));
        }
    }

    @Nested
    @DisplayName("fire: edges")
    class FireEdges {
        @Test
        @DisplayName("rack positions at 0 are inside the world, at width/height outside")
        void rackEdges() {
            setup("");
            int[][] cases = {{15, 300, 64}, {685, 300, 0}, {300, 15, 96}, {300, 685, 32}};
            int[] made = new int[4];
            for (int c = 0; c < 4; c++) {
                p.initPixels(cases[c][0], cases[c][1]);
                p.dir = cases[c][2];
                int before = o.count();
                s.missiles.fire(p, 0, false, p.px, p.py, Obj.TORPEDO, p.dir, 0, -1);
                made[c] = o.count() - before;
            }
            assertAll(
                () -> assertEquals(-15f, p.ship.rackX[0][64], "the rack is 15 px ahead"),
                () -> assertEquals(1, made[0], "x 15 - 15 = 0 is inside"),
                () -> assertEquals(0, made[1], "x 685 + 15 = 700 is outside"),
                () -> assertEquals(1, made[2], "y 0 is inside"),
                () -> assertEquals(0, made[3], "y 700 is outside"));
        }

        @Test
        @DisplayName("racks cycle across salvos: the second three-mini salvo goes rack 1, rack 0, rack 0")
        void secondSalvo() {
            setup("");
            p.ship = new ShipGeom(ShipShape.parseOrDefault(RACK_SHIP));
            lock();
            s.missiles.fire(p, 0, false, p.px, p.py, Obj.SMART_SHOT, 0, Mods.withMini(0, 2), -1);
            s.missiles.fire(p, 0, false, p.px, p.py, Obj.SMART_SHOT, 0, Mods.withMini(0, 2), -1);
            assertAll(
                () -> assertEquals(6, o.count(), "six missiles"),
                () -> assertEquals(Click.fromPixels(300 - 4), o.cy[o.active[3]], "salvo 2 first from rack 1"),
                () -> assertEquals(Click.fromPixels(300 + 4), o.cy[o.active[4]], "then rack 0 (wrapped)"),
                () -> assertEquals(Click.fromPixels(300 + 4), o.cy[o.active[5]], "and rack 0 again"),
                () -> assertEquals(0, p.missileRack, "the next salvo starts at rack 0"));
        }

        @Test
        @DisplayName("a smart missile needs the LOCK_PLAYER bit even with a valid lock id; exactly nukeMinSmarts suffice")
        void lockBitAndNukeMinimum() {
            setup("");
            lock();
            p.lockTagged = Player.LOCK_VISIBLE;
            s.missiles.fire(p, 0, false, p.px, p.py, Obj.SMART_SHOT, 0, 0, -1);
            int noBit = o.count();
            p.items[MISSILE] = 3;
            s.missiles.fire(p, 0, false, p.px, p.py, Obj.TORPEDO, 0, NUKE, -1);
            assertAll(
                () -> assertEquals(0, noBit, "LOCK_VISIBLE alone is no lock"),
                () -> assertEquals(1, o.count(), "3 missiles fire a nuke with nukeMinSmarts 3"),
                () -> assertEquals(0, p.items[MISSILE], "using all three"));
        }

        @Test
        @DisplayName("mini torpedoes at dir 16: y pushes, decays and the y recoil from every mini")
        void miniY() {
            setup("");
            p.dir = 16;
            p.vy = 1;
            float m = p.mass;
            s.missiles.fire(p, 0, false, p.px, p.py, Obj.TORPEDO, 16, Mods.withSpread(Mods.withMini(0, 1), 1), -1);
            // on_this_rack 2: angle (1 - 2r)/3 * 32 -> (int) 10.67, -10.67 = 10, -10; spread 1 halves the push
            float mvy0 = 20 * Trig.tsin(26) / 2.0f;
            float mvy1 = 20 * Trig.tsin(6) / 2.0f;
            float vy = 1;
            for (int i = 0; i < 2; i++) {
                int h = o.active[i];
                vy -= ((o.vy[h] - vy) * o.mass[h]) / m;
            }
            float recoil = vy;
            assertAll(
                () -> assertEquals((float) (mvy0 + 1.0 + Trig.tsin(16) * 21f), o.vy[o.active[0]], "first: push at 26 + ship + speed"),
                () -> assertEquals((float) (mvy1 + 1.0 + Trig.tsin(16) * 21f), o.vy[o.active[1]], "second: push at 6"),
                () -> assertEquals(-mvy1 / 6, o.accY[o.active[1]], "the y push decays over 6 frames"),
                () -> assertEquals(recoil, p.vy, "y recoil from both minis"));
        }
    }

    @Nested
    @DisplayName("move: torpedo edges")
    class TorpedoEdges {
        @Test
        @DisplayName("a nuke accelerates for frames 0..119 at its heading's sine and cosine")
        void nukeTime() {
            setup("");
            int a = obj(w, Obj.TORPEDO, p.id, 100, 100, 0, 0, 15, 100);
            o.mods[a] = NUKE;
            o.info[a] = 119;
            o.missileDir[a] = 16;
            s.missiles.move(a);
            float vx1 = o.vx[a];
            float vy1 = o.vy[a];
            int i1 = o.info[a];
            s.missiles.move(a);
            float nuke = (float) (5 * (18.0 * 22.0 / (60 * 120)));
            assertAll(
                () -> assertEquals(nuke * Trig.tcos(16), vx1, "frame 119: x += NUKE_ACC * tcos(16)"),
                () -> assertEquals(nuke * Trig.tsin(16), vy1, "and y += NUKE_ACC * tsin(16)"),
                () -> assertEquals(120, i1, "the counter goes up"),
                () -> assertEquals(vy1, o.vy[a], "frame 120 (NUKE_SPEED_TIME) does not accelerate"),
                () -> assertEquals(121, o.info[a], "the counter keeps going up"));
        }
    }

    @Nested
    @DisplayName("move: heat-seeker search and timeouts")
    class HeatSearch {
        Player r;

        void big() {
            s = sim(FRAME, 60, 20, "___");
            w = s.world;
            o = w.objects;
            p = ready(s, "P", 0, 100, 100);
            q = ready(s, "Q", 0, 100, 600);
            r = ready(s, "R", 0, 100, 650);
            for (Player x : new Player[] {p, q, r}) {
                x.dir = 0;
                x.status &= ~Obj.THRUSTING;
            }
        }

        int heat(double x, double y, int target, int counter) {
            int h = obj(w, Obj.HEAT_SHOT, Obj.NO_ID, x, y, 10, 0, 5, 100);
            o.info[h] = target;
            o.counter[h] = counter;
            o.turnSpeed[h] = 4.42f;
            o.maxSpeed[h] = 37.4f;
            return h;
        }

        /** Error counter after a fresh search (no target) with only R thrusting at (rx, ry), heading rdir. */
        int searchError(double rx, double ry, int rdir) {
            r.initPixels(rx, ry);
            r.dir = rdir;
            r.status |= Obj.THRUSTING;
            int h = heat(300, 300, -1, 0);
            s.missiles.move(h);
            r.status &= ~Obj.THRUSTING;
            assertEquals(r.id, o.info[h], "the search finds R");
            return o.counter[h];
        }

        @Test
        @DisplayName("the new target's error: 0 below 500 px from its engine, 8 below 1000, else 16")
        void errorClasses() {
            big();
            assertAll(
                () -> assertEquals(0, searchError(808, 300, 0), "engine 799 - 300 = 499 px: 0"),
                () -> assertEquals(8, searchError(809, 300, 0), "500 px: 8"),
                () -> assertEquals(8, searchError(1308, 300, 0), "999 px: 8"),
                () -> assertEquals(16, searchError(1309, 300, 0), "1000 px: 16"),
                () -> assertEquals(0, searchError(300, 808, 32), "heading up the engine is 9 px below: 499 px"));
        }

        @Test
        @DisplayName("afterburners scale the engine distance by (16 - n)/16, and by (16 - n) again with HAS_AFTERBURNER")
        void afterburnerScaling() {
            big();
            r.items[Item.AFTERBURNER.ordinal()] = 4;
            int itemsOnly = searchError(909, 300, 0);
            r.have |= Has.AFTERBURNER;
            int withHave = searchError(409, 300, 0);
            assertAll(
                () -> assertEquals(0, itemsOnly, "600 * 12/16 = 450: error 0"),
                () -> assertEquals(8, withHave, "100 * 12/16 * 12 = 900: error 8"));
        }

        /** Q is the quiet target at engine distance d (Q heading right, engine 9 px behind it); R thrusts 100 px away. */
        int quiet(int d, int counter) {
            q.dir = 0;
            q.initPixels(300 + d + 9, 300);
            r.initPixels(300 + 9, 400);
            r.status |= Obj.THRUSTING;
            int h = heat(300, 300, q.id, counter);
            s.missiles.move(h);
            return o.info[h];
        }

        /** As {@link #quiet} with Q heading up, d px above the missile's engine-relative position. */
        int quietUp(int d, int counter) {
            q.dir = 32;
            q.initPixels(300, 300 + d + 9);
            r.initPixels(400, 300 + 9);
            r.status |= Obj.THRUSTING;
            int h = heat(300, 300, q.id, counter);
            s.missiles.move(h);
            q.dir = 0;
            return o.info[h];
        }

        @Test
        @DisplayName("a quiet target is kept until count > 2*FPS (close), 4*FPS + 8 (mid), 8*FPS + 16 (wide)")
        void timeouts() {
            big();
            assertAll(
                () -> assertEquals(q.id, quiet(499, 119), "close: count 120 keeps the target"),
                () -> assertEquals(r.id, quiet(499, 120), "close: count 121 switches to the thrusting R"),
                () -> assertEquals(q.id, quiet(500, 120), "500 px is mid range: count 121 keeps"),
                () -> assertEquals(r.id, quietUp(499, 120), "Q heading up, engine 9 px below: 499 px is close"),
                () -> assertEquals(q.id, quiet(700, 247), "mid: count 248 keeps"),
                () -> assertEquals(r.id, quiet(700, 248), "mid: count 249 switches"),
                () -> assertEquals(q.id, quiet(1200, 495), "wide: count 496 keeps"),
                () -> assertEquals(r.id, quiet(1200, 496), "wide: count 497 switches"));
        }

        @Test
        @DisplayName("the search reaches HEAT_RANGE * (count / (2*FPS)) (integer division)")
        void searchRange() {
            big();
            q.initPixels(300 + 499 + 9, 300);
            r.initPixels(300 + 609, 300);
            r.status |= Obj.THRUSTING;
            int h = heat(300, 300, q.id, 120);
            s.missiles.move(h);
            r.initPixels(300 + 509, 300);
            int exact = heat(300, 300, q.id, 120);
            s.missiles.move(exact);
            assertAll(
                () -> assertEquals(q.id, o.info[h], "count 121: range 500 * 1; R at 600 px is not found"),
                () -> assertEquals(121, o.counter[h], "the error keeps counting"),
                () -> assertEquals(q.id, o.info[exact], "R exactly 500 px away is not closer than the range"));
        }

        @Test
        @DisplayName("the aim errs by (int)(rfrac * 4 * count) on each axis")
        void aimError() {
            big();
            q.initPixels(800, 300);
            q.status |= Obj.THRUSTING;
            int seed = 1;
            while (true) {
                Rng t = new Rng(seed);
                t.nextFloat();
                if (t.nextFloat() > 0.6f) {
                    break;
                }
                seed++;
            }
            Rng ref = new Rng(seed);
            int xd = (int) (ref.nextFloat() * 4 * 16);
            int yd = (int) (ref.nextFloat() * 4 * 16);
            int theta = (int) XMath.findDir((float) (800 + xd - 1900), (float) yd);
            w.rng.seed(seed);
            int h = heat(1900, 300, q.id, 0);
            s.missiles.move(h);
            int angle = theta - 64;
            int expected = angle < 0 ? Math.min(-angle, 4) : Trig.mod(-Math.min(angle, 4));
            assertAll(
                () -> assertEquals(16, o.counter[h], "the thrusting target 1109 px from its engine: error 16"),
                () -> assertTrue(theta <= 62, "the y error of at least 38 px moves the aim off 'behind'"),
                () -> assertEquals(expected, o.missileDir[h], "turns toward the erring aim"));
        }
    }

    @Nested
    @DisplayName("move: confusion edges")
    class ConfusedMore {
        int smart(int counter, int status) {
            int h = obj(w, Obj.SMART_SHOT, p.id, 300, 300, 10, 0, 5, 100);
            o.info[h] = q.id;
            o.newInfo[h] = p.id;
            o.status[h] = status;
            o.counter[h] = counter;
            o.turnSpeed[h] = 2.6f;
            o.maxSpeed[h] = 22;
            return h;
        }

        @Test
        @DisplayName("an unconfused missile ignores the confusion clock")
        void unconfused() {
            setup("");
            w.frames = 20;
            int h = smart(0, 0);
            s.missiles.move(h);
            assertAll(
                () -> assertEquals(q.id, o.info[h], "keeps its target at frame 20 with count 0"),
                () -> assertEquals(0, o.counter[h], "count unchanged"));
        }

        @Test
        @DisplayName("the random target is Players[(int)(rfrac * NumPlayers)]")
        void randomIndex() {
            setup("");
            w.frames = 10;
            int seed = seedWhere(2, v -> v == 1);
            w.rng.seed(seed);
            int h = smart(2, Obj.CONFUSED);
            s.missiles.move(h);
            assertEquals(w.playerAt(1).id, o.info[h], "rfrac * 2 in [1, 2): the second player");
        }

        @Test
        @DisplayName("at half the ECM distance the re-lock chance is 75%: (int)(rfrac*100) <= 75")
        void halfway() {
            setup("");
            w.frames = 30;
            int[] got = new int[3];
            int[][] ranges = {{75, 75}, {51, 74}, {76, 99}};
            for (int c = 0; c < 3; c++) {
                int lo = ranges[c][0];
                int hi = ranges[c][1];
                w.rng.seed(seedWhere(100, v -> v >= lo && v <= hi));
                int h = smart(0, Obj.CONFUSED);
                o.ecmRange[h] = (float) (Ecm.ECM_DISTANCE / 2);
                s.missiles.move(h);
                got[c] = o.info[h];
            }
            assertAll(
                () -> assertEquals(p.id, got[0], "75 re-locks"),
                () -> assertEquals(p.id, got[1], "51..74 re-locks"),
                () -> assertEquals(q.id, got[2], "76..99 keeps the old target"));
        }
    }
}
