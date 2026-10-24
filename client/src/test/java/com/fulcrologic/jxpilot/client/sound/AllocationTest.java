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
package com.fulcrologic.jxpilot.client.sound;

import com.fulcrologic.jxpilot.client.game.SoundQueue;
import com.fulcrologic.jxpilot.common.SoundEvent;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Playing sound events allocates nothing: the queue, the player's voice choice, loops, mute and the per-frame
 * step, over the shipped sound map. Runs in the allocation-test surefire execution (escape analysis off).
 */
class AllocationTest {
    static final int WARMUP = 50_000;
    static final int FRAMES = 20_000;

    @Test
    void playingSoundsAllocatesZeroBytes() throws Exception {
        com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        SoundMap map = SoundMap.parse(SoundSystem.text(SoundSystem.MAP));
        FakeDevice dev = new FakeDevice(SoundPlayer.VOICES);
        dev.record = false;
        int[] handles = new int[map.clipCount()];
        SoundPlayer p = new SoundPlayer(dev, map, handles);
        SoundQueue q = new SoundQueue(256);
        long[] now = {0};
        Runnable frame = () -> {
            now[0] += 16_000_000L;
            long n = now[0] / 16_000_000L;
            for (int e = 0; e < 6; e++) {
                q.add((int) ((n * 7 + e * 13) % SoundEvent.COUNT), 10 + (int) (n % 91));
            }
            if (n % 3 != 0) {
                q.add(SoundEvent.THRUST.ordinal(), 60);
            }
            if (n % 1000 == 0) {
                p.toggleMuted();
            }
            for (int v = 0; v < dev.voices(); v++) {
                if ((n + v) % 9 == 0) {
                    dev.finish(v);
                }
            }
            p.frame(q, 14, 1 / 14.0, now[0]);
        };
        for (int i = 0; i < WARMUP; i++) {
            frame.run();
        }
        long before = mx.getCurrentThreadAllocatedBytes();
        for (int i = 0; i < FRAMES; i++) {
            frame.run();
        }
        long allocated = mx.getCurrentThreadAllocatedBytes() - before;
        assertAll(
            () -> assertTrue(q.total() > (long) (WARMUP + FRAMES) * 6, "(sounds were played)"),
            () -> assertEquals(0, allocated, FRAMES + " frames of ~7 sounds allocate nothing"));
    }
}
