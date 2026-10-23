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

import com.fulcrologic.jxpilot.common.Const;
import com.fulcrologic.jxpilot.common.map.BlockType;
import com.fulcrologic.jxpilot.common.map.Cannon;
import com.fulcrologic.jxpilot.common.math.Rng;
import com.fulcrologic.jxpilot.common.math.XMath;
import com.fulcrologic.jxpilot.server.TestWorlds;
import com.fulcrologic.jxpilot.server.world.Click;
import com.fulcrologic.jxpilot.server.world.Has;
import com.fulcrologic.jxpilot.server.world.Obj;
import com.fulcrologic.jxpilot.server.world.Objects;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static com.fulcrologic.jxpilot.server.sim.SimFixture.FPS14;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.FRAME;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.ready;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Cannons (cannon.c, update.c)")
class CannonsTest {
    static final double EPS = 1e-9;
    /** Cannon 'r' (facing up, dir 32) at block (20, 19): pixel (717.5, 676.655), C (int) cpx 717, cpy 676. */
    static final int CX = 717;
    static final int CY = 676;

    static Simulation sim(String extra, String bases) {
        String[] rows = SimFixture.open(40, 40, bases);
        char[] r = rows[20].toCharArray();
        r[20] = 'r';
        rows[20] = new String(r);
        return TestWorlds.sim(extra, rows);
    }

    @Nested
    @DisplayName("aim (Cannon_aim, CW_SHOT)")
    class Aim {
        Simulation s;
        World w;
        Cannons c;

        Player setup(String extra, double dx, double dy) {
            s = sim(FRAME + extra, "__");
            w = s.world;
            c = s.cannons;
            return ready(s, "P", 0, CX + dx, CY + dy);
        }

        @Test
        @DisplayName("the cannon is where the map puts it")
        void cannonPlacement() {
            setup("", 0, 0);
            Cannon cn = w.map.cannons().get(0);
            assertAll(
                () -> assertEquals(32, cn.dir(), "an 'r' cannon faces up"),
                () -> assertEquals(CX, (int) cn.x(), "pixel x"),
                () -> assertEquals(CY, (int) cn.y(), "pixel y"));
        }

        @Test
        @DisplayName("smartness 0 fires straight ahead at anyone in visual range (500 px)")
        void dumb() {
            setup("cannonSmartness: 0\n", 150, -200);
            w.rng.seed(1);
            boolean seen = c.aim(0);
            int dir = c.aimDir;
            int target = c.target;
            int next = w.rng.nextInt();
            setup("cannonSmartness: 0\n", 0, 499);
            boolean edge = c.aim(0);
            setup("cannonSmartness: 0\n", 0, 500);
            boolean outY = c.aim(0);
            setup("cannonSmartness: 0\n", -500, 0);
            boolean outX = c.aim(0);
            setup("cannonSmartness: 0\n", 360, 360);
            boolean outDiag = c.aim(0);
            int noTarget = c.target;
            setup("cannonSmartness: 0\n", 300, 400);
            boolean exact500 = c.aim(0);
            assertAll(
                () -> assertTrue(seen, "a ship 250 px away is seen"),
                () -> assertEquals(32, dir, "fires along the cannon's own direction"),
                () -> assertEquals(0, target, "targets the player"),
                () -> assertEquals(new Rng(1).nextInt(), next, "draws no random numbers"),
                () -> assertTrue(edge, "499 px straight up is in range"),
                () -> assertFalse(outY, "|dy| = 500 is out of range"),
                () -> assertFalse(outX, "|dx| = 500 is out of range"),
                () -> assertEquals(-1, noTarget, "no target"),
                () -> assertFalse(outDiag, "(360, 360) is 509 px away: out of range"),
                () -> assertTrue(exact500, "(300, 400) is exactly 500 px away: still seen"));
        }

