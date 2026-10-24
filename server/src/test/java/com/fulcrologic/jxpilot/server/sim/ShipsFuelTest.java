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

import com.fulcrologic.jxpilot.common.map.BlockType;
import com.fulcrologic.jxpilot.common.map.FuelStation;
import com.fulcrologic.jxpilot.common.net.ControlKey;
import com.fulcrologic.jxpilot.common.sim.Has;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static com.fulcrologic.jxpilot.server.sim.ShipFx.C_FRAMES;
import static com.fulcrologic.jxpilot.server.sim.ShipFx.FPS14;
import static com.fulcrologic.jxpilot.server.sim.ShipFx.keys;
import static com.fulcrologic.jxpilot.server.sim.ShipFx.sim;
import static com.fulcrologic.jxpilot.server.sim.ShipFx.tick;
import static com.fulcrologic.jxpilot.server.sim.ShipFx.unshield;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Refuelling: event.c Refuel (station choice) and the update.c refuel step; station regeneration. */
class ShipsFuelTest {
    static final long REFUEL = keys(ControlKey.REFUEL);
    /** Base (5,5); station NEAR at block (3,6) is 78 px from the base centre, FAR at (8,2) is 148 px. */
    static final String[] STATIONS = {
        "xxxxxxxxxxx",
        "x         x",
        "x         x",
        "x         x",
        "x  #      x",
        "x    _    x",
        "x         x",
        "x         x",
        "x       # x",
        "x         x",
        "xxxxxxxxxxx",
    };
    /** Team play: team 1 base at (2,2), team 2 base at (8,2); the station at (7,2) is closest to team 2. */
    static final String[] TEAM_STATION = {
        "xxxxxxxxxxx",
        "x         x",
        "x 1    #2 x",
        "x         x",
        "xxxxxxxxxxx",
    };

    static int station(World w, int bx) {
        for (FuelStation f : w.map.fuels()) {
            if (f.bx() == bx) {
                return f.index();
            }
        }
        throw new IllegalStateException();
    }

    @Nested
    @DisplayName("startRefuel")
    class StartRefuel {
        @Test
        void theNearestExistingStationIsChosen() {
            Simulation s = sim(C_FRAMES, STATIONS);
            World w = s.world;
            Player p = s.ships.join("a", 0);
            s.ships.startRefuel(p);
            int nearest = p.fs;
            w.map.setBlock(3, 6, BlockType.SPACE);
            s.ships.startRefuel(p);
            assertAll(
                () -> assertEquals(station(w, 3), nearest, "the 78 px station is chosen over the 148 px one"),
                () -> assertEquals(station(w, 8), p.fs, "a destroyed station is skipped"),
                () -> assertEquals(Has.REFUEL, p.used & Has.REFUEL, "refuelling is on"));
        }

        @Test
        void distanceIsMeasuredFromTheShipToTheStation() {
            // base (5,5); station A (8,6) is 111 px away up-right, B (1,1) 197 px away down-left
            Simulation s = sim(C_FRAMES,
                "xxxxxxxxxxx",
                "x         x",
                "x         x",
                "x         x",
                "x       # x",
                "x    _    x",
                "x         x",
                "x         x",
                "x         x",
                "x#        x",
                "xxxxxxxxxxx");
            Player p = s.ships.join("a", 0);
            s.ships.startRefuel(p);
            assertEquals(station(s.world, 8), p.fs, "the nearer station by wrapped pixel distance");
        }

        @Test
        void equallyNearStationsGoToTheFirst() {
            // ship pixel (192, 192); A (3,5) centre (122.5, 192.5) and B (5,3) centre (192.5, 122.5) are both
            // sqrt(69.5^2 + 0.5^2) away
            Simulation s = sim(C_FRAMES,
                "xxxxxxxxxxx",
                "x         x",
                "x         x",
                "x         x",
                "x         x",
                "x  # _    x",
                "x         x",
                "x    #    x",
                "x         x",
                "x         x",
                "xxxxxxxxxxx");
            Player p = s.ships.join("a", 0);
            s.ships.startRefuel(p);
            assertEquals(station(s.world, 3), p.fs, "event.c Refuel: l < dist, so the first of equals wins");
        }

        @Test
        void withoutStationsOrTheAbilityRefuellingStaysOff() {
            Simulation s = sim(C_FRAMES, ShipFx.ROOM);
            Player p = s.ships.join("a", 0);
            s.ships.startRefuel(p);
            int noStation = p.used & Has.REFUEL;
            Simulation s2 = sim(C_FRAMES, STATIONS);
            Player q = s2.ships.join("a", 0);
            q.have &= ~Has.REFUEL;
            s2.ships.startRefuel(q);
            assertAll(
                () -> assertEquals(0, noStation, "no station: refuelling stays off"),
                () -> assertEquals(0, q.used & Has.REFUEL, "without HAS_REFUEL nothing happens"));
        }
    }

