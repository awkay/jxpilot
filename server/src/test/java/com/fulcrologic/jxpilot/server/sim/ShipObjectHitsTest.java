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

import com.fulcrologic.jxpilot.server.TestWorlds;
import com.fulcrologic.jxpilot.common.sim.Obj;
import com.fulcrologic.jxpilot.server.world.Objects;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.fulcrologic.jxpilot.server.sim.ModeFx.ballOf;
import static com.fulcrologic.jxpilot.server.sim.ModeFx.ship;
import static com.fulcrologic.jxpilot.server.sim.ModeFx.teamSim;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ships hit balls and asteroids in a full tick (collision.c PlayerObjectCollision:676-760). A merge once left a
 * {@code continue} ahead of these handlers, so ships flew through both; the unit tests called the handlers directly.
 */
class ShipObjectHitsTest {
    @Test
    @DisplayName("a ship on a loose ball pays ED_BALL_HIT fuel and is pushed away (Player_collides_with_ball)")
    void shipOnLooseBall() {
        Simulation s = teamSim("");
        World w = s.world;
        Objects o = w.objects;
        int h = ballOf(w, 2);
        w.modes.treasureHave[2] = false;
        o.status[h] |= Obj.GRAVITY;
        o.initPixels(h, 300, 150);
        Player p = ship(s, "P", 1, 300, 150);
        double fuel0 = p.fuel;
        TestWorlds.run(s, 1, 0L);
        assertAll(
            () -> assertEquals(fuel0 - 50 * 256, p.fuel, 1e-9, "50 fuel units for the hit"),
            () -> assertTrue(p.vx != 0 || p.vy != 0, "the ship is repelled"));
    }

    @Test
    @DisplayName("a ship flying into an asteroid is killed and damages it (Player_collides_with_asteroid)")
    void shipOnAsteroid() {
        Simulation s = SimFixture.sim(SimFixture.FPS14 + "maxAsteroidDensity: 0.01\n", 30, 30, "___");
        Player p = SimFixture.ready(s, "P", 0, 300, 300);
        int h = s.asteroids.make(300, 300, 1, 0, 0f);
        TestWorlds.run(s, 1, 0L);
        assertAll(
            () -> assertTrue((p.status & Obj.KILLED) != 0 || !p.isAlive(), "the ship dies"),
            () -> assertTrue(s.world.objects.life[h] < 1000 || !s.world.objects.isLive(h), "the asteroid is damaged"));
    }
}
