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

import com.fulcrologic.jxpilot.common.sim.ShipMover;
import com.fulcrologic.jxpilot.common.sim.MoveState;
import com.fulcrologic.jxpilot.common.Item;
import com.fulcrologic.jxpilot.common.math.Rng;
import com.fulcrologic.jxpilot.common.net.Records;
import com.fulcrologic.jxpilot.server.TestWorlds;
import com.fulcrologic.jxpilot.server.world.Events;
import com.fulcrologic.jxpilot.common.sim.Has;
import com.fulcrologic.jxpilot.common.sim.Obj;
import com.fulcrologic.jxpilot.server.world.Objects;
import com.fulcrologic.jxpilot.server.world.Params;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.common.sim.Trig;
import com.fulcrologic.jxpilot.server.world.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static com.fulcrologic.jxpilot.common.sim.MoveState.*;
import static com.fulcrologic.jxpilot.common.sim.Click.BLOCK_CLICKS;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Mover#movePlayer} and {@link Mover#wallBounce}: C {@code Move_player} (walls.c:2209-2599).
 * Worlds run at 60 fps unless stated (one tick is one C frame at any fps). The default ship at heading 0 has hull points (15,0), (-9,8),
 * (-9,-8) px = (960,0), (-576,512), (-576,-512) clicks from its centre.
 */
class MoverPlayerTest {
    static final int BC = BLOCK_CLICKS;
    static final String FPS60 = "framesPerSecond: 60";

    /** Walls at blocks (5,1)-(5,3): their left face is x = 175 px = 11200 clicks. Base at (1,2). */
    static final String[] RIGHT_WALL = {"       ", "     x ", " _   x ", "     x ", "       "};
    /** Walls at blocks (1,1)-(1,3): their right face is x = 70 px = 4480 clicks. Base at (5,2). */
    static final String[] LEFT_WALL = {"       ", " x     ", " x   _ ", " x     ", "       "};
    static final String E9 = "         ";
    /** 9x9 open map, base in the bottom-left corner: block (4,4) has walldist 10. */
    static final String[] OPEN = {E9, E9, E9, E9, E9, E9, E9, E9, "_        "};
    /** Like RIGHT_WALL with a target (index 0) at block (5,2) where the nose hits. */
    static final String[] TARGET = {"       ", "     x ", " _   ! ", "     x ", "       "};

    static Simulation sim(String extra, String... rows) {
        Simulation s = TestWorlds.sim(FPS60 + "\n" + extra, rows);
        Params p = s.world.params;
        p.friction = 0;
        p.coriolis = 0;
        p.playerWallBrakeFactor = 0.5f;
        p.maxUnshieldedWallBounceSpeed = 10;
        p.maxShieldedWallBounceSpeed = 20;
        p.maxUnshieldedAngle = 64;
        p.maxShieldedAngle = 64;
        p.wallBounceFuelDrainMult = 1;
        p.wallBounceExplosionMult = 1;
        p.edgeBounce = true;
        s.world.frames = 7;
        return s;
    }

    static Player ship(Simulation s, int cx, int cy, int dir, double vx, double vy) {
        Player p = TestWorlds.join(s, "p", 0);
        p.used = 0;
        p.have = 0;
        p.mass = 20;
        p.emptyMass = 20;
        p.fuel = 10000;
        p.fuelMax = 1e7;
        p.setClicks(cx, cy);
        p.remember();
        p.dir = dir;
        p.floatDir = dir;
        p.vx = (float) vx;
        p.vy = (float) vy;
        p.velocity = (float) Math.hypot(vx, vy);
        p.lastWallTouch = -1;
        s.world.objects.clear();
        return p;
    }

    /** The default ship at heading 0, nose 128 clicks left of the RIGHT_WALL face, moving right 4 px/frame. */
    static Player headOn(Simulation s) {
        return ship(s, 10112, 5600, 0, 4, 0);
    }

    static String lastMessage(World w) {
        Events e = w.events;
        for (long q = e.seq() - 1; q >= e.oldestSeq(); q--) {
            int i = Events.index(q);
            if (e.kind[i] == Events.MESSAGE) {
                return e.text[i].toString();
            }
        }
        return null;
    }

    static boolean killed(Player p) {
        return (p.status & Obj.KILLED) != 0;
    }

    // ---------------------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("a survived wall bounce")
    class Bounce {
        @Test
        @DisplayName("brakes, reflects, drains fuel by cost and makes a WALL_SPARK burst of intensity/2..intensity")
        void simple() {
            Simulation s = sim("", RIGHT_WALL);
            World w = s.world;
            Player p = headOn(s);
            w.rng.seed(42);
            int expectedSparks = 1 + (int) (new Rng(42).nextFloat() * (3 - 1));
            s.mover.movePlayer(p);
            Objects o = w.objects;
            int h = o.active[0];
            // speed 4: v = 4>>2 = 1; m = (int)(20 - 0.75*20) = 5; b = 1 - 0.5*0.5 = 0.75: cost = (long)3.75 = 3;
            // heading 0 vs wall normal RES/2: |delta| = 64, cost = 3*(64+64)/128 = 3; fuel -= 3 << 8
            assertAll(
                () -> assertEquals(10176, p.cx, "128 to the wall, bounce, (int)(-128*0.5) = 64 back: nose at 11136"),
                () -> assertEquals(5600, p.cy, "y unchanged"),
                () -> assertEquals(-2.0, p.vx, "velocity reflected and braked by 0.5"),
                () -> assertEquals(0.0, p.vy, "no y velocity"),
                () -> assertEquals(2.0, p.velocity, "velocity magnitude updated"),
                () -> assertEquals(10000 - 768, p.fuel, "fuel drops by cost << 8 * wallBounceFuelDrainMult = 768"),
                () -> assertEquals(7.0, p.lastWallTouch, "last wall touch is now"),
                () -> assertEquals(10176 >> 6, p.prevX, "the bounce makes the new position the previous one"),
                () -> assertEquals(false, killed(p), "the ship survives"),
                () -> assertEquals(expectedSparks, o.count(), "intensity 3: 1 + (int)(rfrac * 2) sparks"),
                () -> assertEquals(Obj.SPARK, o.type[h], "the particles are sparks"),
                () -> assertEquals(Obj.GRAVITY | Obj.OWNERIMMUNE | Obj.FROMBOUNCE, o.status[h], "owner immune bounce sparks"),
                () -> assertEquals(p.id, o.owner[h], "owned by the ship"),
                () -> assertEquals(1L, w.events.burstSeq(), "one burst is recorded for clients"),
                () -> assertEquals(Records.BurstKind.WALL_SPARK, w.events.burstKind[0], "a WALL_SPARK burst"),
                () -> assertEquals(p.id, w.events.burstOwner[0], "owned by the ship"));
        }

