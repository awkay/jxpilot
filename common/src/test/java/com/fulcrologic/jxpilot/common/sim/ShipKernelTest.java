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
package com.fulcrologic.jxpilot.common.sim;

import com.fulcrologic.jxpilot.common.Const;
import com.fulcrologic.jxpilot.common.Item;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static com.fulcrologic.jxpilot.common.sim.KernelFx.moverWorld;
import static com.fulcrologic.jxpilot.common.sim.KernelFx.ship;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ShipKernel}: the ship's part of C {@code Update_objects} (update.c:735-1218) and {@code Player_set_mass}.
 * Expected values follow C's float arithmetic, as the server's {@code ShipsFlightTest} derives them.
 */
class ShipKernelTest {
    static final String E9 = "         ";
    /** 9x9 open map (walldist 10 at the centre). */
    static final String[] OPEN = ShipMoverTest.OPEN;
    static final int C = 4 * Click.BLOCK_CLICKS + 1000;

    static ShipKernel kernel(KernelFx.TestWorld w) {
        return new ShipKernel(w, w::rfrac);
    }

    /** A ship in the middle of the open map, at rest, heading up, mass 25 (20 + 256000 fuel * 0.005/256). */
    static ShipBody resting() {
        ShipBody p = ship(C, C, 32, 0, 0);
        p.mass = 25;
        p.fuel = 256000;
        p.tankFuel[0] = 256000;
        p.fuelMax = 256000 * 4;
        return p;
    }

    static float mass(double fuel) {
        return (float) (20f + fuel * 0.005 / 256 + 0f);
    }

    @Nested
    @DisplayName("limits and the playing test")
    class Limits {
        @Test
        @DisplayName("power, turn speed and turn resistance are limited to C's ranges; values inside are kept")
        void limits() {
            ShipKernel k = kernel(moverWorld("", OPEN));
            ShipBody hi = ship(C, C, 0, 0, 0);
            hi.power = 1000;
            hi.turnSpeed = 1000;
            hi.turnResistance = 5;
            k.limits(hi);
            ShipBody lo = ship(C, C, 0, 0, 0);
            lo.power = -1;
            lo.turnSpeed = -1;
            lo.turnResistance = -1;
            k.limits(lo);
            ShipBody in = ship(C, C, 0, 0, 0);
            k.limits(in);
            assertAll(
                () -> assertEquals((float) Const.MAX_PLAYER_POWER, hi.power, "power above the maximum is the maximum"),
                () -> assertEquals((float) Const.MAX_PLAYER_TURNSPEED, hi.turnSpeed, "turn speed above: the maximum"),
                () -> assertEquals((float) Const.MAX_PLAYER_TURNRESISTANCE, hi.turnResistance, "resistance above: max"),
                () -> assertEquals((float) Const.MIN_PLAYER_POWER, lo.power, "power below the minimum is the minimum"),
                () -> assertEquals((float) Const.MIN_PLAYER_TURNSPEED, lo.turnSpeed, "turn speed below: the minimum"),
                () -> assertEquals((float) Const.MIN_PLAYER_TURNRESISTANCE, lo.turnResistance, "resistance below: min"),
                () -> assertEquals(45f, in.power, "power inside the range is kept"),
                () -> assertEquals(35f, in.turnSpeed, "turn speed inside is kept"),
                () -> assertEquals(0.12f, in.turnResistance, "resistance inside is kept"));
        }

        @Test
        @DisplayName("a ship plays iff PLAYING is set and neither GAME_OVER nor PAUSE")
        void playing() {
            ShipBody p = new ShipBody();
            p.status = Obj.PLAYING | Obj.THRUSTING | Obj.KILLED;
            boolean yes = ShipKernel.playing(p);
            p.status = Obj.PLAYING | Obj.PAUSE;
            boolean paused = ShipKernel.playing(p);
            p.status = Obj.PLAYING | Obj.GAME_OVER;
            boolean over = ShipKernel.playing(p);
            p.status = Obj.THRUSTING;
            boolean dead = ShipKernel.playing(p);
            assertAll(
                () -> assertTrue(yes, "PLAYING (other bits do not matter)"),
                () -> assertFalse(paused, "paused"),
                () -> assertFalse(over, "game over"),
                () -> assertFalse(dead, "not playing"));
        }
    }

