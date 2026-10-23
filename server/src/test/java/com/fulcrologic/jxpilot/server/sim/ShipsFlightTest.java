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
import com.fulcrologic.jxpilot.common.net.ControlKey;
import com.fulcrologic.jxpilot.server.world.Has;
import com.fulcrologic.jxpilot.server.world.Obj;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.Trig;
import com.fulcrologic.jxpilot.server.world.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static com.fulcrologic.jxpilot.server.sim.ShipFx.C_FRAMES;
import static com.fulcrologic.jxpilot.server.sim.ShipFx.FPS14;
import static com.fulcrologic.jxpilot.server.sim.ShipFx.ROOM;
import static com.fulcrologic.jxpilot.server.sim.ShipFx.keys;
import static com.fulcrologic.jxpilot.server.sim.ShipFx.sim;
import static com.fulcrologic.jxpilot.server.sim.ShipFx.tick;
import static com.fulcrologic.jxpilot.server.sim.ShipFx.ticks;
import static com.fulcrologic.jxpilot.server.sim.ShipFx.unshield;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ships.update flight: thrust, turning, gravity, shields, limits and mass (update.c player loop). */
class ShipsFlightTest {
    static final long THRUST = keys(ControlKey.THRUST);
    static final long LEFT = keys(ControlKey.TURN_LEFT);

    /** C FUEL_MASS on top of the 20-unit ship (no items). */
    /** C Player_set_mass for an item-less ship: (float)(20f + fuel*0.005/256 + 0f). */
    static float mass(double fuel) {
        return (float) (20f + fuel * 0.005 / 256 + 0f);
    }

    @Nested
    @DisplayName("thrust")
    class Thrust {
        @Test
        void oneCFrameOfThrustAcceleratesByPowerOverMassAndBurnsTruncatedFuel() {
            Simulation s = sim(C_FRAMES, ROOM);
            Player p = s.ships.join("a", 0);
            unshield(p);
            tick(s, p, THRUST);
            // update.c: acc = power * tsin(dir) / mass (mass before this frame's Player_set_mass);
            // fuel -= (long)(45 * 0.0008 * 256) = 9
            float acc1 = 45f * Trig.tsin(32) / mass(256000);
            double vy1 = p.vy;
            double fuel1 = p.fuel;
            double mass1 = p.mass;
            int y1 = p.cy;
            tick(s, p, THRUST);
            float acc2 = 45f * Trig.tsin(32) / mass(255991);
            assertAll(
                () -> assertEquals(1.8, acc1, 1e-6, "45 / 25 = 1.8 px/frame^2 (as a C float)"),
                () -> assertEquals(acc1, vy1, "one frame of thrust adds acc to vy"),
                () -> assertEquals(256000 - 9.0, fuel1, "fuel drops by (long)(power*0.0008*256) = 9"),
                () -> assertEquals(mass(255991), mass1, "mass follows the fuel (Player_set_mass)"),
                () -> assertEquals(12320 + (int) (acc1 * 64), y1, "the ship moved vy pixels up (in clicks)"),
                () -> assertEquals(acc2, p.accY, "the next frame uses the lighter mass"),
                () -> assertEquals(acc1 + acc2, p.vy, "velocity accumulates"),
                () -> assertEquals(45f * Trig.tcos(32) / mass(255991), p.accX, "x acceleration uses tcos(dir)"),
                () -> assertEquals(256000 - 18.0, p.fuel, "9 more fuel units are used"),
                () -> assertEquals(Obj.THRUSTING, p.status & Obj.THRUSTING, "the ship is thrusting"));
        }

        @Test
        void at14FpsEachTickIsAWholeFrame() {
            Simulation s = sim(FPS14, ROOM);
            Player p = s.ships.join("a", 0);
            unshield(p);
            tick(s, p, THRUST);
            assertAll(
                () -> assertEquals(14, s.world.params.fps, "the sim ticks at framesPerSecond"),
                () -> assertEquals(p.accY, p.vy, "velocity gains one frame of acceleration"),
                () -> assertEquals(256000 - 9.0, p.fuel, "fuel use is one frame's 9"));
        }

