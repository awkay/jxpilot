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
import com.fulcrologic.jxpilot.common.sim.Obj;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.common.sim.ShipGeom;
import com.fulcrologic.jxpilot.server.world.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static com.fulcrologic.jxpilot.server.sim.ItemFx.reseed;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.FRAME;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.obj;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.ready;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.sim;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tanks: player.c Player_add_tank/remove_tank/init_fuel, ship.c Update_tanks/Tank_handle_detach, robot.c Tank_play. */
@DisplayName("Tanks (player.c, ship.c)")
class TanksTest {
    /** C TANK_CAP(0) = MAX_PLAYER_FUEL = 2600 << 8. */
    static final double CAP0 = 665600;
    /** C TANK_CAP(n > 0) = MAX_PLAYER_FUEL / 3 (integer division). */
    static final double CAP = 221866;
    /** Initial fuel 1000 << 8. */
    static final double FUEL0 = 256000;
    static final int TANK = Item.TANK.ordinal();

    Simulation s = sim(FRAME, 20, 20, "__");
    World w = s.world;
    Tanks t = s.tanks;
    Player p = ready(s, "A", 0, 300, 300);

    /** Give p extra tanks with the fuel amounts (main tank and sum unchanged by this). */
    void tanks(double... fuel) {
        for (double f : fuel) {
            t.add(p, f);
        }
    }

    List<Double> tankList() {
        Double[] d = new Double[p.numTanks + 1];
        for (int i = 0; i <= p.numTanks; i++) {
            d[i] = p.tankFuel[i];
        }
        return List.of(d);
    }

    @Nested
    @DisplayName("macros")
    class Macros {
        @Test
        @DisplayName("TANK_CAP, TANK_FUEL and TANK_MASS")
        void macros() {
            Rng m = reseed(w, 5);
            long f1 = (221866L * (5 + (m.nextInt() & 3))) / 32;
            long f0 = (665600L * (5 + (m.nextInt() & 3))) / 32;
            long tf1 = t.tankFuel(1);
            long tf0 = t.tankFuel(0);
            assertAll(
                () -> assertEquals(CAP0, Tanks.cap(0), "the main tank holds MAX_PLAYER_FUEL"),
                () -> assertEquals(List.of(CAP, CAP, CAP), List.of(Tanks.cap(1), Tanks.cap(4), Tanks.cap(8)),
                    "extra tanks hold MAX_PLAYER_FUEL / 3"),
                () -> assertEquals(f1, tf1, "TANK_FUEL(1) = (cap * (5 + (rand & 3))) / 32"),
                () -> assertEquals(f0, tf0, "TANK_FUEL(0) uses the main tank's capacity"),
                () -> assertEquals(2f, t.tankMass(), "TANK_MASS = ShipMass / 10"));
        }
    }

    @Nested
    @DisplayName("add (Player_add_tank)")
    class Add {
        @Test
        @DisplayName("a tank adds its fuel (limited to [0, cap]) to the sum, its capacity to the maximum, TANK_MASS")
        void adds() {
            double max0 = p.fuelMax;
            float m0 = p.emptyMass;
            tanks(100000, 300000, -5, CAP);
            assertAll(
                () -> assertEquals(4, p.numTanks, "four tanks"),
                () -> assertEquals(List.of(FUEL0, 100000.0, CAP, 0.0, CAP), tankList(),
                    "fuel as given, over the capacity limited to it, negative to 0, exactly the capacity kept"),
                () -> assertEquals(FUEL0 + 100000 + 2 * CAP, p.fuel, "the sum grows by what went in"),
                () -> assertEquals(max0 + 4 * CAP, p.fuelMax, "the maximum by the capacities"),
                () -> assertEquals(m0 + 8f, p.emptyMass, "TANK_MASS 2 each"),
                () -> assertEquals(4, p.items[TANK], "the TANK count follows"));
        }

        @Test
        @DisplayName("at MAX_TANKS nothing is added")
        void full() {
            for (int i = 0; i < 8; i++) {
                t.add(p, 10);
            }
            double sum = p.fuel;
            t.add(p, 10);
            assertAll(
                () -> assertEquals(8, p.numTanks, "still 8"),
                () -> assertEquals(sum, p.fuel, "no fuel added"));
        }
    }