    @Nested
    @DisplayName("fly: the player loop for one frame")
    class Fly {
        @Test
        @DisplayName("calls the world interactions in C's order around the turn, thrust and move")
        void order() {
            KernelFx.TestWorld w = moverWorld("", OPEN);
            ShipBody p = resting();
            p.status |= Obj.THRUSTING;
            KernelFx.Rec r = new KernelFx.Rec();
            boolean stop = kernel(w).fly(p, r);
            assertAll(
                () -> assertEquals("begin2 devices upkeep thrustSparks sensors end2", r.log(), "the hooks in C's order"),
                () -> assertFalse(stop, "a frame without a warp does not stop the player loop"));
        }

        @Test
        @DisplayName("thrust accelerates by power/mass and burns fuel; the mass follows the fuel; the ship moves")
        void thrust() {
            KernelFx.TestWorld w = moverWorld("", OPEN);
            ShipBody p = resting();
            p.status |= Obj.THRUSTING;
            kernel(w).fly(p, new KernelFx.Rec());
            float acc = 45f * Trig.tsin(32) / 25f;
            assertAll(
                () -> assertEquals(acc, p.accY, "acc = power * tsin(dir) / mass (the mass before this frame's update)"),
                () -> assertEquals(acc, p.vy, "one frame of thrust adds acc to vy"),
                () -> assertEquals(256000 - 9.0, p.fuel, "fuel drops by (long)(power*0.0008*256) = 9"),
                () -> assertEquals(mass(255991), p.mass, "mass follows the fuel (Player_set_mass)"),
                () -> assertEquals(C + (int) (acc * 64), p.cy, "the ship moved vy pixels up (in clicks)"));
        }

        @Test
        @DisplayName("gravity applies to ships with GRAVITY, on top of the acceleration")
        void gravity() {
            KernelFx.TestWorld w = moverWorld("gravity: -0.5", OPEN);
            ShipBody g = resting();
            g.status |= Obj.GRAVITY;
            kernel(w).fly(g, new KernelFx.Rec());
            KernelFx.TestWorld planet = moverWorld("", "         ", "         ", "    +    ", E9, E9, E9, E9, E9, E9);
            ShipBody t = resting();
            t.status |= Obj.GRAVITY | Obj.THRUSTING;
            t.dir = 16;
            t.floatDir = 16;
            t.vx = 1;
            t.vy = 2;
            kernel(planet).fly(t, new KernelFx.Rec());
            float tgx = planet.gravityX(t.bx, t.by);
            float tgy = planet.gravityY(t.bx, t.by);
            ShipBody u = resting();
            u.status |= Obj.THRUSTING;
            u.dir = 16;
            u.floatDir = 16;
            u.vx = 1;
            u.vy = 2;
            kernel(planet).fly(u, new KernelFx.Rec());
            ShipBody n = resting();
            kernel(w).fly(n, new KernelFx.Rec());
            float gy = w.gravityY(g.bx, g.by);
            assertAll(
                () -> assertTrue(gy < 0, "(fixture: the map pulls down)"),
                () -> assertEquals(gy, g.vy, "a GRAVITY ship gains the block's gravity"),
                () -> assertEquals(w.gravityX(g.bx, g.by), g.vx, "x too"),
                () -> assertEquals(0.0f, n.vy, "a ship without GRAVITY does not"),
                () -> assertTrue(tgx != 0 && tgy != 0, "(fixture: the planet pulls in x and y)"),
                () -> assertTrue(Math.abs(u.accX) > 1, "(fixture: thrust at 45 degrees has an x part)"),
                () -> assertEquals(1f + (t.accX + tgx), t.vx, "vx += accX + gravity x"),
                () -> assertEquals(2f + (t.accY + tgy), t.vy, "vy += accY + gravity y"),
                () -> assertEquals(1f + u.accX, u.vx, "without GRAVITY vx += accX"),
                () -> assertEquals(2f + u.accY, u.vy, "and vy += accY"));
        }

        @Test
        @DisplayName("a stun ticks down and drops shields, laser, shot and thrust")
        void stun() {
            ShipBody p = resting();
            p.stunned = 2;
            p.have = Has.SHIELD | Has.LASER | Has.SHOT | Has.COMPASS;
            p.used = Has.SHIELD | Has.LASER | Has.SHOT | Has.COMPASS;
            p.status |= Obj.THRUSTING;
            KernelFx.Rec r = new KernelFx.Rec();
            kernel(moverWorld("", OPEN)).fly(p, r);
            assertAll(
                () -> assertEquals(1, p.stunned, "the stun counts down"),
                () -> assertEquals(Has.COMPASS, p.used, "shield, laser and shot are dropped, other uses kept"),
                () -> assertEquals(0, p.status & Obj.THRUSTING, "thrust stops"),
                () -> assertFalse(r.log().contains("thrustSparks"), "so there is no exhaust"));
        }