    @Nested
    @DisplayName("refuel")
    class Refuel {
        @Test
        void holdingRefuelMovesFiveUnitsPerFrameUntilTheStationIsEmpty() {
            Simulation s = sim(C_FRAMES, STATIONS);
            World w = s.world;
            Player p = s.ships.join("a", 0);
            unshield(p);
            int f = station(w, 3);
            int far = station(w, 8);
            tick(s, p, REFUEL);
            double st1 = w.fuelLevel[f];
            double pl1 = p.fuel;
            for (int i = 0; i < 4; i++) {
                tick(s, p, REFUEL);
            }
            // const.h: START_STATION_FUEL 20*256 = 5120, REFUEL_RATE 5*256 = 1280,
            // STATION_REGENERATION (int)(1 * 0.06*256) = 15 per frame before the player loop
            assertAll(
                () -> assertEquals(5120 + 15 - 1280.0, st1, "frame 1: the station regenerates 15 then gives 1280"),
                () -> assertEquals(256000 + 1280.0, pl1, "and the player gets 1280"),
                () -> assertEquals(0.0, w.fuelLevel[f], "frame 5: the station gives its last 75 and is empty"),
                () -> assertEquals(256000 + 4 * 1280.0 + 75, p.fuel, "the player got 4*1280 + 75"),
                () -> assertEquals(0, p.used & Has.REFUEL, "an empty station stops refuelling"),
                () -> assertEquals(5120 + 5 * 15.0, w.fuelLevel[far], "the other station only regenerated"));
        }

        @Test
        void at14FpsATickTransfersOneFrameOfFuel() {
            Simulation s = sim(FPS14, STATIONS);
            World w = s.world;
            Player p = s.ships.join("a", 0);
            unshield(p);
            int f = station(w, 3);
            tick(s, p, REFUEL);
            assertAll(
                () -> assertEquals(5120 + 15 - 1280.0, w.fuelLevel[f], "station: +15 - 1280 (one tick is one frame)"),
                () -> assertEquals(256000 + 1280.0, p.fuel, "player: +1280"));
        }

        @Test
        void releasingTheKeyStopsRefuelling() {
            Simulation s = sim(C_FRAMES, STATIONS);
            Player p = s.ships.join("a", 0);
            unshield(p);
            tick(s, p, REFUEL);
            tick(s, p, 0);
            assertAll(
                () -> assertEquals(0, p.used & Has.REFUEL, "refuelling is off"),
                () -> assertEquals(256000 + 1280.0, p.fuel, "only the first frame transferred fuel"));
        }

        @Test
        void refuellingStopsBeyond90PixelsWhenFullOrWhenTheStationIsGone() {
            Simulation s = sim(C_FRAMES, STATIONS);
            World w = s.world;
            Player p = s.ships.join("a", 0);
            int f = station(w, 3);
            s.ships.startRefuel(p);
            // the station centre is (122.5, 227.5); ship pixels are whole numbers
            p.initPixels(213, 227); // dx 90.5: beyond 90 px
            s.ships.refuel(p);
            int far = p.used & Has.REFUEL;
            p.initPixels(212, 227); // dx 89.5, dy -0.5: within 90 px
            s.ships.startRefuel(p);
            s.ships.refuel(p);
            int at90 = p.used & Has.REFUEL;
            double fuelAt90 = p.fuel;
            p.fuel = p.fuelMax;
            s.ships.refuel(p);
            int full = p.used & Has.REFUEL;
            p.fuel = 1000;
            s.ships.startRefuel(p);
            w.map.setBlock(3, 6, BlockType.SPACE);
            s.ships.refuel(p);
            assertAll(
                () -> assertEquals(0, far, "90.5 px away stops refuelling (> 90.0)"),
                () -> assertEquals(Has.REFUEL, at90, "89.5 px away still refuels"),
                () -> assertEquals(256000 + 1280.0, fuelAt90, "and transfers fuel"),
                () -> assertEquals(0, full, "a full tank stops refuelling"),
                () -> assertEquals(0, p.used & Has.REFUEL, "a destroyed station stops refuelling"),
                () -> assertEquals(1000.0, p.fuel, "without a transfer"),
                () -> assertEquals(5120 - 1280.0, w.fuelLevel[f], "the station only lost the one transfer"));
        }