        @Test
        void releasingThrustZeroesTheAcceleration() {
            Simulation s = sim(C_FRAMES, ROOM);
            Player p = s.ships.join("a", 0);
            unshield(p);
            tick(s, p, THRUST);
            tick(s, p, 0);
            assertAll(
                () -> assertEquals(0.0, p.accX, "no x acceleration"),
                () -> assertEquals(0.0, p.accY, "no y acceleration"),
                () -> assertEquals(45f * Trig.tsin(32) / mass(256000), p.vy, "velocity is kept (no friction)"),
                () -> assertEquals(256000 - 9.0, p.fuel, "no fuel is used without thrust"));
        }

        @Test
        void afterburnersMultiplyPowerAndFuelUse() {
            Simulation s = sim(C_FRAMES + "initialAfterburners: 4\n", ROOM);
            Player p = s.ships.join("a", 0);
            unshield(p);
            double m0 = p.mass;
            tick(s, p, THRUST);
            // serverconst.h AFTER_BURN_POWER: 45*(1+4*(3.2/16)) = 81;
            // AFTER_BURN_FUEL: 0.036*(16+4*2)/16 = 0.054 -> (long)(0.054*256) = 13
            assertAll(
                () -> assertEquals(20 + 5 + 4 * 1.0, m0, "4 afterburners weigh minItemMass each"),
                () -> assertEquals((float) (45f * (1.0 + 4 * ((4.2 - 1.0) / 16))) * Trig.tsin(32) / (float) m0, p.accY,
                    "power is (float)(45*1.8), times tsin, over the float mass"),
                () -> assertEquals(81.0, 45 * (1.0 + 4 * ((4.2 - 1.0) / 16)), 1e-12, "which is 81"),
                () -> assertEquals(256000 - 13.0, p.fuel, "fuel use is 13 per frame"));
        }

        @Test
        void emergencyThrustCountsAsMaximumAfterburners() {
            Simulation s = sim(C_FRAMES, ROOM);
            Player p = s.ships.join("a", 0);
            unshield(p);
            p.have |= Has.EMERGENCY_THRUST;
            p.used |= Has.EMERGENCY_THRUST;
            tick(s, p, THRUST);
            // a = MAX_AFTERBURNER = 15: power 45*(1+15*0.2) = 180, fuel (long)(0.036*46/16*256) = 26
            assertAll(
                () -> assertEquals(180f * Trig.tsin(32) / 25f, p.accY, "power is 4x"),
                () -> assertEquals(256000 - 26.0, p.fuel, "fuel use is 26 per frame"));
        }
        @Test
        void thrustAtAnAngleSplitsThePowerByCosAndSin() {
            Simulation s = sim(C_FRAMES, ROOM);
            Player p = s.ships.join("a", 0);
            p.dir = 16;
            p.status |= Obj.THRUSTING;
            s.ships.thrustAcceleration(p);
            assertAll(
                () -> assertEquals(45f * Trig.tcos(16) / 25f, p.accX, "x: power * cos(45 deg) / mass (floats)"),
                () -> assertEquals(45f * Trig.tsin(16) / 25f, p.accY, "y: power * sin(45 deg) / mass"),
                () -> assertEquals((float) Math.sqrt(0.5), Trig.tsin(16), "heading 16 is 45 degrees (a float)"));
        }
    }

    @Nested
    @DisplayName("turn")
    class Turn {
        @Test
        void atOneFrameATurnKeyFollowsTheCRecurrence() {
            Simulation s = sim(C_FRAMES, ROOM);
            Player p = s.ships.join("a", 0);
            tick(s, p, LEFT);
            double v1 = p.turnVel;
            double d1 = p.floatDir;
            int dir1 = p.dir;
            tick(s, p, LEFT);
            // update.c: turnvel = (turnvel + turnacc) * turnresistance; float_dir += turnvel (C floats)
            float ev1 = (0f + 35f) * 0.12f;
            float ed1 = 32f + ev1;
            float ev2 = (ev1 + 35f) * 0.12f;
            assertAll(
                () -> assertEquals(35.0f, p.turnAcc, "left adds turnspeed (client default 35)"),
                () -> assertEquals(ev1, v1, "(0 + 35) * 0.12f"),
                () -> assertEquals(ed1, d1, "32 + 4.2"),
                () -> assertEquals(36, dir1, "dir is float_dir rounded"),
                () -> assertEquals(ev2, p.turnVel, "(4.2 + 35) * 0.12f"),
                () -> assertEquals(ed1 + ev2, p.floatDir, "heading accumulates"));
        }

