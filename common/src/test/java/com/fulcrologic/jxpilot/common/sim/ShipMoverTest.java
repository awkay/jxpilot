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

import com.fulcrologic.jxpilot.common.Item;
import com.fulcrologic.jxpilot.common.math.Rng;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static com.fulcrologic.jxpilot.common.sim.KernelFx.moverWorld;
import static com.fulcrologic.jxpilot.common.sim.KernelFx.ship;
import static com.fulcrologic.jxpilot.common.sim.MoveState.*;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ShipMover#movePlayer} and {@link ShipMover#wallBounce}: C {@code Move_player} (walls.c:2209-2599), as the
 * kernel sees it: the ship's new state and the {@link KernelEvents} it reports (the server's sparks, messages and
 * cannon deaths are its {@code MoverPlayerTest}). The default ship at heading 0 has hull points (15,0), (-9,8),
 * (-9,-8) px = (960,0), (-576,512), (-576,-512) clicks from its centre.
 */
class ShipMoverTest {
    static final int BC = Click.BLOCK_CLICKS;
    /** Walls at blocks (5,1)-(5,3): their left face is x = 175 px = 11200 clicks. */
    static final String[] RIGHT_WALL = {"       ", "     x ", " _   x ", "     x ", "       "};
    /** Walls at blocks (1,1)-(1,3): their right face is x = 70 px = 4480 clicks. */
    static final String[] LEFT_WALL = {"       ", " x     ", " x   _ ", " x     ", "       "};
    static final String E9 = "         ";
    /** 9x9 open map: block (4,4) has walldist 10. */
    static final String[] OPEN = {E9, E9, E9, E9, E9, E9, E9, E9, "_        "};
    /** Like RIGHT_WALL with a target (index 0) at block (5,2) where the nose hits. */
    static final String[] TARGET = {"       ", "     x ", " _   ! ", "     x ", "       "};

    /** A mover over {@code w} drawing tie-breaks from its seeded rfrac(). */
    static ShipMover mover(KernelFx.TestWorld w) {
        return new ShipMover(w, w::rfrac);
    }

    /** One Move_player of {@code p}; returns the events. */
    static KernelFx.Rec move(KernelFx.TestWorld w, ShipBody p) {
        KernelFx.Rec r = new KernelFx.Rec();
        mover(w).movePlayer(p, r);
        return r;
    }

    /** The default ship at heading 0, nose 128 clicks left of the RIGHT_WALL face, moving right 4 px/frame. */
    static ShipBody headOn() {
        return ship(10112, 5600, 0, 4, 0);
    }

    @Nested
    @DisplayName("a survived wall bounce")
    class Bounce {
        @Test
        @DisplayName("brakes, reflects, drains fuel by cost and reports the bounce with its wall normal and cost")
        void simple() {
            KernelFx.TestWorld w = moverWorld("", RIGHT_WALL);
            ShipBody p = headOn();
            KernelFx.Rec r = move(w, p);
            // speed 4: v = 4>>2 = 1; m = (int)(20 - 0.75*20) = 5; b = 1 - 0.5*0.5 = 0.75: cost = (long)3.75 = 3;
            // heading 0 vs wall normal RES/2: |delta| = 64, cost = 3*(64+64)/128 = 3; fuel -= 3 << 8
            assertAll(
                () -> assertEquals(10176, p.cx, "128 to the wall, bounce, (int)(-128*0.5) = 64 back"),
                () -> assertEquals(5600, p.cy, "y unchanged"),
                () -> assertEquals(-2.0, p.vx, "velocity reflected and braked by 0.5"),
                () -> assertEquals(0.0, p.vy, "no y velocity"),
                () -> assertEquals(2.0, p.velocity, "velocity magnitude updated"),
                () -> assertEquals(10000 - 768, p.fuel, "fuel drops by cost << 8 * wallBounceFuelDrainMult = 768"),
                () -> assertEquals(10000 - 768, p.tankFuel[0], "from the current tank"),
                () -> assertEquals(7L, p.lastWallTouch, "last wall touch is now"),
                () -> assertEquals(10176 >> 6, p.prevX, "the bounce makes the new position the previous one"),
                () -> assertEquals(0, r.crashes, "the ship survives"),
                () -> assertEquals(1, r.bounces, "one costly bounce is reported"),
                () -> assertEquals(64, r.wallDir, "off the low-x face: normal RES/2"),
                () -> assertEquals(3L, r.cost, "with its cost"),
                () -> assertEquals(-1, r.target, "no target"),
                () -> assertEquals(1, r.wallDamage, "a ship that is not fully shielded may lose items"));
        }

        @Test
        @DisplayName("a traced move near the wall that touches nothing keeps the pre-move position as the previous one")
        void tracedNoBounce() {
            KernelFx.TestWorld w = moverWorld("", RIGHT_WALL);
            ShipBody p = ship(10112, 5600, 0, 1, 0);
            KernelFx.Rec r = move(w, p);
            assertAll(
                () -> assertEquals(10112 + 64, p.cx, "moved 1 px towards the wall, no bounce"),
                () -> assertEquals(1.0, p.vx, "velocity unchanged"),
                () -> assertEquals(10112 >> 6, p.prevX, "previous position: before the move"),
                () -> assertEquals(-1L, p.lastWallTouch, "no wall touched"),
                () -> assertEquals("", r.log(), "no events"));
        }

        @Test
        @DisplayName("cost formula: (long)((1-brake/2)*(int)(mass-0.75*emptyMass)*((int)speed>>2)) * (RES/2+|delta|)/RES")
        void costFormula() {
            KernelFx.TestWorld w = moverWorld("", RIGHT_WALL);
            w.params.maxUnshieldedWallBounceSpeed = 30;
            w.params.wallBounceFuelDrainMult = 2;
            // heading up; its rightmost hull point (-9,-8) rotated is at +512 clicks, 100 from the wall
            ShipBody p = ship(11200 - 100 - 512, 5600, 32, 20, 0);
            p.mass = 30;
            p.fuel = 100000;
            KernelFx.Rec r = move(w, p);
            // v = 20>>2 = 5, m = (int)(30 - 15) = 15, b = 0.75: (long)56.25 = 56; |delta| = |32 - 64| = 32:
            // 56 * 96 / 128 = 42; fuel -= 42 << 8 * 2 = 21504
            assertAll(
                () -> assertEquals(100000 - 21504, p.fuel, "fuel drops by 42 * 256 * 2"),
                () -> assertEquals(42L, r.cost, "cost 42 is reported"),
                () -> assertEquals(-10.0, p.vx, "velocity reflected and halved"),
                () -> assertEquals(10098, p.cx, "100 in, bounce, (int)(-1180*0.5) = 590 back"),
                () -> assertEquals(5600, p.cy, "y unchanged"),
                () -> assertEquals(32, p.dir, "the heading is outside RES/16 of the normal: unchanged"),
                () -> assertEquals(32.0, p.floatDir, "floatDir unchanged"));
        }

