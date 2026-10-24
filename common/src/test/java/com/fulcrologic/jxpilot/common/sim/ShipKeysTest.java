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
import com.fulcrologic.jxpilot.common.net.ControlKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static com.fulcrologic.jxpilot.common.sim.KernelFx.moverWorld;
import static com.fulcrologic.jxpilot.common.sim.KernelFx.ship;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link ShipKeys}: the key-edge loop of C {@code Handle_keyboard} (event.c) and the flight keys. */
class ShipKeysTest {
    static final long THRUST = ControlKey.THRUST.mask();
    static final long LEFT = ControlKey.TURN_LEFT.mask();
    static final long RIGHT = ControlKey.TURN_RIGHT.mask();
    static final long SHIELD = ControlKey.SHIELD.mask();
    static final long FIRE = ControlKey.FIRE_SHOT.mask();
    static final long PAUSE = ControlKey.PAUSE.mask();
    static final long CLOAK = ControlKey.CLOAK.mask();

    static ShipKeys keys(KernelFx.TestWorld w) {
        return new ShipKeys(w, new ShipShooter(w));
    }

    static ShipBody pilot() {
        ShipBody p = ship(4 * Click.BLOCK_CLICKS, 4 * Click.BLOCK_CLICKS, 32, 0, 0);
        p.have = Has.SHIELD | Has.SHOT | Has.LASER;
        return p;
    }

    @Nested
    @DisplayName("the edge loop")
    class Loop {
        @Test
        @DisplayName("only changed keys act, in ascending key order; the handled mask is remembered")
        void edges() {
            KernelFx.TestWorld w = moverWorld("", ShipMoverTest.OPEN);
            ShipKeys k = keys(w);
            ShipBody p = pilot();
            KernelFx.Rec r = new KernelFx.Rec();
            k.apply(p, CLOAK | ControlKey.LOCK_NEXT.mask() | ControlKey.ECM.mask(), r);
            KernelFx.Rec again = new KernelFx.Rec();
            k.apply(p, CLOAK | ControlKey.LOCK_NEXT.mask() | ControlKey.ECM.mask(), again);
            KernelFx.Rec up = new KernelFx.Rec();
            k.apply(p, ControlKey.ECM.mask(), up);
            assertAll(
                () -> assertEquals("begin0 pressed:LOCK_NEXT pressed:CLOAK pressed:ECM end0", r.log(),
                    "the other keys are passed on in key order"),
                () -> assertEquals("begin0 end0", again.log(), "unchanged keys do nothing"),
                () -> assertEquals("begin0 released:LOCK_NEXT released:CLOAK end0", up.log(), "releases too"),
                () -> assertEquals(ControlKey.ECM.mask(), p.prevKeys, "the handled mask is remembered"),
                () -> assertEquals(ControlKey.ECM.mask(), p.lastReceivedKeys, "and the received one"));
        }

        @Test
        @DisplayName("any change except SHIELD marks the ship busy")
        void busy() {
            KernelFx.TestWorld w = moverWorld("", ShipMoverTest.OPEN);
            w.frames = 40;
            ShipBody p = pilot();
            p.frameLastBusy = 3;
            keys(w).apply(p, SHIELD, new KernelFx.Rec());
            long afterShield = p.frameLastBusy;
            keys(w).apply(p, SHIELD | THRUST, new KernelFx.Rec());
            assertAll(
                () -> assertEquals(3L, afterShield, "a shield change alone is not activity"),
                () -> assertEquals(40L, p.frameLastBusy, "thrust is"));
        }

        @Test
        @DisplayName("after Go_home held keys wait for a change of the client's mask; then all held keys count as pressed")
        void keysCleared() {
            KernelFx.TestWorld w = moverWorld("", ShipMoverTest.OPEN);
            ShipKeys k = keys(w);
            ShipBody p = pilot();
            p.keysCleared = true;
            p.lastReceivedKeys = THRUST;
            p.prevKeys = 0;
            k.apply(p, THRUST, new KernelFx.Rec());
            int held = p.status & Obj.THRUSTING;
            k.apply(p, THRUST | LEFT, new KernelFx.Rec());
            assertAll(
                () -> assertEquals(0, held, "the same mask is ignored"),
                () -> assertTrue(p.keysCleared == false, "a new mask ends the wait"),
                () -> assertEquals(Obj.THRUSTING, p.status & Obj.THRUSTING, "the held thrust counts as pressed"),
                () -> assertEquals(35f, p.turnAcc, "and so does the new key"));
        }

