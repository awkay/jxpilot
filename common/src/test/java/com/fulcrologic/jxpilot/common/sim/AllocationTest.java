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
    static final String[] ROWS = {
        "xxxxxxxxxxxx",
        "x    a    sx",
        "x  !    r  x",
        "x     +    x",
        "x  w    q  x",
        "x _     x  x",
        "xxxxxxxxxxxx"
    };

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
    @DisplayName("10k kernel steps with the wire codec allocate zero bytes")
    void kernelStepIsAllocationFree() {
        KernelFx.TestWorld w = new KernelFx.TestWorld(KernelFx.options("gravity: -0.3\nturnThrust: yes", ROWS), 3);
        ShipKernel k = new ShipKernel(w, w::rfrac);
        Events ev = new Events();
        ShipBody b = KernelFx.ship(3 * Click.BLOCK_CLICKS, 2 * Click.BLOCK_CLICKS, 32, 0, 0);
        b.status |= Obj.GRAVITY;
        b.have = Has.SHIELD | Has.SHOT;
        b.fuelMax = 1e7;
        ev.startX = b.cx;
        ev.startY = b.cy;
        ShipBody copy = new ShipBody();
        ByteBuffer buf = ByteBuffer.allocateDirect(ShipBody.WIRE_SIZE);
        Rng rng = new Rng(9);
        long[] mask = new long[1];
        for (int i = 0; i < 10_000; i++) {
            step(k, b, ev, copy, buf, rng, mask, w);
        }
        int touches0 = ev.touches;
        int crashes0 = ev.crashes;
        int shots0 = ev.shots;
        long start = MX.getCurrentThreadAllocatedBytes();
        for (int i = 0; i < 10_000; i++) {
            step(k, b, ev, copy, buf, rng, mask, w);
        }
        long allocated = MX.getCurrentThreadAllocatedBytes() - start;
        int touches = ev.touches - touches0;
        int crashes = ev.crashes - crashes0;
        int shots = ev.shots - shots0;
        assertAll(
            () -> assertEquals(0, allocated, "10k kernel steps allocate no bytes"),
            () -> assertTrue(touches > 0, "the ship touched walls (" + touches + ")"),
            () -> assertTrue(crashes > 0, "and crashed (" + crashes + ")"),
            () -> assertTrue(shots > 0, "and fired (" + shots + ")"),
            () -> assertTrue(copy.sameState(b), "the decoded copy matches the body"));
    }

    private static void step(ShipKernel k, ShipBody b, Events ev, ShipBody copy, ByteBuffer buf, Rng rng, long[] mask,
                             KernelFx.TestWorld w) {
        for (int j = 0; j < KEYS.length; j++) {
            if (rng.nextFloat() < 0.06f) {
                mask[0] ^= KEYS[j];
            }
        }
        k.step(b, mask[0], ev);
        if (b.lastWallTouch == w.frames) {
            ev.touches++;
        }
        b.encode(buf, 0);
        copy.decode(buf, 0);
        w.frames++;
    }
}