        @Test
        @DisplayName("a traced move near the wall that touches nothing keeps the pre-move position as the previous one")
        void tracedNoBounce() {
            Simulation s = sim("", RIGHT_WALL);
            Player p = ship(s, 10112, 5600, 0, 1, 0);
            s.mover.movePlayer(p);
            assertAll(
                () -> assertEquals(10112 + 64, p.cx, "moved 1 px towards the wall, no bounce"),
                () -> assertEquals(1.0, p.vx, "velocity unchanged"),
                () -> assertEquals(10112 >> 6, p.prevX, "previous position: before the move"));
        }

        @Test
        @DisplayName("cost formula: (long)((1-brake/2)*(int)(mass-0.75*emptyMass)*((int)speed>>2)) * (RES/2+|delta|)/RES")
        void costFormula() {
            Simulation s = sim("", RIGHT_WALL);
            World w = s.world;
            Params prm = w.params;
            prm.maxUnshieldedWallBounceSpeed = 30;
            prm.wallBounceFuelDrainMult = 2;
            prm.wallBounceExplosionMult = (float) Math.sqrt(2);
            // heading up; its rightmost hull point (-9,-8) rotated is at +512 clicks (C float rotation), 100 from the wall
            Player p = ship(s, 11200 - 100 - 512, 5600, 32, 20, 0);
            p.mass = 30;
            p.fuel = 100000;
            w.rng.seed(42);
            int expectedSparks = 29 + (int) (new Rng(42).nextFloat() * (59 - 29));
            s.mover.movePlayer(p);
            // v = 20>>2 = 5, m = (int)(30 - 15) = 15, b = 0.75: (long)56.25 = 56; |delta| = |32 - 64| = 32:
            // 56 * 96 / 128 = 42; fuel -= 42 << 8 * 2 = 21504; intensity (int)(42 * sqrt 2) = 59
            assertAll(
                () -> assertEquals(100000 - 21504, p.fuel, "fuel drops by 42 * 256 * 2"),
                () -> assertEquals(expectedSparks, w.objects.count(), "29 + (int)(rfrac * 30) sparks for intensity 59"),
                () -> assertEquals(-10.0, p.vx, "velocity reflected and halved"),
                () -> assertEquals(10098, p.cx, "100 in, bounce, (int)(-1180*0.5) = 590 back"),
                () -> assertEquals(5600, p.cy, "y unchanged (the nose is 15 px above the centre)"),
                () -> assertEquals(32, p.dir, "the heading is outside RES/16 of the normal: unchanged"),
                () -> assertEquals(32.0, p.floatDir, "floatDir unchanged"));
        }

        @Test
        @DisplayName("within RES/16 of the wall normal the heading is nudged by (1-brake)*delta, wrapping into [0, RES)")
        void headingNudge() {
            Simulation a = sim("", RIGHT_WALL);
            // heading 60: rightmost hull point (-9,-8) rotated is at +664 clicks
            Player pa = ship(a, 11200 - 100 - 664, 5600, 60, 4, 0);
            a.mover.movePlayer(pa);
            Simulation b = sim("", LEFT_WALL);
            b.world.params.playerWallBrakeFactor = 0;
            // heading 127: leftmost hull point is at -600 clicks
            Player pb = ship(b, 4480 + 100 + 600, 5600, 127, -4, 0);
            pb.floatDir = 127.4f;
            b.mover.movePlayer(pb);
            Simulation c = sim("", LEFT_WALL);
            c.world.params.playerWallBrakeFactor = 0;
            Player pc = ship(c, 4480 + 100 + 600, 5600, 1, -4, 0);
            pc.floatDir = 0.6f;
            c.mover.movePlayer(pc);
            assertAll(
                () -> assertEquals(62.0, pa.floatDir, 1e-9, "heading 60, normal 64: floatDir += 0.5 * 4"),
                () -> assertEquals(60, pa.dir, "dir itself is left to Turn_player"),
                () -> assertTrue(pa.vx < 0, "(it bounced)"),
                () -> assertEquals((127.4f + 1.0f) - 128, pb.floatDir, "127.4 + 1 = 128.4 wraps to 0.4 (C float)"),
                () -> assertEquals((0.6f - 1.0f) + 128, pc.floatDir, "0.6 - 1 = -0.4 wraps to 127.6 (C float)"),
                () -> assertEquals(0.0, pb.vx, "brake factor 0 stops the ship"));
        }

        @Test
        @DisplayName("bouncing off a target (players do not crash on targets) behaves like a wall")
        void target() {
            Simulation s = sim("", "       ", "     ! ", " _   ! ", "     ! ", "       ");
            s.world.params.targetTeamCollision = true;
            Player p = headOn(s);
            s.mover.movePlayer(p);
            assertAll(
                () -> assertEquals(10176, p.cx, "same path as a wall bounce"),
                () -> assertEquals(false, killed(p), "survives"));
        }
    }

    // ---------------------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("fatal wall bounces")
    class Fatal {
        @Test
        @DisplayName("too fast: CRASH_WALL_SPEED ('smashed'), killed on the face, braked velocity, no fuel cost")
        void speed() {
            Simulation s = sim("", RIGHT_WALL);
            s.world.params.maxUnshieldedWallBounceSpeed = 3.9f;
            Player p = headOn(s);
            s.mover.movePlayer(p);
            assertAll(
                () -> assertTrue(killed(p), "the ship is killed"),
                () -> assertEquals("p smashed head first against a wall.", lastMessage(s.world),
                    "CrashWallSpeed message; the nose (point 0) hit"),
                () -> assertEquals(11200 - 960, p.cx, "it stays with the nose on the face"),
                () -> assertEquals(-2.0, p.vx, "the velocity was braked before the check (as C)"),
                () -> assertEquals(10000.0, p.fuel, "no fuel is drained"),
                () -> assertEquals(0, s.world.objects.count(), "no sparks"));
        }

        @Test
        @DisplayName("speed exactly at the limit survives")
        void speedAtLimit() {
            Simulation s = sim("", RIGHT_WALL);
            s.world.params.maxUnshieldedWallBounceSpeed = 4;
            Player p = headOn(s);
            s.mover.movePlayer(p);
            assertEquals(false, killed(p), "speed 4 with limit 4 is not fatal");
        }