        @Test
        @DisplayName("during the round delay only C's round-delay presses act; releases always act")
        void roundDelay() {
            KernelFx.TestWorld w = moverWorld("", ShipMoverTest.OPEN);
            w.roundDelay = 10;
            ShipKeys k = keys(w);
            ShipBody p = pilot();
            KernelFx.Rec r = new KernelFx.Rec();
            k.apply(p, THRUST | PAUSE, r);
            ShipBody q = pilot();
            q.status |= Obj.THRUSTING;
            q.prevKeys = THRUST;
            k.apply(q, 0, new KernelFx.Rec());
            assertAll(
                () -> assertEquals(0, p.status & Obj.THRUSTING, "thrust is not a round-delay key"),
                () -> assertEquals("begin0 pressed:PAUSE end0", r.log(), "pause is"),
                () -> assertEquals(THRUST | PAUSE, p.prevKeys, "both count as handled"),
                () -> assertEquals(0, q.status & Obj.THRUSTING, "a release acts in the round delay"));
        }

        @Test
        @DisplayName("a dead, paused or game-over ship handles only the keys C allows then (turning, settings, pause...)")
        void dead() {
            KernelFx.TestWorld w = moverWorld("", ShipMoverTest.OPEN);
            ShipBody p = pilot();
            p.status = 0;
            KernelFx.Rec r = new KernelFx.Rec();
            keys(w).apply(p, THRUST | LEFT | CLOAK | PAUSE, r);
            ShipBody h = pilot();
            h.status |= Obj.HOVERPAUSE;
            keys(w).apply(h, THRUST, new KernelFx.Rec());
            assertAll(
                () -> assertEquals(0, p.status & Obj.THRUSTING, "no thrust while dead"),
                () -> assertEquals(35f, p.turnAcc, "turning is allowed"),
                () -> assertEquals("begin0 pressed:PAUSE end0", r.log(), "pause is passed on, cloak is not"),
                () -> assertEquals(0, h.status & Obj.THRUSTING, "a hover-paused ship counts as not alive"));
        }

        @Test
        @DisplayName("a phased ship ignores presses C does not allow while phased; releases still act")
        void phased() {
            KernelFx.TestWorld w = moverWorld("", ShipMoverTest.OPEN);
            ShipBody p = pilot();
            p.used = Has.PHASING_DEVICE;
            KernelFx.Rec r = new KernelFx.Rec();
            keys(w).apply(p, FIRE | THRUST | ControlKey.ECM.mask(), r);
            assertAll(
                () -> assertEquals(0, p.used & Has.SHOT, "fire is not a phased key"),
                () -> assertEquals(Obj.THRUSTING, p.status & Obj.THRUSTING, "thrust is"),
                () -> assertEquals("begin0 end0", r.log(), "ECM is not passed on"));
        }

        @Test
        @DisplayName("a press that ends key handling (an unpause) leaves just that key handled")
        void unpause() {
            KernelFx.TestWorld w = moverWorld("", ShipMoverTest.OPEN);
            ShipBody p = pilot();
            KernelFx.Rec r = new KernelFx.Rec();
            r.pressedStops = true;
            keys(w).apply(p, PAUSE | THRUST, r);
            assertAll(
                () -> assertEquals(PAUSE, p.prevKeys, "the handled mask is PAUSE alone"),
                () -> assertEquals(0, p.status & Obj.THRUSTING, "the later THRUST is not handled"),
                () -> assertEquals("begin0 pressed:PAUSE end0", r.log(), "the phase still ends"));
        }

        @Test
        @DisplayName("a press that changes the power is reported")
        void powerChanged() {
            KernelFx.TestWorld w = moverWorld("", ShipMoverTest.OPEN);
            ShipBody p = pilot();
            KernelFx.Rec r = new KernelFx.Rec();
            keys(w).apply(p, ControlKey.INCREASE_POWER.mask(), r);
            ShipBody q = pilot();
            q.power = (float) Const.MAX_PLAYER_POWER;
            KernelFx.Rec same = new KernelFx.Rec();
            keys(w).apply(q, ControlKey.INCREASE_POWER.mask(), same);
            assertAll(
                () -> assertEquals("begin0 powerChanged end0", r.log(), "more power: reported"),
                () -> assertEquals("begin0 end0", same.log(), "at the maximum nothing changes: not reported"));
        }
    }