        @Test
        void at14FpsTheRecurrenceIsIntegratedExactlyOverWholeFrames() {
            Simulation s = sim(FPS14, ROOM);
            Player p = s.ships.join("a", 0);
            ticks(s, p, 7, LEFT);
            float v = 0;
            for (int i = 0; i < 7; i++) {
                v = (v + 35f) * 0.12f;
            }
            float expected = v;
            assertEquals(expected, p.turnVel, "at 14 fps 7 ticks are 7 C frames of the float recurrence");
        }

        @Test
        void bothTurnKeysCancelAndRightTurnsNegative() {
            Simulation s = sim(C_FRAMES, ROOM);
            Player p = s.ships.join("a", 0);
            tick(s, p, keys(ControlKey.TURN_LEFT, ControlKey.TURN_RIGHT));
            double both = p.turnAcc;
            double dirBoth = p.floatDir;
            tick(s, p, keys(ControlKey.TURN_RIGHT));
            assertAll(
                () -> assertEquals(0.0, both, "left and right cancel"),
                () -> assertEquals(32.0, dirBoth, "no turn"),
                () -> assertEquals(-35.0f, p.turnAcc, "right alone subtracts turnspeed"),
                () -> assertEquals(32f + (-35f * 0.12f), p.floatDir, "and turns clockwise"));
        }

        @Test
        void zeroResistanceTurnsByTheAccelerationOnceAndStops() {
            Simulation s = sim(C_FRAMES, ROOM);
            Player p = s.ships.join("a", 0);
            p.turnResistance = 0;
            p.turnAcc = 3;
            p.turnVel = 1;
            double tf = s.ships.turn(p, s.world.params);
            assertAll(
                () -> assertEquals(36.0, p.floatDir, "pointer mode: float_dir += turnvel + turnacc"),
                () -> assertEquals(0.0, p.turnVel, "turnvel is reset (linear turning)"),
                () -> assertEquals(0.0, tf, "no turn fuel without turnThrust"));
        }

        @Test
        void fullResistanceKeepsAddingTheAcceleration() {
            Simulation s = sim(FPS14, ROOM);
            Player p = s.ships.join("a", 0);
            p.turnResistance = 1;
            p.turnAcc = 2;
            p.turnVel = 1;
            s.ships.turn(p, s.world.params);
            assertAll(
                () -> assertEquals(3.0f, p.turnVel, "r = 1: turnvel += turnacc, kept"),
                () -> assertEquals(35.0f, p.floatDir, "heading moves by turnvel"));
        }

        @Test
        void theHeadingWrapsIntoZeroToRes() {
            Simulation s = sim(C_FRAMES, ROOM);
            Player p = s.ships.join("a", 0);
            p.turnResistance = 0;
            p.floatDir = 127;
            p.turnAcc = 3;
            s.ships.turn(p, s.world.params);
            double up = p.floatDir;
            p.floatDir = 1;
            p.turnAcc = -3;
            s.ships.turn(p, s.world.params);
            double down = p.floatDir;
            p.floatDir = 0;
            p.turnAcc = -128;
            s.ships.turn(p, s.world.params);
            double exact = p.floatDir;
            p.floatDir = 127;
            p.turnAcc = 1;
            s.ships.turn(p, s.world.params);
            double res = p.floatDir;
            assertAll(
                () -> assertEquals(2.0, up, "127 + 3 wraps to 2"),
                () -> assertEquals(126.0, down, "1 - 3 wraps to 126"),
                () -> assertEquals(0.0, exact, "-128 wraps to exactly 0, never RES"),
                () -> assertEquals(0.0, res, "exactly RES wraps to 0"));
        }