        @Test
        @DisplayName("too steep: CRASH_WALL_ANGLE ('was trashed'); exactly at the limit survives")
        void angle() {
            Simulation s = sim("", RIGHT_WALL);
            s.world.params.maxUnshieldedAngle = 63;
            Player p = headOn(s);
            s.mover.movePlayer(p);
            Simulation ok = sim("", RIGHT_WALL);
            Player q = headOn(ok);
            ok.mover.movePlayer(q);
            assertAll(
                () -> assertTrue(killed(p), "|delta| 64 > 63: killed"),
                () -> assertEquals("p was trashed head first against a wall.", lastMessage(s.world), "CrashWallAngle message"),
                () -> assertEquals(10000.0, p.fuel, "no fuel is drained"),
                () -> assertEquals(false, killed(q), "|delta| 64 with limit 64 survives"));
        }

        @Test
        @DisplayName("out of fuel: CRASH_WALL_NO_FUEL ('smacked') unless the drain multiplier is 0")
        void noFuel() {
            Simulation s = sim("", RIGHT_WALL);
            Player p = headOn(s);
            p.fuel = 500;
            s.mover.movePlayer(p);
            Simulation z = sim("", RIGHT_WALL);
            z.world.params.wallBounceFuelDrainMult = 0;
            z.world.params.wallBounceExplosionMult = 0;
            Player q = headOn(z);
            q.fuel = 0;
            z.mover.movePlayer(q);
            assertAll(
                () -> assertTrue(killed(p), "500 - 768 leaves no fuel: killed"),
                () -> assertEquals(0.0, p.fuel, "fuel is clamped at 0"),
                () -> assertEquals("p smacked head first against a wall.", lastMessage(s.world), "CrashWallNoFuel message"),
                () -> assertEquals(false, killed(q), "no drain: an empty tank is no crash"),
                () -> assertEquals(0, z.world.objects.count(), "intensity cost * sqrt(0) = 0 makes no sparks"));
        }

        @Test
        @DisplayName("a fatal bounce off a target is CRASH_TARGET")
        void target() {
            Simulation s = sim("", TARGET);
            s.world.params.targetTeamCollision = true;
            s.world.params.maxUnshieldedWallBounceSpeed = 3;
            Player p = headOn(s);
            s.mover.movePlayer(p);
            Simulation a = sim("", TARGET);
            a.world.params.targetTeamCollision = true;
            a.world.params.maxUnshieldedAngle = 10;
            Player q = headOn(a);
            a.mover.movePlayer(q);
            Simulation f = sim("", TARGET);
            f.world.params.targetTeamCollision = true;
            Player r = headOn(f);
            r.fuel = 1;
            f.mover.movePlayer(r);
            assertAll(
                () -> assertEquals("p smashed head first against a target.", lastMessage(s.world), "speed on a target"),
                () -> assertEquals("p smashed head first against a target.", lastMessage(a.world), "angle on a target"),
                () -> assertEquals("p smashed head first against a target.", lastMessage(f.world), "no fuel on a target"));
        }
    }

    // ---------------------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("shields and armor")
    class Shields {
        @Test
        @DisplayName("a shield uses the shielded speed and angle limits but still pays fuel")
        void shield() {
            Simulation s = sim("", RIGHT_WALL);
            Params prm = s.world.params;
            prm.maxUnshieldedWallBounceSpeed = 3;
            prm.maxShieldedWallBounceSpeed = 5;
            prm.maxUnshieldedAngle = 10;
            prm.maxShieldedAngle = 64;
            Player p = headOn(s);
            p.used = Has.SHIELD;
            s.mover.movePlayer(p);
            Simulation t = sim("", RIGHT_WALL);
            t.world.params.maxShieldedWallBounceSpeed = 3;
            Player q = headOn(t);
            q.used = Has.SHIELD;
            t.mover.movePlayer(q);
            assertAll(
                () -> assertEquals(false, killed(p), "speed 4 <= 5 and angle 64 <= 64 with the shield"),
                () -> assertEquals(10000.0 - 768, p.fuel, "fuel is still drained"),
                () -> assertTrue(killed(q), "the shielded speed limit 3 still kills at speed 4"));
        }

        @Test
        @DisplayName("shield + emergency shield: speed limit at least 100, any angle, no fuel cost, sparks still")
        void fullyShielded() {
            Simulation s = sim("", RIGHT_WALL);
            Params prm = s.world.params;
            prm.maxShieldedWallBounceSpeed = 2;
            prm.maxShieldedAngle = 0;
            Player p = headOn(s);
            p.used = Has.SHIELD | Has.EMERGENCY_SHIELD;
            s.mover.movePlayer(p);
            Simulation e = sim("", RIGHT_WALL);
            e.world.params.maxUnshieldedWallBounceSpeed = 2;
            Player q = headOn(e);
            q.used = Has.EMERGENCY_SHIELD;
            e.mover.movePlayer(q);
            Simulation hi = sim("", RIGHT_WALL);
            hi.world.params.maxShieldedWallBounceSpeed = 3.5f;
            hi.world.params.maxUnshieldedWallBounceSpeed = 3;
            Player r = ship(hi, 10112, 5600, 0, 120, 0);
            r.used = Has.SHIELD | Has.EMERGENCY_SHIELD;
            hi.mover.movePlayer(r);
            assertAll(
                () -> assertEquals(false, killed(p), "survives speed 4 with limit 2 and angle 64 with limit 0"),
                () -> assertEquals(10000.0, p.fuel, "no fuel cost"),
                () -> assertTrue(s.world.objects.count() > 0, "bounce sparks are still made"),
                () -> assertTrue(killed(q), "an emergency shield alone is not a shield"),
                () -> assertTrue(killed(r), "speed 120 exceeds the raised limit of 100"));
        }

