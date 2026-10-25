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
package com.fulcrologic.jxpilot.server.host;

import com.fulcrologic.jxpilot.common.map.Cannon;
import com.fulcrologic.jxpilot.common.map.Target;
import com.fulcrologic.jxpilot.common.map.WorldMap;
import com.fulcrologic.jxpilot.common.net.Msg;
import com.fulcrologic.jxpilot.common.net.ReliableChannel;
import com.fulcrologic.jxpilot.server.world.World;

import java.util.Arrays;

/**
 * The run-time state the ship kernel reads that the map file does not give (protocol v2, KERNEL_STATE): cannon
 * phasing, target teams, the temporary wormholes, and the blocks that change (cannons and targets destroyed and
 * revived, temporary wormholes opened and closed). Scanned once per tick against shadows into a change log
 * ({@link #RING} entries, each with the net tick of its scan); each connection sends the entries after its cursor,
 * one message per tick, and a newcomer (or a connection the log ran past) first gets everything that differs from
 * the map as loaded. A temporary wormhole's block is tracked while it differs from the map and dropped once its
 * restore is logged. Allocation free once the tracking arrays hold the most temporary wormholes open at once.
 */
final class KernelState {
    /** Change log entries kept. */
    static final int RING = 4096;

    private final World w;
    private final WorldMap map;
    private final Msg.KernelState writer = new Msg.KernelState();
    private final int[] kind = new int[RING];
    private final int[] ea = new int[RING];
    private final int[] eb = new int[RING];
    private final int[] ec = new int[RING];
    private final int[] ed = new int[RING];
    private final int[] etick = new int[RING];
    private long seq;

    private final boolean[] phased;
    private final int[] team;
    private final int[] team0;
    private int wormCount;
    private int[] wBx = new int[64];
    private int[] wBy = new int[64];
    private int[] wType = new int[64];
    /** Block indices that can change, with their last scanned and pristine block and item id. */
    private int[] idx;
    private byte[] block;
    private int[] item;
    private byte[] block0;
    private int[] item0;
    private int tracked;
    /** Tracked entries below this are the cannons' and targets' blocks (kept for good). */
    private final int fixed;
    private final byte[] pristineBlocks;
    private final int[] pristineItems;

    KernelState(World w) {
        this.w = w;
        this.map = w.map;
        phased = new boolean[map.cannons().size()];
        team = new int[map.targets().size()];
        team0 = new int[team.length];
        for (int i = 0; i < team.length; i++) {
            team[i] = w.targetTeam(i);
            team0[i] = team[i];
        }
        wormCount = w.worms.count();
        int cap = map.cannons().size() + map.targets().size() + 64;
        idx = new int[cap];
        block = new byte[cap];
        item = new int[cap];
        block0 = new byte[cap];
        item0 = new int[cap];
        pristineBlocks = map.blocks().clone();
        pristineItems = new int[pristineBlocks.length];
        for (int by = 0; by < map.height(); by++) {
            for (int bx = 0; bx < map.width(); bx++) {
                pristineItems[map.index(bx, by)] = map.itemId(bx, by);
            }
        }
        for (Cannon c : map.cannons()) {
            track(map.index(c.bx(), c.by()));
        }
        for (Target t : map.targets()) {
            track(map.index(t.bx(), t.by()));
        }
        fixed = tracked;
    }

    /** Track block index {@code i} unless it is already. */
    private void track(int i) {
        for (int k = 0; k < tracked; k++) {
            if (idx[k] == i) {
                return;
            }
        }
        if (tracked == idx.length) {
            int cap = 2 * tracked;
            idx = Arrays.copyOf(idx, cap);
            block = Arrays.copyOf(block, cap);
            item = Arrays.copyOf(item, cap);
            block0 = Arrays.copyOf(block0, cap);
            item0 = Arrays.copyOf(item0, cap);
        }
        idx[tracked] = i;
        block0[tracked] = pristineBlocks[i];
        item0[tracked] = pristineItems[i];
        block[tracked] = pristineBlocks[i];
        item[tracked] = pristineItems[i];
        tracked++;
    }

    private void log(int k, int a, int b, int c, int d) {
        int s = (int) (seq & (RING - 1));
        etick[s] = w.tick;
        kind[s] = k;
        ea[s] = a;
        eb[s] = b;
        ec[s] = c;
        ed[s] = d;
        seq++;
    }