        @Test
        void aPhasedShipCannotRefuel() {
            Simulation s = sim(C_FRAMES, STATIONS);
            Player p = s.ships.join("a", 0);
            s.ships.startRefuel(p);
            p.used |= Has.PHASING_DEVICE;
            s.ships.refuel(p);
            assertAll(
                () -> assertEquals(0, p.used & Has.REFUEL, "phasing stops refuelling"),
                () -> assertEquals(256000.0, p.fuel, "no fuel is moved"));
        }

        @Test
        void nearlyFullTanksStopRefuellingAfterTopping() {
            Simulation s = sim(C_FRAMES, STATIONS);
            Player p = s.ships.join("a", 0);
            unshield(p);
            p.fuel = p.fuelMax - 1000;
            tick(s, p, REFUEL);
            assertAll(
                () -> assertEquals(p.fuelMax, p.fuel, "the tank is topped to its capacity"),
                () -> assertEquals(0, p.used & Has.REFUEL, "within REFUEL_RATE of full, refuelling stops"));
        }

        @Test
        void refuellingContinuesWhileExactlyOneRateBelowFull() {
            Simulation s = sim(C_FRAMES, STATIONS);
            Player p = s.ships.join("a", 0);
            unshield(p);
            p.fuel = p.fuelMax - 2 * 1280;
            tick(s, p, REFUEL);
            assertAll(
                () -> assertEquals(p.fuelMax - 1280, p.fuel, "one transfer"),
                () -> assertEquals(Has.REFUEL, p.used & Has.REFUEL, "fuel > max - REFUEL_RATE is false: still on"));
        }

        @Test
        void aStationWithExactlyOneRateLeftGivesItAndStops() {
            Simulation s = sim(C_FRAMES, STATIONS);
            World w = s.world;
            Player p = s.ships.join("a", 0);
            s.ships.startRefuel(p);
            w.fuelLevel[p.fs] = 1280;
            s.ships.refuel(p);
            assertAll(
                () -> assertEquals(256000 + 1280.0, p.fuel, "the last 1280 are given"),
                () -> assertEquals(0.0, w.fuelLevel[p.fs], "the station is empty"),
                () -> assertEquals(0, p.used & Has.REFUEL, "fuel > REFUEL_RATE is false: refuelling stops"));
        }

        @Test
        void withTeamFuelOnlyTheOwnTeamsStationsGiveFuel() {
            Simulation s = sim(C_FRAMES + "teamPlay: yes\nteamFuel: yes\n", TEAM_STATION);
            Player one = s.ships.join("one", 1);
            Player two = s.ships.join("two", 2);
            one.initPixels(two.px, two.py);
            s.ships.startRefuel(one);
            s.ships.refuel(one);
            s.ships.startRefuel(two);
            s.ships.refuel(two);
            Simulation open = sim(C_FRAMES + "teamPlay: yes\n", TEAM_STATION);
            Player o1 = open.ships.join("one", 1);
            Player o2 = open.ships.join("two", 2);
            o1.initPixels(o2.px, o2.py);
            open.ships.startRefuel(o1);
            open.ships.refuel(o1);
            assertAll(
                () -> assertEquals(2, s.world.map.fuels().get(0).team(), "the station belongs to team 2"),
                () -> assertEquals(256000.0, one.fuel, "team 1 gets nothing from team 2's station"),
                () -> assertEquals(0, one.used & Has.REFUEL, "and stops refuelling"),
                () -> assertEquals(256000 + 1280.0, two.fuel, "team 2 refuels there"),
                () -> assertEquals(256000 + 1280.0, o1.fuel, "without teamFuel anyone refuels"));
        }
    }

    @Nested
    @DisplayName("station regeneration (Simulation)")
    class Regeneration {
        @Test
        void stationsRegeneratePerPlayerAndStopAtTheCap() {
            Simulation s = sim(FPS14, STATIONS);
            World w = s.world;
            s.tick(new long[1]);
            double none = w.fuelLevel[0];
            Player p = s.ships.join("a", 0);
            w.fuelLevel[1] = 500 * 256 - 1;
            tick(s, p, 0);
            assertAll(
                () -> assertEquals(5120.0, none, "nothing without players"),
                () -> assertEquals(5120 + 15.0, w.fuelLevel[0], "(int)(1*15.36) = 15 per frame (= tick)"),
                () -> assertEquals(500 * 256.0, w.fuelLevel[1], "capped at MAX_STATION_FUEL"));
        }
    }
}
