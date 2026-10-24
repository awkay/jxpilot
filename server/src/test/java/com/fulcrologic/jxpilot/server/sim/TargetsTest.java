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
import com.fulcrologic.jxpilot.common.map.Target;
import com.fulcrologic.jxpilot.server.TestWorlds;
import com.fulcrologic.jxpilot.common.sim.Has;
import com.fulcrologic.jxpilot.server.world.ModeState;
import com.fulcrologic.jxpilot.common.sim.Obj;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.fulcrologic.jxpilot.server.sim.ModeFx.ship;
import static com.fulcrologic.jxpilot.server.sim.ModeFx.targetSim;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.gameEvents;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.messages;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.obj;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Targets (walls.c Object_hits_target, update.c target update and repair, event.c Repair)")
class TargetsTest {
    static final int FULL = ModeState.TARGET_DAMAGE;
    static final double EPS = 1e-9;

    static boolean solid(World w, int i) {
        Target t = w.map.targets().get(i);
        return w.map.block(t.bx(), t.by()) == BlockType.TARGET;
    }

    @Nested
    @DisplayName("update (Update_objects target loop)")
    class Update {
        @Test
        @DisplayName("dead targets count down and reappear; damaged ones repair TARGET_DAMAGE/(FPS*600) per frame")
        void countdownAndRepair() {
            Simulation s = targetSim("");
            World w = s.world;
            ModeState m = w.modes;
            w.targetDeadTime[1] = 2;
            w.map.setBlock(15, 3, BlockType.SPACE);
            m.targetDamage[0] = 100;
            m.targetDamage[2] = FULL - 3;
            s.targets.update();
            boolean solidAfter1 = solid(w, 1);
            int damage0 = m.targetDamage[0];
            s.targets.update();
            assertAll(
                () -> assertEquals(64000 / (14 * 600), s.targets.repairPerFrame, "7 per frame at 14 fps"),
                () -> assertEquals(64000 / (14 * 10), s.targets.fuelRepairPerFrame, "fuel repair 457 per frame"),
                () -> assertTrue(!solidAfter1, "still dead after one frame"),
                () -> assertTrue(solid(w, 1), "reappears when the count reaches 0"),
                () -> assertEquals(0, w.targetDeadTime[1], "no longer dead"),
                () -> assertEquals(107, damage0, "+7 after one frame"),
                () -> assertEquals(114, m.targetDamage[0], "+7 again"),
                () -> assertEquals(FULL, m.targetDamage[2], "repair stops at full strength"));
        }

        @Test
        @DisplayName("targetSync: when one target of a team reappears, all of its team reappear at full strength")
        void sync() {
            Simulation s = targetSim("targetSync: yes\n");
            World w = s.world;
            ModeState m = w.modes;
            w.targetDeadTime[1] = 1;
            w.targetDeadTime[2] = 50;
            w.map.setBlock(15, 3, BlockType.SPACE);
            w.map.setBlock(17, 3, BlockType.SPACE);
            m.targetDamage[2] = 5;
            w.targetDeadTime[0] = 50;
            s.targets.update();
            assertAll(
                () -> assertTrue(solid(w, 1) && solid(w, 2), "both team 2 targets are back"),
                () -> assertEquals(0, w.targetDeadTime[2], "the other is no longer dead"),
                () -> assertEquals(FULL, m.targetDamage[2], "and at full strength"),
                () -> assertEquals(49, w.targetDeadTime[0], "team 1's target keeps counting"));
        }
    }

    @Nested
    @DisplayName("repair (Repair key and the player loop)")
    class Repair {
        @Test
        @DisplayName("the key picks the nearest live target of the own team")
        void pick() {
            Simulation s = targetSim("");
            World w = s.world;
            Player p = ship(s, "P", 2, 16.5 * 35, 3.5 * 35 + 20);
            p.have |= Has.REPAIR;
            w.targetDeadTime[1] = 0;
            s.targets.startRepair(p);
            int nearest = p.repairTarget;
            boolean on = (p.used & Has.REPAIR) != 0;
            w.targetDeadTime[2] = 5;
            p.initPixels(17.5 * 35, 3.5 * 35 + 20);
            s.targets.startRepair(p);
            int notDead = p.repairTarget;
            Player q = ship(s, "Q", 1, 300, 100);
            q.have &= ~Has.REPAIR;
            s.targets.startRepair(q);
            Player r = ship(s, "R", 1, 300, 100);
            r.used |= Has.REPAIR;
            w.targetDeadTime[0] = 3;
            s.targets.startRepair(r);
            assertAll(
                () -> assertTrue(on, "repair on"),
                () -> assertTrue(nearest == 1 || nearest == 2, "a team 2 target"),
                () -> assertEquals(1, notDead, "the dead one is skipped"),
                () -> assertEquals(0, q.used & Has.REPAIR, "without HAS_REPAIR nothing"),
                () -> assertEquals(0, r.used & Has.REPAIR, "no live own target: repair off"));
        }