        @Test
        @DisplayName("armor raises the limits to the shielded ones when needed and is used up")
        void armor() {
            Simulation s = sim("", RIGHT_WALL);
            Params prm = s.world.params;
            prm.maxUnshieldedWallBounceSpeed = 3;
            prm.maxShieldedWallBounceSpeed = 5;
            Player p = headOn(s);
            p.have = Has.ARMOR;
            p.items[Item.ARMOR.ordinal()] = 2;
            s.mover.movePlayer(p);
            Simulation last = sim("", RIGHT_WALL);
            last.world.params.maxUnshieldedWallBounceSpeed = 3;
            last.world.params.maxShieldedWallBounceSpeed = 5;
            Player q = headOn(last);
            q.have = Has.ARMOR;
            q.items[Item.ARMOR.ordinal()] = 1;
            last.mover.movePlayer(q);
            Simulation both = sim("", RIGHT_WALL);
            both.world.params.maxUnshieldedWallBounceSpeed = 3;
            both.world.params.maxShieldedWallBounceSpeed = 5;
            both.world.params.maxUnshieldedAngle = 10;
            Player r = headOn(both);
            r.have = Has.ARMOR;
            r.items[Item.ARMOR.ordinal()] = 3;
            both.mover.movePlayer(r);
            Simulation angle = sim("", RIGHT_WALL);
            angle.world.params.maxUnshieldedAngle = 10;
            Player t = headOn(angle);
            t.have = Has.ARMOR;
            t.items[Item.ARMOR.ordinal()] = 3;
            angle.mover.movePlayer(t);
            Simulation unneeded = sim("", RIGHT_WALL);
            Player u = headOn(unneeded);
            u.have = Has.ARMOR;
            u.items[Item.ARMOR.ordinal()] = 3;
            unneeded.mover.movePlayer(u);
            Simulation tooFast = sim("", RIGHT_WALL);
            tooFast.world.params.maxUnshieldedWallBounceSpeed = 3.5f;
            tooFast.world.params.maxShieldedWallBounceSpeed = 3;
            Player v = headOn(tooFast);
            v.have = Has.ARMOR;
            v.items[Item.ARMOR.ordinal()] = 3;
            tooFast.mover.movePlayer(v);
            Simulation shielded = sim("", RIGHT_WALL);
            shielded.world.params.maxShieldedWallBounceSpeed = 3;
            shielded.world.params.maxUnshieldedWallBounceSpeed = 2;
            Player x = headOn(shielded);
            x.used = Has.SHIELD;
            x.have = Has.ARMOR;
            x.items[Item.ARMOR.ordinal()] = 3;
            shielded.mover.movePlayer(x);
            assertAll(
                () -> assertEquals(false, killed(p), "armor lets speed 4 pass the shielded limit 5"),
                () -> assertEquals(1, p.items[Item.ARMOR.ordinal()], "one armor piece is used"),
                () -> assertEquals(Has.ARMOR, p.have, "armor remains while pieces are left"),
                () -> assertEquals(0, q.items[Item.ARMOR.ordinal()], "the last piece is used"),
                () -> assertEquals(0, q.have, "and the ARMOR ability is lost"),
                () -> assertEquals(false, killed(r), "armor used for speed also raises the angle limit"),
                () -> assertEquals(2, r.items[Item.ARMOR.ordinal()], "so one piece covers both"),
                () -> assertEquals(false, killed(t), "armor lets |delta| 64 pass the shielded angle 64"),
                () -> assertEquals(2, t.items[Item.ARMOR.ordinal()], "angle alone uses one piece"),
                () -> assertEquals(3, u.items[Item.ARMOR.ordinal()], "armor is not used when not needed"),
                () -> assertTrue(killed(v), "armor does not help when the unshielded limit is not below the shielded one"),
                () -> assertEquals(3, v.items[Item.ARMOR.ordinal()], "and is not used then"),
                () -> assertTrue(killed(x), "armor is not used with the shield up"),
                () -> assertEquals(3, x.items[Item.ARMOR.ordinal()], "no piece used with the shield up"));
        }
    }

    // ---------------------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("several hull points and edges")
    class Hull {
        @Test
        @DisplayName("an edge bounce is free: no brake, no fuel, no sparks; ties consume one rfrac()")
        void edgeBounce() {
            Simulation s = sim("", RIGHT_WALL);
            World w = s.world;
            Player p = ship(s, 676, 5600, 0, -4, 0);
            w.rng.seed(42);
            Rng ref = new Rng(42);
            ref.nextFloat();
            float second = ref.nextFloat();
            s.mover.movePlayer(p);
            Simulation stop = sim("", RIGHT_WALL);
            stop.world.params.edgeBounce = false;
            Player q = ship(stop, 676, 5600, 0, -4, 0);
            stop.mover.movePlayer(q);
            assertAll(
                () -> assertEquals(4.0, p.vx, "reflected without braking"),
                () -> assertEquals(732, p.cx, "100 to the edge, 156 back: rear points at 156, centre at 732"),
                () -> assertEquals(10000.0, p.fuel, "no fuel cost"),
                () -> assertEquals(0, w.objects.count(), "no sparks"),
                () -> assertEquals(7.0, p.lastWallTouch, "an edge touch counts as a wall touch"),
                () -> assertEquals(second, w.rfrac(), "the two simultaneous edge bounces consumed exactly one rfrac()"),
                () -> assertEquals(0.0, q.vx, "without edgeBounce the x motion stops"),
                () -> assertEquals(576, q.cx, "with the rear points on the edge"));
        }

        @Test
        @DisplayName("two hull points hitting a wall together: one is chosen with rfrac(), the bounce happens once")
        void twoPoints() {
            Simulation s = sim("", LEFT_WALL);
            World w = s.world;
            // heading 0 moving left: both rear points (-576, +-512) reach the face x = 4480 together; speed 2 costs 0
            Player p = ship(s, 4480 + 576 + 64, 5600, 0, -2, 0);
            w.rng.seed(42);
            Rng ref = new Rng(42);
            ref.nextFloat();
            float second = ref.nextFloat();
            s.mover.movePlayer(p);
            assertAll(
                () -> assertEquals(1.0, p.vx, "reflected and braked once"),
                () -> assertEquals(4480 + 576 + 32, p.cx, "64 in, bounce, 32 back out"),
                () -> assertEquals(second, w.rfrac(), "exactly one rfrac() was used to pick the point"),
                () -> assertEquals(10000.0, p.fuel, "speed 2: (int)2 >> 2 = 0, no cost"));
        }

        @Test
        @DisplayName("a crash wins over bounces: hitting a cannon kills and destroys it; fully shielded survives it")
        void cannonCrash() {
            String[] cannon = {"       ", "     x ", " _   f ", "     x ", "       "};
            Simulation s = sim("", cannon);
            Player p = headOn(s);
            s.mover.movePlayer(p);
            Simulation t = sim("", cannon);
            Player q = headOn(t);
            q.used = Has.SHIELD | Has.EMERGENCY_SHIELD;
            t.mover.movePlayer(q);
            assertAll(
                () -> assertTrue(killed(p), "the ship is killed"),
                () -> assertEquals("p smashed head first against a cannon.", lastMessage(s.world), "cannon crash message"),
                () -> assertTrue(s.world.cannonDeadTime[0] > 0, "the cannon is destroyed"),
                () -> assertEquals(11200 - 960, p.cx, "stopped with the nose on the cannon"),
                () -> assertEquals(4.0, p.vx, "a crash does not change the velocity"),
                () -> assertEquals(false, killed(q), "a fully shielded ship survives"),
                () -> assertTrue(t.world.cannonDeadTime[0] > 0, "and destroys the cannon"));
        }