    @Nested
    @DisplayName("flight key presses")
    class Presses {
        @Test
        @DisplayName("SHIELD raises shields (dropping the laser) if the ship has them")
        void shield() {
            KernelFx.TestWorld w = moverWorld("", ShipMoverTest.OPEN);
            ShipBody p = pilot();
            p.used = Has.LASER;
            keys(w).pressed(p, ControlKey.SHIELD, new KernelFx.Rec());
            ShipBody q = pilot();
            q.have = Has.LASER;
            q.used = Has.LASER;
            keys(w).pressed(q, ControlKey.SHIELD, new KernelFx.Rec());
            assertAll(
                () -> assertEquals(Has.SHIELD, p.used, "shield up, laser off"),
                () -> assertEquals(Has.LASER, q.used, "without shields nothing changes"));
        }

        @Test
        @DisplayName("FIRE_SHOT fires and starts autofire unless shielded or already firing")
        void fire() {
            KernelFx.TestWorld w = moverWorld("", ShipMoverTest.OPEN);
            w.frames = 100;
            ShipBody p = pilot();
            KernelFx.Rec r = new KernelFx.Rec();
            keys(w).pressed(p, ControlKey.FIRE_SHOT, r);
            ShipBody s = pilot();
            s.used = Has.SHIELD;
            KernelFx.Rec rs = new KernelFx.Rec();
            keys(w).pressed(s, ControlKey.FIRE_SHOT, rs);
            ShipBody f = pilot();
            f.used = Has.SHOT;
            KernelFx.Rec rf = new KernelFx.Rec();
            keys(w).pressed(f, ControlKey.FIRE_SHOT, rf);
            ShipBody n = pilot();
            n.have = 0;
            keys(w).pressed(n, ControlKey.FIRE_SHOT, new KernelFx.Rec());
            assertAll(
                () -> assertEquals(Has.SHOT, p.used, "autofire starts"),
                () -> assertEquals(1, r.shots, "and a shot is fired"),
                () -> assertEquals(0, rs.shots, "not with shields up"),
                () -> assertEquals(0, rf.shots, "not when already firing"),
                () -> assertEquals(0, n.used, "not without the ability"));
        }

        @Test
        @DisplayName("TURN keys set turnAcc = (left - right) * turnSpeed; THRUST thrusts; both switch an autopilot off")
        void turnAndThrust() {
            KernelFx.TestWorld w = moverWorld("", ShipMoverTest.OPEN);
            ShipKeys k = keys(w);
            ShipBody p = pilot();
            k.apply(p, LEFT, new KernelFx.Rec());
            float left = p.turnAcc;
            k.apply(p, LEFT | RIGHT, new KernelFx.Rec());
            float both = p.turnAcc;
            k.apply(p, RIGHT, new KernelFx.Rec());
            float right = p.turnAcc;
            k.apply(p, 0, new KernelFx.Rec());
            ShipBody a = pilot();
            a.used = Has.AUTOPILOT;
            KernelFx.Rec ra = new KernelFx.Rec();
            k.apply(a, THRUST, ra);
            KernelFx.Rec rr = new KernelFx.Rec();
            a.used = Has.AUTOPILOT;
            k.apply(a, 0, rr);
            ShipBody t = pilot();
            t.used = Has.AUTOPILOT;
            KernelFx.Rec rt = new KernelFx.Rec();
            k.apply(t, LEFT, rt);
            t.used = Has.AUTOPILOT;
            KernelFx.Rec rtu = new KernelFx.Rec();
            k.apply(t, 0, rtu);
            assertAll(
                () -> assertEquals(35f, left, "left: +turnSpeed"),
                () -> assertEquals(0f, both, "both: 0"),
                () -> assertEquals(-35f, right, "right: -turnSpeed"),
                () -> assertEquals(0f, p.turnAcc, "released: 0"),
                () -> assertEquals("begin0 autopilotOff end0", ra.log(), "thrust switches the autopilot off"),
                () -> assertEquals("begin0 autopilotOff end0", rr.log(), "releasing thrust switches it off too"),
                () -> assertEquals(0, a.status & Obj.THRUSTING, "and stops thrusting"),
                () -> assertEquals("begin0 autopilotOff end0", rt.log(), "turning switches it off"),
                () -> assertEquals("begin0 autopilotOff end0", rtu.log(), "releasing a turn key too"));
        }