        @Test
        void turnThrustCostsTruncatedFuelPerTurnVelocityChangeAndMakesSparks() {
            Simulation s = sim(C_FRAMES + "turnThrust: yes\n", ROOM);
            Player p = s.ships.join("a", 0);
            unshield(p);
            int objs0 = s.world.objects.count();
            tick(s, p, LEFT);
            double fuel1 = p.fuel;
            int sparks = s.world.objects.count() - objs0;
            tick(s, p, LEFT);
            // update.c: tf = (long)(0 - 35*0.12f) = -4; TURN_FUEL(tf) = (long)(1.28 * 4) = 5; TURN_SPARKS(5) = 6
            // second frame: (long)(4.2 - 4.704) = 0 costs nothing, no sparks
            assertAll(
                () -> assertEquals(256000 - 5.0, fuel1, "the first frame costs (long)(1.28 * (long)|dv|) = 5"),
                () -> assertEquals(6, sparks, "and makes 5 + (5 >> 2) = 6 turn sparks"),
                () -> assertEquals(256000 - 5.0, p.fuel, "a change below one whole unit costs nothing"),
                () -> assertEquals(p.turnVel, p.oldTurnVel, "the old turn velocity is remembered"));
        }

        @Test
        void turnFuelTruncatesTheVelocityChangeBeforeScalingLikeC() {
            Simulation s = sim(FPS14 + "turnThrust: yes\n", ROOM);
            Player p = s.ships.join("a", 0);
            p.turnAcc = 0;
            p.turnResistance = 1;
            p.oldTurnVel = 0;
            p.turnVel = 3.9f;
            long tf = s.ships.turn(p, s.world.params);
            assertAll(
                () -> assertEquals(3L, tf, "(long)(1.28 * (long)3.9) = (long)3.84 = 3, not (long)(1.28*3.9) = 4"),
                () -> assertEquals(256000 - 3.0, p.fuel, "and that is what is charged"));
        }

        @Test
        void turnThrustWithoutEnoughFuelCancelsTheTurn() {
            Simulation s = sim(C_FRAMES + "turnThrust: yes\n", ROOM);
            Player p = s.ships.join("a", 0);
            p.turnAcc = 30;
            p.oldTurnVel = 0.5f;
            p.turnVel = 0.5f;
            p.fuel = 3;
            double tf = s.ships.turn(p, s.world.params);
            double acc4 = p.turnAcc;
            double vel4 = p.turnVel;
            double dir4 = p.floatDir;
            p.turnAcc = 30;
            p.turnVel = 0.5f;
            p.fuel = 4;
            double tf5 = s.ships.turn(p, s.world.params);
            // update.c: turnvel = (0.5 + 30) * 0.12f = 3.66; TURN_FUEL = (long)(1.28 * |(long)(0.5 - 3.66)|) = 3
            assertAll(
                () -> assertEquals(0.0, tf, "fuel 3 <= cost 3: nothing is charged"),
                () -> assertEquals(0.0, acc4, "turnacc is cleared"),
                () -> assertEquals(0.5, vel4, "turnvel is restored"),
                () -> assertEquals(32.5, dir4, "the heading moves only by the restored velocity"),
                () -> assertEquals(3.0, tf5, "with 4 fuel the turn is charged 3"),
                () -> assertEquals(1.0, p.fuel, "leaving the rest"));
        }
    }

    @Nested
    @DisplayName("gravity")
    class Gravity {
        @Test
        void globalGravityAddsItsVectorEveryFrame() {
            Simulation s = sim("framesPerSecond: 60\ngravity: -0.5\ngravityAngle: 0\n", ROOM);
            World w = s.world;
            Player p = s.ships.join("a", 0);
            unshield(p);
            double gx = w.gravityX(5, 5);
            double gy = w.gravityY(5, 5);
            tick(s, p, 0);
            double vx1 = p.vx;
            tick(s, p, 0);
            assertAll(
                () -> assertEquals(-0.5, gx, 1e-12, "gravityAngle 0 points the -0.5 gravity along x"),
                () -> assertEquals(0.0, gy, 1e-12, "and none along y"),
                () -> assertEquals(gx, vx1, "update_object_speed: vel += gravity once per frame"),
                () -> assertEquals(2 * gx, p.vx, 1e-15, "twice after two frames"),
                () -> assertEquals(2 * gy, p.vy, 1e-15, "y gets the y component"));
        }