        @Test
        @DisplayName("dead, paused or game-over ships and own-team ships are not targets")
        void candidates() {
            Player p = setup("cannonSmartness: 0\n", 0, 200);
            p.status |= Obj.KILLED;
            boolean dead = c.aim(0);
            p.status = Obj.PLAYING | Obj.PAUSE;
            boolean paused = c.aim(0);
            p.status = Obj.PLAYING | Obj.GAME_OVER;
            boolean over = c.aim(0);
            p.status = 0;
            boolean notPlaying = c.aim(0);
            s = sim(FRAME + "cannonSmartness: 0\nteamPlay: yes\nreserveRobotTeam: no\nteamCannons: yes\n", "01");
            w = s.world;
            int cteam = w.map.cannons().get(0).team();
            Player mate = ready(s, "M", cteam, CX, CY + 100);
            boolean own = s.cannons.aim(0);
            Player foe = ready(s, "F", 1 - cteam, CX, CY + 300);
            boolean other = s.cannons.aim(0);
            int target = s.cannons.target;
            assertAll(
                () -> assertFalse(dead, "a killed ship is ignored"),
                () -> assertFalse(paused, "a paused ship is ignored"),
                () -> assertFalse(over, "a game-over ship is ignored"),
                () -> assertFalse(notPlaying, "a ship that is not playing is ignored"),
                () -> assertTrue(cteam == 0 || cteam == 1, "the cannon belongs to a team"),
                () -> assertFalse(own, "a ship of the cannon's team is ignored"),
                () -> assertTrue(other, "a ship of another team is a target"),
                () -> assertEquals(1, target, "the other-team ship is the target"));
        }

        @Test
        @DisplayName("smartness 1 fires ahead with a random spread of +-RES/6 at ships within 32 * shotSpeed")
        void random() {
            setup("cannonSmartness: 1\n", 100, 300);
            w.rng.seed(7);
            Rng ref = new Rng(7);
            int expected = (32 + (int) ((ref.nextFloat() - 0.5f) * 42)) & 127;
            boolean fired = c.aim(0);
            setup("cannonSmartness: 1\nshotSpeed: 10\n", 0, 320);
            boolean outOfRange = c.aim(0);
            setup("cannonSmartness: 1\nshotSpeed: 10\n", 0, 319);
            boolean inRange = c.aim(0);
            setup("cannonSmartness: 5\n", 100, 300);
            w.rng.seed(7);
            boolean five = c.aim(0);
            int fiveDir = c.aimDir;
            assertAll(
                () -> assertTrue(fired, "a ship in range is targeted"),
                () -> assertEquals(expected, fiveDir, "smartness above 3 behaves like 1"),
                () -> assertTrue(five, "smartness 5 fires"),
                () -> assertFalse(outOfRange, "320 px is not within 32 * 10 (strict)"),
                () -> assertTrue(inRange, "319 px is within 32 * 10"));
            setup("cannonSmartness: 1\n", 100, 300);
            w.rng.seed(7);
            c.aim(0);
            assertEquals(expected, c.aimDir, "dir = cannon dir + (int)((rfrac - 0.5f) * CANNON_SPREAD)");
        }

        @Test
        @DisplayName("smartness 1 spread covers -21..+20 around the cannon direction")
        void randomSpread() {
            setup("cannonSmartness: 1\n", 0, 300);
            int lo = 999;
            int hi = -999;
            for (int i = 0; i < 4000; i++) {
                c.aim(0);
                int d = ((c.aimDir - 32 + 64) & 127) - 64;
                lo = Math.min(lo, d);
                hi = Math.max(hi, d);
            }
            int fl = lo;
            int fh = hi;
            assertAll(
                () -> assertEquals(-21, fl, "the smallest offset is (int)(-0.5 * 42) = -21 (rfrac == 0)"),
                () -> assertEquals(20, fh, "the largest offset is (int)(0.4999 * 42) = 20"));
        }

        @Test
        @DisplayName("smartness 2 aims at the ship, clamped to the sector +-(RES/6 + 3), plus noise -3..3")
        void aimed() {
            Player p = setup("cannonSmartness: 2\n", 100, 100);
            w.rng.seed(8);
            Rng ref = new Rng(8);
            int noise = (int) (ref.nextFloat() * 7) - 3;
            boolean fired = c.aim(0);
            int inside = c.aimDir;
            setup("cannonSmartness: 2\n", 200, 10);
            w.rng.seed(8);
            c.aim(0);
            int right = c.aimDir;
            setup("cannonSmartness: 2\n", -200, 10);
            w.rng.seed(8);
            c.aim(0);
            int left = c.aimDir;
            // (100, 100) -> findDir 16, ddir = 112: inside the sector. (200, 10) -> 2, ddir 98: clamped to
            // (int)(32 - 21 - 3) = 8. (-200, 10) -> 63, ddir 31: clamped to (int)(32 + 21 + 3) = 56.
            assertAll(
                () -> assertTrue(fired, "fires at a ship in range"),
                () -> assertEquals(1, noise, "seed 8 gives noise (int)(0.658 * 7) - 3 = 1"),
                () -> assertEquals(16, (int) XMath.findDir(100, 100), "the ship is at heading 16"),
                () -> assertEquals((16 + noise) & 127, inside, "inside the sector: the ship's heading plus noise"),
                () -> assertEquals((8 + noise) & 127, right, "beyond the right edge: dir - 24 plus noise"),
                () -> assertEquals((56 + noise) & 127, left, "beyond the left edge: dir + 24 plus noise"));
        }