    @Nested
    @DisplayName("remove (Player_remove_tank)")
    class Remove {
        @Test
        @DisplayName("tank `which` and its fuel go, later tanks move down, the current tank stays")
        void removes() {
            tanks(10, 20, 30);
            p.currentTank = 1;
            double max = p.fuelMax;
            float m = p.emptyMass;
            t.remove(p, 2);
            assertAll(
                () -> assertEquals(List.of(FUEL0, 10.0, 30.0), tankList(), "tank 3 moved down"),
                () -> assertEquals(FUEL0 + 40, p.fuel, "its 20 is gone from the sum"),
                () -> assertEquals(max - CAP, p.fuelMax, "and its capacity from the maximum"),
                () -> assertEquals(m - 2f, p.emptyMass, "and its mass"),
                () -> assertEquals(1, p.currentTank, "the current tank is kept"),
                () -> assertEquals(2, p.items[TANK], "the TANK count follows"));
        }

        @Test
        @DisplayName("which is limited to 1..numTanks")
        void limited() {
            tanks(10, 20, 30);
            t.remove(p, 0);
            List<Double> afterLow = tankList();
            t.remove(p, 9);
            assertAll(
                () -> assertEquals(List.of(FUEL0, 20.0, 30.0), afterLow, "0 removes tank 1"),
                () -> assertEquals(List.of(FUEL0, 20.0), tankList(), "9 removes the last tank"));
        }

        @Test
        @DisplayName("a current tank past the new end becomes the main tank, and (as C) nothing moves down")
        void currentPastEnd() {
            tanks(10, 20);
            p.currentTank = 2;
            t.remove(p, 1);
            assertAll(
                () -> assertEquals(0, p.currentTank, "current reset to the main tank"),
                () -> assertEquals(List.of(FUEL0, 10.0), tankList(), "tank 2 did not move into slot 1 (C quirk)"),
                () -> assertEquals(FUEL0 + 20, p.fuel, "tank 1's 10 left the sum"));
        }

        @Test
        @DisplayName("without tanks nothing happens")
        void none() {
            double max = p.fuelMax;
            t.remove(p, 1);
            assertAll(
                () -> assertEquals(0, p.numTanks, "no tanks"),
                () -> assertEquals(FUEL0, p.fuel, "fuel kept"),
                () -> assertEquals(max, p.fuelMax, "maximum kept"));
        }
    }

    @Nested
    @DisplayName("initFuel (Player_init_fuel)")
    class InitFuel {
        @Test
        @DisplayName("the main tank fills first, then initialTanks extra tanks share the rest")
        void initial() {
            w.items.initial[TANK] = 2;
            p.currentTank = 1;
            t.initFuel(p, CAP0 + 300000);
            assertAll(
                () -> assertEquals(List.of(CAP0, CAP, 300000 - CAP), tankList(), "main full, then 221866, then 78134"),
                () -> assertEquals(CAP0 + 300000, p.fuel, "all of it"),
                () -> assertEquals(CAP0 + 2 * CAP, p.fuelMax, "three capacities"),
                () -> assertEquals(0, p.currentTank, "the main tank is current"),
                () -> assertEquals(24f, p.emptyMass, "ShipMass + 2 TANK_MASS"),
                () -> assertEquals(2, p.items[TANK], "two tanks"));
        }

        @Test
        @DisplayName("less fuel than the main tank holds leaves extra tanks empty")
        void small() {
            w.items.initial[TANK] = 1;
            t.initFuel(p, 1000);
            assertAll(
                () -> assertEquals(List.of(1000.0, 0.0), tankList(), "main tank 1000, tank 1 empty"),
                () -> assertEquals(1000.0, p.fuel, "sum 1000"));
        }
    }

    @Nested
    @DisplayName("update (Update_tanks)")
    class Update {
        /** Set the tanks directly; the sum is {@code sum}. */
        void set(int current, double sum, double... tanks) {
            p.numTanks = tanks.length - 1;
            p.currentTank = current;
            System.arraycopy(tanks, 0, p.tankFuel, 0, tanks.length);
            p.fuel = sum;
            p.fuelMax = CAP0 + p.numTanks * CAP;
        }

        @Test
        @DisplayName("without extra tanks the main tank holds the sum")
        void noTanks() {
            p.tankFuel[0] = 5;
            t.update(p);
            assertEquals(FUEL0, p.tankFuel[0], "tank[0] = sum");
        }