        @Test
        void at14FpsSidewaysGravityIsAWholeFrame() {
            Simulation s = sim("gravity: -0.5\ngravityAngle: 0\n", ROOM);
            Player p = s.ships.join("a", 0);
            float gx = s.world.gravityX(5, 5);
            tick(s, p, 0);
            assertEquals(gx, p.vx, "vel.x += g.x (one tick is one frame at 14 fps)");
        }

        @Test
        void at14FpsGravityIsAWholeFrame() {
            Simulation s = sim("gravity: -0.5\n", ROOM);
            Player p = s.ships.join("a", 0);
            float gy = s.world.gravityY(5, 5);
            tick(s, p, 0);
            assertAll(
                () -> assertEquals(-0.5f, gy, "default angle 90: gravity points down"),
                () -> assertEquals(gy, p.vy, "vel += g"));
        }

        @Test
        void aLocalGravitySourceAddsTheFieldOfTheShipsBlock() {
            Simulation s = sim(C_FRAMES,
                "xxxxxxxxxxx",
                "x         x",
                "x         x",
                "x         x",
                "x         x",
                "x    _+   x",
                "x         x",
                "x         x",
                "x         x",
                "x         x",
                "xxxxxxxxxxx");
            World w = s.world;
            Player p = s.ships.join("a", 0);
            double gx = w.gravityX(5, 5);
            double gy = w.gravityY(5, 5);
            tick(s, p, 0);
            assertAll(
                () -> assertTrue(gx != 0, "the + source next to the base makes a field there"),
                () -> assertEquals(gx, p.vx, "vx gains the block's field"),
                () -> assertEquals(gy, p.vy, "vy gains the block's field"));
        }
    }

    @Nested
    @DisplayName("shields")
    class Shields {
        @Test
        void raisedShieldsDrainTruncatedFuelEachFrame() {
            Simulation s = sim(C_FRAMES, ROOM);
            Player p = s.ships.join("a", 0);
            tick(s, p, 0);
            double one = p.fuel;
            tick(s, p, 0);
            // score.h ED_SHIELD = -0.20*256, cast to long in update.c: -51
            assertAll(
                () -> assertEquals(Has.SHIELD, p.used & Has.SHIELD, "a new ship is shielded"),
                () -> assertEquals(256000 - 51.0, one, "one frame costs 51"),
                () -> assertEquals(256000 - 102.0, p.fuel, "two frames cost 102"));
        }

        @Test
        void at14FpsTheShieldDrainIsAWholeFrame() {
            Simulation s = sim(FPS14, ROOM);
            Player p = s.ships.join("a", 0);
            tick(s, p, 0);
            assertEquals(256000 - 51.0, p.fuel, "fuel -= 51 per tick");
        }

        @Test
        void runningOutOfFuelDropsShieldsAndThrust() {
            Simulation s = sim(C_FRAMES, ROOM);
            Player p = s.ships.join("a", 0);
            p.fuel = 30;
            tick(s, p, THRUST);
            assertAll(
                () -> assertEquals(0.0, p.fuel, "the drain empties the tank"),
                () -> assertEquals(0, p.used & Has.SHIELD, "shields drop"),
                () -> assertEquals(0, p.status & Obj.THRUSTING, "thrust stops"),
                () -> assertEquals(0.0, p.accY, "there is no acceleration"),
                () -> assertEquals(0.0, p.vy, "and no speed"));
        }

        @Test
        void aTimedStartShieldLastsExactly2SecondsThenTheAbilityGoes() {
            Simulation s = sim(C_FRAMES + "allowShields: no\n", ROOM);
            Player p = s.ships.join("a", 0);
            ticks(s, p, 119, 0);
            int used119 = p.used & Has.SHIELD;
            double t119 = p.shieldTime;
            tick(s, p, 0);
            assertAll(
                () -> assertEquals(Has.SHIELD, used119, "still shielded after 119 frames"),
                () -> assertEquals(1.0, t119, "with 1 frame left"),
                () -> assertEquals(0, p.used & Has.SHIELD, "the shield drops after 120 frames"),
                () -> assertEquals(0, p.have & Has.SHIELD, "and the ability is gone"),
                () -> assertEquals(0.0, p.shieldTime, "the timer is 0"),
                () -> assertEquals(256000 - 120 * 51.0 + 51, p.fuel, "it drained 51 per frame while up (119 frames)"));
        }