        @Test
        @DisplayName("smartness 2: the closest ship sets the direction; a later candidate still becomes the target (C)")
        void closest() {
            s = sim(FRAME + "cannonSmartness: 2\n", "__");
            w = s.world;
            Player near = ready(s, "N", 0, CX + 100, CY + 100);
            Player far = ready(s, "F", 0, CX - 50, CY + 300);
            w.rng.seed(6);
            Rng ref = new Rng(6);
            int noise = (int) (ref.nextFloat() * 7) - 3;
            s.cannons.aim(0);
            int target1 = s.cannons.target;
            int dir1 = s.cannons.aimDir;
            s = sim(FRAME + "cannonSmartness: 2\n", "__");
            w = s.world;
            Player far2 = ready(s, "F", 0, CX - 50, CY + 300);
            Player near2 = ready(s, "N", 0, CX + 100, CY + 100);
            w.rng.seed(6);
            s.cannons.aim(0);
            int dirFar = (int) XMath.findDir(-50, 300);
            assertAll(
                () -> assertEquals(1, target1, "C quirk: once found, every later ship in sight overwrites the target"),
                () -> assertEquals((16 + noise) & 127, dir1, "but only a closer ship changes the direction"),
                () -> assertEquals(1, s.cannons.target, "a closer later ship is the target"),
                () -> assertEquals((16 + noise) & 127, s.cannons.aimDir, "and sets the direction"),
                () -> assertTrue(dirFar != 16, "the far ship is in another direction"));
        }

        @Test
        @DisplayName("smartness 3 leads the target with a whole-frame flight time and fires only inside the sector")
        void leading() {
            Player p = setup("cannonSmartness: 3\n", 0, 200);
            p.vx = 10;
            p.accX = 0.5f;
            boolean fired = c.aim(0);
            int dir = c.aimDir;
            p.vy = -4;
            p.accY = 1;
            c.aimDir = -1;
            c.aim(0);
            // npy = (int)(876 - 4*9 + 1*81) = 921
            int dirY = c.aimDir;
            int expectedY = (int) XMath.findDir(847 - CX, 921 - CY);
            // time = tdist / speed = 200 / 21 = 9 (C int division); npx = (int)(717 + 10*9 + 0.5*81) = 847
            int expected = (int) XMath.findDir(847 - CX, 200);
            double tf = 200 / 21.0;
            int fractional = (int) XMath.findDir((int) (CX + 10 * tf + 0.5 * tf * tf) - CX, 200);
            Player q = setup("cannonSmartness: 3\n", 200, 10);
            boolean outside = c.aim(0);
            Player r = setup("cannonSmartness: 3\n", 0, 200);
            r.used |= Has.PHASING_DEVICE;
            boolean phased = c.aim(0);
            Player t = setup("cannonSmartness: 2\n", 0, 200);
            t.used |= Has.PHASING_DEVICE;
            boolean phased2 = c.aim(0);
            assertAll(
                () -> assertTrue(fired, "fires at a predicted position inside the sector"),
                () -> assertEquals(expected, dir, "aims at the position after 9 whole frames"),
                () -> assertEquals(20, expected, "which is heading 20"),
                () -> assertEquals(19, fractional, "a fractional 9.52-frame lead would give 19"),
                () -> assertEquals(expectedY, dirY, "the y lead uses vy * t + accY * t^2 too"),
                () -> assertEquals(22, expectedY, "heading 22 for (130, 245)"),
                () -> assertFalse(outside, "a ship outside the sector is not fired at"),
                () -> assertFalse(phased, "smartness 3 ignores phased ships"),
                () -> assertTrue(phased2, "smartness 2 still shoots at phased ships"));
        }
    }

    /** Offset 300 px from the cannon at the middle of RES heading {@code h}. */
    static int[] at(int h) {
        double a = (h + 0.5) * 2 * Math.PI / Const.RES;
        return new int[] {(int) Math.round(300 * Math.cos(a)), (int) Math.round(300 * Math.sin(a))};
    }

