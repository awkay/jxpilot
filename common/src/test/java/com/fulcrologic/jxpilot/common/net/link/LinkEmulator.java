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
package com.fulcrologic.jxpilot.common.net.link;

import java.util.function.LongSupplier;

/**
 * An emulated lossy link between one client and the server (docs/latency-fixes.md §7): an {@link #up() uplink}
 * (client to server) and a {@link #down() downlink}, each with its own {@link LinkProfile} and random stream,
 * and the clock both read. The decorators over the client {@code Transport} and the server {@code Datagrams}
 * (in those modules' tests) and {@code tools/lagproxy} drive it. Seeded and reproducible; allocation free
 * after construction.
 */
public final class LinkEmulator {
    /** Default packets in flight per direction. */
    public static final int DEFAULT_SLOTS = 1024;
    /** Default largest datagram (the protocol's are at most 1200 bytes; this leaves room for UDP in general). */
    public static final int DEFAULT_SLOT_BYTES = 2048;

    private final LongSupplier clock;
    private final LinkDirection up;
    private final LinkDirection down;

    /** BEHAVIOR: both directions with {@link #DEFAULT_SLOTS} slots of {@link #DEFAULT_SLOT_BYTES} bytes. */
    public LinkEmulator(LongSupplier clock, LinkProfile up, LinkProfile down, long seed) {
        this(clock, up, down, seed, DEFAULT_SLOTS, DEFAULT_SLOT_BYTES);
    }

    /**
     * BEHAVIOR: the two directions draw from different streams derived from {@code seed} ({@link #upSeed},
     * {@link #downSeed}), so the same seed reproduces both and neither direction's traffic shifts the other's
     * draws.
     */
    public LinkEmulator(LongSupplier clock, LinkProfile up, LinkProfile down, long seed, int slots, int slotBytes) {
        this.clock = clock;
        this.up = new LinkDirection(up, upSeed(seed), slots, slotBytes);
        this.down = new LinkDirection(down, downSeed(seed), slots, slotBytes);
    }

    /** BEHAVIOR: the uplink's seed: the first SplitMix64 output of {@code seed}. */
    public static long upSeed(long seed) {
        return new LinkRandom(seed).nextLong();
    }

    /** BEHAVIOR: the downlink's seed: the second SplitMix64 output of {@code seed}. */
    public static long downSeed(long seed) {
        LinkRandom r = new LinkRandom(seed);
        r.nextLong();
        return r.nextLong();
    }

    /** Client to server. */
    public LinkDirection up() {
        return up;
    }

    /** Server to client. */
    public LinkDirection down() {
        return down;
    }

    /** The current (virtual or real) time in nanoseconds. */
    public long now() {
        return clock.getAsLong();
    }

    /** BEHAVIOR: the earlier of the two directions' next deliveries ({@link Long#MAX_VALUE} when both are empty). */
    public long nextDue() {
        return Math.min(up.nextDue(), down.nextDue());
    }
}