        @Test
        @DisplayName("within RES/16 of the wall normal the heading is nudged by (1-brake)*delta, wrapping into [0, RES)")
        void headingNudge() {
            KernelFx.TestWorld a = moverWorld("", RIGHT_WALL);
            ShipBody pa = ship(11200 - 100 - 664, 5600, 60, 4, 0);
            move(a, pa);
            KernelFx.TestWorld b = moverWorld("", LEFT_WALL);
            b.params.playerWallBrakeFactor = 0;
            ShipBody pb = ship(4480 + 100 + 600, 5600, 127, -4, 0);
            pb.floatDir = 127.4f;
            move(b, pb);
            KernelFx.TestWorld c = moverWorld("", LEFT_WALL);
            c.params.playerWallBrakeFactor = 0;
            ShipBody pc = ship(4480 + 100 + 600, 5600, 1, -4, 0);
            pc.floatDir = 0.6f;
            move(c, pc);
            assertAll(
                () -> assertEquals(62.0, pa.floatDir, 1e-9, "heading 60, normal 64: floatDir += 0.5 * 4"),
                () -> assertEquals(60, pa.dir, "dir itself is left to Turn_player"),
                () -> assertTrue(pa.vx < 0, "(it bounced)"),
                () -> assertEquals((127.4f + 1.0f) - 128, pb.floatDir, "127.4 + 1 = 128.4 wraps to 0.4 (C float)"),
                () -> assertEquals((0.6f - 1.0f) + 128, pc.floatDir, "0.6 - 1 = -0.4 wraps to 127.6 (C float)"),
                () -> assertEquals(0.0, pb.vx, "brake factor 0 stops the ship"));
        }

        @Test
        @DisplayName("the nudge applies up to |delta| = RES/16 = 8 inclusive; floatDir 128 wraps to 0, 0 stays 0")
        void nudgeBoundaries() {
            KernelFx.TestWorld a = moverWorld("", RIGHT_WALL);
            ShipBody p = ship(11200 - 100 - 728, 5600, 56, 4, 0);
            move(a, p);
            KernelFx.TestWorld b = moverWorld("", RIGHT_WALL);
            ShipBody q = ship(11200 - 100 - 739, 5600, 55, 4, 0);
            move(b, q);
            KernelFx.TestWorld c = moverWorld("", LEFT_WALL);
            c.params.playerWallBrakeFactor = 0;
            ShipBody r = ship(4480 + 100 + 600, 5600, 127, -4, 0);
            move(c, r);
            KernelFx.TestWorld d = moverWorld("", LEFT_WALL);
            d.params.playerWallBrakeFactor = 0;
            ShipBody t = ship(4480 + 100 + 600, 5600, 1, -4, 0);
            move(d, t);
            assertAll(
                () -> assertTrue(p.vx < 0 && q.vx < 0, "(both bounced)"),
                () -> assertEquals(60.0, p.floatDir, 1e-9, "|delta| 8: 56 + 0.5 * 8"),
                () -> assertEquals(55.0, q.floatDir, 1e-9, "|delta| 9: no nudge"),
                () -> assertEquals(0.0, r.floatDir, 1e-9, "127 + 1 = 128 wraps to 0"),
                () -> assertEquals(0.0, t.floatDir, 1e-9, "1 - 1 = 0 stays 0"));
        }

        @Test
        @DisplayName("bouncing off a target (players do not crash on targets) reports the target hit")
        void target() {
            KernelFx.TestWorld w = moverWorld("", "       ", "     ! ", " _   ! ", "     ! ", "       ");
            w.params.targetTeamCollision = true;
            ShipBody p = headOn();
            KernelFx.Rec r = move(w, p);
            assertAll(
                () -> assertEquals(10176, p.cx, "same path as a wall bounce"),
                () -> assertEquals(0, r.crashes, "survives"),
                () -> assertTrue(r.target >= 0, "the bounce names the target it hit"));
        }

        @Test
        @DisplayName("a bounce that costs nothing reports no bounce effects")
        void freeBounce() {
            KernelFx.TestWorld w = moverWorld("", LEFT_WALL);
            ShipBody p = ship(4480 + 576 + 64, 5600, 0, -2, 0);
            KernelFx.Rec r = move(w, p);
            assertAll(
                () -> assertEquals(1.0, p.vx, "reflected and braked once"),
                () -> assertEquals(0, r.bounces, "speed 2: (int)2 >> 2 = 0, cost 0, no bounce effects"),
                () -> assertEquals(1, r.wallDamage, "items may still be damaged"),
                () -> assertEquals(7L, p.lastWallTouch, "the wall was touched"));
        }
    }

    @Nested
    @DisplayName("fatal wall bounces")
    class Fatal {
        @Test
        @DisplayName("too fast: CRASH_WALL_SPEED from the nose, on the face, braked velocity, no fuel cost")
        void speed() {
            KernelFx.TestWorld w = moverWorld("", RIGHT_WALL);
            w.params.maxUnshieldedWallBounceSpeed = 3.9f;
            ShipBody p = headOn();
            KernelFx.Rec r = move(w, p);
            assertAll(
                () -> assertEquals(CRASH_WALL_SPEED, r.crashType, "the crash is CRASH_WALL_SPEED"),
                () -> assertEquals(0, r.crashPoint, "the nose (point 0) hit"),
                () -> assertEquals(11200 - 960, p.cx, "it stays with the nose on the face"),
                () -> assertEquals(-2.0, p.vx, "the velocity was braked before the check (as C)"),
                () -> assertEquals(10000.0, p.fuel, "no fuel is drained"),
                () -> assertEquals(0, r.bounces + r.wallDamage, "no bounce effects"));
        }

        @Test
        @DisplayName("speed exactly at the limit survives")
        void speedAtLimit() {
            KernelFx.TestWorld w = moverWorld("", RIGHT_WALL);
            w.params.maxUnshieldedWallBounceSpeed = 4;
            KernelFx.Rec r = move(w, headOn());
            assertEquals(0, r.crashes, "speed 4 with limit 4 is not fatal");
        }