        @Test
        @DisplayName("SWAP_SETTINGS swaps power, turn speed and resistance with the alternates when not turning")
        void swap() {
            KernelFx.TestWorld w = moverWorld("", ShipMoverTest.OPEN);
            ShipBody p = pilot();
            p.powerS = 10;
            p.turnSpeedS = 20;
            p.turnResistanceS = 0.5f;
            keys(w).pressed(p, ControlKey.SWAP_SETTINGS, new KernelFx.Rec());
            ShipBody t = pilot();
            t.turnAcc = 1;
            t.powerS = 10;
            keys(w).pressed(t, ControlKey.SWAP_SETTINGS, new KernelFx.Rec());
            ShipBody h = pilot();
            h.status |= Obj.HOVERPAUSE;
            h.powerS = 10;
            keys(w).pressed(h, ControlKey.SWAP_SETTINGS, new KernelFx.Rec());
            assertAll(
                () -> assertEquals(10f, p.power, "power swapped"),
                () -> assertEquals(45f, p.powerS, "into the alternate"),
                () -> assertEquals(20f, p.turnSpeed, "turn speed swapped"),
                () -> assertEquals(35f, p.turnSpeedS, "into the alternate"),
                () -> assertEquals(0.5f, p.turnResistance, "resistance swapped"),
                () -> assertEquals(0.12f, p.turnResistanceS, "into the alternate"),
                () -> assertEquals(45f, t.power, "not while turning"),
                () -> assertEquals(45f, h.power, "not while hover-paused"));
        }

        @Test
        @DisplayName("power +-10% and turn speed +-5% (not while turning) within C's limits; not hover-paused or on autopilot")
        void settings() {
            KernelFx.TestWorld w = moverWorld("", ShipMoverTest.OPEN);
            ShipKeys k = keys(w);
            ShipBody p = pilot();
            k.pressed(p, ControlKey.INCREASE_POWER, new KernelFx.Rec());
            float up = p.power;
            k.pressed(p, ControlKey.DECREASE_POWER, new KernelFx.Rec());
            float down = p.power;
            k.pressed(p, ControlKey.INCREASE_TURNSPEED, new KernelFx.Rec());
            float tsUp = p.turnSpeed;
            k.pressed(p, ControlKey.DECREASE_TURNSPEED, new KernelFx.Rec());
            float tsDown = p.turnSpeed;
            ShipBody lim = pilot();
            lim.power = (float) Const.MIN_PLAYER_POWER;
            lim.turnSpeed = (float) Const.MAX_PLAYER_TURNSPEED;
            k.pressed(lim, ControlKey.DECREASE_POWER, new KernelFx.Rec());
            k.pressed(lim, ControlKey.INCREASE_TURNSPEED, new KernelFx.Rec());
            ShipBody lo = pilot();
            lo.turnSpeed = (float) Const.MIN_PLAYER_TURNSPEED;
            lo.power = (float) Const.MAX_PLAYER_POWER;
            k.pressed(lo, ControlKey.DECREASE_TURNSPEED, new KernelFx.Rec());
            k.pressed(lo, ControlKey.INCREASE_POWER, new KernelFx.Rec());
            ShipBody turning = pilot();
            turning.turnAcc = 2;
            k.pressed(turning, ControlKey.INCREASE_TURNSPEED, new KernelFx.Rec());
            k.pressed(turning, ControlKey.DECREASE_TURNSPEED, new KernelFx.Rec());
            ShipBody auto = pilot();
            auto.used = Has.AUTOPILOT;
            k.pressed(auto, ControlKey.INCREASE_POWER, new KernelFx.Rec());
            k.pressed(auto, ControlKey.DECREASE_POWER, new KernelFx.Rec());
            k.pressed(auto, ControlKey.INCREASE_TURNSPEED, new KernelFx.Rec());
            k.pressed(auto, ControlKey.DECREASE_TURNSPEED, new KernelFx.Rec());
            assertAll(
                () -> assertEquals((float) (45f * 1.10), up, "power * 1.10"),
                () -> assertEquals((float) (up * 0.90), down, "power * 0.90"),
                () -> assertEquals((float) (35f * 1.05), tsUp, "turn speed * 1.05"),
                () -> assertEquals((float) (tsUp * 0.95), tsDown, "turn speed * 0.95"),
                () -> assertEquals((float) Const.MIN_PLAYER_POWER, lim.power, "power stops at the minimum"),
                () -> assertEquals((float) Const.MAX_PLAYER_TURNSPEED, lim.turnSpeed, "turn speed at the maximum"),
                () -> assertEquals((float) Const.MIN_PLAYER_TURNSPEED, lo.turnSpeed, "turn speed at the minimum"),
                () -> assertEquals((float) Const.MAX_PLAYER_POWER, lo.power, "power at the maximum"),
                () -> assertEquals(35f, turning.turnSpeed, "turn speed does not change while turning"),
                () -> assertEquals(45f, auto.power, "on autopilot the power keys do nothing"),
                () -> assertEquals(35f, auto.turnSpeed, "nor the turn speed keys"));
        }