        @Test
        @DisplayName("each frame near the target: +TARGET_FUEL_REPAIR_PER_FRAME for REFUEL_RATE fuel, capped")
        void perFrame() {
            Simulation s = targetSim("");
            World w = s.world;
            ModeState m = w.modes;
            Player p = ship(s, "P", 2, 15.5 * 35, 3.5 * 35 + 50);
            p.used |= Has.REPAIR;
            p.repairTarget = 1;
            m.targetDamage[1] = 1000;
            double fuel = p.fuel;
            s.targets.repair(p);
            int one = m.targetDamage[1];
            m.targetDamage[1] = FULL - 10;
            s.targets.repair(p);
            int capped = m.targetDamage[1];
            assertAll(
                () -> assertEquals(1000 + 457, one, "+457"),
                () -> assertEquals(fuel - 2 * 5 * 256, p.fuel, EPS, "REFUEL_RATE fuel each frame"),
                () -> assertEquals(FULL, capped, "capped at full strength"),
                () -> assertTrue((p.used & Has.REPAIR) != 0, "still repairing"));
        }

        @Test
        @DisplayName("repair stops when far, full, dead, phased or out of fuel")
        void stops() {
            Simulation s = targetSim("");
            World w = s.world;
            ModeState m = w.modes;
            Player p = ship(s, "P", 2, 15.5 * 35 + 91, 3.5 * 35);
            m.targetDamage[1] = 1000;
            int[] states = new int[5];
            p.repairTarget = 1;
            p.used |= Has.REPAIR;
            s.targets.repair(p);
            states[0] = p.used & Has.REPAIR;
            p.initPixels(15.5 * 35, 3.5 * 35 + 50);
            m.targetDamage[1] = FULL;
            p.used |= Has.REPAIR;
            s.targets.repair(p);
            states[1] = p.used & Has.REPAIR;
            m.targetDamage[1] = 1000;
            w.targetDeadTime[1] = 4;
            p.used |= Has.REPAIR;
            s.targets.repair(p);
            states[2] = p.used & Has.REPAIR;
            w.targetDeadTime[1] = 0;
            p.used |= Has.REPAIR | Has.PHASING_DEVICE;
            s.targets.repair(p);
            states[3] = p.used & Has.REPAIR;
            p.used &= ~Has.PHASING_DEVICE;
            p.used |= Has.REPAIR;
            p.fuel = 5 * 256;
            p.tankFuel[0] = p.fuel; // the main tank holds it all (no extra tanks)
            s.targets.repair(p);
            states[4] = p.used & Has.REPAIR;
            assertAll(
                () -> assertEquals(0, states[0], "more than 90 px away"),
                () -> assertEquals(0, states[1], "at full strength"),
                () -> assertEquals(0, states[2], "dead"),
                () -> assertEquals(0, states[3], "phased"),
                () -> assertEquals(0, states[4], "fuel not above REFUEL_RATE"),
                () -> assertEquals(1000, m.targetDamage[1], "no repair from the stopped frames"));
        }
    }

