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
package com.fulcrologic.jxpilot.server.world;

import com.fulcrologic.jxpilot.common.sim.Has;
import com.fulcrologic.jxpilot.common.sim.Obj;
import com.fulcrologic.jxpilot.common.Item;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Player: position helpers (objpos.c), status/ability predicates and the single-tank Add_fuel. */
class PlayerTest {
    @Nested
    @DisplayName("position")
    class Position {
        @Test
        void clicksDerivePixelsByShiftAndBlocksByTruncation() {
            Player p = new Player(0);
            p.setClicks(35 * 64 + 63, -1);
            assertAll(
                () -> assertEquals(35 * 64 + 63, p.cx, "clicks are stored"),
                () -> assertEquals(35, p.px, "pixel x = clicks >> 6"),
                () -> assertEquals(-1, p.py, "negative clicks floor to pixel -1"),
                () -> assertEquals(1, p.bx, "block x = 35 / 35"),
                () -> assertEquals(0, p.by, "pixel -1 is in block 0: C pos.x / BLOCK_SZ truncates toward zero"));
        }

        @Test
        void initPixelsMakesItThePreviousPosition() {
            Player p = new Player(0);
            p.setClicks(0, 0);
            p.initPixels(100.75, 50.5);
            Player q = new Player(1);
            q.initPixels(4252.50001, 1.0000001);
            assertAll(
                () -> assertEquals(6448, p.cx, "100.75 px = 6448 clicks"),
                () -> assertEquals(3232, p.cy, "50.5 px = 3232 clicks"),
                () -> assertEquals(100, p.prevX, "previous x = new pixel x"),
                () -> assertEquals(50, p.prevY, "previous y = new pixel y"),
                () -> assertEquals(272160, q.cx, "the pixel argument is a C float: 4252.50001 rounds to 4252.5f first"),
                () -> assertEquals(64, q.cy, "1.0000001 rounds to the float 1.0 (a double would give 64 too)"));
        }

        @Test
        void rememberCopiesThePixelPosition() {
            Player p = new Player(0);
            p.initPixels(10, 20);
            p.setClicks(64 * 30, 64 * 40);
            p.remember();
            assertAll(
                () -> assertEquals(30, p.prevX, "previous x"),
                () -> assertEquals(40, p.prevY, "previous y"));
        }
    }

    @Nested
    @DisplayName("predicates")
    class Predicates {
        @Test
        void aliveMeansPlayingAndNotPausedOverOrKilled() {
            Player p = new Player(0);
            p.status = Obj.PLAYING | Obj.GRAVITY | Obj.THRUSTING;
            boolean playing = p.isAlive();
            p.status = Obj.PLAYING | Obj.PAUSE;
            boolean paused = p.isAlive();
            p.status = Obj.PLAYING | Obj.GAME_OVER;
            boolean over = p.isAlive();
            p.status = Obj.PLAYING | Obj.KILLED;
            boolean killed = p.isAlive();
            p.status = Obj.GRAVITY;
            assertAll(
                () -> assertTrue(playing, "PLAYING (other bits irrelevant) is alive"),
                () -> assertFalse(paused, "paused is not"),
                () -> assertFalse(over, "game over is not"),
                () -> assertFalse(killed, "killed is not"),
                () -> assertFalse(p.isAlive(), "not playing is not"));
        }

        @Test
        void fullyShieldedNeedsBothShieldBits() {
            Player p = new Player(0);
            p.used = Has.SHIELD;
            boolean one = p.fullyShielded();
            p.used = Has.EMERGENCY_SHIELD;
            boolean other = p.fullyShielded();
            p.used = Has.SHIELD | Has.EMERGENCY_SHIELD | Has.COMPASS;
            assertAll(
                () -> assertFalse(one, "shield alone is not full"),
                () -> assertFalse(other, "emergency shield alone is not full"),
                () -> assertTrue(p.fullyShielded(), "both are"));
        }

        @Test
        void emergencyThrustMeansMaximumAfterburners() {
            Player p = new Player(0);
            p.items[Item.AFTERBURNER.ordinal()] = 3;
            int items = p.afterburners();
            p.used = Has.EMERGENCY_THRUST;
            assertAll(
                () -> assertEquals(3, items, "the afterburner item count"),
                () -> assertEquals(15, p.afterburners(), "MAX_AFTERBURNER with emergency thrust"));
        }
    }

    @Nested
    @DisplayName("addFuel (ship.c Add_fuel)")
    class AddFuel {
        @Test
        @DisplayName("the change is truncated to a C long, then clamped so the sum stays in [0, max]")
        void fuelIsClampedToTheTank() {
            Player p = new Player(0);
            p.fuelMax = 1000;
            p.fuel = 500;
            p.tankFuel[0] = 500;
            p.addFuel(250.5);
            double up = p.fuel;
            p.addFuel(600);
            double full = p.fuel;
            p.addFuel(-400.75);
            double down = p.fuel;
            p.addFuel(-2000);
            assertAll(
                () -> assertEquals(750.0, up, "250.5 is added as (long) 250"),
                () -> assertEquals(1000.0, full, "at most the maximum"),
                () -> assertEquals(600.0, down, "-400.75 is taken as (long) -400"),
                () -> assertEquals(0.0, p.fuel, "at least 0"),
                () -> assertEquals(0.0, p.tankFuel[0], "the main tank follows the sum"));
        }

        @Test
        @DisplayName("the change goes to the current tank only")
        void currentTank() {
            Player p = new Player(0);
            p.fuelMax = 3000;
            p.fuel = 1500;
            p.numTanks = 2;
            p.tankFuel[0] = 1000;
            p.tankFuel[1] = 300;
            p.tankFuel[2] = 200;
            p.currentTank = 2;
            p.addFuel(-150);
            p.addFuel(2000);
            assertAll(
                () -> assertEquals(3000.0, p.fuel, "sum: 1500 - 150, then up to the maximum 3000"),
                () -> assertEquals(1000.0, p.tankFuel[0], "the main tank is not touched"),
                () -> assertEquals(300.0, p.tankFuel[1], "tank 1 is not touched"),
                () -> assertEquals(200 - 150 + 1650.0, p.tankFuel[2], "tank 2 takes both changes (the second clamped)"));
        }
    }
}