        @Test
        void aTimedStartShieldAt14FpsLasts28Ticks() {
            Simulation s = sim(FPS14 + "allowShields: no\n", ROOM);
            Player p = s.ships.join("a", 0);
            ticks(s, p, 27, 0);
            int used27 = p.used & Has.SHIELD;
            tick(s, p, 0);
            assertAll(
                () -> assertEquals(28.0, 2 * s.world.params.fps, 1e-12, "2*FPS = 28 frames"),
                () -> assertEquals(Has.SHIELD, used27, "still up after 27 ticks"),
                () -> assertEquals(0, p.used & Has.SHIELD, "down after 28 ticks = 2 s"));
        }

        @Test
        void loweringATimedShieldEndsItAndTheAbility() {
            Simulation s = sim(C_FRAMES + "allowShields: no\n", ROOM);
            Player p = s.ships.join("a", 0);
            tick(s, p, keys(ControlKey.SHIELD));
            tick(s, p, 0);
            assertAll(
                () -> assertEquals(0, p.used & Has.SHIELD, "releasing the key lowers the shield"),
                () -> assertEquals(0, p.have & Has.SHIELD, "the temporary ability is removed"),
                () -> assertEquals(0.0, p.shieldTime, "and the timer cleared"));
        }

        @Test
        void anEmergencyShieldKeepsTheShieldWhenTheTimerEnds() {
            Simulation s = sim(C_FRAMES + "allowShields: no\n", ROOM);
            Player p = s.ships.join("a", 0);
            p.have |= Has.EMERGENCY_SHIELD;
            p.used |= Has.EMERGENCY_SHIELD;
            p.shieldTime = 1;
            tick(s, p, 0);
            assertAll(
                () -> assertEquals(Has.SHIELD, p.used & Has.SHIELD, "the shield stays up"),
                () -> assertEquals(Has.SHIELD, p.have & Has.SHIELD, "the ability stays"),
                () -> assertEquals(0.0, p.shieldTime, "the timer has run out"));
        }
    }

    @Nested
    @DisplayName("update limits and states")
    class Limits {
        @Test
        void powerTurnspeedAndResistanceAreLimitedToTheirCRanges() {
            Simulation s = sim(C_FRAMES, ROOM);
            Player p = s.ships.join("a", 0);
            p.power = 100;
            p.turnSpeed = 100;
            p.turnResistance = 2;
            tick(s, p, 0);
            double pw = p.power;
            double ts = p.turnSpeed;
            double tr = p.turnResistance;
            p.power = 1;
            p.turnSpeed = 1;
            p.turnResistance = -1;
            tick(s, p, 0);
            // const.h MIN/MAX_PLAYER_POWER 5/55, TURNSPEED 4/64, TURNRESISTANCE 0/1
            assertAll(
                () -> assertEquals(55.0, pw, "power above 55 is limited"),
                () -> assertEquals(64.0, ts, "turnspeed above 64 is limited"),
                () -> assertEquals(1.0, tr, "turnresistance above 1 is limited"),
                () -> assertEquals(5.0, p.power, "power below 5 is raised"),
                () -> assertEquals(4.0, p.turnSpeed, "turnspeed below 4 is raised"),
                () -> assertEquals(0.0, p.turnResistance, "turnresistance below 0 is raised"));
        }