        @Test
        @DisplayName("a hull point in a wormhole whose block holds the centre starts warping")
        void wormhole() {
            Simulation s = sim("", "       ", "       ", " _ @ @ ", "       ", "       ");
            Player p = ship(s, 3 * BC + 1120, 5600, 0, 1, 0);
            s.mover.movePlayer(p);
            assertAll(
                () -> assertEquals(Obj.WARPING, p.status & Obj.WARPING, "WARPING is set"),
                () -> assertEquals(0, p.wormHoleHit, "the hole hit is recorded"),
                () -> assertEquals(3 * BC + 1120, p.cx, "the ship does not move"),
                () -> assertEquals(false, killed(p), "it is not killed"));
        }
    }

    // ---------------------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("free movement")
    class Free {
        final int c = 4 * BC + 1000;

        @Test
        @DisplayName("far from walls the ship moves FLOAT_TO_CLICK(vel) clicks, the fraction dropped as in C")
        void truncates() {
            Simulation s = sim("", OPEN);
            Player p = ship(s, c, c, 0, 1.0078125, -1.0078125);
            p.prevX = -5;
            s.mover.movePlayer(p);
            int x1 = p.cx;
            int prev1 = p.prevX;
            s.mover.movePlayer(p);
            assertAll(
                () -> assertEquals(c + 64, x1, "tick 1: 64 of 64.5 clicks"),
                () -> assertEquals(c >> 6, prev1, "the previous position is remembered before moving"),
                () -> assertEquals(c + 128, p.cx, "tick 2: 64 again (no remainder is carried)"),
                () -> assertEquals(c - 128, p.cy, "negative velocity truncates toward zero"),
                () -> assertEquals((float) Math.hypot(1.0078125, 1.0078125), p.velocity,
                    "velocity is the speed (C VECTOR_LENGTH as a float)"));
        }

        @Test
        @DisplayName("friction multiplies by (1.0f - f) in float; blockFriction applies on FRICTION blocks unless phased")
        void friction() {
            Simulation s = sim("", OPEN);
            s.world.params.friction = 0.1f;
            Player p = ship(s, c, c, 0, 10, 0);
            s.mover.movePlayer(p);
            Simulation slow = sim("framesPerSecond: 14", OPEN);
            slow.world.params.friction = 0.1f;
            Player q = ship(slow, c, c, 0, 10, 0);
            slow.mover.movePlayer(q);
            float vp = (1.0f - 0.1f) * (10f * 1f + 0f * 0f);
            String[] fr = OPEN.clone();
            fr[4] = "    z    ";
            Simulation f = sim("", fr);
            f.world.params.blockFriction = 0.5f;
            Player r = ship(f, c, c, 0, 10, 0);
            f.mover.movePlayer(r);
            Simulation ph = sim("", fr);
            ph.world.params.blockFriction = 0.5f;
            Player t = ship(ph, c, c, 0, 10, 0);
            t.used = Has.PHASING_DEVICE;
            ph.mover.movePlayer(t);
            assertAll(
                () -> assertEquals(vp, p.vx, "10 * (1.0f - 0.1f) in float (C: 9.0000004)"),
                () -> assertEquals(c + (int) (vp * 64), p.cx, "and moves FLOAT_TO_CLICK(vx)"),
                () -> assertEquals(vp, q.vx, "14 fps damps exactly the same per tick (one tick is one frame)"),
                () -> assertEquals(5.0f, r.vx, "on a friction block blockFriction 0.5 halves the velocity"),
                () -> assertEquals(10.0f, t.vx, "a phased ship uses the normal friction (0) there"));
        }

        @Test
        @DisplayName("coriolis rotates the velocity clockwise by coriolis*RES/360 RES units (MOD RES), float tables")
        void coriolis() {
            Simulation s = sim("", OPEN);
            s.world.params.coriolis = 90;
            Player p = ship(s, c, c, 0, 2, 0);
            s.mover.movePlayer(p);
            Simulation n = sim("", OPEN);
            n.world.params.coriolis = -90;
            Player q = ship(n, c, c, 0, 2, 0);
            n.mover.movePlayer(q);
            Simulation f = sim("", OPEN);
            f.world.params.coriolis = 360;
            f.world.params.friction = 0.5f;
            Player r = ship(f, c, c, 0, 2, 2);
            f.mover.movePlayer(r);
            Simulation slow = sim("framesPerSecond: 14", OPEN);
            slow.world.params.coriolis = 90;
            Player t = ship(slow, c, c, 0, 2, 0);
            slow.mover.movePlayer(t);
            float c32 = Trig.tcos(32);
            assertAll(
                () -> assertEquals((1.0f - 0f) * (2f * c32 + 0f), p.vx, "90 degrees: (2,0) -> (2*tcos(32), -2): x"),
                () -> assertEquals(-2.0f, p.vy, "90 degrees: (2,0) -> (0,-2): y"),
                () -> assertEquals(2.0f, q.vy, "-90 degrees is MOD2(-32, RES) = 96: (2,0) -> (0,2)"),
                () -> assertEquals(1.0f, r.vx, "360 degrees is no rotation; friction 0.5 still halves x"),
                () -> assertEquals(1.0f, r.vy, "and y"),
                () -> assertEquals(-2.0f, t.vy, "14 fps rotates by the same 90 degrees per tick"));
        }

        @Test
        @DisplayName("the free-flight check uses the speed after the previous move (C pl->velocity)")
        void staleVelocity() {
            Simulation s = sim("", OPEN);
            Player p = ship(s, c, c, 64, 200, 0);
            p.velocity = 122;
            s.mover.movePlayer(p);
            Simulation t = sim("", OPEN);
            Player q = ship(t, c, c, 64, 200, 0);
            t.mover.movePlayer(q);
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
            Simulation s = sim("", OPEN);
            Player p = ship(s, c, c, 64, 100, 0);
            p.velocity = 200;
            int prev0 = p.prevX;
            s.mover.movePlayer(p);
            Simulation t = sim("", OPEN);
            Player q = ship(t, c, c, 0, 40, -140);
            q.velocity = 200;
            t.mover.movePlayer(q);
            Simulation wr = sim("edgeWrap: yes", OPEN);
            Player r = ship(wr, c, c, 0, 300, 0);
            r.velocity = 400;
            wr.mover.movePlayer(r);
            Simulation wy = sim("edgeWrap: yes", OPEN);
            Player u = ship(wy, c, c, 0, 0, -300);
            u.velocity = 400;
            wy.mover.movePlayer(u);
            assertAll(
                () -> assertEquals(c + 6400, p.cx, "6400 clicks fit the first chunk of ((10-3)*2240)>>1 = 7840"),
                () -> assertEquals(c + 2560, q.cx, "x arrives at +2560"),
                () -> assertEquals(c - 8960, q.cy, "y arrives at -8960 (a 7840 vertical chunk, then traced)"),
                () -> assertEquals(c + 19200 - wr.world.clickWidth, r.cx, "with wrap the chunks wrap around: x"),
                () -> assertEquals(c - 19200 + wy.world.clickHeight, u.cy, "with wrap the chunks wrap around: y"),
                () -> assertEquals(300.0, r.vx, "no bounce"),
                () -> assertEquals(prev0, p.prevX, "without a bounce or crash the previous position is kept"));
        }