        @Test
        @DisplayName("too steep: CRASH_WALL_ANGLE; exactly at the limit survives")
        void angle() {
            KernelFx.TestWorld w = moverWorld("", RIGHT_WALL);
            w.params.maxUnshieldedAngle = 63;
            ShipBody p = headOn();
            KernelFx.Rec r = move(w, p);
            KernelFx.Rec ok = move(moverWorld("", RIGHT_WALL), headOn());
            assertAll(
                () -> assertEquals(CRASH_WALL_ANGLE, r.crashType, "|delta| 64 > 63: CRASH_WALL_ANGLE"),
                () -> assertEquals(10000.0, p.fuel, "no fuel is drained"),
                () -> assertEquals(0, ok.crashes, "|delta| 64 with limit 64 survives"));
        }

        @Test
        @DisplayName("out of fuel: CRASH_WALL_NO_FUEL unless the drain multiplier is 0")
        void noFuel() {
            KernelFx.TestWorld w = moverWorld("", RIGHT_WALL);
            ShipBody p = headOn();
            p.fuel = 500;
            p.tankFuel[0] = 500;
            KernelFx.Rec r = move(w, p);
            KernelFx.TestWorld z = moverWorld("", RIGHT_WALL);
            z.params.wallBounceFuelDrainMult = 0;
            ShipBody q = headOn();
            q.fuel = 0;
            KernelFx.Rec rq = move(z, q);
            assertAll(
                () -> assertEquals(CRASH_WALL_NO_FUEL, r.crashType, "500 - 768 leaves no fuel: CRASH_WALL_NO_FUEL"),
                () -> assertEquals(0.0, p.fuel, "fuel is clamped at 0"),
                () -> assertEquals(0, rq.crashes, "no drain: an empty tank is no crash"),
                () -> assertEquals(1, rq.bounces, "the bounce cost 3 is still reported"));
        }

        @Test
        @DisplayName("a fatal bounce off a target is CRASH_TARGET (speed, angle and fuel alike)")
        void target() {
            KernelFx.TestWorld s = moverWorld("", TARGET);
            s.params.targetTeamCollision = true;
            s.params.maxUnshieldedWallBounceSpeed = 3;
            KernelFx.Rec rs = move(s, headOn());
            KernelFx.TestWorld a = moverWorld("", TARGET);
            a.params.targetTeamCollision = true;
            a.params.maxUnshieldedAngle = 10;
            KernelFx.Rec ra = move(a, headOn());
            KernelFx.TestWorld f = moverWorld("", TARGET);
            f.params.targetTeamCollision = true;
            ShipBody low = headOn();
            low.fuel = 1;
            KernelFx.Rec rf = move(f, low);
            assertAll(
                () -> assertEquals(CRASH_TARGET, rs.crashType, "speed on a target"),
                () -> assertEquals(0, rs.crashTarget, "names the target"),
                () -> assertEquals(CRASH_TARGET, ra.crashType, "angle on a target"),
                () -> assertEquals(CRASH_TARGET, rf.crashType, "no fuel on a target"));
        }
    }

    @Nested
    @DisplayName("shields and armor")
    class Shields {
        @Test
        @DisplayName("a shield uses the shielded speed and angle limits but still pays fuel")
        void shield() {
            KernelFx.TestWorld s = moverWorld("", RIGHT_WALL);
            s.params.maxUnshieldedWallBounceSpeed = 3;
            s.params.maxShieldedWallBounceSpeed = 5;
            s.params.maxUnshieldedAngle = 10;
            s.params.maxShieldedAngle = 64;
            ShipBody p = headOn();
            p.used = Has.SHIELD;
            KernelFx.Rec r = move(s, p);
            KernelFx.TestWorld t = moverWorld("", RIGHT_WALL);
            t.params.maxShieldedWallBounceSpeed = 3;
            ShipBody q = headOn();
            q.used = Has.SHIELD;
            KernelFx.Rec rq = move(t, q);
            assertAll(
                () -> assertEquals(0, r.crashes, "speed 4 <= 5 and angle 64 <= 64 with the shield"),
                () -> assertEquals(10000.0 - 768, p.fuel, "fuel is still drained"),
                () -> assertEquals(CRASH_WALL_SPEED, rq.crashType, "the shielded speed limit 3 still kills at speed 4"));
        }

        @Test
        @DisplayName("shield + emergency shield: speed limit at least 100, any angle, no fuel cost or item damage")
        void fullyShielded() {
            KernelFx.TestWorld s = moverWorld("", RIGHT_WALL);
            s.params.maxShieldedWallBounceSpeed = 2;
            s.params.maxShieldedAngle = 0;
            ShipBody p = headOn();
            p.used = Has.SHIELD | Has.EMERGENCY_SHIELD;
            KernelFx.Rec r = move(s, p);
            KernelFx.TestWorld e = moverWorld("", RIGHT_WALL);
            e.params.maxUnshieldedWallBounceSpeed = 2;
            ShipBody q = headOn();
            q.used = Has.EMERGENCY_SHIELD;
            KernelFx.Rec rq = move(e, q);
            KernelFx.TestWorld hi = moverWorld("", RIGHT_WALL);
            hi.params.maxShieldedWallBounceSpeed = 3.5f;
            hi.params.maxUnshieldedWallBounceSpeed = 3;
            ShipBody fast = ship(10112, 5600, 0, 120, 0);
            fast.used = Has.SHIELD | Has.EMERGENCY_SHIELD;
            KernelFx.Rec rf = move(hi, fast);
            KernelFx.TestWorld big = moverWorld("", RIGHT_WALL);
            big.params.maxShieldedWallBounceSpeed = 150;
            ShipBody kept = ship(10112, 5600, 0, 120, 0);
            kept.used = Has.SHIELD | Has.EMERGENCY_SHIELD;
            KernelFx.Rec rk = move(big, kept);
            assertAll(
                () -> assertEquals(0, r.crashes, "survives speed 4 with limit 2 and angle 64 with limit 0"),
                () -> assertEquals(10000.0, p.fuel, "no fuel cost"),
                () -> assertEquals(0, r.wallDamage, "no item damage"),
                () -> assertEquals(1, r.bounces, "bounce effects are still reported"),
                () -> assertEquals(CRASH_WALL_SPEED, rq.crashType, "an emergency shield alone is not a shield"),
                () -> assertEquals(CRASH_WALL_SPEED, rf.crashType, "speed 120 exceeds the raised limit of 100"),
                () -> assertEquals(0, rk.crashes, "a shielded limit above 100 is kept"));
        }