        @Test
        @DisplayName("shields drain (long)ED_SHIELD = 51 fuel per frame")
        void shieldFuel() {
            ShipBody p = resting();
            p.have = Has.SHIELD;
            p.used = Has.SHIELD;
            kernel(moverWorld("", OPEN)).fly(p, new KernelFx.Rec());
            assertEquals(256000 - 51.0, p.fuel, "51 fuel units");
        }

        @Test
        @DisplayName("out of fuel drops shields, cloak, deflector and thrust; a nearly full ship stops refuelling")
        void fuelLimits() {
            ShipBody empty = resting();
            empty.fuel = 0;
            empty.tankFuel[0] = 0;
            empty.have = Has.SHIELD | Has.CLOAKING_DEVICE | Has.DEFLECTOR | Has.COMPASS;
            empty.used = Has.SHIELD | Has.CLOAKING_DEVICE | Has.DEFLECTOR | Has.COMPASS;
            empty.status |= Obj.THRUSTING;
            kernel(moverWorld("", OPEN)).fly(empty, new KernelFx.Rec());
            ShipBody full = resting();
            full.fuel = full.fuelMax - ShipKernel.REFUEL_RATE + 1;
            full.have = Has.REFUEL;
            full.used = Has.REFUEL;
            kernel(moverWorld("", OPEN)).fly(full, new KernelFx.Rec());
            ShipBody room = resting();
            room.fuel = room.fuelMax - ShipKernel.REFUEL_RATE;
            room.have = Has.REFUEL;
            room.used = Has.REFUEL;
            kernel(moverWorld("", OPEN)).fly(room, new KernelFx.Rec());
            ShipBody one = resting();
            one.fuel = 1;
            one.have = Has.CLOAKING_DEVICE;
            one.used = Has.CLOAKING_DEVICE;
            kernel(moverWorld("", OPEN)).fly(one, new KernelFx.Rec());
            assertAll(
                () -> assertEquals(Has.COMPASS, empty.used, "shield, cloak and deflector are dropped"),
                () -> assertEquals(0, empty.status & Obj.THRUSTING, "and thrust"),
                () -> assertEquals(0, full.used, "more than fuelMax - REFUEL_RATE: refuelling stops"),
                () -> assertEquals(Has.REFUEL, room.used, "exactly fuelMax - REFUEL_RATE: it goes on"),
                () -> assertEquals(Has.CLOAKING_DEVICE, one.used, "one unit of fuel keeps the cloak"));
        }

        @Test
        @DisplayName("a warping ship goes through the warp; when it ends the player loop the frame stops there")
        void warp() {
            KernelFx.TestWorld w = moverWorld("", OPEN);
            ShipBody p = resting();
            p.vx = 2;
            p.status |= Obj.WARPING;
            KernelFx.Rec stop = new KernelFx.Rec();
            stop.warpStops = true;
            boolean stopped = kernel(w).fly(p, stop);
            ShipBody q = resting();
            q.vx = 2;
            q.status |= Obj.WARPING;
            KernelFx.Rec go = new KernelFx.Rec();
            boolean went = kernel(w).fly(q, go);
            ShipBody n = resting();
            KernelFx.Rec none = new KernelFx.Rec();
            kernel(w).fly(n, none);
            assertAll(
                () -> assertTrue(stopped, "the loop's break is returned"),
                () -> assertEquals("begin2 devices upkeep warp end2", stop.log(), "nothing after the warp, end still"),
                () -> assertEquals(C, p.cx, "the ship does not move"),
                () -> assertFalse(went, "a warp that does not break goes on"),
                () -> assertEquals(C + 128, q.cx, "and the ship moves"),
                () -> assertFalse(none.log().contains("warp"), "a ship that is not warping does not warp"));
        }

        @Test
        @DisplayName("a paused ship does not gain gravity or move")
        void paused() {
            ShipBody p = resting();
            p.status |= Obj.PAUSE | Obj.GRAVITY;
            p.vx = 2;
            kernel(moverWorld("gravity: -0.5", OPEN)).fly(p, new KernelFx.Rec());
            assertAll(
                () -> assertEquals(C, p.cx, "no move"),
                () -> assertEquals(0.0f, p.vy, "no gravity"));
        }