        @Test
        @DisplayName("on a wrapping map free flight wraps")
        void wraps() {
            Simulation s = sim("edgeWrap: yes", OPEN);
            Player p = ship(s, s.world.clickWidth - 10, c, 0, 1, -1);
            s.mover.movePlayer(p);
            assertAll(
                () -> assertEquals(54, p.cx, "x wraps"),
                () -> assertEquals(c - 64, p.cy, "y moves"));
        }
    }

    // ---------------------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("ships not playing")
    class NotPlaying {
        @Test
        @DisplayName("a dead ship (not PLAYING) drifts with its velocity; killed or paused ships do not move")
        void drift() {
            Simulation s = sim("", RIGHT_WALL);
            Player p = ship(s, 10112, 5600, 0, 1.0078125, -1.0078125);
            p.status = 0;
            p.velocity = 99;
            p.prevX = -5;
            s.mover.movePlayer(p);
            int x1 = p.cx;
            int prev1 = p.prevX;
            s.mover.movePlayer(p);
            Simulation k = sim("", RIGHT_WALL);
            Player q = ship(k, 10112, 5600, 0, 3, 4);
            q.status = Obj.PLAYING | Obj.KILLED;
            k.mover.movePlayer(q);
            Simulation ps = sim("", RIGHT_WALL);
            Player r = ship(ps, 10112, 5600, 0, 3, 4);
            r.status = Obj.PLAYING | Obj.PAUSE;
            ps.mover.movePlayer(r);
            Simulation z = sim("", RIGHT_WALL);
            Player t = ship(z, 10112, 5600, 0, 0, 0);
            t.status = 0;
            t.prevX = -5;
            z.mover.movePlayer(t);
            Simulation g = sim("", RIGHT_WALL);
            Player u = ship(g, 10112, 5600, 0, 1, 0);
            u.status = Obj.PLAYING | Obj.GAME_OVER;
            g.mover.movePlayer(u);
            assertAll(
                () -> assertEquals(10112 + 64, x1, "drifts 64 of 64.5 clicks"),
                () -> assertEquals(10112 >> 6, prev1, "remembers the old position"),
                () -> assertEquals(10112 + 128, p.cx, "then 64 again: nothing is carried"),
                () -> assertEquals((float) Math.hypot(1.0078125, 1.0078125), p.velocity, "velocity is recomputed"),
                () -> assertEquals(5600 - 128, p.cy, "y drifts too (-64, then -64)"),
                () -> assertEquals(10112, q.cx, "a killed ship does not move"),
                () -> assertEquals(5.0, q.velocity, "but its velocity is updated: |(3,4)| = 5"),
                () -> assertEquals(10112, r.cx, "a paused ship does not move"),
                () -> assertEquals(-5, t.prevX, "a ship that does not move keeps its previous position"),
                () -> assertEquals(10112 + 64, u.cx, "a game-over ship drifts"));
        }
    }