        @Test
        @DisplayName("armor raises the limits to the shielded ones when needed and is used up")
        void armor() {
            ShipBody p = armored(3, 5, 64, 64, 2, 0);
            KernelFx.Rec r = lastRec;
            ShipBody q = armored(3, 5, 64, 64, 1, 0);
            ShipBody both = armored(3, 5, 10, 64, 3, 0);
            KernelFx.Rec rb = lastRec;
            ShipBody angle = armored(10, 20, 10, 64, 3, 0);
            KernelFx.Rec ra = lastRec;
            ShipBody unneeded = armored(10, 20, 64, 64, 3, 0);
            ShipBody tooFast = armored(3.5f, 3, 64, 64, 3, 0);
            KernelFx.Rec rt = lastRec;
            ShipBody shielded = armored(2, 3, 64, 64, 3, Has.SHIELD);
            KernelFx.Rec rs = lastRec;
            assertAll(
                () -> assertEquals(0, r.crashes, "armor lets speed 4 pass the shielded limit 5"),
                () -> assertEquals(1, p.items[Item.ARMOR.ordinal()], "one armor piece is used"),
                () -> assertEquals(Has.ARMOR, p.have, "armor remains while pieces are left"),
                () -> assertEquals(0, q.items[Item.ARMOR.ordinal()], "the last piece is used"),
                () -> assertEquals(0, q.have, "and the ARMOR ability is lost"),
                () -> assertEquals(0, rb.crashes, "armor used for speed also raises the angle limit"),
                () -> assertEquals(2, both.items[Item.ARMOR.ordinal()], "so one piece covers both"),
                () -> assertEquals(0, ra.crashes, "armor lets |delta| 64 pass the shielded angle 64"),
                () -> assertEquals(2, angle.items[Item.ARMOR.ordinal()], "angle alone uses one piece"),
                () -> assertEquals(3, unneeded.items[Item.ARMOR.ordinal()], "armor is not used when not needed"),
                () -> assertEquals(CRASH_WALL_SPEED, rt.crashType,
                    "armor does not help when the unshielded limit is not below the shielded one"),
                () -> assertEquals(3, tooFast.items[Item.ARMOR.ordinal()], "and is not used then"),
                () -> assertEquals(CRASH_WALL_SPEED, rs.crashType, "armor is not used with the shield up"),
                () -> assertEquals(3, shielded.items[Item.ARMOR.ordinal()], "no piece used with the shield up"));
        }

        @Test
        @DisplayName("armor is not used at exactly the limit, nor when the limits are equal")
        void armorBoundaries() {
            ShipBody atSpeed = armored(4, 5, 64, 64, 3, 0);
            KernelFx.Rec r1 = lastRec;
            ShipBody equalSpeed = armored(3, 3, 64, 64, 3, 0);
            KernelFx.Rec r2 = lastRec;
            ShipBody equalAngle = armored(10, 20, 10, 10, 3, 0);
            KernelFx.Rec r3 = lastRec;
            KernelFx.TestWorld d = moverWorld("", RIGHT_WALL);
            d.params.maxUnshieldedAngle = 32;
            d.params.maxUnshieldedWallBounceSpeed = 30;
            ShipBody u = ship(11200 - 100 - 511, 5600, 32, 20, 0);
            u.mass = 30;
            u.fuel = 100000;
            u.have = Has.ARMOR;
            u.items[Item.ARMOR.ordinal()] = 3;
            KernelFx.Rec r4 = move(d, u);
            assertAll(
                () -> assertEquals(3, u.items[Item.ARMOR.ordinal()], "|delta| 32 exactly at the angle limit needs no armor"),
                () -> assertEquals(0, r4.crashes, "and survives"),
                () -> assertEquals(3, atSpeed.items[Item.ARMOR.ordinal()], "speed exactly at the limit needs no armor"),
                () -> assertEquals(0, r1.crashes, "and survives"),
                () -> assertEquals(3, equalSpeed.items[Item.ARMOR.ordinal()], "equal speed limits: armor is not used"),
                () -> assertEquals(CRASH_WALL_SPEED, r2.crashType, "and the ship dies"),
                () -> assertEquals(3, equalAngle.items[Item.ARMOR.ordinal()], "equal angle limits: armor is not used"),
                () -> assertEquals(CRASH_WALL_ANGLE, r3.crashType, "and the ship dies"));
        }

        KernelFx.Rec lastRec;

        /** A head-on bounce at speed 4 with the given limits, armor count and used bits. */
        ShipBody armored(float unshieldedSpeed, float shieldedSpeed, int unshieldedAngle, int shieldedAngle,
                         int armor, int used) {
            KernelFx.TestWorld w = moverWorld("", RIGHT_WALL);
            w.params.maxUnshieldedWallBounceSpeed = unshieldedSpeed;
            w.params.maxShieldedWallBounceSpeed = shieldedSpeed;
            w.params.maxUnshieldedAngle = unshieldedAngle;
            w.params.maxShieldedAngle = shieldedAngle;
            ShipBody p = headOn();
            p.used = used;
            p.have = Has.ARMOR;
            p.items[Item.ARMOR.ordinal()] = armor;
            lastRec = move(w, p);
            return p;
        }
    }

    @Nested
    @DisplayName("several hull points and edges")
    class Hull {
        @Test
        @DisplayName("an edge bounce is free: no brake, no fuel, no bounce effects; ties consume one rfrac()")
        void edgeBounce() {
            KernelFx.TestWorld w = moverWorld("", RIGHT_WALL);
            ShipBody p = ship(676, 5600, 0, -4, 0);
            w.rng.seed(42);
            Rng ref = new Rng(42);
            ref.nextFloat();
            float second = ref.nextFloat();
            KernelFx.Rec r = move(w, p);
            KernelFx.TestWorld stop = moverWorld("", RIGHT_WALL);
            stop.params.edgeBounce = false;
            ShipBody q = ship(676, 5600, 0, -4, 0);
            move(stop, q);
            assertAll(
                () -> assertEquals(4.0, p.vx, "reflected without braking"),
                () -> assertEquals(732, p.cx, "100 to the edge, 156 back: rear points at 156, centre at 732"),
                () -> assertEquals(10000.0, p.fuel, "no fuel cost"),
                () -> assertEquals("", r.log(), "no events"),
                () -> assertEquals(7L, p.lastWallTouch, "an edge touch counts as a wall touch"),
                () -> assertEquals(second, w.rfrac(), "the two simultaneous edge bounces consumed exactly one rfrac()"),
                () -> assertEquals(0.0, q.vx, "without edgeBounce the x motion stops"),
                () -> assertEquals(576, q.cx, "with the rear points on the edge"));
        }

