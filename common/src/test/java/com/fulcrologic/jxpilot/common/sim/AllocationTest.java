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

import com.fulcrologic.jxpilot.common.math.Rng;
import com.fulcrologic.jxpilot.common.net.ControlKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ship kernel allocates nothing per step (SPEC GC rules): 10k measured steps of a ship flying random keys
 * through walls, diagonals, a cannon, a target and gravity, with the prediction state encoded and decoded every
 * step. Runs in the allocation-test execution (escape analysis off).
 */
@DisplayName("The ship kernel is allocation free")
class AllocationTest {
    private static final com.sun.management.ThreadMXBean MX =
        (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static final long[] KEYS = {
        ControlKey.THRUST.mask(), ControlKey.TURN_LEFT.mask(), ControlKey.TURN_RIGHT.mask(),
        ControlKey.SHIELD.mask(), ControlKey.FIRE_SHOT.mask(), ControlKey.SWAP_SETTINGS.mask(),
        ControlKey.INCREASE_POWER.mask(), ControlKey.DECREASE_POWER.mask(), ControlKey.CLOAK.mask()
    };
    /** 16x10: walls, diagonals, a cannon, a target and a gravity source around an open middle. */
    static final String[] ROWS = {
        "xxxxxxxxxxxxxxxx",
        "x      a    s  x",
        "x  !        r  x",
        "x       +      x",
        "x              x",
        "x    _         x",
        "x  w        q  x",
        "x       xx     x",
        "x              x",
        "xxxxxxxxxxxxxxxx"
    };
    /** The same open middle with no outer walls on a wrapping map. */
    static final String[] WRAP_ROWS = {
        "                ",
        "       a    s   ",
        "   !        r   ",
        "        +       ",
        "                ",
        "     _          ",
        "   w        q   ",
        "        xx      ",
        "                ",
        "                "
    };
    static final int RING = 8;

    /**
     * A per-(frame, call) tie-break source (as predictableBounces would draw): the same frame gives the same draws, so
     * a replay of a frame decides corner bounces as the original did.
     */
    static final class TieBreak implements java.util.function.DoubleSupplier {
        final double[] table = new double[1024];
        int base;
        int n;

        TieBreak() {
            Rng r = new Rng(77);
            for (int i = 0; i < table.length; i++) {
                table[i] = r.nextFloat();
            }
        }

        void frame(long f) {
            base = (int) (f * 16);
            n = 0;
        }

        @Override
        public double getAsDouble() {
            return table[(base + n++) & (table.length - 1)];
        }
    }

    /** One run's counters. */
    static final class Counts {
        long allocated;
        int bounces;
        int touches;
        int crashes;
        int shots;
        int wraps;
        int blocks;
        int replays;
        int mismatches;
    }

    /** Client-style effects: no-ops, except that a crash puts the ship back on its start, at rest. */
    static final class Events implements KernelEvents {
        int crashes;
        int bounces;
        int touches;
        int shots;
        int startX;
        int startY;

        @Override
        public boolean keyPressed(ShipBody b, ControlKey k) {
            return false;
        }

        @Override
        public void keyReleased(ShipBody b, ControlKey k) {
        }

        @Override
        public void autopilotOff(ShipBody b) {
        }

        @Override
        public void powerChanged(ShipBody b) {
        }

        @Override
        public boolean shotRoom() {
            return true;
        }

        @Override
        public int shot(ShipBody b, float x, float y, int dir, float vx, float vy, boolean outside) {
            shots++;
            b.shots = 0;
            return -1;
        }

        @Override
        public void devices(ShipBody b) {
        }

        @Override
        public void upkeep(ShipBody b) {
            b.fuel = b.fuelMax / 2;
        }

        @Override
        public boolean warp(ShipBody b) {
            return false;
        }

        @Override
        public void thrustSparks(ShipBody b) {
        }

        @Override
        public void turnSparks(ShipBody b, int count) {
        }

        @Override
        public void sensors(ShipBody b) {
        }

        @Override
        public void crashed(ShipBody b, MoveState m, int point) {
            crashes++;
            b.setClicks(startX, startY);
            b.vx = 0;
            b.vy = 0;
            b.velocity = 0;
        }

        @Override
        public void wallDamage(ShipBody b) {
        }

        @Override
        public void bounced(ShipBody b, int wallDir, long cost, int target) {
            bounces++;
        }
    }

    @Test
    @DisplayName("10k kernel steps with the codec, the predictor's copy, replay and compare allocate zero bytes")
    void kernelStepIsAllocationFree() {
        Counts c = run("gravity: -0.3\nturnThrust: yes\nmaxUnshieldedWallBounceSpeed: 60\n"
            + "maxUnshieldedPlayerWallBounceAngle: 180\nplayerWallBounceBrakeFactor: 0.9", ROWS);
        assertAll(
            () -> assertEquals(0, c.allocated, "10k kernel steps allocate no bytes"),
            () -> assertTrue(c.bounces > 0, "the ship bounced off walls with a cost (" + c.bounces + ")"),
            () -> assertTrue(c.touches > 50, "and touched walls often (" + c.touches + ")"),
            () -> assertTrue(c.blocks >= 25, "it flew through many blocks (" + c.blocks + " of 160)"),
            () -> assertTrue(c.crashes < 1000, "crashes were rare, so it was flying (" + c.crashes + " in 10k)"),
            () -> assertTrue(c.shots > 0, "and fired (" + c.shots + ")"),
            () -> assertTrue(c.replays > 1000, "the predictor's replays ran (" + c.replays + ")"),
            () -> assertEquals(0, c.mismatches, "and every replay matched the live body"));
    }

    @Test
    @DisplayName("on a wrapping map the measured steps wrap the ship and still allocate zero bytes")
    void wrapIsAllocationFree() {
        Counts c = run("edgeWrap: yes\ngravity: -0.3\nmaxUnshieldedWallBounceSpeed: 60\n"
            + "maxUnshieldedPlayerWallBounceAngle: 180", WRAP_ROWS);
        assertAll(
            () -> assertEquals(0, c.allocated, "10k kernel steps allocate no bytes"),
            () -> assertTrue(c.wraps > 0, "the ship wrapped round an edge (" + c.wraps + ")"),
            () -> assertTrue(c.crashes < 1000, "crashes were rare (" + c.crashes + " in 10k)"),
            () -> assertEquals(0, c.mismatches, "every replay matched the live body"));
    }

    /**
     * 10k warm-up then 10k measured steps of a ship flying random keys from the open middle, as a predictor would
     * run them: each step is encoded and decoded, kept in a ring of RING bodies with its key mask and frame, and every
     * RING steps the oldest is re-seeded and the following steps replayed and compared with the live body.
     */
    static Counts run(String options, String[] rows) {
        KernelFx.TestWorld w = new KernelFx.TestWorld(KernelFx.options(options, rows), 3);
        TieBreak tb = new TieBreak();
        ShipKernel k = new ShipKernel(w, tb);
        Events ev = new Events();
        ShipBody b = KernelFx.ship(5 * Click.BLOCK_CLICKS + 1120, 5 * Click.BLOCK_CLICKS + 1120, 32, 0, 0);
        b.status |= Obj.GRAVITY;
        b.have = Has.SHIELD | Has.SHOT;
        b.fuelMax = 1e7;
        ev.startX = b.cx;
        ev.startY = b.cy;
        Events rev = new Events();
        rev.startX = b.cx;
        rev.startY = b.cy;
        ShipBody copy = new ShipBody();
        ShipBody replay = new ShipBody();
        ShipBody[] ring = new ShipBody[RING];
        long[] ringMask = new long[RING];
        long[] ringFrame = new long[RING];
        for (int i = 0; i < RING; i++) {
            ring[i] = new ShipBody();
        }
        boolean[] visited = new boolean[w.width() * w.height()];
        ByteBuffer buf = ByteBuffer.allocateDirect(ShipBody.WIRE_SIZE);
        Rng rng = new Rng(9);
        long[] mask = new long[1];
        Counts c = new Counts();
        for (int i = 0; i < 10_000; i++) {
            step(k, tb, b, ev, rev, copy, replay, ring, ringMask, ringFrame, buf, rng, mask, w, visited, c, i);
        }
        Counts warm = new Counts();
        copyCounts(ev, c, warm);
        java.util.Arrays.fill(visited, false);
        long start = MX.getCurrentThreadAllocatedBytes();
        for (int i = 0; i < 10_000; i++) {
            step(k, tb, b, ev, rev, copy, replay, ring, ringMask, ringFrame, buf, rng, mask, w, visited, c, i);
        }
        c.allocated = MX.getCurrentThreadAllocatedBytes() - start;
        c.bounces = ev.bounces - warm.bounces;
        c.touches = ev.touches - warm.touches;
        c.crashes = ev.crashes - warm.crashes;
        c.shots = ev.shots - warm.shots;
        c.wraps -= warm.wraps;
        c.replays -= warm.replays;
        c.mismatches -= warm.mismatches;
        for (boolean v : visited) {
            c.blocks += v ? 1 : 0;
        }
        System.out.printf("KERNEL-ALLOC bytes %d bounces %d touches %d crashes %d shots %d wraps %d blocks %d replays %d"
            + " mismatches %d%n", c.allocated, c.bounces, c.touches, c.crashes, c.shots, c.wraps, c.blocks, c.replays,
            c.mismatches);
        return c;
    }

    private static void copyCounts(Events ev, Counts c, Counts into) {
        into.bounces = ev.bounces;
        into.touches = ev.touches;
        into.crashes = ev.crashes;
        into.shots = ev.shots;
        into.wraps = c.wraps;
        into.replays = c.replays;
        into.mismatches = c.mismatches;
    }

    private static void step(ShipKernel k, TieBreak tb, ShipBody b, Events ev, Events rev, ShipBody copy, ShipBody replay,
                             ShipBody[] ring, long[] ringMask, long[] ringFrame, ByteBuffer buf, Rng rng, long[] mask,
                             KernelFx.TestWorld w, boolean[] visited, Counts c, int i) {
        for (int j = 0; j < KEYS.length; j++) {
            if (rng.nextFloat() < (j < 3 ? 0.08f : 0.03f)) {
                mask[0] ^= KEYS[j];
            }
        }
        int slot = i % RING;
        ring[slot].copyFrom(b);
        ringMask[slot] = mask[0];
        ringFrame[slot] = w.frames;
        int x0 = b.cx;
        int y0 = b.cy;
        tb.frame(w.frames);
        k.step(b, mask[0], ev);
        if (b.lastWallTouch == w.frames) {
            ev.touches++;
        }
        if (Math.abs(b.cx - x0) > w.clickWidth() / 2 || Math.abs(b.cy - y0) > w.clickHeight() / 2) {
            c.wraps++;
        }
        if (b.bx >= 0 && b.bx < w.width() && b.by >= 0 && b.by < w.height()) {
            visited[b.by * w.width() + b.bx] = true;
        }
        b.encode(buf, 0, w.frames);
        copy.decode(buf, 0, w.frames);
        w.frames++;
        if (slot == RING - 1) {
            // the predictor: re-seed from the oldest kept state and replay the kept frames; the result must match
            long now = w.frames;
            replay.copyFrom(ring[0]);
            for (int r = 0; r < RING; r++) {
                w.frames = ringFrame[r];
                tb.frame(w.frames);
                k.step(replay, ringMask[r], rev);
                w.frames = ringFrame[r] + 1;
            }
            w.frames = now;
            c.replays++;
            if (!replay.sameState(b, w.frames)) {
                c.mismatches++;
            }
        }
    }
}