    @Nested
    @DisplayName("damage (Object_hits_target)")
    class Damage {
        @Test
        @DisplayName("per type: shot 25*256*drain, pulse 100*256, missile 120*256/(mini+1) or all (nuclear), mine all/(mini+1), ship its cost up to a quarter")
        void amounts() {
            Simulation s = targetSim("");
            World w = s.world;
            w.params.shotHitFuelDrainUsesKineticEnergy = false;
            ModeState m = w.modes;
            Player p = ship(s, "P", 1, 300, 100);
            int[] d = new int[10];
            s.targets.damage(1, Obj.SHOT, p.id, 1, 0, 0, 1, false, 0, 1f, -1);
            d[0] = FULL - m.targetDamage[1];
            m.targetDamage[1] = FULL;
            s.targets.damage(1, Obj.PULSE, p.id, 1, 0, 0, 1, false, 0, 1f, -1);
            d[1] = FULL - m.targetDamage[1];
            m.targetDamage[1] = FULL;
            s.targets.damage(1, Obj.SMART_SHOT, p.id, 1, 0, 0, 1, false, 1, 1f, -1);
            d[2] = FULL - m.targetDamage[1];
            m.targetDamage[1] = FULL;
            s.targets.damage(1, Obj.TORPEDO, p.id, 1, 0, 0, 0, false, 0, 1f, -1);
            d[3] = FULL - m.targetDamage[1];
            s.targets.damage(1, Obj.MINE, p.id, 1, 0, 0, 30, false, 3, 1f, -1);
            d[4] = FULL - m.targetDamage[1];
            m.targetDamage[1] = FULL;
            s.targets.damage(1, Obj.MINE, p.id, 1, 0, 0, 0, false, 0, 1f, -1);
            d[5] = FULL - m.targetDamage[1];
            s.targets.damage(1, Obj.PLAYER, p.id, 1, 0, 0, 20, false, 0, 1f, -1);
            d[6] = FULL - m.targetDamage[1];
            m.targetDamage[1] = FULL;
            s.targets.damage(1, Obj.PLAYER, p.id, 1, 0, 0, 20, false, 0, 1f, 1000);
            d[7] = FULL - m.targetDamage[1];
            m.targetDamage[1] = FULL;
            s.targets.damage(1, Obj.PLAYER, p.id, 1, 0, 0, 20, false, 0, 1f, FULL / 4 + 1);
            d[8] = FULL - m.targetDamage[1];
            m.targetDamage[1] = FULL;
            s.targets.damage(1, Obj.HEAT_SHOT, p.id, 1, 0, 0, 1, true, 0, 1f, -1);
            d[9] = m.targetDamage[1];
            assertAll(
                () -> assertEquals(6400, d[0], "shot: 25*256"),
                () -> assertEquals(25600, d[1], "pulse: 100*256"),
                () -> assertEquals(15360, d[2], "missile mini 1: 120*256/2"),
                () -> assertEquals(0, d[3], "a massless missile (round reset) does nothing"),
                () -> assertEquals(FULL / 4, d[4], "mine mini 3: TARGET_DAMAGE/4"),
                () -> assertEquals(0, d[5], "a massless mine does nothing"),
                () -> assertEquals(FULL / 4, d[6], "ship crash (cost -1): a quarter"),
                () -> assertEquals(1000, d[7], "ship bounce: its cost"),
                () -> assertEquals(FULL / 4, d[8], "a cost above a quarter is capped"),
                () -> assertEquals(FULL, d[9], "a nuclear missile destroys at once (strength reset after death)"),
                () -> assertTrue(w.targetDeadTime[1] > 0, "(it died)"));
        }

        @Test
        @DisplayName("kinetic drain: |v|^2 * |mass| / (shotSpeed^2 * shotMass); no damage from debris, unowned, cannon shots or own team")
        void kineticAndImmune() {
            Simulation s = targetSim("shotSpeed: 21\nshotMass: 0.1\n");
            World w = s.world;
            w.params.shotHitFuelDrainUsesKineticEnergy = true;
            ModeState m = w.modes;
            Player p = ship(s, "P", 1, 300, 100);
            s.targets.damage(1, Obj.SHOT, p.id, 1, 42, 0, 0.1f, false, 0, 1f, -1);
            int kinetic = FULL - m.targetDamage[1];
            m.targetDamage[1] = FULL;
            s.targets.damage(1, Obj.DEBRIS, p.id, 1, 42, 0, 0.1f, false, 0, 1f, -1);
            s.targets.damage(1, Obj.SHOT, Obj.NO_ID, 1, 42, 0, 0.1f, false, 0, 1f, -1);
            s.targets.damage(1, Obj.CANNON_SHOT, p.id, 1, 42, 0, 0.1f, false, 0, 1f, -1);
            s.targets.damage(1, Obj.SHOT, p.id, 2, 42, 0, 0.1f, false, 0, 1f, -1);
            s.targets.damage(1, Obj.SHOT, 200, 1, 42, 0, 0.1f, false, 0, 1f, -1);
            s.targets.damage(-1, Obj.SHOT, p.id, 1, 42, 0, 0.1f, false, 0, 1f, -1);
            float df = 42f;
            df = (df * df * 0.1f) / (21f * 21f * 0.1f);
            int expected = -(int) (-25.0 * 256 * df * 1.0f);
            assertAll(
                () -> assertEquals(expected, kinetic, "twice the shot speed: four times the damage"),
                () -> assertEquals(FULL, m.targetDamage[1], "debris, unowned shots, cannon shots (not killing here),"
                    + " the target's own team, unknown owners and no target do nothing"));
        }