        @Test
        void damageAndStunTimersRunDownAndStunBlocksShieldsShotsAndThrust() {
            Simulation s = sim(FPS14, ROOM);
            Player p = s.ships.join("a", 0);
            p.damaged = 5;
            p.stunned = 1;
            p.used |= Has.SHOT;
            p.have |= Has.SHOT;
            tick(s, p, THRUST);
            assertAll(
                () -> assertEquals(4, p.damaged, "damage counts down by one frame"),
                () -> assertEquals(0.0, p.stunned, "stun counts down and stops at 0"),
                () -> assertEquals(0, p.used & (Has.SHIELD | Has.SHOT), "stunned: shields and shots off"),
                () -> assertEquals(0, p.status & Obj.THRUSTING, "stunned: no thrust"),
                () -> assertEquals(0.0, p.vy, "so the ship did not accelerate"));
        }

        @Test
        void aPausedShipLosesALittleScoreAndDoesNotMove() {
            Simulation s = sim(FPS14, ROOM);
            Player p = s.ships.join("a", 0);
            p.status |= Obj.PAUSE;
            p.vx = 5;
            int x = p.cx;
            tick(s, p, 0);
            assertAll(
                () -> assertEquals(-0.01, p.score, 1e-15, "score -= 0.01 per frame"),
                () -> assertEquals(x, p.cx, "the ship does not move"),
                () -> assertEquals(256000.0, p.fuel, "and uses no fuel"));
        }

        @Test
        void aGameOverShipIsNotUpdated() {
            Simulation s = sim(C_FRAMES, ROOM);
            Player p = s.ships.join("a", 0);
            p.status |= Obj.GAME_OVER;
            p.vx = 5;
            int x = p.cx;
            s.ships.update(p);
            assertAll(
                () -> assertEquals(0.0, p.score, "no pause penalty"),
                () -> assertEquals(x, p.cx, "no movement"),
                () -> assertEquals(256000.0, p.fuel, "no shield drain"));
        }

        @Test
        void usedAbilitiesAreLimitedToOwnedOnes() {
            Simulation s = sim(C_FRAMES, ROOM);
            Player p = s.ships.join("a", 0);
            p.used |= Has.CLOAKING_DEVICE;
            tick(s, p, 0);
            assertEquals(0, p.used & Has.CLOAKING_DEVICE, "used &= have at the end of the frame");
        }

        @Test
        void aCloakedShipMakesNoTurnSparksUnlessCloakedExhaust() {
            Simulation s = sim(C_FRAMES + "turnThrust: yes\ncloakedExhaust: no\n", ROOM);
            Player p = s.ships.join("a", 0);
            unshield(p);
            p.have |= Has.CLOAKING_DEVICE;
            p.used |= Has.CLOAKING_DEVICE;
            int objs = s.world.objects.count();
            tick(s, p, LEFT);
            assertEquals(objs, s.world.objects.count(), "no exhaust while cloaked");
        }

        @Test
        void aPhasedShipMakesNoTurnSparks() {
            Simulation s = sim(C_FRAMES + "turnThrust: yes\n", ROOM);
            Player p = s.ships.join("a", 0);
            unshield(p);
            p.have |= Has.PHASING_DEVICE;
            p.used |= Has.PHASING_DEVICE;
            int objs = s.world.objects.count();
            tick(s, p, LEFT);
            assertEquals(objs, s.world.objects.count(), "no exhaust while phased");
        }

        @Test
        void aCloakedShipWithCloakedExhaustStillMakesTurnSparks() {
            Simulation s = sim(C_FRAMES + "turnThrust: yes\n", ROOM);
            Player p = s.ships.join("a", 0);
            unshield(p);
            p.have |= Has.CLOAKING_DEVICE;
            p.used |= Has.CLOAKING_DEVICE;
            int objs = s.world.objects.count();
            tick(s, p, LEFT);
            assertEquals(objs + 6, s.world.objects.count(), "cloakedExhaust: the 6 sparks are made");
        }

        @Test
        void anUncloakedShipMakesTurnSparksEvenWithoutCloakedExhaust() {
            Simulation s = sim(C_FRAMES + "turnThrust: yes\ncloakedExhaust: no\n", ROOM);
            Player p = s.ships.join("a", 0);
            unshield(p);
            int objs = s.world.objects.count();
            tick(s, p, LEFT);
            assertEquals(objs + 6, s.world.objects.count(), "cloakedExhaust only matters when cloaked");
        }