        @Test
        @DisplayName("two hull points hitting a wall together: one is chosen with rfrac(), the bounce happens once")
        void twoPoints() {
            KernelFx.TestWorld w = moverWorld("", LEFT_WALL);
            ShipBody p = ship(4480 + 576 + 64, 5600, 0, -2, 0);
            w.rng.seed(42);
            Rng ref = new Rng(42);
            ref.nextFloat();
            float second = ref.nextFloat();
            move(w, p);
            assertAll(
                () -> assertEquals(1.0, p.vx, "reflected and braked once"),
                () -> assertEquals(4480 + 576 + 32, p.cx, "64 in, bounce, 32 back out"),
                () -> assertEquals(second, w.rfrac(), "exactly one rfrac() was used to pick the point"),
                () -> assertEquals(10000.0, p.fuel, "speed 2: (int)2 >> 2 = 0, no cost"));
        }

        @Test
        @DisplayName("simultaneous bounces: point i replaces the choice iff (int)(rfrac * (n - choice)) == i")
        void simultaneous() {
            String[] floorTarget = {"       ", " _     ", "     ! ", "xxxxxxx", "       "};
            String[] leftPair = {"       ", "     _ ", " !     ", " x     ", "       "};
            for (int seed = 1; seed <= 20; seed++) {
                KernelFx.TestWorld s = moverWorld("", floorTarget);
                s.params.targetTeamCollision = true;
                s.params.maxUnshieldedWallBounceSpeed = 3;
                ShipBody p = ship(11200 - 960, 4480 + 512, 0, 4, -4);
                s.rng.seed(seed);
                boolean second = (int) (new Rng(seed).nextFloat() * 3) == 2;
                KernelFx.Rec r = move(s, p);
                assertEquals(second ? CRASH_WALL_SPEED : CRASH_TARGET, r.crashType,
                    "seed " + seed + ": point 2 (wall) replaces point 0 (target) iff (int)(rfrac*3) == 2");
                assertEquals(second ? 2 : 0, r.crashPoint, "seed " + seed + ": the chosen point crashed");
                KernelFx.TestWorld t = moverWorld("", leftPair);
                t.params.targetTeamCollision = true;
                t.params.maxUnshieldedWallBounceSpeed = 3;
                ShipBody q = ship(4480 + 576, 4480, 0, -4, 0);
                t.rng.seed(seed);
                KernelFx.Rec rq = move(t, q);
                assertEquals(CRASH_TARGET, rq.crashType, "seed " + seed + ": (int)(rfrac*2) is never 2, point 1 stays");
            }
        }

        @Test
        @DisplayName("a crash wins over bounces: hitting a cannon reports CRASH_CANNON with the cannon")
        void cannonCrash() {
            String[] cannon = {"       ", "     x ", " _   f ", "     x ", "       "};
            KernelFx.TestWorld s = moverWorld("", cannon);
            ShipBody p = headOn();
            KernelFx.Rec r = move(s, p);
            KernelFx.TestWorld ph = moverWorld("", cannon);
            ph.cannonPhased[0] = true;
            ShipBody q = headOn();
            KernelFx.Rec rq = move(ph, q);
            assertAll(
                () -> assertEquals(CRASH_CANNON, r.crashType, "the crash is CRASH_CANNON"),
                () -> assertEquals(0, r.crashCannon, "naming the cannon"),
                () -> assertEquals(0, r.crashPoint, "hit by the nose"),
                () -> assertEquals(11200 - 960, p.cx, "stopped with the nose on the cannon"),
                () -> assertEquals(4.0, p.vx, "a crash does not change the velocity"),
                () -> assertEquals(p.px, p.prevX, "a crash makes the new position the previous one"),
                () -> assertEquals(0, rq.crashes, "a phased cannon is no cannon to crash into"),
                () -> assertEquals(10112 + 256, q.cx, "the ship flies into its block"));
        }

        @Test
        @DisplayName("a hull point in a wormhole whose block holds the centre reports CRASH_WORMHOLE")
        void wormhole() {
            KernelFx.TestWorld s = moverWorld("", "       ", "       ", " _ @ @ ", "       ", "       ");
            ShipBody p = ship(3 * BC + 1120, 5600, 0, 1, 0);
            KernelFx.Rec r = move(s, p);
            assertAll(
                () -> assertEquals(CRASH_WORMHOLE, r.crashType, "the wormhole is reported as a crash"),
                () -> assertEquals(0, r.crashWormhole, "with the hole hit"),
                () -> assertEquals(3 * BC + 1120, p.cx, "the ship does not move"));
        }

        @Test
        @DisplayName("a ship outside a non-wrapping world leaves the known universe")
        void universe() {
            KernelFx.Rec r = move(moverWorld("", OPEN), ship(20500, 19040, 0, 1, 0));
            assertEquals(CRASH_UNIVERSE, r.crashType, "CRASH_UNIVERSE");
        }
    }

    @Nested
    @DisplayName("free movement")
    class Free {
        final int c = 4 * BC + 1000;

        @Test
        @DisplayName("far from walls the ship moves FLOAT_TO_CLICK(vel) clicks, the fraction dropped as in C")
        void truncates() {
            KernelFx.TestWorld w = moverWorld("", OPEN);
            ShipBody p = ship(c, c, 0, 1.0078125, -1.0078125);
            p.prevX = -5;
            move(w, p);
            int x1 = p.cx;
            int prev1 = p.prevX;
            move(w, p);
            assertAll(
                () -> assertEquals(c + 64, x1, "tick 1: 64 of 64.5 clicks"),
                () -> assertEquals(c >> 6, prev1, "the previous position is remembered before moving"),
                () -> assertEquals(c + 128, p.cx, "tick 2: 64 again (no remainder is carried)"),
                () -> assertEquals(c - 128, p.cy, "negative velocity truncates toward zero"),
                () -> assertEquals((float) StrictMath.hypot(1.0078125, 1.0078125), p.velocity,
                    "velocity is the speed (C VECTOR_LENGTH as a float)"));
        }