        @Test
        @DisplayName("no player killing: shots do nothing, ships still do")
        void noKilling() {
            Simulation s = targetSim("allowPlayerKilling: no\n");
            World w = s.world;
            Player p = ship(s, "P", 1, 300, 100);
            s.targets.damage(1, Obj.SHOT, p.id, 1, 0, 0, 1, false, 0, 1f, -1);
            int afterShot = w.modes.targetDamage[1];
            s.targets.damage(1, Obj.PLAYER, p.id, 1, 0, 0, 1, false, 0, 1f, -1);
            assertAll(
                () -> assertEquals(FULL, afterShot, "shots are not killing shots"),
                () -> assertEquals(FULL - FULL / 4, w.modes.targetDamage[1], "a ship still damages it"));
        }
    }

    @Nested
    @DisplayName("destruction and scoring")
    class Destroy {
        @Test
        @DisplayName("one of several: dead for targetDeadTime, block SPACE, 75-149 debris, event; partial score and message")
        void oneOfSeveral() {
            Simulation s = targetSim("targetDeadTime: 3\n");
            World w = s.world;
            Player p = ship(s, "P", 1, 300, 100);
            Player v = ship(s, "V", 2, 500, 100);
            long from = w.events.seq();
            long bursts = w.events.burstSeq();
            w.modes.targetDamage[1] = 10;
            s.targets.damage(1, Obj.PLAYER, p.id, 1, 0, 0, 1, false, 0, 1f, -1);
            var b = SimFixture.lastBurst(w);
            double sc = Scoring.rate(0, Scoring.CANNON_SCORE) / 4 * (2 - 1) / (2 + 1);
            assertAll(
                () -> assertEquals(3 * 14, w.targetDeadTime[1], "dead targetDeadTime seconds"),
                () -> assertEquals(FULL, w.modes.targetDamage[1], "strength restored for later"),
                () -> assertTrue(!solid(w, 1), "the block is SPACE"),
                () -> assertEquals(bursts + 1, w.events.burstSeq(), "one explosion"),
                () -> assertTrue(b.count >= 75 && b.count < 150, "75-149 debris"),
                () -> assertEquals(List.of("5:" + p.id + ":1"), gameEvents(w, from), "TARGET_DESTROYED(killer, target)"),
                () -> assertEquals(sc, w.teamScore[1], 1e-9, "the killer: Rate(score, CANNON)/4 * destroyed/(total+1)"),
                () -> assertEquals(List.of("P blew up one of team 2's targets."), messages(w, from), "message"),
                () -> assertEquals(0.0, v.score, EPS, "the losing team loses nothing yet"));
        }

        @Test
        @DisplayName("the last target: message, Rate(winners, losers) to each loser and *losers/winners to each winner; targetKillTeam kills")
        void last() {
            Simulation s = targetSim("targetKillTeam: yes\n");
            World w = s.world;
            Player p = ship(s, "P", 1, 300, 100);
            Player v = ship(s, "V", 2, 500, 100);
            Player v2 = ship(s, "W", 2, 540, 100);
            v2.status |= Obj.PAUSE;
            v2.count = 5;
            w.targetDeadTime[2] = 9;
            w.modes.targetDamage[1] = 10;
            long from = w.events.seq();
            s.targets.damage(1, Obj.PLAYER, p.id, 1, 0, 0, 1, false, 0, 1f, -1);
            double sc = Scoring.rate(0, 0);
            assertAll(
                () -> assertEquals(List.of("P blew up team 2's last target."), messages(w, from), "message"),
                () -> assertEquals(1, p.kills, "targetKillTeam: a kill"),
                () -> assertTrue((v.status & Obj.KILLED) != 0, "the live loser dies"),
                () -> assertEquals(0, v2.status & Obj.KILLED, "a paused loser does not"),
                () -> assertEquals(-2 * sc, w.teamScore[2], 1e-9, "each loser loses Rate(winners, losers)"),
                () -> assertEquals(sc * 2 / 1, w.teamScore[1], 1e-9, "each winner gains sc * losers / winners"));
        }

