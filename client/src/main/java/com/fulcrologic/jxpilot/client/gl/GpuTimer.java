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
package com.fulcrologic.jxpilot.client.gl;

import com.fulcrologic.jxpilot.client.render.FrameStats;

import static org.lwjgl.opengl.GL33C.*;

/**
 * GPU frame time from {@code GL_TIME_ELAPSED} queries in a small ring, read back {@code RING} frames
 * late (only if available) so measuring never stalls the pipeline. Uses the array overloads: LWJGL's
 * single-value query getters allocate a buffer wrapper per call.
 */
public final class GpuTimer {
    private static final int RING = 4;
    private final int[] queries = new int[RING];
    private final boolean[] pending = new boolean[RING];
    private final long[] result = new long[1];
    private final int[] available = new int[1];
    private int slot;

    public GpuTimer() {
        glGenQueries(queries);
    }

    /**
     * Start timing this frame. The slot being reused holds the query from {@code RING} frames ago:
     * its result is collected into {@code stats} if available, otherwise dropped (never waited for).
     */
    public void begin(FrameStats stats) {
        if (pending[slot]) {
            glGetQueryObjectiv(queries[slot], GL_QUERY_RESULT_AVAILABLE, available);
            if (available[0] == GL_TRUE) {
                glGetQueryObjecti64v(queries[slot], GL_QUERY_RESULT, result);
                stats.recordGpu(result[0]);
            }
            pending[slot] = false;
        }
        glBeginQuery(GL_TIME_ELAPSED, queries[slot]);
    }

    /** End timing this frame. */
    public void end() {
        glEndQuery(GL_TIME_ELAPSED);
        pending[slot] = true;
        slot = (slot + 1) % RING;
    }

    /** Forget queries in flight (e.g. at the end of a warm-up), so earlier frames are not counted. */
    public void discardPending() {
        java.util.Arrays.fill(pending, false);
    }

    public void dispose() {
        glDeleteQueries(queries);
    }
}