    @Nested
    @DisplayName("aim at sector edges")
    class Edges {
        int aimed(String extra, int heading) {
            Simulation s = sim(FRAME + extra, "_");
            int[] d = at(heading);
            assertEquals(heading, (int) XMath.findDir(d[0], d[1]), "the ship sits at heading " + heading);
            ready(s, "P", 0, CX + d[0], CY + d[1]);
            s.world.rng.seed(6);
            return s.cannons.aim(0) ? s.cannons.aimDir : -1;
        }

        @Test
        @DisplayName("smartness 2 clamps headings beyond +-21 of the cannon to +-24, except straight behind (C)")
        void clamp2() {
            String two = "cannonSmartness: 2\n";
            assertAll(
                () -> assertEquals(53, aimed(two, 53), "ddir 21 is not clamped"),
                () -> assertEquals(56, aimed(two, 54), "ddir 22 is clamped to dir + 24"),
                () -> assertEquals(11, aimed(two, 11), "ddir 107 is not clamped"),
                () -> assertEquals(8, aimed(two, 10), "ddir 106 is clamped to dir - 24"),
                () -> assertEquals(96, aimed(two, 96), "ddir 64 (straight behind) is not clamped"),
                () -> assertEquals(56, aimed(two, 63), "ddir 31 is clamped to dir + 24"),
                () -> assertEquals(8, aimed(two, 97), "ddir 65 is clamped to dir - 24"));
        }

        @Test
        @DisplayName("smartness 3 fires only when the predicted heading is within 20 of the cannon's")
        void sector3() {
            String three = "cannonSmartness: 3\n";
            assertAll(
                () -> assertEquals(-1, aimed(three, 53), "ddir 21 is outside"),
                () -> assertEquals(52, aimed(three, 52), "ddir 20 is inside"),
                () -> assertEquals(-1, aimed(three, 11), "ddir 107 is outside"),
                () -> assertEquals(12, aimed(three, 12), "ddir 108 is inside"));
        }

        @Test
        @DisplayName("ties: a ship at the same distance as the current choice does not change the direction")
        void ties() {
            for (String smart : new String[] {"cannonSmartness: 2\n", "cannonSmartness: 3\n"}) {
                Simulation s = sim(FRAME + smart, "__");
                ready(s, "A", 0, CX + 120, CY + 160);
                ready(s, "B", 0, CX - 120, CY + 160);
                s.world.rng.seed(6);
                s.cannons.aim(0);
                assertAll(
                    () -> assertEquals(18, (int) XMath.findDir(120, 160), "the first ship is at heading 18"),
                    () -> assertEquals(45, (int) XMath.findDir(-120, 160), "the second, equally far, at 45"),
                    () -> assertEquals(18, s.cannons.aimDir, smart.trim() + ": aims at the first of two equal ships"));
            }
        }

        @Test
        @DisplayName("smartness 3 range: 32 * shotSpeed, strict")
        void range3() {
            Simulation s = sim(FRAME + "cannonSmartness: 3\nshotSpeed: 10\n", "_");
            Player p = ready(s, "P", 0, CX, CY + 320);
            boolean at = s.cannons.aim(0);
            p.vy = -5;
            boolean approaching = s.cannons.aim(0);
            p.vy = 0;
            p.initPixels(CX, CY + 319);
            boolean inside = s.cannons.aim(0);
            assertAll(
                () -> assertFalse(at, "320 px is not within 32 * 10"),
                () -> assertFalse(approaching, "even if the predicted position (160 px) would be"),
                () -> assertTrue(inside, "319 px is"));
        }
    }

    @Nested
    @DisplayName("update and fire")
    class Update {
        @Test
        @DisplayName("a dead cannon counts down by dt, draws no random numbers and comes back at zero")
        void deadCountdown() {
            Simulation s = sim(FRAME, "_");
            World w = s.world;
            Cannon cn = w.map.cannons().get(0);
            w.cannonDeadTime[0] = 3;
            w.map.setBlock(cn.bx(), cn.by(), BlockType.SPACE);
            w.rng.seed(2);
            s.cannons.update();
            s.cannons.update();
            byte mid = w.map.block(cn.bx(), cn.by());
            double left = w.cannonDeadTime[0];
            int next = w.rng.nextInt();
            s.cannons.update();
            Simulation s14 = sim(FPS14, "_");
            World w14 = s14.world;
            w14.cannonDeadTime[0] = 1;
            w14.map.setBlock(cn.bx(), cn.by(), BlockType.SPACE);
            s14.cannons.update();
            assertAll(
                () -> assertEquals(BlockType.SPACE, mid, "still dead after 2 frames"),
                () -> assertEquals(1.0, left, EPS, "one frame left"),
                () -> assertEquals(new Rng(2).nextInt(), next, "dead cannons draw no random numbers"),
                () -> assertEquals(BlockType.CANNON, w.map.block(cn.bx(), cn.by()), "back after 3 frames"),
                () -> assertEquals(0.0, w.cannonDeadTime[0], EPS, "alive again"),
                () -> assertEquals(BlockType.CANNON, w14.map.block(cn.bx(), cn.by()), "at 14 fps one tick is one frame"),
                () -> assertEquals(0.0, w14.cannonDeadTime[0], EPS, "the countdown stops at 0"));
        }