        @Test
        @DisplayName("no scoring outside team play or without a live loser; tiny scores and big target counts are quiet")
        void quiet() {
            Simulation solo = TestWorlds.sim(SimFixture.FPS14, ModeFx.targetRows());
            Player p = ship(solo, "P", 0, 300, 100);
            solo.world.modes.targetDamage[0] = 1;
            long from = solo.world.events.seq();
            solo.targets.damage(0, Obj.PLAYER, p.id, Obj.NO_ID, 0, 0, 1, false, 0, 1f, -1);
            Simulation dead = targetSim("");
            Player q = ship(dead, "Q", 1, 300, 100);
            Player v = ship(dead, "V", 2, 500, 100);
            v.status |= Obj.GAME_OVER;
            dead.world.modes.targetDamage[1] = 1;
            long from2 = dead.world.events.seq();
            dead.targets.damage(1, Obj.PLAYER, q.id, 1, 0, 0, 1, false, 0, 1f, -1);
            Simulation noColl = targetSim("targetTeamCollision: no\n");
            Player r = ship(noColl, "R", 1, 300, 100);
            ship(noColl, "V", 2, 500, 100);
            noColl.world.modes.targetDamage[1] = 1;
            long from3 = noColl.world.events.seq();
            noColl.targets.damage(1, Obj.PLAYER, r.id, 1, 0, 0, 1, false, 0, 1f, -1);
            assertAll(
                () -> assertTrue(solo.world.targetDeadTime[0] > 0, "outside team play the target (team 0) still dies"),
                () -> assertEquals(0.0, p.score, EPS, "but nobody scores"),
                () -> assertEquals(List.of(), messages(solo.world, from), "and nothing is said"),
                () -> assertEquals(List.of(), messages(dead.world, from2), "no live loser: nothing said"),
                () -> assertEquals(0.0, dead.world.teamScore[1], EPS, "and nothing scored"),
                () -> assertEquals(List.of(), messages(noColl.world, from3),
                    "without targetTeamCollision (targets as shields) the partial message is not given"));
        }

        @Test
        @DisplayName("a target crash damages through Crashes; a shot through Object_crash; the round reset restores all")
        void hooksAndReset() {
            Simulation s = targetSim("");
            World w = s.world;
            w.params.shotHitFuelDrainUsesKineticEnergy = false;
            Player p = ship(s, "P", 1, 300, 100);
            MoveState ms = new MoveState();
            ms.crash = MoveState.CRASH_TARGET;
            ms.target = 1;
            s.crashes.playerCrashed(p, ms, 1);
            int afterCrash = w.modes.targetDamage[1];
            int h = obj(w, Obj.SHOT, p.id, 100, 100, 0, 0, 0.1, 30);
            w.objects.team[h] = 1;
            s.crashes.objectCrashed(h, ms);
            int afterShot = w.modes.targetDamage[1];
            w.targetDeadTime[0] = 7;
            w.map.setBlock(2, 3, BlockType.SPACE);
            s.targets.resetRound();
            assertAll(
                () -> assertEquals(FULL - FULL / 4, afterCrash, "a crashing ship does a quarter"),
                () -> assertEquals(FULL - FULL / 4 - 6400, afterShot, "a crashing shot 25*256"),
                () -> assertEquals(0.0, w.objects.life[h], EPS, "the shot dies"),
                () -> assertEquals(FULL, w.modes.targetDamage[1], "reset: full strength"),
                () -> assertEquals(0, w.targetDeadTime[0], "reset: alive"),
                () -> assertTrue(solid(w, 0), "reset: solid again"));
        }
    }
}