        @Test
        @DisplayName("a non-current extra tank above low_level + REFUEL_RATE gives REFUEL_RATE to the one below")
        void flows() {
            // low = 51000/2 - 1 = 24499, limited to TANK_REFILL_LIMIT 11200
            set(0, 51000, 1000, 50000);
            t.update(p);
            List<Double> moved = tankList();
            set(1, 51000, 1000, 50000);
            t.update(p);
            assertAll(
                () -> assertEquals(List.of(2280.0, 48720.0), moved, "1280 flows to the main tank"),
                () -> assertEquals(List.of(1000.0, 50000.0), tankList(), "the current tank does not give"));
        }

        @Test
        @DisplayName("no flow into a tank with less than REFUEL_RATE room")
        void noRoom() {
            set(0, CAP0 + 50000, CAP0 - 1279, 50000);
            t.update(p);
            List<Double> full = tankList();
            set(0, CAP0 + 50000, CAP0 - 1280, 50000);
            t.update(p);
            assertAll(
                () -> assertEquals(List.of(CAP0 - 1279, 50000.0), full, "1279 room is not enough"),
                () -> assertEquals(List.of(CAP0, 50000.0 - 1280), tankList(), "exactly 1280 room takes the flow"));
        }

        @Test
        @DisplayName("an extra tank below low_level takes REFUEL_RATE from the one below")
        void refills() {
            // low = 5100/2 - 1 = 2549
            set(0, 5100, 5000, 100);
            t.update(p);
            assertEquals(List.of(3720.0, 1380.0), tankList(), "tank 1 takes 1280 from the main tank");
        }

        @Test
        @DisplayName("low_level is limited below by 0 and above by TANK_REFILL_LIMIT")
        void lowLimits() {
            // sum 0: low = -1 -> 0, so 1279 < 0 + 1280 does not flow (with -1 it would)
            set(0, 0, 0, 1279);
            t.update(p);
            List<Double> zero = tankList();
            // sum 51000: low 24499 -> 11200; 12000 is neither >= 12480 nor < 11200
            set(0, 51000, 39000, 12000);
            t.update(p);
            List<Double> capped = tankList();
            // at exactly low + REFUEL_RATE it flows; just below low it refills
            set(0, 51000, 38520, 12480);
            t.update(p);
            List<Double> atFlow = tankList();
            set(0, 51000, 39801, 11199);
            t.update(p);
            assertAll(
                () -> assertEquals(List.of(0.0, 1279.0), zero, "low 0: nothing moves"),
                () -> assertEquals(List.of(39000.0, 12000.0), capped, "low capped at 11200: nothing moves"),
                () -> assertEquals(List.of(39800.0, 11200.0), atFlow, "12480 >= 11200 + 1280 gives"),
                () -> assertEquals(List.of(38521.0, 12479.0), tankList(), "11199 < 11200 takes"));
        }

        @Test
        @DisplayName("low_level = sum / (tanks + 1) - 1: a tank at low_level or one above it neither gives nor takes")
        void lowLevel() {
            // sum 5100, one extra tank: low = 2549
            set(0, 5100, 2550, 2550);
            t.update(p);
            List<Double> above = tankList();
            set(0, 5100, 2551, 2549);
            t.update(p);
            assertAll(
                () -> assertEquals(List.of(2550.0, 2550.0), above, "2550 is not below 2549 (nor 1280 above it)"),
                () -> assertEquals(List.of(2551.0, 2549.0), tankList(), "2549 is not below 2549"));
        }

        @Test
        @DisplayName("over- and underflows are carried to the next tank down")
        void carries() {
            set(1, 0, 1000, CAP + 500);
            t.update(p);
            List<Double> over = tankList();
            set(1, 0, 1000, -300);
            t.update(p);
            assertAll(
                () -> assertEquals(List.of(1500.0, CAP), over, "500 over the cap goes to the main tank"),
                () -> assertEquals(List.of(700.0, 0.0), tankList(), "300 under zero is taken from the main tank"));
        }

        @Test
        @DisplayName("a main-tank overflow wraps round to the last tank")
        void wraps() {
            set(1, CAP0 + 20400, CAP0 + 400, 20000);
            t.update(p);
            assertEquals(List.of(CAP0, 20400.0), tankList(), "the 400 over goes to tank 1");
        }

        @Test
        @DisplayName("when exactly MAX_TANKS*4 steps end the pass, the sum is redistributed from the main tank up")
        void redistributes() {
            // found by search: 32 steps end with t < 0, so C's !check is true (ship.c:359)
            set(2, 696873, -15360, -15360, 237226, 224426);
            t.update(p);
            assertAll(
                () -> assertEquals(List.of(CAP0, 696873 - CAP0, 0.0, 0.0), tankList(), "main full, rest in tank 1"),
                () -> assertEquals(696873.0, p.fuel, "the sum is kept"),
                () -> assertEquals(CAP0 + 3 * CAP, p.fuelMax, "the maximum is recomputed"));
        }