        @Test
        @DisplayName("friction multiplies by (1.0f - f) in float; blockFriction applies on FRICTION blocks unless phased")
        void friction() {
            KernelFx.TestWorld w = moverWorld("", OPEN);
            w.params.friction = 0.1f;
            ShipBody p = ship(c, c, 0, 10, 0);
            move(w, p);
            float vp = (1.0f - 0.1f) * (10f * 1f + 0f * 0f);
            String[] fr = OPEN.clone();
            fr[4] = "    z    ";
            KernelFx.TestWorld f = moverWorld("", fr);
            f.params.blockFriction = 0.5f;
            ShipBody r = ship(c, c, 0, 10, 0);
            move(f, r);
            KernelFx.TestWorld ph = moverWorld("", fr);
            ph.params.blockFriction = 0.5f;
            ShipBody t = ship(c, c, 0, 10, 0);
            t.used = Has.PHASING_DEVICE;
            move(ph, t);
            assertAll(
                () -> assertEquals(vp, p.vx, "10 * (1.0f - 0.1f) in float (C: 9.0000004)"),
                () -> assertEquals(c + (int) (vp * 64), p.cx, "and moves FLOAT_TO_CLICK(vx)"),
                () -> assertEquals(5.0f, r.vx, "on a friction block blockFriction 0.5 halves the velocity"),
                () -> assertEquals(10.0f, t.vx, "a phased ship uses the normal friction (0) there"));
        }

        @Test
        @DisplayName("coriolis rotates the velocity clockwise by coriolis*RES/360 RES units (MOD RES), float tables")
        void coriolis() {
            KernelFx.TestWorld s = moverWorld("", OPEN);
            s.params.coriolis = 90;
            ShipBody p = ship(c, c, 0, 2, 0);
            move(s, p);
            KernelFx.TestWorld n = moverWorld("", OPEN);
            n.params.coriolis = -90;
            ShipBody q = ship(c, c, 0, 2, 0);
            move(n, q);
            KernelFx.TestWorld f = moverWorld("", OPEN);
            f.params.coriolis = 360;
            f.params.friction = 0.5f;
            ShipBody r = ship(c, c, 0, 2, 2);
            move(f, r);
            KernelFx.TestWorld h = moverWorld("", OPEN);
            h.params.coriolis = 45;
            ShipBody t = ship(c, c, 0, 2, 1);
            move(h, t);
            float c32 = Trig.tcos(32);
            float c16 = Trig.tcos(16);
            float s16 = Trig.tsin(16);
            assertAll(
                () -> assertEquals((1.0f - 0f) * (2f * c32 + 0f), p.vx, "90 degrees: (2,0) -> (2*tcos(32), -2): x"),
                () -> assertEquals(-2.0f, p.vy, "90 degrees: (2,0) -> (0,-2): y"),
                () -> assertEquals(2.0f, q.vy, "-90 degrees is MOD2(-32, RES) = 96: (2,0) -> (0,2)"),
                () -> assertEquals(1.0f, r.vx, "360 degrees is no rotation; friction 0.5 still halves x"),
                () -> assertEquals(1.0f, r.vy, "and y"),
                () -> assertEquals((1.0f - 0f) * (2f * c16 + 1f * s16), t.vx, "45: x = vx tcos + vy tsin in float"),
                () -> assertEquals((1.0f - 0f) * (1f * c16 - 2f * s16), t.vy, "45: y = vy tcos - vx tsin"));
        }

        @Test
        @DisplayName("the free-flight check uses the speed after the previous move (C pl->velocity)")
        void staleVelocity() {
            KernelFx.TestWorld s = moverWorld("", OPEN);
            ShipBody p = ship(c, c, 64, 200, 0);
            p.velocity = 122;
            move(s, p);
            KernelFx.TestWorld t = moverWorld("", OPEN);
            ShipBody q = ship(c, c, 64, 200, 0);
            move(t, q);
            assertAll(
                () -> assertEquals(c + 12800, p.cx,
                    "stale speed 122 = ((10-3)*35)>>1: free flight, the ship jumps 200 px, even out of the world"),
                () -> assertEquals(200.0f, p.velocity, "velocity is updated afterwards"),
                () -> assertEquals(-200.0, q.vx, "with the real speed the move is traced and bounces off the edge"),
                () -> assertEquals(16406, q.cx, "7840 + 1120 in open-space chunks, 663 to the edge, 3177 back"));
        }

        @Test
        @DisplayName("traced moves through open space advance in walldist-sized chunks and arrive exactly")
        void chunks() {
            ShipBody p = ship(c, c, 64, 100, 0);
            p.velocity = 200;
            int prev0 = p.prevX;
            move(moverWorld("", OPEN), p);
            ShipBody q = ship(c, c, 0, 40, -140);
            q.velocity = 200;
            move(moverWorld("", OPEN), q);
            KernelFx.TestWorld wr = moverWorld("edgeWrap: yes", OPEN);
            ShipBody r = ship(c, c, 0, 300, 0);
            r.velocity = 400;
            move(wr, r);
            KernelFx.TestWorld wy = moverWorld("edgeWrap: yes", OPEN);
            ShipBody u = ship(c, c, 0, 0, -300);
            u.velocity = 400;
            move(wy, u);
            ShipBody h = ship(c, c, 64, 150, 20);
            h.velocity = 200;
            move(moverWorld("", OPEN), h);
            assertAll(
                () -> assertEquals(c + 6400, p.cx, "6400 clicks fit the first chunk of ((10-3)*2240)>>1 = 7840"),
                () -> assertEquals(c + 2560, q.cx, "x arrives at +2560"),
                () -> assertEquals(c - 8960, q.cy, "y arrives at -8960 (a 7840 vertical chunk, then traced)"),
                () -> assertEquals(c + 19200 - wr.clickWidth(), r.cx, "with wrap the chunks wrap around: x"),
                () -> assertEquals(c - 19200 + wy.clickHeight(), u.cy, "with wrap the chunks wrap around: y"),
                () -> assertEquals(300.0, r.vx, "no bounce"),
                () -> assertEquals(prev0, p.prevX, "without a bounce or crash the previous position is kept"),
                () -> assertEquals(c + 9600, h.cx, "a horizontal chunk with a y part: x arrives"),
                () -> assertEquals(c + 1280, h.cy, "and y arrives"));
        }

        @Test
        @DisplayName("on a wrapping map free flight wraps")
        void wraps() {
            KernelFx.TestWorld s = moverWorld("edgeWrap: yes", OPEN);
            ShipBody p = ship(s.clickWidth() - 10, c, 0, 1, -1);
            move(s, p);
            ShipBody q = ship(5, c, 0, -1, 0);
            move(s, q);
            ShipBody t = ship(c, s.clickHeight() - 10, 0, 0, 1);
            move(s, t);
            ShipBody u = ship(c, 5, 0, 0, -1);
            move(s, u);
            assertAll(
                () -> assertEquals(54, p.cx, "x wraps past the right edge"),
                () -> assertEquals(c - 64, p.cy, "y moves"),
                () -> assertEquals(s.clickWidth() + 5 - 64, q.cx, "x wraps past the left edge"),
                () -> assertEquals(54, t.cy, "y wraps past the top"),
                () -> assertEquals(s.clickHeight() + 5 - 64, u.cy, "y wraps past the bottom"));
        }