        @Test
        @DisplayName("a settings key that does nothing (hover-paused or on autopilot) does not end key handling")
        void settingsKeepHandling() {
            KernelFx.TestWorld w = moverWorld("", ShipMoverTest.OPEN);
            ControlKey[] settings = {ControlKey.SWAP_SETTINGS, ControlKey.INCREASE_POWER, ControlKey.DECREASE_POWER,
                ControlKey.INCREASE_TURNSPEED, ControlKey.DECREASE_TURNSPEED};
            int handled = 0;
            for (ControlKey s : settings) {
                ShipBody p = pilot();
                p.used = Has.AUTOPILOT;
                keys(w).apply(p, s.mask() | THRUST, new KernelFx.Rec());
                if ((p.status & Obj.THRUSTING) != 0 && p.prevKeys == (s.mask() | THRUST)) {
                    handled++;
                }
            }
            int n = handled;
            assertEquals(settings.length, n, "the later THRUST is handled after each of the five settings keys");
        }

        @Test
        @DisplayName("every other key is the server's (its answer is returned)")
        void others() {
            KernelFx.TestWorld w = moverWorld("", ShipMoverTest.OPEN);
            KernelFx.Rec r = new KernelFx.Rec();
            r.pressedStops = true;
            boolean stop = keys(w).pressed(pilot(), ControlKey.PAUSE, r);
            boolean no = keys(w).pressed(pilot(), ControlKey.CLOAK, new KernelFx.Rec());
            assertAll(
                () -> assertTrue(stop, "the server's true is returned"),
                () -> assertEquals(ControlKey.PAUSE, r.lastKey, "for that key"),
                () -> assertFalse(no, "and its false"));
        }
    }

    @Nested
    @DisplayName("flight key releases")
    class Releases {
        @Test
        @DisplayName("SHIELD lowers shields and laser and restarts the fire timer, except in the round delay")
        void shield() {
            KernelFx.TestWorld w = moverWorld("", ShipMoverTest.OPEN);
            w.frames = 50;
            ShipBody p = pilot();
            p.used = Has.SHIELD | Has.LASER | Has.SHOT;
            keys(w).released(p, ControlKey.SHIELD, new KernelFx.Rec());
            ShipBody down = pilot();
            down.used = Has.LASER;
            keys(w).released(down, ControlKey.SHIELD, new KernelFx.Rec());
            KernelFx.TestWorld d = moverWorld("", ShipMoverTest.OPEN);
            d.roundDelay = 3;
            ShipBody q = pilot();
            q.used = Has.SHIELD;
            keys(d).released(q, ControlKey.SHIELD, new KernelFx.Rec());
            assertAll(
                () -> assertEquals(Has.SHOT, p.used, "shield and laser are dropped"),
                () -> assertEquals(50L, p.shotTime, "the fire-repeat timer restarts"),
                () -> assertEquals(Has.LASER, down.used, "shields already down: nothing changes"),
                () -> assertEquals(0L, down.shotTime, "and the timer is not touched"),
                () -> assertEquals(Has.SHIELD, q.used, "the round delay keeps shields up"));
        }