        @Test
        @DisplayName("exhaust: none while cloaked (unless cloakedExhaust) or phased; turn sparks 5 + (fuel >> 2)")
        void exhaust() {
            KernelFx.TestWorld w = moverWorld("", OPEN);
            w.params.cloakedExhaust = false;
            KernelFx.Rec cloaked = thrusting(w, Has.CLOAKING_DEVICE);
            KernelFx.Rec plain = thrusting(w, Has.SHIELD);
            KernelFx.Rec phased = thrusting(w, Has.PHASING_DEVICE);
            KernelFx.TestWorld ce = moverWorld("", OPEN);
            ce.params.cloakedExhaust = true;
            KernelFx.Rec shown = thrusting(ce, Has.CLOAKING_DEVICE);
            KernelFx.TestWorld tt = moverWorld("", OPEN);
            tt.params.turnThrust = true;
            ShipBody t = resting();
            t.turnAcc = 35;
            KernelFx.Rec turn = new KernelFx.Rec();
            kernel(tt).fly(t, turn);
            KernelFx.TestWorld nt = moverWorld("", OPEN);
            ShipBody u = resting();
            u.turnAcc = 35;
            KernelFx.Rec noTurn = new KernelFx.Rec();
            kernel(nt).fly(u, noTurn);
            KernelFx.Rec still = new KernelFx.Rec();
            kernel(nt).fly(resting(), still);
            assertAll(
                () -> assertFalse(cloaked.log().contains("Sparks"), "cloaked: no exhaust"),
                () -> assertTrue(plain.log().contains("thrustSparks"), "not cloaked: exhaust, cloakedExhaust or not"),
                () -> assertFalse(phased.log().contains("Sparks"), "phased: no exhaust"),
                () -> assertTrue(shown.log().contains("thrustSparks"), "cloakedExhaust shows it"),
                () -> assertEquals(6, turn.turnSparks, "tf = (long)(1.28 * 4) = 5: 5 + (5 >> 2) = 6 turn sparks"),
                () -> assertEquals(256000 - 5.0, t.fuel, "the turn cost 5"),
                () -> assertEquals(-1, noTurn.turnSparks, "without turnThrust no turn sparks"),
                () -> assertEquals("begin2 devices upkeep sensors end2", still.log(), "no thrust, no turn: no exhaust"));
        }

        KernelFx.Rec thrusting(KernelFx.TestWorld w, int used) {
            ShipBody p = resting();
            p.status |= Obj.THRUSTING;
            p.have = used;
            p.used = used;
            KernelFx.Rec r = new KernelFx.Rec();
            kernel(w).fly(p, r);
            return r;
        }

        @Test
        @DisplayName("the shield timer runs in the frame")
        void shieldTimer() {
            ShipBody p = resting();
            p.shieldTime = 4;
            p.have = Has.SHIELD;
            p.used = Has.SHIELD;
            kernel(moverWorld("", OPEN)).fly(p, new KernelFx.Rec());
            assertEquals(3, p.shieldTime, "one frame off the timer");
        }

        @Test
        @DisplayName("abilities the ship does not have are no longer used after the frame")
        void usedFollowsHave() {
            ShipBody p = resting();
            p.have = Has.COMPASS;
            p.used = Has.COMPASS | Has.CONNECTOR;
            kernel(moverWorld("", OPEN)).fly(p, new KernelFx.Rec());
            assertEquals(Has.COMPASS, p.used, "used &= have");
        }

        @Test
        @DisplayName("turning: the heading follows floatDir, near a wall Turn_player traces it")
        void turns() {
            ShipBody p = resting();
            p.turnAcc = 35;
            kernel(moverWorld("", OPEN)).fly(p, new KernelFx.Rec());
            float v = 35f * 0.12f;
            assertAll(
                () -> assertEquals(v, p.turnVel, "(0 + 35) * 0.12f"),
                () -> assertEquals(32f + v, p.floatDir, "floatDir += turnVel"),
                () -> assertEquals(36, p.dir, "dir is floatDir rounded"));
        }
    }