    /**
     * Log what changed since the last scan.
     * <p>
     * BEHAVIOR: a cannon's phasing, a target's team, the wormhole count, a temporary wormhole's block and type, and
     * a tracked block's type or item id (cannon, target and open temporary wormhole positions) that differ from the
     * shadow are logged, with {@link World#tick}, and the shadow updated. A temporary wormhole's block that is back
     * to the map's is no longer tracked.
     */
    void scan() {
        for (int i = 0; i < phased.length; i++) {
            boolean p = w.cannonPhased(i);
            if (p != phased[i]) {
                phased[i] = p;
                log(Msg.KernelState.CANNON, i, 0, p ? 1 : 0, 0);
            }
        }
        for (int i = 0; i < team.length; i++) {
            int t = w.targetTeam(i);
            if (t != team[i]) {
                team[i] = t;
                log(Msg.KernelState.TARGET, i, 0, 0, t);
            }
        }
        int n = w.worms.count();
        if (n != wormCount) {
            wormCount = n;
            log(Msg.KernelState.WORM_COUNT, 0, 0, 0, n);
        }
        for (int i = w.worms.mapCount; i < n; i++) {
            int k = i - w.worms.mapCount;
            if (k >= wBx.length) {
                wBx = Arrays.copyOf(wBx, 2 * k);
                wBy = Arrays.copyOf(wBy, 2 * k);
                wType = Arrays.copyOf(wType, 2 * k);
            }
            int bx = w.worms.bx(i);
            int by = w.worms.by(i);
            int ty = w.worms.type(i);
            if (bx != wBx[k] || by != wBy[k] || ty != wType[k]) {
                wBx[k] = bx;
                wBy[k] = by;
                wType[k] = ty;
                log(Msg.KernelState.WORM, i, bx, by, ty);
            }
            track(map.index(bx, by));
        }
        for (int k = 0; k < tracked; k++) {
            int i = idx[k];
            int bx = i % map.width();
            int by = i / map.width();
            byte b = map.block(bx, by);
            int it = map.itemId(bx, by);
            if (b != block[k] || it != item[k]) {
                block[k] = b;
                item[k] = it;
                log(Msg.KernelState.BLOCK, bx, by, b & 0xFF, it);
            }
            if (k >= fixed && b == block0[k] && it == item0[k]) {
                untrack(k--);
            }
        }
    }

    /** Drop tracked entry {@code k} (the last takes its place). */
    private void untrack(int k) {
        tracked--;
        idx[k] = idx[tracked];
        block[k] = block[tracked];
        item[k] = item[tracked];
        block0[k] = block0[tracked];
        item0[k] = item0[tracked];
    }

    /** Blocks tracked now (the cannons', the targets' and the open temporary wormholes'). */
    int tracked() {
        return tracked;
    }

    /** The next sequence number. */
    long seq() {
        return seq;
    }

    /** Whether the log no longer holds the entries after {@code cursor}. */
    boolean behind(long cursor) {
        return seq - cursor > RING;
    }

    /**
     * Send the log entries from {@code cursor} in KERNEL_STATE messages (as the sim's events: the channel queues
     * what its window cannot hold yet).
     * <p>
     * BEHAVIOR: one message per scan tick (split when full), tagged with that tick; returns the cursor after the
     * entries sent ({@link #seq}).
     */
    long drain(ReliableChannel ch, long cursor) {
        long c = cursor;
        while (c < seq) {
            int t = etick[(int) (c & (RING - 1))];
            Msg.KernelState m = writer.begin(ch, t);
            while (c < seq && m.room() && etick[(int) (c & (RING - 1))] == t) {
                int s = (int) (c & (RING - 1));
                m.add(kind[s], ea[s], eb[s], ec[s], ed[s]);
                c++;
            }
            m.end(ch);
        }
        return c;
    }

    /**
     * Everything that differs from the map as loaded (what a client's kernel world starts from), as KERNEL_STATE
     * messages: phased cannons, targets off their initial team, the wormhole count and every temporary wormhole,
     * and every tracked block that differs from the map's.
     * <p>
     * BEHAVIOR: with {@code reset} (a resync of a client that has state) a RESET entry comes first. The messages
     * are tagged with {@link World#tick}; there is always at least one (empty for a newcomer whose world is the map
     * as loaded), so the client knows the tick its state holds from.
     */
    void initial(ReliableChannel ch, boolean reset) {
        Msg.KernelState m = writer.begin(ch, w.tick);
        if (reset) {
            m.add(Msg.KernelState.RESET, 0, 0, 0, 0);
        }
        for (int i = 0; i < phased.length; i++) {
            if (w.cannonPhased(i)) {
                m = put(ch, m, Msg.KernelState.CANNON, i, 0, 1, 0);
            }
        }
        for (int i = 0; i < team.length; i++) {
            if (w.targetTeam(i) != team0[i]) {
                m = put(ch, m, Msg.KernelState.TARGET, i, 0, 0, w.targetTeam(i));
            }
        }
        int n = w.worms.count();
        if (n != w.worms.mapCount) {
            m = put(ch, m, Msg.KernelState.WORM_COUNT, 0, 0, 0, n);
        }
        for (int i = w.worms.mapCount; i < n; i++) {
            m = put(ch, m, Msg.KernelState.WORM, i, w.worms.bx(i), w.worms.by(i), w.worms.type(i));
        }
        for (int k = 0; k < tracked; k++) {
            int i = idx[k];
            int bx = i % map.width();
            int by = i / map.width();
            byte b = map.block(bx, by);
            int it = map.itemId(bx, by);
            if (b != block0[k] || it != item0[k]) {
                m = put(ch, m, Msg.KernelState.BLOCK, bx, by, b & 0xFF, it);
            }
        }
        m.end(ch, true);
    }

    private Msg.KernelState put(ReliableChannel ch, Msg.KernelState m, int k, int a, int b, int c, int d) {
        if (!m.room()) {
            m.end(ch);
            m = writer.begin(ch, w.tick);
        }
        m.add(k, a, b, c, d);
        return m;
    }
}