        @Test
        @DisplayName("FIRE_SHOT stops autofire; other keys are the server's")
        void others() {
            KernelFx.TestWorld w = moverWorld("", ShipMoverTest.OPEN);
            ShipBody p = pilot();
            p.used = Has.SHOT | Has.SHIELD;
            keys(w).released(p, ControlKey.FIRE_SHOT, new KernelFx.Rec());
            KernelFx.Rec r = new KernelFx.Rec();
            keys(w).released(p, ControlKey.REFUEL, r);
            assertAll(
                () -> assertEquals(Has.SHIELD, p.used, "autofire stops"),
                () -> assertEquals(ControlKey.REFUEL, r.lastKey, "REFUEL is passed on"));
        }
    }

    @Nested
    @DisplayName("pointer control (netserver.c:3140)")
    class Pointer {
        @Test
        @DisplayName("turnVel -= dir * |movement * turnSpeed / MAX|, limited; ignored hover-paused; autopilot goes off")
        void pointer() {
            KernelFx.TestWorld w = moverWorld("", ShipMoverTest.OPEN);
            ShipKeys k = keys(w);
            ShipBody p = pilot();
            k.pointerMove(p, 10, new KernelFx.Rec());
            ShipBody n = pilot();
            k.pointerMove(n, -10, new KernelFx.Rec());
            ShipBody zero = pilot();
            k.pointerMove(zero, 0, new KernelFx.Rec());
            ShipBody small = pilot();
            k.pointerMove(small, 1, new KernelFx.Rec());
            ShipBody big = pilot();
            k.pointerMove(big, 100000, new KernelFx.Rec());
            ShipBody free = pilot();
            free.turnResistance = 0;
            k.pointerMove(free, 1, new KernelFx.Rec());
            ShipBody freeBig = pilot();
            freeBig.turnResistance = 0;
            freeBig.turnSpeed = (float) Const.MAX_PLAYER_TURNSPEED;
            k.pointerMove(freeBig, 100000, new KernelFx.Rec());
            ShipBody hover = pilot();
            hover.status |= Obj.HOVERPAUSE;
            hover.used = Has.AUTOPILOT;
            KernelFx.Rec rh = new KernelFx.Rec();
            k.pointerMove(hover, 10, rh);
            ShipBody auto = pilot();
            auto.used = Has.AUTOPILOT;
            KernelFx.Rec ra = new KernelFx.Rec();
            k.pointerMove(auto, 10, ra);
            float t10 = (float) (10 * 35f / Const.MAX_PLAYER_TURNSPEED);
            assertAll(
                () -> assertEquals(-Math.max((float) Const.MIN_PLAYER_TURNSPEED, t10), p.turnVel, "right: negative"),
                () -> assertEquals(Math.max((float) Const.MIN_PLAYER_TURNSPEED, t10), n.turnVel, "left: positive"),
                () -> assertEquals(-(float) Const.MIN_PLAYER_TURNSPEED, small.turnVel, "at least the minimum turn speed"),
                () -> assertEquals(-(float) Const.MIN_PLAYER_TURNSPEED, zero.turnVel, "no movement counts as rightward"),
                () -> assertEquals(-(float) Const.MAX_PLAYER_TURNSPEED, big.turnVel, "at most the maximum"),
                () -> assertEquals(-(float) (1 * 35f / Const.MAX_PLAYER_TURNSPEED), free.turnVel,
                    "without resistance no minimum"),
                () -> assertEquals(-5f * Const.RES, freeBig.turnVel, "without resistance at most 5 * RES"),
                () -> assertEquals(0f, hover.turnVel, "hover-paused: ignored"),
                () -> assertEquals("", rh.log(), "and the autopilot stays"),
                () -> assertEquals("autopilotOff", ra.log(), "otherwise the autopilot goes off first"));
        }

        @Test
        @DisplayName("hoverOrAutopilot: hover-paused or on autopilot")
        void hover() {
            ShipBody p = pilot();
            boolean none = ShipKeys.hoverOrAutopilot(p);
            p.status |= Obj.HOVERPAUSE;
            boolean h = ShipKeys.hoverOrAutopilot(p);
            ShipBody a = pilot();
            a.used = Has.AUTOPILOT;
            assertAll(
                () -> assertFalse(none, "neither"),
                () -> assertTrue(h, "hover-paused"),
                () -> assertTrue(ShipKeys.hoverOrAutopilot(a), "autopilot"));
        }
    }
}