    @Nested
    @DisplayName("step: one predicted own-ship frame")
    class Step {
        @Test
        @DisplayName("keys, autofire, limits and fly, in that order; the thrust key takes effect in the same frame")
        void all() {
            KernelFx.TestWorld w = moverWorld("", OPEN);
            ShipBody p = resting();
            p.power = 1000;
            KernelFx.Rec r = new KernelFx.Rec();
            boolean stop = kernel(w).step(p, com.fulcrologic.jxpilot.common.net.ControlKey.THRUST.mask(), r);
            assertAll(
                () -> assertEquals("begin0 end0 begin1 end1 begin2 devices upkeep thrustSparks sensors end2", r.log(),
                    "the three phases"),
                () -> assertEquals((float) Const.MAX_PLAYER_POWER, p.power, "the power was limited before thrusting"),
                () -> assertEquals((float) Const.MAX_PLAYER_POWER * Trig.tsin(32) / 25f, p.accY, "and thrust used it"),
                () -> assertFalse(stop, "no warp"));
        }

        @Test
        @DisplayName("a ship that is not playing, or any ship during the round delay, does not fly; a warp break is returned")
        void gates() {
            KernelFx.TestWorld w = moverWorld("", OPEN);
            ShipBody dead = resting();
            dead.status = 0;
            KernelFx.Rec rd = new KernelFx.Rec();
            boolean d = kernel(w).step(dead, 0, rd);
            KernelFx.TestWorld delay = moverWorld("", OPEN);
            delay.roundDelay = 5;
            ShipBody p = resting();
            KernelFx.Rec rp = new KernelFx.Rec();
            kernel(delay).step(p, 0, rp);
            ShipBody warping = resting();
            warping.status |= Obj.WARPING;
            KernelFx.Rec rw = new KernelFx.Rec();
            rw.warpStops = true;
            boolean wb = kernel(w).step(warping, 0, rw);
            assertAll(
                () -> assertEquals("begin0 end0 begin1 end1", rd.log(), "dead: keys and autofire only"),
                () -> assertFalse(d, "and false"),
                () -> assertEquals("begin0 end0 begin1 end1", rp.log(), "round delay: keys and autofire only"),
                () -> assertTrue(wb, "fly's warp break is returned"));
        }

        @Test
        @DisplayName("a replaced tie-break source is the mover's")
        void tieBreak() {
            KernelFx.TestWorld w = moverWorld("", ShipMoverTest.LEFT_WALL);
            ShipKernel k = kernel(w);
            int[] calls = new int[1];
            k.tieBreak(() -> {
                calls[0]++;
                return 0.0;
            });
            k.mover.movePlayer(ship(4480 + 576 + 64, 5600, 0, -2, 0), new KernelFx.Rec());
            assertEquals(1, calls[0], "the kernel's mover drew from the new source");
        }
    }

    @Nested
    @DisplayName("shield timer (update.c:793-805)")
    class ShieldTime {
        @Test
        @DisplayName("counts down; at 0 drops the shield unless an emergency shield is used; then stops and the ability goes")
        void timer() {
            ShipKernel k = kernel(moverWorld("", OPEN));
            ShipBody run = shielded(3, 0);
            k.shieldTime(run);
            ShipBody last = shielded(1, 0);
            k.shieldTime(last);
            ShipBody emergency = shielded(1, Has.EMERGENCY_SHIELD);
            k.shieldTime(emergency);
            ShipBody down = shielded(5, 0);
            down.used = 0;
            k.shieldTime(down);
            ShipBody idle = shielded(0, 0);
            k.shieldTime(idle);
            ShipBody stopped = shielded(0, 0);
            stopped.used = 0;
            k.shieldTime(stopped);
            ShipBody downEm = shielded(5, 0);
            downEm.used = Has.EMERGENCY_SHIELD;
            k.shieldTime(downEm);
            assertAll(
                () -> assertEquals(2, run.shieldTime, "a running timer counts down"),
                () -> assertEquals(Has.SHIELD, run.used & Has.SHIELD, "the shield stays up meanwhile"),
                () -> assertEquals(0, last.used & Has.SHIELD, "reaching 0 drops the shield"),
                () -> assertEquals(0, last.have & Has.SHIELD, "and the ability"),
                () -> assertEquals(0, last.shieldTime, "the timer stops"),
                () -> assertEquals(Has.SHIELD, emergency.used & Has.SHIELD, "an emergency shield keeps it up"),
                () -> assertEquals(0, emergency.shieldTime, "the timer still ran out"),
                () -> assertEquals(0, down.have & Has.SHIELD, "a shield already down loses the ability"),
                () -> assertEquals(0, down.shieldTime, "and the timer stops at once"),
                () -> assertEquals(Has.SHIELD, idle.have & Has.SHIELD, "no timer: nothing changes"),
                () -> assertEquals(Has.SHIELD, stopped.have, "no timer and the shield down: the ability stays"),
                () -> assertEquals(0, stopped.shieldTime, "and the timer stays 0"),
                () -> assertEquals(Has.SHIELD, downEm.have & Has.SHIELD, "with an emergency shield the ability stays"),
                () -> assertEquals(0, downEm.shieldTime, "but the timer stops"));
        }

