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
package com.fulcrologic.jxpilot.client.app;

/**
 * R13: the shortest interval between rendered frames. With vsync the swap normally paces the loop; when it
 * does not block (macOS stops blocking for an occluded window) or the window is iconified, the loop is capped
 * at {@value #CAP_FPS} fps instead of spinning. Without vsync (the user asked for speed) nothing is capped.
 * Pure.
 */
public final class FramePacer {
    /** Frame rate cap when the swap does not pace the loop. */
    public static final int CAP_FPS = 60;
    /** Frame interval of the cap. */
    public static final long CAP_NANOS = 1_000_000_000L / CAP_FPS;
    /** Floor while vsync paces (a safety net, 250 fps). */
    public static final long VSYNC_FLOOR_NANOS = 1_000_000_000L / 250;
    /** A swap faster than this did not wait for a vertical retrace. */
    public static final long FAST_SWAP_NANOS = 1_000_000L;
    /** Consecutive fast swaps after which vsync is taken as not blocking. */
    public static final int FAST_SWAPS = 30;

    private int fastSwaps;

    /**
     * The minimum interval before the next frame.
     * <p>
     * BEHAVIOR: without vsync 0; iconified or invisible {@link #CAP_NANOS}; with vsync, after
     * {@link #FAST_SWAPS} consecutive swaps shorter than {@link #FAST_SWAP_NANOS} {@link #CAP_NANOS}, else
     * {@link #VSYNC_FLOOR_NANOS}; one slow (blocking) swap resets the count.
     */
    public long minFrameNanos(boolean vsync, boolean hidden, long lastSwapNanos) {
        if (lastSwapNanos < FAST_SWAP_NANOS) {
            fastSwaps = Math.min(fastSwaps + 1, FAST_SWAPS);
        } else {
            fastSwaps = 0;
        }
        if (!vsync) {
            return 0;
        }
        if (hidden || fastSwaps >= FAST_SWAPS) {
            return CAP_NANOS;
        }
        return VSYNC_FLOOR_NANOS;
    }
}