        @Test
        void turnThrustMakesNoSparksWithoutATurn() {
            Simulation s = sim(C_FRAMES + "turnThrust: yes\n", ROOM);
            Player p = s.ships.join("a", 0);
            unshield(p);
            int objs = s.world.objects.count();
            tick(s, p, 0);
            assertEquals(objs, s.world.objects.count(), "no turn fuel used: no sparks (tf > 0)");
        }

        @Test
        void at14FpsATickMakesAWholeFramesTurnSparks() {
            Simulation s = sim(FPS14 + "turnThrust: yes\n", ROOM);
            Player p = s.ships.join("a", 0);
            unshield(p);
            int objs = s.world.objects.count();
            tick(s, p, LEFT);
            int n = s.world.objects.count() - objs;
            // tf = (long)(1.28 * 4) = 5: TURN_SPARKS = 5 + (5 >> 2) = 6, no per-tick scaling
            assertEquals(6, n, "TURN_SPARKS(tf) sparks, exactly as one C frame");
        }

        @Test
        void thrustMakesExhaustSparks() {
            Simulation s = sim(C_FRAMES, ROOM);
            Player p = s.ships.join("a", 0);
            unshield(p);
            int objs = s.world.objects.count();
            tick(s, p, THRUST);
            assertTrue(s.world.objects.count() > objs, "a thrusting ship makes exhaust (C Thrust)");
        }
    }

    @Nested
    @DisplayName("setMass, hitArmor, recordShove")
    class MassArmorShove {
        @Test
        void massIsEmptyMassPlusFuelPlusArmorPlusOtherItems() {
            Simulation s = sim(C_FRAMES + "minItemMass: 2\n", ROOM);
            Player p = s.ships.join("a", 0);
            p.items[Item.ARMOR.ordinal()] = 3;
            p.items[Item.MINE.ordinal()] = 2;
            p.items[Item.TANK.ordinal()] = 5;
            p.items[Item.FUEL.ordinal()] = 7;
            p.fuel = 512;
            s.ships.setMass(p);
            // player.c Player_set_mass: ARMOR_MASS = ShipMass/14; fuel and tanks weigh nothing as items
            float items = 0f + 2 * 2f;
            items += 3 * (20f / 14);
            assertEquals((float) (20f + 512 * 0.005 / 256 + items), p.mass,
                "20 + 0.01 + (float)(2*2 + 3*20/14) in item order, as C floats");
        }

        @Test
        void eachHitUsesOneArmorAndTheLastRemovesTheAbility() {
            Simulation s = sim(C_FRAMES + "initialArmor: 2\n", ROOM);
            Player p = s.ships.join("a", 0);
            s.ships.hitArmor(p);
            int afterOne = p.have & Has.ARMOR;
            s.ships.hitArmor(p);
            assertAll(
                () -> assertEquals(Has.ARMOR, afterOne, "one armor left keeps HAS_ARMOR"),
                () -> assertEquals(0, p.items[Item.ARMOR.ordinal()], "both armors are used"),
                () -> assertEquals(0, p.have & Has.ARMOR, "the last one removes HAS_ARMOR"));
        }

        @Test
        void shovesAreRecordedInARingOfFourWithTheFrameClock() {
            Simulation s = sim(C_FRAMES, ShipsJoinTest.THREE);
            Player p = s.ships.join("a", 0);
            Player q = s.ships.join("b", 0);
            for (int i = 0; i < 5; i++) {
                s.world.frames = 10 + i;
                s.ships.recordShove(p, i % 2 == 0 ? q : p);
            }
            assertAll(
                () -> assertEquals(q.id, p.shovePusher[0], "the fifth shove overwrites slot 0"),
                () -> assertEquals(14.0, p.shoveTime[0], "with its frame time"),
                () -> assertEquals(p.id, p.shovePusher[1], "slot 1 holds the second shove"),
                () -> assertEquals(11.0, p.shoveTime[1], "at frame 11"),
                () -> assertEquals(13.0, p.shoveTime[3], "slot 3 holds the fourth shove"),
                () -> assertEquals(1, p.shoveNext, "the next shove goes to slot 1"));
        }
    }
}