        @Test
        @DisplayName("a never-ending overflow runs out of steps without redistribution (C's check-- ends at -1)")
        void endless() {
            set(1, CAP0 + CAP + 400, CAP0 + 400, CAP);
            t.update(p);
            assertAll(
                () -> assertEquals(List.of(CAP0, CAP), tankList(), "both tanks full, the 400 is carried away"),
                () -> assertEquals(CAP0 + CAP + 400, p.fuel, "the sum is not touched (no redistribution)"));
        }
    }

    @Nested
    @DisplayName("select (KEY_TANK_NEXT / KEY_TANK_PREV)")
    class Select {
        @Test
        @DisplayName("steps through 0..numTanks, wrapping both ways; without tanks nothing happens")
        void steps() {
            t.select(p, true);
            int none = p.currentTank;
            tanks(1, 1);
            t.select(p, true);
            int one = p.currentTank;
            t.select(p, true);
            int two = p.currentTank;
            t.select(p, true);
            int wrapUp = p.currentTank;
            t.select(p, false);
            int wrapDown = p.currentTank;
            t.select(p, false);
            int back = p.currentTank;
            t.select(p, false);
            assertAll(
                () -> assertEquals(0, none, "no tanks: stays 0"),
                () -> assertEquals(List.of(1, 2, 0, 2, 1, 0), List.of(one, two, wrapUp, wrapDown, back, p.currentTank),
                    "next 1, 2, wrap to 0; prev wraps to 2, then 1, then 0"));
        }
    }

    @Nested
    @DisplayName("detach (Tank_handle_detach)")
    class Detach {
        Player other = ready(s, "B", 0, 600, 600);

        void prepare() {
            tanks(100000, 50000);
            p.vx = 1.5f;
            p.vy = -2.5f;
            p.accX = 0.25f;
            p.accY = -0.75f;
            p.dir = 40;
            p.floatDir = 40.5f;
            p.turnSpeed = 11;
            p.turnResistance = 0.5f;
            p.turnVel = 0.125f;
            p.oldTurnVel = 0.0625f;
            p.turnAcc = 0.375f;
            p.velocity = 3;
            p.power = 9;
            p.score = 100;
            p.homeBase = 1;
            p.team = 0;
            p.have = w.params.defHave & ~Has.COMPASS;
        }

        Player tankOf(Player owner) {
            for (int k = 0; k < w.numPlayers; k++) {
                Player q = w.playerAt(k);
                if (q.tank && q.lockPlId == owner.id) {
                    return q;
                }
            }
            return null;
        }

