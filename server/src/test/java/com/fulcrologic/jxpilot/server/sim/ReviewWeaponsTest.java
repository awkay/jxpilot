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
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.World;
import org.junit.jupiter.api.Test;

import static com.fulcrologic.jxpilot.server.sim.SimFixture.FRAME;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.obj;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.ready;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.sim;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Review findings (weapons), kept as regression tests: each assertion encodes what the cited C code computes. */
class ReviewWeaponsTest {

    static float rate(float winner, float loser) {
        float t = (10 * 1024) / (Math.abs(loser - winner) + 1024); // C DFLOAT: float arithmetic
        return loser > winner ? 20 - t : t;
    }

    static Player tankOf(Simulation s, Player p) {
        s.tanks.add(p, 50 * 256);
        s.tanks.detach(p);
        World w = s.world;
        for (int k = 0; k < w.numPlayers; k++) {
            if (w.playerAt(k).tank) {
                return w.playerAt(k);
            }
        }
        return null;
    }

    /**
     * score.c:131-134 Score_players: when the loser is a tank and the winner owns it
     * (GetInd[loser->lock.pl_id] == winner) both scores are made non-positive.
     * collision.c:1311-1316: a player's shot killing its own tank goes through Score_players.
     */
    @Test
    void ownerShootingOwnTankLosesPoints() {
        Simulation s = sim(FRAME, 20, 20, "__");
        World w = s.world;
        Player p = ready(s, "P", 0, 400, 400);
        Player t = tankOf(s, p);
        assertTrue(t != null, "tank detached");
        t.used = 0;
        t.initPixels(200, 200);
        t.vx = 0;
        t.vy = 0;
        p.score = 50;
        t.score = 100;
        int h = obj(w, Obj.SHOT, p.id, 200, 200, 21, 0, 0.1, 10);
        w.grid.rebuild(w.objects);
        s.collisions.check();
        float sc = rate(50, 100);
        assertAll(
            () -> assertTrue((t.status & Obj.KILLED) != 0, "the tank dies"),
            () -> assertEquals(t.lockPlId, p.id, "tank belongs to P"),
            () -> assertEquals(50 - sc, p.score, 1e-9, "C: owner LOSES Rate(owner, tank) for killing own tank"),
            () -> assertEquals(100 - sc, t.score, 1e-9, "C: tank loses Rate too"));
    }

    /**
     * walls.c:2000 obj->life = (long)(obj->life * objectWallBounceLifeFactor): long * DFLOAT is a float
     * product. life 10 * 0.9f = 8.99999976 rounds to the float 9.0, so C keeps 9 frames.
     */
    @Test
    void bounceLifeIsFloatProduct() {
        Simulation s = TestWorlds.sim("framesPerSecond: 60", "     ", "  x  ", "     ");
        World w = s.world;
        w.params.objBounceMask = Obj.SHOT;
        w.params.objectWallBounceLifeFactor = 0.9f;
        w.params.objectWallBrakeFactor = 1f;
        w.params.maxObjectWallBounceSpeed = 100;
        int bc = 35 * 64;
        int h = w.objects.alloc();
        w.objects.type[h] = Obj.SHOT;
        w.objects.setClicks(h, 2 * bc - 100, bc + 1000);
        w.objects.vx[h] = 5f;
        w.objects.life[h] = 10;
        s.mover.moveObject(h);
        float cProduct = 10L * 0.9f;
        assertEquals((double) (long) cProduct, w.objects.life[h], "C: (long)(10 * 0.9f) = 9");
    }

    /**
     * shot.c:584-590 then 973-979: a shot's start outside a non-wrapping world. C has already paid the fuel and
     * counted pl->shots++ and has an OBJ_SHOT object (life, id, type set) that Delete_shot later uses to give the
     * shot back.
     */
    @Test
    void shotOutsideWorldLeaksShotCount() {
        Simulation s = sim(FRAME + "edgeWrap: false\n", 20, 20, "__");
        World w = s.world;
        Player p = ready(s, "P", 0, 400, 400);
        p.dir = 64;
        p.initPixels(0.5, 400);
        int before = w.objects.count();
        s.shots.fireMainShot(p, p.dir);
        int made = w.objects.count() - before;
        for (int i = 0; i < w.params.shotLife + 5; i++) {
            s.expireObjects();
        }
        assertAll(
            () -> assertEquals(1, made, "C's unpositioned shot object is allocated (shot.c:950-994)"),
            () -> assertEquals(0, p.shots, "C: the ghost shot expires and Delete_shot gives the slot back"));
    }
}
