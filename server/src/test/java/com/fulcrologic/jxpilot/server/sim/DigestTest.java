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
import com.fulcrologic.jxpilot.server.world.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

@DisplayName("Digest")
class DigestTest {
    static final String[] ROWS = {"xxxxxxxx", "x  r   x", "x _ _ #x", "xxxxxxxx"};

    static Simulation fresh() {
        Simulation s = TestWorlds.sim("framesPerSecond: 60", ROWS);
        TestWorlds.join(s, "a", 0);
        s.shots.fire(s.world.playerAt(0), 0, false, 60, 60, com.fulcrologic.jxpilot.server.world.Obj.SHOT, 0, 0);
        return s;
    }

    long changed(Consumer<Simulation> change) {
        Simulation s = fresh();
        change.accept(s);
        return Digest.of(s.world);
    }

    @Test
    @DisplayName("is stable for equal states and sensitive to each hashed part of the state")
    void sensitivity() {
        long base = Digest.of(fresh().world);
        Simulation s = fresh();
        long twice1 = Digest.of(s.world);
        long twice2 = Digest.of(s.world);
        assertAll(
            () -> assertEquals(base, twice1, "two equal worlds have the same digest"),
            () -> assertEquals(twice1, twice2, "computing the digest does not change the world"),
            () -> assertNotEquals(base, changed(x -> x.world.tick++), "tick"),
            () -> assertNotEquals(base, changed(x -> x.world.frames += 0.5), "frame clock"),
            () -> assertNotEquals(base, changed(x -> x.world.playerAt(0).cx++), "player x"),
            () -> assertNotEquals(base, changed(x -> x.world.playerAt(0).vy += 1e-9), "player velocity"),
            () -> assertNotEquals(base, changed(x -> x.world.playerAt(0).fuel -= 1), "player fuel"),
            () -> assertNotEquals(base, changed(x -> x.world.playerAt(0).score += 1), "player score"),
            () -> assertNotEquals(base, changed(x -> x.world.playerAt(0).floatDir += 0.1), "player heading"),
            () -> assertNotEquals(base, changed(x -> x.world.objects.life[x.world.objects.active[0]] -= 1), "object life"),
            () -> assertNotEquals(base, changed(x -> x.world.objects.cy[x.world.objects.active[0]] += 1), "object y"),
            () -> assertNotEquals(base, changed(x -> x.world.cannonDeadTime[0] = 5), "cannon state"),
            () -> assertNotEquals(base, changed(x -> x.world.fuelLevel[0] += 1), "fuel station"),
            () -> assertNotEquals(base, changed(x -> x.world.teamScore[3] += 1), "team score"),
            () -> assertNotEquals(base, changed(x -> x.world.events.add(1, 0, 0, 0, 0f)), "event count"),
            () -> assertNotEquals(base, changed(x -> x.world.events.addBurst(0, 0, 0, 0)), "burst count"),
            () -> assertNotEquals(base, changed(x -> x.world.playerAt(0).id = 9), "player id"),
            () -> assertNotEquals(base, changed(x -> x.world.playerAt(0).cy++), "player y"),
            () -> assertNotEquals(base, changed(x -> x.world.playerAt(0).vx += 1e-9), "player vx"),
            () -> assertNotEquals(base, changed(x -> x.world.playerAt(0).dir++), "player dir"),
            () -> assertNotEquals(base, changed(x -> x.world.playerAt(0).status ^= 1), "player status"),
            () -> assertNotEquals(base, changed(x -> x.world.playerAt(0).used ^= 1), "player abilities"),
            () -> assertNotEquals(base, changed(x -> x.world.playerAt(0).count = 3), "player respawn count"),
            () -> assertNotEquals(base, changed(x -> x.world.playerAt(0).kills++), "player kills"),
            () -> assertNotEquals(base, changed(x -> x.world.playerAt(0).deaths++), "player deaths"),
            () -> assertNotEquals(base, changed(x -> x.world.objects.alloc()), "object count"),
            () -> assertNotEquals(base, changed(x -> x.world.objects.type[x.world.objects.active[0]] = 2), "object type"),
            () -> assertNotEquals(base, changed(x -> x.world.objects.cx[x.world.objects.active[0]] += 1), "object x"),
            () -> assertNotEquals(base, changed(x -> x.world.objects.vx[x.world.objects.active[0]] += 1), "object vx"),
            () -> assertNotEquals(base, changed(x -> x.world.objects.vy[x.world.objects.active[0]] += 1), "object vy"));
        Player p = s.world.playerAt(0);
        assertEquals(1, p.id, "fixture sanity: one player");
    }
}