        @Test
        @DisplayName("the current-or-last extra tank leaves as pseudo player NAME's tank with the ship's motion")
        void detaches() {
            prepare();
            int heat = obj(w, Obj.HEAT_SHOT, other.id, 50, 50, 0, 0, 1, 100);
            w.objects.info[heat] = p.id;
            int heatOther = obj(w, Obj.HEAT_SHOT, p.id, 50, 50, 0, 0, 1, 100);
            w.objects.info[heatOther] = other.id;
            long seq = w.events.seq();
            int n0 = w.numPlayers;
            t.detach(p);
            Player d = tankOf(p);
            assertTrue(d != null, "a tank player exists");
            // Update_tanks first: tank 2 gives 1280 to tank 1, which gives 1280 to the main tank
            assertAll(
                () -> assertEquals(n0 + 1, w.numPlayers, "one more player"),
                () -> assertEquals("A's tank", d.name.toString(), "named after the owner"),
                () -> assertEquals('T', d.mychar, "shown as T"),
                () -> assertEquals(List.of(0, 1, 100f - 500), List.of(d.team, d.homeBase, d.score),
                    "the owner's team and home, score - tankScoreDecrement"),
                () -> assertEquals(p.pseudoTeam, d.pseudoTeam, "the owner's pseudo team"),
                () -> assertEquals(p.id, d.lockPlId, "the owner is remembered in the lock"),
                () -> assertEquals(List.of(48720.0, 48720.0, CAP, 0, 0),
                    List.of(d.fuel, d.tankFuel[0], d.fuelMax, d.numTanks, d.currentTank),
                    "the tank's fuel after Update_tanks, its capacity as maximum, no tanks of its own"),
                () -> assertEquals(20f, d.emptyMass, "empty mass ShipMass"),
                () -> assertEquals((float) (20 + 48720 * 0.005 / 256), d.mass, "mass ShipMass + FUEL_MASS"),
                () -> assertEquals(6.3f, d.power, "power * 0.7 in double, rounded to float (9 -> 6.3, not 6.2999997)"),
                () -> assertEquals(List.of(p.px, p.py, p.px, p.py), List.of(d.px, d.py, d.prevX, d.prevY),
                    "at the ship's position"),
                () -> assertEquals(List.of(1.5f, -2.5f, 0.25f, -0.75f, 40.5f, 11f, 0.5f, 0.125f, 0.0625f, 0.375f, 3f),
                    List.of(d.vx, d.vy, d.accX, d.accY, d.floatDir, d.turnSpeed, d.turnResistance, d.turnVel,
                        d.oldTurnVel, d.turnAcc, d.velocity), "with the ship's motion"),
                () -> assertEquals(40, d.dir, "and heading"),
                () -> assertEquals(Obj.PLAYING | Obj.GRAVITY | Obj.THRUSTING, d.status, "playing, falling, thrusting"),
                () -> assertEquals(w.params.defHave, d.have, "default abilities"),
                () -> assertEquals(Has.SHIELD, d.used, "(DEF_USED & ~USED_KILL & owner's have) | SHIELD: no compass"),
                () -> assertTrue(d.updateVisibility, "visibility is recomputed"),
                () -> assertTrue(d.scoreDirty, "a SCORE goes out"),
                () -> assertEquals(List.of(FUEL0 + 1280, 100000.0), tankList(), "the owner lost tank 2"),
                () -> assertEquals(FUEL0 + 100000 + 1280, p.fuel, "and its fuel (48720 after the flow)"),
                () -> assertEquals(n0, w.objects.info[heat], "heat-seekers on the owner chase the tank's index"),
                () -> assertEquals(other.id, w.objects.info[heatOther], "others keep their target"),
                () -> assertTrue(ShipFx.events(w, seq).contains("JOIN " + d.id + " " + Msg.PlayerJoin.FLAG_TANK + " 0"),
                    "JOIN with FLAG_TANK"));
        }

        @Test
        @DisplayName("a current extra tank is the one detached; shield time 30*FPS without shields; tank ship shape")
        void currentTank() {
            Simulation s2 = TestWorlds.sim(FRAME + "allowShields: no\nallowShipShapes: yes\n",
                SimFixture.open(20, 20, "_"));
            Player q = ready(s2, "Q", 0, 300, 300);
            s2.tanks.add(q, 100000);
            s2.tanks.add(q, 50000);
            q.currentTank = 1;
            s2.tanks.detach(q);
            Player d = null;
            for (int k = 0; k < s2.world.numPlayers; k++) {
                if (s2.world.playerAt(k).tank) {
                    d = s2.world.playerAt(k);
                }
            }
            Player dd = d;
            assertAll(
                // Update_tanks: tank 2 gives 1280 to tank 1, which is current and keeps it
                () -> assertEquals(101280.0, dd.fuel, "the current tank 1 left, with the 1280 it just got"),
                () -> assertEquals(List.of(1, 48720.0), List.of(q.numTanks, q.tankFuel[1]), "tank 2 moved down"),
                () -> assertEquals(30 * 60, dd.shieldTime, "30 s of shield"),
                () -> assertEquals(Has.SHIELD, dd.have & Has.SHIELD, "shields granted"),
                () -> assertNotSame(ShipGeom.defaultShip(), dd.ship, "the tank ship shape"),
                () -> assertTrue(dd.ship != null, "parsed from tankShipShape"));
        }

        @Test
        @DisplayName("with shields allowed there is no shield timer; the tank wears Ships.tankShipShape, which without"
            + " ship shapes is defaultShipShape (C ship.c:416-418, Init_player)")
        void shieldsAllowed() {
            prepare();
            t.detach(p);
            Player d = tankOf(p);
            Simulation noShapes = sim(FRAME + "allowShipShapes: no\n", 20, 20, "__");
            assertAll(
                () -> assertEquals(0, d.shieldTime, "no shield time"),
                () -> assertSame(s.ships.tankShipShape, d.ship, "the one parsed tank ship"),
                () -> assertSame(noShapes.ships.defaultShipShape, noShapes.ships.tankShipShape, "without ship shapes"));
        }