        @Test
        @DisplayName("moves longer than two world sizes wrap in every chunk")
        void multiWrap() {
            int[] out = new int[4];
            double[][] vel = {{700, 0}, {-700, 0}, {0, 700}, {0, -700}};
            for (int i = 0; i < 4; i++) {
                ShipBody p = ship(c, c, 0, vel[i][0], vel[i][1]);
                move(moverWorld("edgeWrap: yes", OPEN), p);
                out[i] = i < 2 ? p.cx : p.cy;
            }
            assertAll(
                () -> assertEquals(14440, out[0], "x forward: 9960 + 44800 - 2*20160"),
                () -> assertEquals(5480, out[1], "x backward: 9960 - 44800 + 2*20160"),
                () -> assertEquals(14440, out[2], "y forward"),
                () -> assertEquals(5480, out[3], "y backward"));
        }
    }

    @Nested
    @DisplayName("tracing details")
    class Tracing {
        final int c = 4 * BC + 1000;

        @Test
        @DisplayName("a traced move truncates its velocity to clicks like a free one")
        void traced() {
            KernelFx.TestWorld s = moverWorld("", RIGHT_WALL);
            ShipBody p = ship(9300, 5600, 0, 1.0078125, 0.0078125);
            move(s, p);
            ShipBody q = ship(10112, 5600, 0, 4.0078125, 0.0078125);
            move(moverWorld("", RIGHT_WALL), q);
            assertAll(
                () -> assertEquals(2, s.wallDist(4, 2), "(fixture: traced)"),
                () -> assertEquals(9364, p.cx, "64 of 64.5 clicks"),
                () -> assertEquals(5600, p.cy, "0.5 clicks is nothing"),
                () -> assertEquals(10176, q.cx, "256 of 256.5 clicks: same bounce as at speed 4"));
        }

        @Test
        @DisplayName("a fall straight onto a floor: the lowest hull point leads, bounces, and the ship rises")
        void floor() {
            KernelFx.TestWorld s = moverWorld("", "       ", "     _ ", "       ", "xxxxxxx", "       ");
            ShipBody p = ship(7000, 4480 + 512 + 64, 0, 0, -4);
            KernelFx.Rec r = move(s, p);
            assertAll(
                () -> assertEquals(0, r.crashes, "survives"),
                () -> assertEquals(4480 + 512 + 96, p.cy, "the centre ends 96 above the contact height"),
                () -> assertEquals(7000, p.cx, "x unchanged"),
                () -> assertEquals(2.0, p.vy, "y velocity reflected and braked"),
                () -> assertEquals(10000.0 - 512, p.fuel, "cost 2 << 8"),
                () -> assertEquals(32, r.wallDir, "off a floor (VerHi): normal RES/4"));
        }

        @Test
        @DisplayName("walldist chunks never jump past a wall: a fast ship is caught at the wall it would cross")
        void chunkWall() {
            String e = "               ";
            String[] rows = new String[15];
            java.util.Arrays.fill(rows, e);
            rows[0] = "_              ";
            rows[13] = "xxxxxxxxxxxxxxx";
            ShipBody p = ship(7 * BC + 1120, 7 * BC + 1120, 0, 0, -200);
            KernelFx.Rec rp = move(moverWorld("", rows), p);
            String w9 = "   x     ";
            ShipBody q = ship(3360, 10080, 0, 100, 0);
            KernelFx.Rec rq = move(moverWorld("edgeWrap: yes", "   x  _  ", w9, w9, w9, w9, w9, w9, w9, w9), q);
            ShipBody r = ship(10080, 7840, 32, 0, 100);
            KernelFx.Rec rr = move(moverWorld("edgeWrap: yes", E9, E9, E9, "xxxxxxxxx", E9, E9, E9, E9, "_        "), r);
            assertAll(
                () -> assertEquals(CRASH_WALL_SPEED, rp.crashType, "chunks 10080, 1120, then traced: smashed"),
                () -> assertEquals(2, rp.crashPoint, "by the lowest point (2)"),
                () -> assertEquals(4480 + 512, p.cy, "stopped on the floor face"),
                () -> assertEquals(0, rq.crashPoint, "wrap map: the nose reaches the wall column"),
                () -> assertEquals(3 * BC - 960, q.cx, "with the nose on its face"),
                () -> assertEquals(0, rr.crashPoint, "the same moving up"),
                () -> assertEquals(5 * BC - 960, r.cy, "with the nose on the face of the wall row"));
        }

        @Test
        @DisplayName("a centre block with walldist 3 (diagonal open corner) is traced, not chunked")
        void wallDistThree() {
            KernelFx.TestWorld s = moverWorld("", "      _", "       ", "       ", "       ", "  w    ", "       ", "       ");
            ShipBody p = ship(7840, 7840, 0, 1, 1);
            move(s, p);
            ShipBody q = ship(7840, 7840, 0, -40, -40);
            q.velocity = 0;
            KernelFx.Rec rq = move(s, q);
            assertAll(
                () -> assertEquals(3, s.wallDist(3, 3), "(fixture: walldist 3)"),
                () -> assertEquals(7904, p.cx, "moves 64 right"),
                () -> assertEquals(7904, p.cy, "moves 64 up"),
                () -> assertEquals(CRASH_WALL_SPEED, rq.crashType,
                    "walldist 3 is never free flight, even with a stale speed of 0: the ship smashes into the wall"));        }

        @Test
        @DisplayName("the chunk sizes come from the hull-derived centre: heading up, falling, the rear points land")
        void hullCentre() {
            String[] floor = {"_        ", E9, E9, E9, E9, E9, E9, "xxxxxxxxx", E9};
            ShipBody q = ship(4 * BC + 1120, 3 * BC + 2200, 32, 0, -100);
            KernelFx.Rec r = move(moverWorld("", floor), q);
            assertAll(
                () -> assertTrue(r.crashPoint == 1 || r.crashPoint == 2, "a rear point hit the floor"),
                () -> assertEquals(4480 + 576, q.cy, "rear points (y -576) on the floor face"));
        }
    }

