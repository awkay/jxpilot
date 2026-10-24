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

import com.fulcrologic.jxpilot.common.sim.Has;
import com.fulcrologic.jxpilot.server.world.ModeState;
import com.fulcrologic.jxpilot.common.sim.Mods;
import com.fulcrologic.jxpilot.common.sim.Obj;
import com.fulcrologic.jxpilot.server.world.Objects;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.fulcrologic.jxpilot.server.sim.ModeFx.ballOf;
import static com.fulcrologic.jxpilot.server.sim.ModeFx.ship;
import static com.fulcrologic.jxpilot.server.sim.ModeFx.targetSim;
import static com.fulcrologic.jxpilot.server.sim.ModeFx.teamSim;
import static com.fulcrologic.jxpilot.server.sim.ModeFx.tow;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.obj;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Where the game modes meet the items: ECM and phasing on balls, modifiers on targets and asteroids. */
@DisplayName("game modes with items (item.c ECM, update.c Phasing, walls.c Object_hits_target, collision.c AsteroidCollision)")
class ModesItemsTest {
    @Test
    @DisplayName("ECM: each ball the ship last connected (C ball->owner) detaches when (int)(rfrac*100) < (int)(20*range)+5")
    void ecmDetach() {
        Simulation s = teamSim("");
        World w = s.world;
        Objects o = w.objects;
        int a = ballOf(w, 0);
        int b = ballOf(w, 2);
        Player p = ship(s, "P", 1, 300, 200);
        Player q = ship(s, "Q", 2, 500, 200);
        tow(w, p, a);
        tow(w, q, b);
        s.balls.ecmDetach(p, -1f);
        int kept = o.owner[a];
        s.balls.ecmDetach(p, 5f);
        assertAll(
            () -> assertEquals(p.id, kept, "range -1: (int)(rfrac*100) < -15 never holds"),
            () -> assertEquals(Obj.NO_ID, o.owner[a], "range 5: < 105 always holds, the ball goes loose"),
            () -> assertEquals(0, p.have & Has.BALL, "and the ship has no ball"),
            () -> assertEquals(q.id, o.owner[b], "another ship's ball is untouched"),
            () -> assertEquals(s.balls, s.ecm.ballDetacher, "the simulation installs it as the ECM hook"));
    }

    @Test
    @DisplayName("phasing on with the connector ends the reach for a ball (update.c:131-132); without it pl->ball stays")
    void phasing() {
        Simulation s = teamSim("");
        Player p = ship(s, "P", 1, 300, 200);
        Player q = ship(s, "Q", 2, 500, 200);
        p.used |= Has.CONNECTOR;
        p.ball = 5;
        q.ball = 6;
        p.items[com.fulcrologic.jxpilot.common.Item.PHASING.ordinal()] = 1;
        q.items[com.fulcrologic.jxpilot.common.Item.PHASING.ordinal()] = 1;
        s.devices.phasing(p, true);
        s.devices.phasing(q, true);
        assertAll(
            () -> assertEquals(-1, p.ball, "connector on: no reach"),
            () -> assertEquals(6, q.ball, "connector off: untouched"));
    }

    @Test
    @DisplayName("targets read the modifiers: a nuclear missile destroys, a mini one does 120*256/(mini+1), a nuclear cluster shot nukeClusterDamage times")
    void targetMods() {
        Simulation s = targetSim("nukeClusterDamage: 3\n");
        World w = s.world;
        w.params.shotHitFuelDrainUsesKineticEnergy = false;
        ModeState m = w.modes;
        Player p = ship(s, "P", 1, 300, 100);
        int full = ModeState.TARGET_DAMAGE;
        int mini = obj(w, Obj.SMART_SHOT, p.id, 0, 0, 0, 0, 1, 10);
        w.objects.team[mini] = 1;
        w.objects.mods[mini] = Mods.withMini(0, 2);
        s.targets.objectHits(1, mini);
        int miniDamage = full - m.targetDamage[1];
        m.targetDamage[1] = full;
        int cluster = obj(w, Obj.SHOT, p.id, 0, 0, 0, 0, 1, 10);
        w.objects.team[cluster] = 1;
        w.objects.mods[cluster] = Mods.withNuclear(Obj.MOD_CLUSTER, Mods.NUCLEAR);
        s.targets.objectHits(1, cluster);
        int clusterDamage = full - m.targetDamage[1];
        m.targetDamage[1] = full;
        int nuke = obj(w, Obj.SMART_SHOT, p.id, 0, 0, 0, 0, 1, 10);
        w.objects.team[nuke] = 1;
        w.objects.mods[nuke] = Mods.withNuclear(0, Mods.NUCLEAR);
        s.targets.objectHits(1, nuke);
        assertAll(
            () -> assertEquals(120 * 256 / 3, miniDamage, "mini 2: a third"),
            () -> assertEquals(3 * 25 * 256, clusterDamage, "nuclear cluster: 3 shots' worth"),
            () -> assertEquals(true, w.targetDeadTime[1] > 0, "the nuclear missile destroyed it"));
    }

    @Test
    @DisplayName("an asteroid hit by a mini/power missile takes ED_SMART_SHOT_HIT / ((mini+1)*(power+1))")
    void asteroidMods() {
        Simulation s = AsteroidsTest.sim("");
        World w = s.world;
        Objects o = w.objects;
        Player p = SimFixture.ready(s, "P", 0, 100, 100);
        int a = s.asteroids.make(500, 500, 4, 0, 0f);
        int a2 = s.asteroids.make(900, 500, 4, 0, 0f);
        o.life[a] = 900; // fresh asteroids (life above their fuse) take no damage yet
        o.life[a2] = 900;
        long life = 900;
        int missile = obj(w, Obj.SMART_SHOT, p.id, 505, 500, 0, 0, 1, 20);
        o.mods[missile] = Mods.withPower(Mods.withMini(0, 1), 1);
        int plain = obj(w, Obj.SMART_SHOT, p.id, 905, 500, 0, 0, 1, 20);
        w.grid.rebuild(o);
        s.asteroids.collide();
        long lostModded = life - (long) o.life[a];
        long lostPlain = life - (long) o.life[a2];
        assertAll(
            () -> assertEquals(true, lostModded > 0, "damaged"),
            () -> assertEquals(lostPlain / 4, lostModded, 1, "a quarter of a plain missile's damage"),
            () -> assertEquals(0.0, o.life[plain], 1e-9, "(the missiles are absorbed)"));
    }
}