        @Test
        @DisplayName("a reused slot is reset: Init_player, a fresh name and fresh visibility times")
        void reusedSlot() {
            prepare();
            t.detach(p);
            Player d1 = tankOf(p);
            d1.kills = 5;
            d1.items[Item.ECM.ordinal()] = 3;
            other.visLastChange[d1.ind] = 99;
            d1.visLastChange[other.ind] = 98;
            w.removePlayer(d1);
            t.detach(p);
            Player d2 = tankOf(p);
            assertAll(
                () -> assertSame(d1, d2, "the same slot is used again"),
                () -> assertEquals("A's tank", d2.name.toString(), "the name is rebuilt, not appended"),
                () -> assertEquals(0, d2.kills, "kills reset by Init_player"),
                () -> assertEquals(0, d2.items[Item.ECM.ordinal()], "items reset by Init_player"),
                () -> assertEquals(0L, other.visLastChange[d2.ind], "others' visibility time of the tank reset"),
                () -> assertEquals(0L, d2.visLastChange[other.ind], "the tank's visibility times reset"));
        }

        @Test
        @DisplayName("the tank uses only the default-used abilities its owner has, plus shields")
        void usedFromOwner() {
            prepare();
            p.have = (w.params.defHave | Has.MIRROR) & ~(Has.SHIELD | Has.COMPASS);
            t.detach(p);
            assertEquals(Has.SHIELD, tankOf(p).used, "(DEF_USED & ~USED_KILL & have) = 0, | SHIELD");
        }

        @Test
        @DisplayName("names are cut to MAX_CHARS - 1 characters")
        void longName() {
            prepare();
            p.name.setLength(0);
            p.name.append("N".repeat(76));
            t.detach(p);
            assertEquals("N".repeat(76) + "'s ", tankOf(p).name.toString(), "76 + 3 of \"'s tank\" = 79");
        }

        @Test
        @DisplayName("nothing while phased, without tanks, or without a free pseudo player slot")
        void refused() {
            prepare();
            p.used |= Has.PHASING_DEVICE;
            int n0 = w.numPlayers;
            t.detach(p);
            int phased = w.numPlayers;
            p.used &= ~Has.PHASING_DEVICE;
            for (Player q : w.players) {
                if (!q.inUse) {
                    q.inUse = true;
                    q.tank = true;
                }
            }
            t.detach(p);
            int noSlot = w.numPlayers;
            Simulation s2 = open();
            Player q = ready(s2, "Q", 0, 300, 300);
            s2.tanks.detach(q);
            assertAll(
                () -> assertEquals(n0, phased, "phased: no tank"),
                () -> assertEquals(n0, noSlot, "no slot: no tank"),
                () -> assertEquals(2, p.numTanks, "the owner keeps its tanks"),
                () -> assertEquals(1, s2.world.numPlayers, "no tanks: nothing"));
        }

        Simulation open() {
            return sim(FRAME, 20, 20, "_");
        }
    }

    @Nested
    @DisplayName("play (Tank_play)")
    class Play {
        @Test
        @DisplayName("tanks thrust from frame 0 to TANK_THRUST_TIME of every TANK_NOTHRUST_TIME + TANK_THRUST_TIME")
        void pulses() {
            Player tank = ready(s, "T", 0, 500, 500);
            tank.tank = true;
            // 60 fps: TANK_NOTHRUST_TIME = 2*60/2 + 2 = 62, TANK_THRUST_TIME = 62/2 + 1 = 32, period 94
            int[] frames = {94 * 3, 94 * 3 + 1, 94 * 3 + 31, 94 * 3 + 32, 94 * 3 + 33, 94 * 4 - 1, 94 * 4};
            int[] start = {0, Obj.THRUSTING, Obj.THRUSTING, Obj.THRUSTING, 0, 0, 0};
            StringBuilder got = new StringBuilder();
            StringBuilder plain = new StringBuilder();
            for (int i = 0; i < frames.length; i++) {
                w.frames = frames[i];
                tank.status = (tank.status & ~Obj.THRUSTING) | start[i];
                p.status &= ~Obj.THRUSTING;
                t.play();
                got.append(frames[i] % 94).append(':').append((tank.status & Obj.THRUSTING) != 0).append(' ');
                plain.append((p.status & Obj.THRUSTING) != 0).append(' ');
            }
            assertAll(
                () -> assertEquals("0:true 1:true 31:true 32:false 33:false 93:false 0:true ", got.toString(),
                    "on at 0, kept between, off at 32"),
                () -> assertEquals("false false false false false false false ", plain.toString(),
                    "ships that are not tanks are not touched"));
        }
    }
}