        ShipBody shielded(int time, int extra) {
            ShipBody p = resting();
            p.shieldTime = time;
            p.have = Has.SHIELD;
            p.used = Has.SHIELD | extra;
            return p;
        }
    }

    @Nested
    @DisplayName("turn (update.c:860-905)")
    class Turn {
        @Test
        @DisplayName("the C recurrence; zero resistance turns once and stops; full resistance keeps adding")
        void recurrence() {
            ShipKernel k = kernel(moverWorld("", OPEN));
            ShipBody p = resting();
            p.turnAcc = 35;
            k.turn(p);
            float v1 = p.turnVel;
            k.turn(p);
            ShipBody z = resting();
            z.turnResistance = 0;
            z.turnAcc = 3;
            z.turnVel = 1;
            long tf = k.turn(z);
            ShipBody f = resting();
            f.turnResistance = 1;
            f.turnAcc = 2;
            f.turnVel = 1;
            k.turn(f);
            assertAll(
                () -> assertEquals((0f + 35f) * 0.12f, v1, "(0 + 35) * 0.12f"),
                () -> assertEquals(((0f + 35f) * 0.12f + 35f) * 0.12f, p.turnVel, "(4.2 + 35) * 0.12f"),
                () -> assertEquals(36.0f, z.floatDir, "pointer mode: float_dir += turnvel + turnacc"),
                () -> assertEquals(0.0f, z.turnVel, "turnvel is reset (linear turning)"),
                () -> assertEquals(0L, tf, "no turn fuel without turnThrust"),
                () -> assertEquals(3.0f, f.turnVel, "r = 1: turnvel += turnacc, kept"),
                () -> assertEquals(35.0f, f.floatDir, "heading moves by turnvel"));
        }

        @Test
        @DisplayName("the heading wraps into [0, RES)")
        void wraps() {
            ShipKernel k = kernel(moverWorld("", OPEN));
            ShipBody p = resting();
            p.turnResistance = 0;
            p.floatDir = 127;
            p.turnAcc = 3;
            k.turn(p);
            float up = p.floatDir;
            p.floatDir = 1;
            p.turnAcc = -3;
            k.turn(p);
            float down = p.floatDir;
            p.floatDir = 0;
            p.turnAcc = -128;
            k.turn(p);
            float exact = p.floatDir;
            p.floatDir = 127;
            p.turnAcc = 1;
            k.turn(p);
            float res = p.floatDir;
            p.floatDir = 1;
            p.turnAcc = -257;
            k.turn(p);
            float twice = p.floatDir;
            assertAll(
                () -> assertEquals(2.0f, up, "127 + 3 wraps to 2"),
                () -> assertEquals(126.0f, down, "1 - 3 wraps to 126"),
                () -> assertEquals(0.0f, exact, "-128 wraps to exactly 0, never RES"),
                () -> assertEquals(0.0f, res, "exactly RES wraps to 0"),
                () -> assertEquals(0.0f, twice, "more than one RES below wraps more than once"));
        }

