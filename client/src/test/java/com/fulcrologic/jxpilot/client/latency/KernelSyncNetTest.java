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
package com.fulcrologic.jxpilot.client.latency;

import com.fulcrologic.jxpilot.client.net.KernelSync;
import com.fulcrologic.jxpilot.common.map.BlockType;
import com.fulcrologic.jxpilot.common.sim.Has;
import com.fulcrologic.jxpilot.common.sim.KernelFingerprint;
import com.fulcrologic.jxpilot.server.world.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The kernel world through the real net path (KERNEL_CONFIG, KERNEL_CHUNK and KERNEL_STATE over the emulated
 * link into {@link KernelSync}) on war.xp (cannons, targets, wormholes, gravity, with gravityVisible and
 * wormholeVisible off so the client's map hides them): after the join the client's world has the server's
 * {@link KernelFingerprint}, and again after a cannon is destroyed and phased and a target changes team.
 */
@DisplayName("Kernel world sync over the net")
class KernelSyncNetTest {
    static final long SEC = LatencyRig.SEC;

    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    @DisplayName("the client's kernel world is the server's after the join and after run-time changes")
    void sync() {
        Scenario s = new Scenario(100, Scenario.Jitter.NORMAL_15, Scenario.Loss.UNIFORM_1);
        LatencyRig rig = new LatencyRig(new String[] {"--map", "../maps/war.xp", "+gravityVisible",
            "+wormholeVisible", "-maxRobots", "0", "-minRobots", "0"}, s.direction(), s.direction(), 9,
            InputToPhoton.tickPhase(9));
        long spawn = rig.runUntilPlaying(LatencyRig.START + 30 * SEC);
        rig.runUntil(spawn + 2 * SEC);
        World w = rig.server.world();
        KernelSync k = rig.client.connection().kernel();
        String joined = k.world() == null ? "no world" : KernelFingerprint.of(k.world());
        String serverJoined = KernelFingerprint.of(w);
        long rebuilds = k.rebuilds();
        int hidden = 0;
        com.fulcrologic.jxpilot.common.map.WorldMap shown = rig.client.game().map();
        for (int by = 0; by < shown.height(); by++) {
            for (int bx = 0; bx < shown.width(); bx++) {
                hidden += shown.block(bx, by) != k.world().map().block(bx, by) ? 1 : 0;
            }
        }
        int hiddenBlocks = hidden;
        int cx = w.map.cannons().get(0).bx();
        int cy = w.map.cannons().get(0).by();
        w.map.setBlock(cx, cy, BlockType.SPACE);
        w.cannonItems.used[1] |= Has.PHASING_DEVICE;
        w.modes.targetTeam[0] = (w.modes.targetTeam[0] + 1) % 4;
        rig.runUntil(spawn + 3 * SEC);
        String changed = KernelFingerprint.of(k.world());
        String serverChanged = KernelFingerprint.of(w);
        assertAll(
            () -> assertNotNull(k.world(), "the kernel config arrived"),
            () -> assertTrue(hiddenBlocks > 0, "the game's map hides blocks the kernel map has (" + hiddenBlocks + ")"),
            () -> assertEquals(serverJoined, joined, "after the join the client's kernel world is the server's"),
            () -> assertEquals(1, rebuilds, "built once"),
            () -> assertTrue(!serverChanged.equals(serverJoined), "the changes changed the server's kernel world"),
            () -> assertEquals(serverChanged, changed, "and KERNEL_STATE carried them"),
            () -> assertEquals(0, k.badKernel(), "nothing malformed"));
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    @DisplayName("a lost KERNEL_STATE: while its retransmit is due, newer PREDICTs are not valid (the state gate); once"
        + " it arrives the world has the change and PREDICT is valid again; no frame trusts a stale world")
    void lostState() {
        Scenario s = new Scenario(100, Scenario.Jitter.NONE, Scenario.Loss.NONE);
        LatencyRig rig = new LatencyRig(new String[] {"--map", "../maps/war.xp", "-maxRobots", "0", "-minRobots", "0"},
            s.direction(), s.direction(), 9, InputToPhoton.tickPhase(9));
        long spawn = rig.runUntilPlaying(LatencyRig.START + 30 * SEC);
        rig.runUntil(spawn + 2 * SEC);
        World w = rig.server.world();
        KernelSync k = rig.client.connection().kernel();
        int cx = w.map.cannons().get(0).bx();
        int cy = w.map.cannons().get(0).by();
        boolean validBefore = k.valid();
        int[] changeTick = {-1};
        int[] phase = {0};
        rig.onTick = (r, t) -> {
            if (phase[0] == 0) {
                w.map.setBlock(cx, cy, BlockType.SPACE); // logged by the next tick's scan, sent in that tick
                r.link.down().profile().uniformLoss(1.0);
                changeTick[0] = r.server.net.currentTick() + 1;
                phase[0] = 1;
            } else if (phase[0] == 1) {
                r.link.down().profile().uniformLoss(0);
                phase[0] = 2;
            }
        };
        int[] gated = {0};
        int[] trustedStale = {0};
        int[] trustedFresh = {0};
        rig.onFrame = (r, t) -> {
            if (changeTick[0] < 0 || !k.have() || k.tick() - changeTick[0] < 0 || k.world() == null) {
                return;
            }
            boolean stale = k.world().map().block(cx, cy) != BlockType.SPACE;
            if (k.valid()) {
                if (stale) {
                    trustedStale[0]++;
                } else {
                    trustedFresh[0]++;
                }
            } else if (stale && !k.stateCurrent()) {
                gated[0]++;
            }
        };
        rig.runUntil(spawn + 5 * SEC);
        int fGated = gated[0];
        int fStale = trustedStale[0];
        int fFresh = trustedFresh[0];
        System.out.printf("[lost state] change at tick %d: %d frames gated, %d trusted stale, %d trusted fresh%n",
            changeTick[0], fGated, fStale, fFresh);
        assertAll(
            () -> assertTrue(validBefore, "PREDICT was valid before"),
            () -> assertTrue(fGated > 0, "frames with a PREDICT of the change's tick or later while its KERNEL_STATE was"
                + " in flight were not valid: " + fGated),
            () -> assertEquals(0, fStale, "no frame trusted the world without the change"),
            () -> assertTrue(fFresh > 0, "after the retransmit the world has the change and PREDICT is valid: " + fFresh),
            () -> assertEquals(BlockType.SPACE, k.world().map().block(cx, cy), "the client's world has the change"));
    }
}