    // ---------------------------------------------------------------------------------------------------------

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
    }

    @Nested
    @DisplayName("MoveState")
    class State {
        @Test
        @DisplayName("start resets the per-move outputs; copyInfo copies only the move info")
        void startAndCopy() {
            MoveState a = new MoveState();
            a.crash = CRASH_WALL;
            a.bounce = BOUNCE_EDGE;
            a.doneX = 3;
            a.doneY = 4;
            a.cannon = 1;
            a.wormhole = 2;
            a.target = 3;
            a.treasure = 4;
            a.start(10, 20, 1.5f, -2.5f, 30, 40, 7);
            MoveState src = new MoveState();
            src.edgeWrap = true;
            src.edgeBounce = true;
            src.wallBounce = true;
            src.cannonCrashes = true;
            src.targetCrashes = true;
            src.treasureCrashes = true;
            src.wormholeWarps = true;
            src.phased = true;
            src.pl = new Player(3);
            src.obj = 9;
            src.team = 2;
            src.status = 5;
            src.type = 6;
            src.posX = 77;
            MoveState b = new MoveState();
            b.copyInfo(src);
            assertAll(
                () -> assertEquals(10, a.posX, "position x"),
                () -> assertEquals(20, a.posY, "position y"),
                () -> assertEquals(1.5, a.velX, "velocity x"),
                () -> assertEquals(-2.5, a.velY, "velocity y"),
                () -> assertEquals(30, a.todoX, "todo x"),
                () -> assertEquals(40, a.todoY, "todo y"),
                () -> assertEquals(7, a.dir, "heading"),
                () -> assertEquals(NOT_A_CRASH, a.crash, "crash cleared"),
                () -> assertEquals(NOT_A_BOUNCE, a.bounce, "bounce cleared"),
                () -> assertEquals(0, a.doneX | a.doneY, "done cleared"),
                () -> assertEquals(-1, a.cannon & a.wormhole & a.target & a.treasure, "hit indices cleared to -1"),
                () -> assertEquals(true, b.edgeWrap && b.edgeBounce && b.wallBounce && b.cannonCrashes && b.targetCrashes
                    && b.treasureCrashes && b.wormholeWarps && b.phased, "all flags copied"),
                () -> assertEquals(src.pl, b.pl, "player copied"),
                () -> assertEquals(9, b.obj, "object copied"),
                () -> assertEquals(2, b.team, "team copied"),
                () -> assertEquals(5, b.status, "status copied"),
                () -> assertEquals(6, b.type, "type copied"),
                () -> assertEquals(0, b.posX, "the state (position) is not copied"));
        }
    }

    // ---------------------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("tracing details")
    class Tracing {
        final int c = 4 * BC + 1000;

        @Test
        @DisplayName("a traced move truncates its velocity to clicks like a free one")
        void traced() {
            Simulation s = sim("", RIGHT_WALL);
            Player p = ship(s, 9300, 5600, 0, 1.0078125, 0.0078125);
            s.mover.movePlayer(p);
            Simulation b = sim("", RIGHT_WALL);
            Player q = ship(b, 10112, 5600, 0, 4.0078125, 0.0078125);
            b.mover.movePlayer(q);
            assertAll(
                () -> assertEquals(2, s.world.wallDist(4, 2), "(fixture: traced)"),
                () -> assertEquals(9364, p.cx, "64 of 64.5 clicks"),
                () -> assertEquals(5600, p.cy, "0.5 clicks is nothing"),
                () -> assertEquals(10176, q.cx, "256 of 256.5 clicks: same bounce as at speed 4"));
        }

        @Test
        @DisplayName("coriolis 45 degrees rotates (2,1) to (3/sqrt2, -1/sqrt2)")
        void coriolis45() {
            Simulation s = sim("", OPEN);
            s.world.params.coriolis = 45;
            Player p = ship(s, c, c, 0, 2, 1);
            s.mover.movePlayer(p);
            float c16 = Trig.tcos(16);
            float s16 = Trig.tsin(16);
            assertAll(
                () -> assertEquals((1.0f - 0f) * (2f * c16 + 1f * s16), p.vx, "x = (1-f) * (vx tcos + vy tsin) in float"),
                () -> assertEquals((1.0f - 0f) * (1f * c16 - 2f * s16), p.vy, "y = (1-f) * (vy tcos - vx tsin)"),
                () -> assertEquals(3 * Math.sqrt(0.5), p.vx, 1e-6, "about 3/sqrt2 (float rounding)"));
        }

        @Test
        @DisplayName("a fall straight onto a floor: the lowest hull point leads, bounces, and the ship rises")
        void floor() {
            Simulation s = sim("", "       ", "     _ ", "       ", "xxxxxxx", "       ");
            Player p = ship(s, 7000, 4480 + 512 + 64, 0, 0, -4);
            s.mover.movePlayer(p);
            // 64 down to the floor, bounce (VerHi, normal RES/4, |delta| 32): cost 3*96/128 = 2, (int)(-192*0.5) = 96 up
            assertAll(
                () -> assertEquals(false, killed(p), "survives"),
                () -> assertEquals(4480 + 512 + 96, p.cy, "the centre ends 96 above the contact height"),
                () -> assertEquals(7000, p.cx, "x unchanged"),
                () -> assertEquals(2.0, p.vy, "y velocity reflected and braked"),
                () -> assertEquals(10000.0 - 512, p.fuel, "cost 2 << 8"));
        }

        @Test
        @DisplayName("walldist chunks never jump past a wall: a fast ship is caught at the wall it would cross")
        void chunkWall() {
            String e = "               ";
            String[] rows = new String[15];
            java.util.Arrays.fill(rows, e);
            rows[0] = "_              ";
            rows[13] = "xxxxxxxxxxxxxxx";
            Simulation s = sim("", rows);
            Player p = ship(s, 7 * BC + 1120, 7 * BC + 1120, 0, 0, -200);
            s.mover.movePlayer(p);
            String w9 = "   x     ";
            Simulation x = sim("edgeWrap: yes", "   x  _  ", w9, w9, w9, w9, w9, w9, w9, w9);
            Player q = ship(x, 3360, 10080, 0, 100, 0);
            x.mover.movePlayer(q);
            Simulation y = sim("edgeWrap: yes", E9, E9, E9, "xxxxxxxxx", E9, E9, E9, E9, "_        ");
            Player r = ship(y, 10080, 7840, 32, 0, 100);
            y.mover.movePlayer(r);
            assertAll(
                () -> assertEquals("p smashed against a wall.", lastMessage(s.world), "chunks 10080, 1120, then traced"),
                () -> assertEquals(4480 + 512, p.cy, "the lowest point (2) stopped on the floor face"),
                () -> assertEquals("p smashed head first against a wall.", lastMessage(x.world),
                    "wrap map: the nose reaches the wall column"),
                () -> assertEquals(3 * BC - 960, q.cx, "with the nose on its face"),
                () -> assertEquals("p smashed head first against a wall.", lastMessage(y.world), "the same moving up"),
                () -> assertEquals(5 * BC - 960, r.cy, "with the nose on the face of the wall row"));
        }

        @Test
        @DisplayName("moves longer than two world sizes wrap in every chunk")
        void multiWrap() {
            int[] out = new int[4];
            double[][] vel = {{700, 0}, {-700, 0}, {0, 700}, {0, -700}};
            for (int i = 0; i < 4; i++) {
                Simulation s = sim("edgeWrap: yes", OPEN);
                Player p = ship(s, c, c, 0, vel[i][0], vel[i][1]);
                s.mover.movePlayer(p);
                out[i] = i < 2 ? p.cx : p.cy;
            }
            // 44800 clicks on a 20160-click world: 9960 + 44800 = 54760 = 14440 + 2*20160
            assertAll(
                () -> assertEquals(14440, out[0], "x forward"),
                () -> assertEquals(5480, out[1], "x backward: 9960 - 44800 + 2*20160"),
                () -> assertEquals(14440, out[2], "y forward"),
                () -> assertEquals(5480, out[3], "y backward"));
        }

        @Test
        @DisplayName("a ship outside a non-wrapping world leaves the known universe")
        void universe() {
            Simulation s = sim("", OPEN);
            Player p = ship(s, 20500, 19040, 0, 1, 0);
            s.mover.movePlayer(p);
            assertAll(
                () -> assertTrue(killed(p), "killed"),
                () -> assertEquals("p left the known universe head first.", lastMessage(s.world), "CrashUniverse message"));
        }

        @Test
        @DisplayName("a centre block with walldist 3 (diagonal open corner) is traced, not chunked")
        void wallDistThree() {
            Simulation s = sim("", "      _", "       ", "       ", "       ", "  w    ", "       ", "       ");
            Player p = ship(s, 7840, 7840, 0, 1, 1);
            s.mover.movePlayer(p);
            assertAll(
                () -> assertEquals(3, s.world.wallDist(3, 3), "(fixture: walldist 3)"),
                () -> assertEquals(7904, p.cx, "moves 64 right"),
                () -> assertEquals(7904, p.cy, "moves 64 up"));
        }

        @Test
        @DisplayName("simultaneous bounces: point i replaces the choice iff (int)(rfrac * (n - choice)) == i")
        void simultaneous() {
            String[] floorTarget = {"       ", " _     ", "     ! ", "xxxxxxx", "       "};
            String[] leftPair = {"       ", "     _ ", " !     ", " x     ", "       "};
            for (int seed = 1; seed <= 20; seed++) {
                Simulation s = sim("", floorTarget);
                s.world.params.targetTeamCollision = true;
                s.world.params.maxUnshieldedWallBounceSpeed = 3;
                // nose on the target face, hull point 2 on the floor face, moving down-right
                Player p = ship(s, 11200 - 960, 4480 + 512, 0, 4, -4);
                s.world.rng.seed(seed);
                boolean second = (int) (new Rng(seed).nextFloat() * 3) == 2;
                s.mover.movePlayer(p);
                assertEquals(second ? "p smashed against a wall." : "p smashed head first against a target.",
                    lastMessage(s.world), "seed " + seed + ": point 2 replaces point 0 iff (int)(rfrac*3) == 2");
                Simulation t = sim("", leftPair);
                t.world.params.targetTeamCollision = true;
                t.world.params.maxUnshieldedWallBounceSpeed = 3;
                // hull points 1 (target) and 2 (wall) both on the face x = 4480 moving left
                Player q = ship(t, 4480 + 576, 4480, 0, -4, 0);
                t.world.rng.seed(seed);
                t.mover.movePlayer(q);
                assertEquals("p smashed against a target.", lastMessage(t.world),
                    "seed " + seed + ": (int)(rfrac*2) is never 2, point 1 stays chosen");
            }
        }

        @Test
        @DisplayName("the walldist checks use the hull-derived centre, the previous speed and the true centre block")
        void lookups() {
            Simulation s = sim("", "      _", "       ", "       ", "       ", "  w    ", "       ", "       ");
            Player p = ship(s, 7840, 7840, 0, -40, -40);
            p.velocity = 0;
            s.mover.movePlayer(p);
            String[] floor = {"_        ", E9, E9, E9, E9, E9, E9, "xxxxxxxxx", E9};
            Simulation f = sim("", floor);
            Player q = ship(f, 4 * BC + 1120, 3 * BC + 2200, 32, 0, -100);
            f.mover.movePlayer(q);
            Simulation h = sim("", OPEN);
            Player r = ship(h, c, c, 64, 150, 20);
            r.velocity = 200;
            h.mover.movePlayer(r);
            assertAll(
                () -> assertTrue(killed(p), "walldist 3 is never free flight, even with a stale speed of 0"),
                () -> assertEquals("p smashed against a wall.", lastMessage(f.world),
                    "heading up, falling: chunks sized from the centre (not the nose) stop the rear points on the floor"),
                () -> assertEquals(4480 + 576, q.cy, "rear points (y -576) on the floor face"),
                () -> assertEquals(c + 9600, r.cx, "a horizontal chunk with a y part: x arrives"),
                () -> assertEquals(c + 1280, r.cy, "and y arrives"));
        }

        @Test
        @DisplayName("armor is not used at exactly the limit, nor when the limits are equal")
        void armorBoundaries() {
            Simulation a = sim("", RIGHT_WALL);
            a.world.params.maxUnshieldedWallBounceSpeed = 4;
            a.world.params.maxShieldedWallBounceSpeed = 5;
            Player p = headOn(a);
            p.have = Has.ARMOR;
            p.items[Item.ARMOR.ordinal()] = 3;
            a.mover.movePlayer(p);
            Simulation b = sim("", RIGHT_WALL);
            b.world.params.maxUnshieldedWallBounceSpeed = 3;
            b.world.params.maxShieldedWallBounceSpeed = 3;
            Player q = headOn(b);
            q.have = Has.ARMOR;
            q.items[Item.ARMOR.ordinal()] = 3;
            b.mover.movePlayer(q);
            Simulation c2 = sim("", RIGHT_WALL);
            c2.world.params.maxUnshieldedAngle = 10;
            c2.world.params.maxShieldedAngle = 10;
            Player r = headOn(c2);
            r.have = Has.ARMOR;
            r.items[Item.ARMOR.ordinal()] = 3;
            c2.mover.movePlayer(r);
            Simulation d = sim("", RIGHT_WALL);
            d.world.params.maxUnshieldedAngle = 32;
            d.world.params.maxUnshieldedWallBounceSpeed = 30;
            Player u = ship(d, 11200 - 100 - 511, 5600, 32, 20, 0);
            u.mass = 30;
            u.fuel = 100000;
            u.have = Has.ARMOR;
            u.items[Item.ARMOR.ordinal()] = 3;
            d.mover.movePlayer(u);
            assertAll(
                () -> assertEquals(3, u.items[Item.ARMOR.ordinal()], "|delta| 32 exactly at the angle limit needs no armor"),
                () -> assertEquals(false, killed(u), "and survives"),
                () -> assertEquals(3, p.items[Item.ARMOR.ordinal()], "speed exactly at the limit needs no armor"),
                () -> assertEquals(false, killed(p), "and survives"),
                () -> assertEquals(3, q.items[Item.ARMOR.ordinal()], "equal speed limits: armor cannot help, is not used"),
                () -> assertTrue(killed(q), "and the ship dies"),
                () -> assertEquals(3, r.items[Item.ARMOR.ordinal()], "equal angle limits: armor is not used"),
                () -> assertTrue(killed(r), "and the ship dies"));
        }

        @Test
        @DisplayName("the heading nudge applies up to |delta| = RES/16 = 8 inclusive; floatDir 128 wraps to 0, 0 stays 0")
        void nudgeBoundaries() {
            Simulation a = sim("", RIGHT_WALL);
            // heading 56: rightmost hull point (-9,-8) at +728 clicks; heading 55: +739
            Player p = ship(a, 11200 - 100 - 728, 5600, 56, 4, 0);
            a.mover.movePlayer(p);
            Simulation b = sim("", RIGHT_WALL);
            Player q = ship(b, 11200 - 100 - 739, 5600, 55, 4, 0);
            b.mover.movePlayer(q);
            Simulation c2 = sim("", LEFT_WALL);
            c2.world.params.playerWallBrakeFactor = 0;
            Player r = ship(c2, 4480 + 100 + 600, 5600, 127, -4, 0);
            c2.mover.movePlayer(r);
            Simulation d = sim("", LEFT_WALL);
            d.world.params.playerWallBrakeFactor = 0;
            Player t = ship(d, 4480 + 100 + 600, 5600, 1, -4, 0);
            d.mover.movePlayer(t);
            assertAll(
                () -> assertTrue(p.vx < 0 && q.vx < 0, "(both bounced)"),
                () -> assertEquals(60.0, p.floatDir, 1e-9, "|delta| 8: 56 + 0.5 * 8"),
                () -> assertEquals(55.0, q.floatDir, 1e-9, "|delta| 9: no nudge"),
                () -> assertEquals(0.0, r.floatDir, 1e-9, "127 + 1 = 128 wraps to 0"),
                () -> assertEquals(0.0, t.floatDir, 1e-9, "1 - 1 = 0 stays 0"));
        }
    }
}