        @Test
        @DisplayName("a live cannon (dead time 0) draws its 1/16 chance every frame")
        void liveDraws() {
            Simulation s = sim(FRAME, "_");
            World w = s.world;
            w.rng.seed(2);
            s.cannons.update();
            int next = w.rng.nextInt();
            Rng r = new Rng(2);
            r.nextFloat();
            assertAll(
                () -> assertEquals(r.nextInt(), next, "one draw this frame"),
                () -> assertEquals(0.0, w.cannonDeadTime[0], EPS, "and stays alive (0)"));
        }

        int fires(String extra, int ticks) {
            Simulation s = sim(extra + "cannonSmartness: 0\n", "_");
            World w = s.world;
            ready(s, "P", 0, CX, CY + 200);
            int n = 0;
            for (int i = 0; i < ticks; i++) {
                s.cannons.update();
                n += w.objects.count();
                w.objects.clear();
            }
            return n;
        }

        @Test
        @DisplayName("a live cannon checks with probability dt/16 per tick: the C rate per original frame")
        void fireRate() {
            int n60 = fires(FRAME, 16000);
            int n14 = fires(FPS14, 16000);
            // binomial: 1000 +- 31 at any fps (one check chance of 1/16 per frame = tick); 4 sigma bounds
            assertAll(
                () -> assertTrue(Math.abs(n60 - 1000) < 125, "about 16000/16 checks at 60 fps, got " + n60),
                () -> assertTrue(Math.abs(n14 - 1000) < 125, "and at 14 fps, got " + n14));
        }

        @Test
        @DisplayName("without anyone in sight a check fires nothing")
        void noTarget() {
            assertEquals(0, firesAlone(), "no shots without a target");
        }

        int firesAlone() {
            Simulation s = sim(FRAME + "cannonSmartness: 0\n", "_");
            for (int i = 0; i < 200; i++) {
                s.cannons.update();
            }
            return s.world.objects.count();
        }

        @Test
        @DisplayName("fire: one cannon shot from the cannon's pixel position along dir, flak with cannonFlak")
        void fire() {
            Simulation s = sim(FRAME + "cannonFlak: no\n", "_");
            World w = s.world;
            s.cannons.fire(0, 40);
            Objects o = w.objects;
            int h = o.active[0];
            Simulation f = sim(FRAME, "_");
            f.cannons.fire(0, 40);
            int hf = f.world.objects.active[0];
            assertAll(
                () -> assertEquals(1, o.count(), "one shot"),
                () -> assertEquals(Obj.CANNON_SHOT, o.type[h], "a cannon shot"),
                () -> assertEquals(Click.fromPixels(CX), o.cx[h], "from (int) cannon x"),
                () -> assertEquals(Click.fromPixels(CY), o.cy[h], "from (int) cannon y"),
                () -> assertEquals(40, o.missileDir[h], "along the aimed dir"),
                () -> assertEquals(w.map.cannons().get(0).team(), o.team[h], "of the cannon's team"),
                () -> assertTrue((o.status[h] & Obj.FROMCANNON) != 0, "FROMCANNON"),
                () -> assertEquals(0, o.mods[h], "plain without cannonFlak"),
                () -> assertEquals(Obj.MOD_CLUSTER, f.world.objects.mods[hf], "cluster with cannonFlak"));
        }

        @Test
        @DisplayName("checkFire fires iff aim finds a target")
        void checkFire() {
            Simulation s = sim(FRAME + "cannonSmartness: 0\n", "_");
            s.cannons.checkFire(0);
            int none = s.world.objects.count();
            ready(s, "P", 0, CX, CY + 200);
            s.cannons.checkFire(0);
            assertAll(
                () -> assertEquals(0, none, "no target, no shot"),
                () -> assertEquals(1, s.world.objects.count(), "a target, one shot"),
                () -> assertEquals(Const.RES / 4, s.world.objects.missileDir[s.world.objects.active[0]],
                    "along the aimed dir"));
        }
    }
}