        @Test
        @DisplayName("turnThrust: (long)(1.28 * |(long)dv|) fuel; too little fuel cancels the turn")
        void turnFuel() {
            KernelFx.TestWorld w = moverWorld("", OPEN);
            w.params.turnThrust = true;
            ShipKernel k = kernel(w);
            ShipBody p = resting();
            p.turnAcc = 0;
            p.turnResistance = 1;
            p.turnVel = 3.9f;
            long tf = k.turn(p);
            ShipBody c = resting();
            c.turnAcc = 30;
            c.oldTurnVel = 0.5f;
            c.turnVel = 0.5f;
            c.fuel = 3;
            long tc = k.turn(c);
            ShipBody e = resting();
            e.turnAcc = 30;
            e.oldTurnVel = 0.5f;
            e.turnVel = 0.5f;
            e.fuel = 4;
            long te = k.turn(e);
            ShipBody n = resting();
            n.turnAcc = -35;
            long tn = k.turn(n);
            assertAll(
                () -> assertEquals(3L, tf, "(long)(1.28 * (long)3.9) = (long)3.84 = 3"),
                () -> assertEquals(256000 - 3.0, p.fuel, "and that is what is charged"),
                () -> assertEquals(3.9f, p.oldTurnVel, "the old turn velocity is remembered"),
                () -> assertEquals(0L, tc, "fuel 3 <= cost 3: nothing is charged"),
                () -> assertEquals(0.0f, c.turnAcc, "turnacc is cleared"),
                () -> assertEquals(0.5f, c.turnVel, "turnvel is restored"),
                () -> assertEquals(32.5f, c.floatDir, "the heading moves only by the restored velocity"),
                () -> assertEquals(3L, te, "with 4 fuel the turn is charged 3"),
                () -> assertEquals(1.0, e.fuel, "leaving the rest"),
                () -> assertEquals(5L, tn, "a right turn costs the same: |(long)(0 - -4.2)| = 4, (long)5.12 = 5"));
        }
    }

    @Nested
    @DisplayName("thrust acceleration (update.c:1018-1037)")
    class Thrust {
        @Test
        @DisplayName("power*(tcos,tsin)/mass, fuel (long)(-power*0.0008*256); afterburners and emergency thrust; none without thrust")
        void acceleration() {
            ShipKernel k = kernel(moverWorld("", OPEN));
            ShipBody a = resting();
            a.dir = 16;
            a.status |= Obj.THRUSTING;
            k.thrustAcceleration(a);
            ShipBody ab = resting();
            ab.status |= Obj.THRUSTING;
            ab.items[Item.AFTERBURNER.ordinal()] = 4;
            k.thrustAcceleration(ab);
            ShipBody em = resting();
            em.status |= Obj.THRUSTING;
            em.used = Has.EMERGENCY_THRUST;
            k.thrustAcceleration(em);
            ShipBody off = resting();
            off.accX = 3;
            off.accY = 4;
            k.thrustAcceleration(off);
            assertAll(
                () -> assertEquals(45f * Trig.tcos(16) / 25f, a.accX, "x: power * cos(45 deg) / mass (floats)"),
                () -> assertEquals(45f * Trig.tsin(16) / 25f, a.accY, "y: power * sin(45 deg) / mass"),
                () -> assertEquals(256000 - 9.0, a.fuel, "fuel drops by 9"),
                () -> assertEquals((float) (45f * (1.0 + 4 * ((4.2 - 1.0) / 16))) * Trig.tsin(32) / 25f, ab.accY,
                    "4 afterburners: power (float)(45*1.8)"),
                () -> assertEquals(256000 - 13.0, ab.fuel, "and (long)(0.036*24/16*256) = 13 fuel"),
                () -> assertEquals(180f * Trig.tsin(32) / 25f, em.accY, "emergency thrust: 15 afterburners, power 4x"),
                () -> assertEquals(256000 - 26.0, em.fuel, "26 fuel"),
                () -> assertEquals(0.0f, off.accX, "without thrust no x acceleration"),
                () -> assertEquals(0.0f, off.accY, "nor y"),
                () -> assertEquals(256000.0, off.fuel, "and no fuel"));
        }
    }

    @Nested
    @DisplayName("Player_set_mass")
    class Mass {
        @Test
        @DisplayName("emptyMass + fuel*0.005/256 + armor*(shipMass/14) + minItemMass per other item; fuel and tanks weigh nothing")
        void mass() {
            KernelFx.TestWorld w = moverWorld("", OPEN);
            w.params.shipMass = 20;
            w.params.minItemMass = 2;
            ShipKernel k = kernel(w);
            ShipBody p = resting();
            p.items[Item.ARMOR.ordinal()] = 2;
            p.items[Item.MINE.ordinal()] = 3;
            p.items[Item.FUEL.ordinal()] = 7;
            p.items[Item.TANK.ordinal()] = 5;
            k.setMass(p);
            assertEquals((float) (20 + 256000 * 0.005 / 256 + (2 * (20f / 14) + 3 * 2f)), p.mass,
                "20 + 5 + 2*(20/14) + 3*2, the items summed in float");
        }
    }
}
