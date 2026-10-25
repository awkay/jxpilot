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
package com.fulcrologic.jxpilot.client.net;

import com.fulcrologic.jxpilot.common.config.KernelConfig;
import com.fulcrologic.jxpilot.common.config.Options;
import com.fulcrologic.jxpilot.common.net.ConfigTransfer;
import com.fulcrologic.jxpilot.common.net.InputHistory;
import com.fulcrologic.jxpilot.common.net.Msg;
import com.fulcrologic.jxpilot.common.net.Records;
import com.fulcrologic.jxpilot.common.net.ReliableType;
import com.fulcrologic.jxpilot.common.net.Section;
import com.fulcrologic.jxpilot.common.net.SnapshotReader;
import com.fulcrologic.jxpilot.common.sim.KernelWorld;
import com.fulcrologic.jxpilot.common.sim.ShipBody;
import com.fulcrologic.jxpilot.common.sim.ShipGeom;
import com.fulcrologic.jxpilot.common.sim.ShipParams;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * What the own-ship predictor (phase 1c) starts from, as the server sends it to the owner (protocol v2):
 * <ul>
 *   <li>the {@link ShipBody} from the PREDICT sections of the newest snapshot, with the C frame clock and
 *   roundDelay it was taken at (the key flags refer to the client's own input history);</li>
 *   <li>the {@link KernelWorld} and {@link ShipParams} built exactly as the server has them, from the KERNEL_CONFIG
 *   blob (the kernel options and the map blocks the client's map hides, {@link KernelConfig}) over the client's
 *   map; rebuilt when an operator changes a kernel option;</li>
 *   <li>the run-time kernel state from KERNEL_STATE (cannon phasing, target teams, temporary wormholes, changed
 *   blocks), kept as a mirror and applied to the world (again after each rebuild);</li>
 *   <li>the own ship's hull, from the PLAYER_JOIN of its own id.</li>
 * </ul>
 * Fed by {@link ClientConnection}. The snapshot path is allocation free; the reliable messages are rare and may
 * allocate (a world rebuild does).
 */
public final class KernelSync {
    private final SnapshotReader reader = new SnapshotReader();
    private final Records.Predict predict = new Records.Predict();
    private final Records.PredictRare rare = new Records.PredictRare();
    private final Records.PredictKeys keys = new Records.PredictKeys();
    private final Records.PredictRound round = new Records.PredictRound();
    /** The newest snapshot tick seen and which of its parts were read. */
    private int newestTick;
    private boolean haveTick;
    private long partsSeen;
    private final ShipBody body = new ShipBody();
    private boolean have;
    private int tick;
    private long frames;
    private int frameOffset;
    private int roundDelay;
    private boolean roundKnown;
    private int coreVersion;
    private int coreKernel;
    private int rareVersion = -1;
    private boolean keysKnown;
    private long predicts;
    private long keyMismatches;

    // ---- kernel world
    private String clientMap;
    private ConfigTransfer.Receiver kernelRx;
    private int kernelRxVersion;
    private int kernelVersion;
    private Options kernelOptions;
    private KernelWorld world;
    private long rebuilds;
    private long badKernel;
    // ---- the KERNEL_STATE mirror
    private boolean[] phased = new boolean[0];
    private boolean[] phasedSet = new boolean[0];
    private int[] team = new int[0];
    private boolean[] teamSet = new boolean[0];
    private int wormCount = -1;
    private int[] wormIndex = new int[16];
    private int[] wormBx = new int[16];
    private int[] wormBy = new int[16];
    private int[] wormType = new int[16];
    private int worms;
    private int[] blockAt = new int[16];
    private int[] blockType = new int[16];
    private int[] blockItem = new int[16];
    private int blocks;
    private int stateTick;
    private boolean stateKnown;
    private ShipGeom hull = ShipGeom.defaultShip();
    private final Msg.PlayerJoin join = new Msg.PlayerJoin();

    /** Forget the ship, the kernel world and its state (a new session). */
    public void reset() {
        have = false;
        rareVersion = -1;
        keysKnown = false;
        predicts = 0;
        keyMismatches = 0;
        clientMap = null;
        haveTick = false;
        kernelRx = null;
        kernelVersion = 0;
        kernelOptions = null;
        world = null;
        clearState();
        stateKnown = false;
        hull = ShipGeom.defaultShip();
        body.ship = hull;
    }

    private void clearState() {
        Arrays.fill(phasedSet, false);
        Arrays.fill(teamSet, false);
        wormCount = -1;
        worms = 0;
        blocks = 0;
    }

    /** The client's map text (the config's mapData, with the blocks the server hides): the base of the kernel map. */
    public void onConfig(Options clientOptions) {
        onConfig(clientOptions, com.fulcrologic.jxpilot.common.map.WorldMap.fromOptions(clientOptions));
    }

    /** As {@link #onConfig(Options)} with the map already built (its size and wrap for view-relative records). */
    public void onConfig(Options clientOptions, com.fulcrologic.jxpilot.common.map.WorldMap map) {
        clientMap = clientOptions.mapData();
        reader.setWorld(map.pixelWidth(), map.pixelHeight(), map.edgeWrap());
    }

    /**
     * A reliable message for the kernel.
     * <p>
     * BEHAVIOR: KERNEL_CONFIG starts receiving a blob of its version (an older unfinished one is dropped); its
     * KERNEL_CHUNKs complete it, and the world is rebuilt from it ({@link KernelConfig#decode}, then
     * {@code KernelWorld.of}) with the mirrored state applied. KERNEL_STATE updates the mirror and the world.
     * PLAYER_JOIN of {@code ownId} sets the hull (its shape, or the default ship); the payload's position is left
     * where it was (the game reads it too). Other types are ignored; a
     * malformed blob or message counts in {@link #badKernel()} and changes nothing.
     */
    public void onReliable(int type, ByteBuffer payload, int ownId) {
        try {
            switch (type) {
                case ReliableType.KERNEL_CONFIG -> {
                    Msg.KernelConfigBegin m = new Msg.KernelConfigBegin();
                    m.read(payload);
                    kernelRx = new ConfigTransfer.Receiver(m.length, m.compressedLength, m.crc32);
                    kernelRxVersion = m.version;
                    if (kernelRx.complete()) {
                        finishKernel();
                    }
                }
                case ReliableType.KERNEL_CHUNK -> {
                    if (kernelRx != null && kernelRx.onChunk(payload)) {
                        finishKernel();
                    }
                }
                case ReliableType.KERNEL_STATE -> onState(payload);
                case ReliableType.PLAYER_JOIN -> {
                    Msg.PlayerJoin j = join;
                    int at = payload.position();
                    j.read(payload);
                    payload.position(at);
                    if (j.id == ownId) {
                        hull = j.shape.length() == 0 ? ShipGeom.defaultShip() : ShipGeom.parse(j.shape);
                        body.ship = hull;
                    }
                }
                default -> { }
            }
        } catch (RuntimeException e) {
            badKernel++;
            if (type == ReliableType.KERNEL_CONFIG || type == ReliableType.KERNEL_CHUNK) {
                kernelRx = null;
            }
        }
    }

    private void finishKernel() {
        byte[] raw = kernelRx.bytes();
        kernelRx = null;
        kernelOptions = KernelConfig.decode(raw, clientMap);
        world = KernelWorld.of(kernelOptions);
        kernelVersion = kernelRxVersion;
        rebuilds++;
        applyState();
    }

    private void onState(ByteBuffer p) {
        int n = Msg.KernelState.count(p);
        if (n < 0) {
            badKernel++;
            return;
        }
        stateTick = Msg.KernelState.tick(p);
        stateKnown = true;
        for (int i = 0; i < n; i++) {
            int k = Msg.KernelState.kind(p, i);
            int a = Msg.KernelState.a(p, i);
            int b = Msg.KernelState.b(p, i);
            int c = Msg.KernelState.c(p, i);
            int d = Msg.KernelState.d(p, i);
            switch (k) {
                case Msg.KernelState.RESET -> {
                    clearState();
                    if (kernelOptions != null) {
                        world = KernelWorld.of(kernelOptions);
                        rebuilds++;
                    }
                }
                case Msg.KernelState.CANNON -> {
                    ensureCannons(a + 1);
                    phased[a] = c != 0;
                    phasedSet[a] = true;
                    if (world != null && a < world.cannonPhased.length) {
                        world.cannonPhased[a] = c != 0;
                    }
                }
                case Msg.KernelState.TARGET -> {
                    ensureTargets(a + 1);
                    team[a] = d;
                    teamSet[a] = true;
                    if (world != null && a < world.targetTeam.length) {
                        world.targetTeam[a] = d;
                    }
                }
                case Msg.KernelState.WORM_COUNT -> {
                    wormCount = d;
                    if (world != null) {
                        world.setWormholeCount(d);
                    }
                }
                case Msg.KernelState.WORM -> {
                    putWorm(a, b, c, d);
                    if (world != null) {
                        world.setTempWormhole(a, b, c, d);
                    }
                }
                case Msg.KernelState.BLOCK -> {
                    putBlock(a, b, c, d);
                    if (world != null) {
                        setBlock(a, b, c, d);
                    }
                }
                default -> { }
            }
        }
    }

    private void ensureCannons(int n) {
        if (n > phased.length) {
            phased = Arrays.copyOf(phased, Math.max(n, 2 * phased.length));
            phasedSet = Arrays.copyOf(phasedSet, phased.length);
        }
    }

    private void ensureTargets(int n) {
        if (n > team.length) {
            team = Arrays.copyOf(team, Math.max(n, 2 * team.length));
            teamSet = Arrays.copyOf(teamSet, team.length);
        }
    }

    private void putWorm(int i, int bx, int by, int type) {
        int k = 0;
        while (k < worms && wormIndex[k] != i) {
            k++;
        }
        if (k == worms) {
            if (worms == wormIndex.length) {
                int cap = 2 * worms;
                wormIndex = Arrays.copyOf(wormIndex, cap);
                wormBx = Arrays.copyOf(wormBx, cap);
                wormBy = Arrays.copyOf(wormBy, cap);
                wormType = Arrays.copyOf(wormType, cap);
            }
            worms++;
        }
        wormIndex[k] = i;
        wormBx[k] = bx;
        wormBy[k] = by;
        wormType[k] = type;
    }

    private void putBlock(int bx, int by, int type, int item) {
        int at = by << 16 | bx;
        int k = 0;
        while (k < blocks && blockAt[k] != at) {
            k++;
        }
        if (k == blocks) {
            if (blocks == blockAt.length) {
                int cap = 2 * blocks;
                blockAt = Arrays.copyOf(blockAt, cap);
                blockType = Arrays.copyOf(blockType, cap);
                blockItem = Arrays.copyOf(blockItem, cap);
            }
            blocks++;
        }
        blockAt[k] = at;
        blockType[k] = type;
        blockItem[k] = item;
    }

    private void setBlock(int bx, int by, int type, int item) {
        if (bx < world.map().width() && by < world.map().height()) {
            world.map().setBlock(bx, by, (byte) type);
            world.map().setItemId(bx, by, item);
        }
    }

    /** The mirror onto a freshly built world. */
    private void applyState() {
        for (int i = 0; i < Math.min(phased.length, world.cannonPhased.length); i++) {
            if (phasedSet[i]) {
                world.cannonPhased[i] = phased[i];
            }
        }
        for (int i = 0; i < Math.min(team.length, world.targetTeam.length); i++) {
            if (teamSet[i]) {
                world.targetTeam[i] = team[i];
            }
        }
        if (wormCount >= 0) {
            world.setWormholeCount(wormCount);
        }
        for (int k = 0; k < worms; k++) {
            world.setTempWormhole(wormIndex[k], wormBx[k], wormBy[k], wormType[k]);
        }
        for (int k = 0; k < blocks; k++) {
            setBlock(blockAt[k] & 0xFFFF, blockAt[k] >>> 16, blockType[k], blockItem[k]);
        }
    }

    /** The kernel world as the server has it (null until the first KERNEL_CONFIG blob completes). */
    public KernelWorld world() { return world; }

    /** The kernel parameters (null with no world). */
    public ShipParams params() { return world == null ? null : world.shipParams(); }

    /** The kernel options the world was built from (null with no world). */
    public Options kernelOptions() { return kernelOptions; }

    /** The version of the kernel config the world was built from (0: none). */
    public int kernelVersion() { return kernelVersion; }

    /**
     * The net tick of the newest KERNEL_STATE message: the mirrored state (and the world's) is the server's as of the
     * snapshot of that tick. Messages come on the reliable channel, so they can arrive before or after that
     * snapshot; a predictor starting from snapshot S has the world as of S when {@code stateTick() - S <= 0} and no
     * newer message is due. Meaningful only when {@link #stateKnown()}.
     */
    public int stateTick() { return stateTick; }

    /** Whether a KERNEL_STATE message (the server sends one with the initial state) has arrived since the reset. */
    public boolean stateKnown() { return stateKnown; }

    /** Worlds built since the reset (the first, each KERNEL_CONFIG after it, each RESET). */
    public long rebuilds() { return rebuilds; }

    /** Malformed kernel blobs and messages. */
    public long badKernel() { return badKernel; }

    /** The own ship's hull. */
    public ShipGeom hull() { return hull; }

    /**
     * Read the PREDICT sections of a snapshot part of the newest tick (the packet body at the buffer's position;
     * nothing is moved).
     * <p>
     * BEHAVIOR: every part of the newest tick is read (the PREDICT sections may be in any of them, and part 1 may
     * arrive before part 0); a part of an older tick, or one already read, is ignored.
     * <p>
     * BEHAVIOR: PREDICT decodes the core block into {@link #body}; its key flags refer to the client's own entry for
     * the header's inputTick ({@code input}); when that entry is no longer (or not yet) in the history, or its hash
     * differs from the one the server applied (counted in {@link #keyMismatches()}), the keys are unknown unless a
     * PREDICT_KEYS section carries them. PREDICT_RARE decodes the rare block and records its version and frame
     * offset; the C frame clock is the snapshot tick plus that offset (the rare block may come after the core in the
     * part). PREDICT_ROUND carries roundDelay when the core says it is nonzero; without it in the part, roundDelay is
     * unknown. A snapshot without PREDICT changes nothing.
     */
    public void onSnapshot(ByteBuffer in, InputHistory input) {
        if (!reader.begin(in)) {
            return;
        }
        int t = reader.tick();
        if (haveTick && t - newestTick < 0) {
            return;
        }
        if (!haveTick || t != newestTick) {
            haveTick = true;
            newestTick = t;
            partsSeen = 0;
        }
        long bit = 1L << (reader.part() & 63);
        if ((partsSeen & bit) != 0) {
            return;
        }
        partsSeen |= bit;
        int inputTick = reader.inputTick();
        boolean known = input.has(inputTick);
        long applied = known ? input.keys(inputTick) : 0;
        boolean got = false;
        boolean keysSent = false;
        int roundValue = -1;
        while (reader.nextSection()) {
            if (reader.count() == 0) {
                continue;
            }
            switch (reader.type()) {
                case Section.PREDICT -> {
                    predict.wrap(reader, 0);
                    got = true;
                }
                case Section.PREDICT_ROUND -> roundValue = round.wrap(reader, 0).roundDelay();
                case Section.PREDICT_RARE -> {
                    Records.PredictRare r = rare.wrap(reader, 0);
                    r.read(body);
                    rareVersion = r.version();
                    frameOffset = r.frameOffset();
                }
                case Section.PREDICT_KEYS -> {
                    keys.wrap(reader, 0);
                    keysSent = true;
                }
                default -> { }
            }
        }
        if (got) {
            Records.Predict p = predict;
            boolean match = known && Records.Predict.hash(applied) == p.appliedHash();
            if (known && !match) {
                keyMismatches++;
            }
            tick = t;
            frames = (t + (long) frameOffset) & 0xFFFF_FFFFL;
            p.read(body, frames, applied);
            if (keysSent) {
                keys.read(body); // the masks the flags could not give
            }
            coreVersion = p.rareVersion();
            coreKernel = p.kernelVersion();
            roundKnown = !p.roundDelaySet() || roundValue > 0;
            roundDelay = p.roundDelaySet() ? Math.max(roundValue, 0) : 0;
            have = true;
            keysKnown = match || keysSent;
            predicts++;
        }
    }

    /** The own ship as of {@link #tick()} (valid only when {@link #valid()}). */
    public ShipBody body() { return body; }

    /**
     * Whether a PREDICT has arrived, its rare block is the version it refers to, its key masks and roundDelay are
     * known, and the kernel world is built from the KERNEL_CONFIG version it was simulated with.
     */
    public boolean valid() {
        return have && rareVersion == coreVersion && keysKnown && roundKnown && world != null
            && (kernelVersion & 0xFF) == coreKernel;
    }

    /** PREDICTs whose applied-input hash differed from the client's own entry for that tick. */
    public long keyMismatches() { return keyMismatches; }

    /** Whether any PREDICT has arrived since the reset. */
    public boolean have() { return have; }

    /** The snapshot tick of the newest PREDICT. */
    public int tick() { return tick; }

    /** The C frame clock at that tick (ShipKernel's {@code frames()}, not the net tick): the tick plus the rare block's frame offset. */
    public long frames() { return frames; }

    public int roundDelay() { return roundDelay; }

    /** PREDICT sections read since the reset. */
    public long predicts() { return predicts; }
}