    @Nested
    @DisplayName("ships not playing")
    class NotPlaying {
        @Test
        @DisplayName("a dead ship (not PLAYING) drifts with its velocity; killed or paused ships do not move")
        void drift() {
            KernelFx.TestWorld s = moverWorld("", RIGHT_WALL);
            ShipBody p = ship(10112, 5600, 0, 1.0078125, -1.0078125);
            p.status = 0;
            p.velocity = 99;
            p.prevX = -5;
            move(s, p);
            int x1 = p.cx;
            int prev1 = p.prevX;
            move(s, p);
            ShipBody q = ship(10112, 5600, 0, 3, 4);
            q.status = Obj.PLAYING | Obj.KILLED;
            move(s, q);
            ShipBody r = ship(10112, 5600, 0, 3, 4);
            r.status = Obj.PLAYING | Obj.PAUSE;
            move(s, r);
            ShipBody t = ship(10112, 5600, 0, 0, 0);
            t.status = 0;
            t.prevX = -5;
            move(s, t);
            ShipBody u = ship(10112, 5600, 0, 1, 0);
            u.status = Obj.PLAYING | Obj.GAME_OVER;
            move(s, u);
            ShipBody v = ship(10112, 5600, 0, 0, 1.0078125);
            v.status = 0;
            v.prevY = -5;
            move(s, v);
            assertAll(
                () -> assertEquals(10112 + 64, x1, "drifts 64 of 64.5 clicks"),
                () -> assertEquals(10112 >> 6, prev1, "remembers the old position"),
                () -> assertEquals(10112 + 128, p.cx, "then 64 again: nothing is carried"),
                () -> assertEquals((float) StrictMath.hypot(1.0078125, 1.0078125), p.velocity, "velocity is recomputed"),
                () -> assertEquals(5600 - 128, p.cy, "y drifts too (-64, then -64)"),
                () -> assertEquals(10112, q.cx, "a killed ship does not move"),
                () -> assertEquals(5.0, q.velocity, "but its velocity is updated: |(3,4)| = 5"),
                () -> assertEquals(10112, r.cx, "a paused ship does not move"),
                () -> assertEquals(-5, t.prevX, "a ship that does not move keeps its previous position"),
                () -> assertEquals(10112 + 64, u.cx, "a game-over ship drifts"),
                () -> assertEquals(5600 >> 6, v.prevY, "a ship that only moves in y remembers its position"));
        }
    }

    @Nested
    @DisplayName("wallDir and deltaDir (walls.c:2452-2470)")
    class Tables {
        @Test
        @DisplayName("the wall normal of each bounce")
        void wallDir() {
            assertAll(
                () -> assertEquals(64, ShipMover.wallDir(BOUNCE_HOR_LO), "low-x face points left"),
                () -> assertEquals(0, ShipMover.wallDir(BOUNCE_HOR_HI), "high-x face points right"),
                () -> assertEquals(96, ShipMover.wallDir(BOUNCE_VER_LO), "low-y face points down"),
                () -> assertEquals(32, ShipMover.wallDir(BOUNCE_VER_HI), "high-y face points up"),
                () -> assertEquals(16, ShipMover.wallDir(BOUNCE_LEFT_DOWN), "LeftDown: RES/8"),
                () -> assertEquals(112, ShipMover.wallDir(BOUNCE_LEFT_UP), "LeftUp: 7RES/8"),
                () -> assertEquals(48, ShipMover.wallDir(BOUNCE_RIGHT_DOWN), "RightDown: 3RES/8"),
                () -> assertEquals(80, ShipMover.wallDir(BOUNCE_RIGHT_UP), "RightUp: 5RES/8"),
                () -> assertEquals(32, ShipMover.wallDir(BOUNCE_EDGE), "anything else: the VerHi default"));
        }

        @Test
        @DisplayName("deltaDir is the signed shortest turn from dir to the wall normal, +-RES/2 at most")
        void deltaDir() {
            assertAll(
                () -> assertEquals(0, ShipMover.deltaDir(5, 5), "same heading: 0"),
                () -> assertEquals(-10, ShipMover.deltaDir(42, 32), "dir above: negative"),
                () -> assertEquals(10, ShipMover.deltaDir(22, 32), "dir below: positive"),
                () -> assertEquals(-64, ShipMover.deltaDir(64, 0), "exactly opposite from above: -RES/2"),
                () -> assertEquals(64, ShipMover.deltaDir(0, 64), "exactly opposite from below: +RES/2"),
                () -> assertEquals(28, ShipMover.deltaDir(100, 0), "dir above by more than RES/2 wraps: +28"),
                () -> assertEquals(-28, ShipMover.deltaDir(0, 100), "dir below by more than RES/2 wraps: -28"),
                () -> assertEquals(1, ShipMover.deltaDir(127, 0), "127 to 0 is +1"),
                () -> assertEquals(-1, ShipMover.deltaDir(0, 127), "0 to 127 is -1"));
        }

        @Test
        @DisplayName("length is C's VECTOR_LENGTH: the double hypot, stored as a float")
        void length() {
            assertAll(
                () -> assertEquals(5.0f, ShipMover.length(3, 4), "3-4-5"),
                () -> assertEquals((float) StrictMath.hypot(1.1f, 2.2f), ShipMover.length(1.1f, 2.2f), "float args"));
        }
    }

    @Nested
    @DisplayName("tie-break source")
    class TieBreak {
        @Test
        @DisplayName("a replaced source decides the hull-point choice and the corner choice")
        void replaced() {
            KernelFx.TestWorld w = moverWorld("", LEFT_WALL);
            ShipMover m = mover(w);
            int[] calls = new int[1];
            m.tieBreak(() -> {
                calls[0]++;
                return 0.0;
            });
            ShipBody p = ship(4480 + 576 + 64, 5600, 0, -2, 0);
            m.movePlayer(p, new KernelFx.Rec());
            assertAll(
                () -> assertEquals(1, calls[0], "the new source was drawn once (two points hit together)"),
                () -> assertEquals(new Rng(1).nextFloat(), w.rfrac(), "the old one (the world's rfrac) was not"));
        }

        @Test
        @DisplayName("the replaced source also decides a corner bounce (two feasible faces of a wall block)")
        void corner() {
            KernelFx.TestWorld w = moverWorld("", "     ", "     ", "  x  ", "     ", "  _  ");
            ShipMover m = mover(w);
            int[] calls = new int[1];
            m.tieBreak(() -> {
                calls[0]++;
                return 0.0;
            });
            // heading 0: the nose (960, 0) sits exactly on the lower-left corner of the wall block (2,2)
            ShipBody p = ship(2 * BC - 960, 2 * BC, 0, 1, 1);
            m.movePlayer(p, new KernelFx.Rec());
            assertAll(
                () -> assertEquals(1, calls[0], "the corner's two feasible bounces drew once from the new source"),
                () -> assertEquals(new Rng(1).nextFloat(), w.rfrac(), "and not from the world's rfrac"));
        }
    }
}
