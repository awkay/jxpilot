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

import com.fulcrologic.jxpilot.common.config.ConfigParser;
import com.fulcrologic.jxpilot.common.config.KernelConfig;
import com.fulcrologic.jxpilot.common.config.OptionOrigin;
import com.fulcrologic.jxpilot.common.config.Options;
import com.fulcrologic.jxpilot.common.map.BlockType;
import com.fulcrologic.jxpilot.common.net.ConfigTransfer;
import com.fulcrologic.jxpilot.common.net.Msg;
import com.fulcrologic.jxpilot.common.net.ReliableChannel;
import com.fulcrologic.jxpilot.common.net.ReliableType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BEHAVIOR (KernelSync, reliable side): the world is built when a KERNEL_CONFIG blob completes, from the kernel
 * options over the client's map; a newer KERNEL_CONFIG replaces an unfinished older one and rebuilds; KERNEL_STATE
 * entries update the world and are re-applied after every rebuild; RESET forgets them; PLAYER_JOIN of the own id
 * sets the hull without moving the payload; malformed input is counted and changes nothing; reset forgets all.
 */
@DisplayName("KernelSync")
class KernelSyncTest {
    static final String MAP = "mapWidth: 12\nmapHeight: 5\nteamPlay: yes\nmapData: \\multiline: E\n"
        + "xxxxxxxxxxxx\nx  ++  r   x\nx 1 ! @ 1  x\nx          x\nxxxxxxxxxxxx\nE\n";

    static Options server() {
        Options o = new Options();
        ConfigParser.parse(MAP, "t", OptionOrigin.MAP, o);
        return o;
    }

    static String clientMap(Options o) {
        return o.mapData().replace('+', ' ').replace('@', ' ');
    }

    /** The reliable messages announcing and carrying the kernel blob of {@code o} as {@code version}. */
    static List<ByteBuffer[]> blob(Options o, int version) {
        ConfigTransfer.Blob b = ConfigTransfer.encode(KernelConfig.encode(o, clientMap(o)));
        ReliableChannel ch = new ReliableChannel();
        Msg.KernelConfigBegin.send(ch, version, b);
        new ConfigTransfer.Sender(b, ReliableType.KERNEL_CHUNK).pump(ch, 0);
        return messages(ch);
    }

    /** Every message queued on {@code ch}, as (type, payload) pairs. */
    static List<ByteBuffer[]> messages(ReliableChannel ch) {
        ReliableChannel in = new ReliableChannel();
        ByteBuffer buf = ByteBuffer.allocate(4096);
        List<ByteBuffer[]> out = new ArrayList<>();
        long now = 1;
        for (int i = 0; i < 1000; i++) {
            buf.clear();
            if (ch.write(buf, now) == 0) {
                break;
            }
            buf.flip();
            in.read(buf, (type, p) -> {
                ByteBuffer copy = ByteBuffer.allocate(p.remaining());
                copy.put(p.duplicate()).flip();
                out.add(new ByteBuffer[] {ByteBuffer.allocate(4).putInt(0, type), copy});
            });
            ch.onAck(in.ack(), ++now);
        }
        return out;
    }

    static void deliver(KernelSync k, List<ByteBuffer[]> msgs) {
        for (ByteBuffer[] m : msgs) {
            k.onReliable(m[0].getInt(0), m[1].duplicate(), 7);
        }
    }

    static List<ByteBuffer[]> state(int... entries) {
        return stateAt(0, entries);
    }

    /** A KERNEL_STATE message of {@code entries} (5 ints each) tagged with {@code tick}. */
    static List<ByteBuffer[]> stateAt(int tick, int... entries) {
        ReliableChannel ch = new ReliableChannel();
        Msg.KernelState w = new Msg.KernelState().begin(ch, tick);
        for (int i = 0; i < entries.length; i += 5) {
            w.add(entries[i], entries[i + 1], entries[i + 2], entries[i + 3], entries[i + 4]);
        }
        w.end(ch);
        return messages(ch);
    }

    @Test
    @DisplayName("the world is built from the blob over the client's map; state entries apply and survive rebuilds")
    void worldAndState() {
        Options o = server();
        KernelSync k = new KernelSync();
        Options client = new Options();
        ConfigParser.parse(o.toConfigText(clientMap(o)), "c", OptionOrigin.MAP, client);
        k.onConfig(client);
        com.fulcrologic.jxpilot.common.map.WorldMap m = com.fulcrologic.jxpilot.common.map.WorldMap.fromOptions(o);
        int cx = m.cannons().get(0).bx();
        int cy = m.cannons().get(0).by();
        List<ByteBuffer[]> b1 = blob(o, 1);
        deliver(k, state(Msg.KernelState.CANNON, 0, 0, 1, 0, Msg.KernelState.TARGET, 0, 0, 0, 2,
            Msg.KernelState.BLOCK, cx, cy, BlockType.SPACE & 0xFF, 0));
        boolean before = k.world() == null;
        deliver(k, b1);
        boolean phased = k.world().cannonPhased[0];
        int team = k.world().targetTeam[0];
        byte block = k.world().map().block(cx, cy);
        int gravity = k.world().map().gravitySources().size();
        int clientGravity = com.fulcrologic.jxpilot.common.map.WorldMap.fromOptions(client).gravitySources().size();
        int afterFirst = (int) k.rebuilds();
        o.force(com.fulcrologic.jxpilot.common.config.Opt.GRAVITY, "-0.8");
        List<ByteBuffer[]> b2 = blob(o, 2);
        deliver(k, b2.subList(0, 1));
        deliver(k, blob(o, 3));
        deliver(k, b2.subList(1, b2.size()));
        boolean stillPhased = k.world().cannonPhased[0];
        int version = k.kernelVersion();
        deliver(k, state(Msg.KernelState.RESET, 0, 0, 0, 0));
        boolean reset = k.world().cannonPhased[0];
        byte restored = k.world().map().block(cx, cy);
        assertAll(
            () -> assertTrue(before, "no world before the blob"),
            () -> assertTrue(phased, "the cannon is phased (state before the blob)"),
            () -> assertEquals(2, team, "the target's team"),
            () -> assertEquals(BlockType.SPACE, block, "the cannon's block is space"),
            () -> assertEquals(2, gravity, "the two hidden gravity blocks are back in the kernel map"),
            () -> assertEquals(0, clientGravity, "the client's own map has none"),
            () -> assertEquals(1, afterFirst, "built once"),
            () -> assertEquals(3, version, "version 3 replaced the unfinished 2; 2's late chunks do nothing"),
            () -> assertEquals(-0.8, k.kernelOptions().getDouble(com.fulcrologic.jxpilot.common.config.Opt.GRAVITY),
                1e-12, "the rebuilt world has the new gravity"),
            () -> assertTrue(stillPhased, "the mirrored state is applied to the rebuilt world"),
            () -> assertFalse(reset, "RESET forgets the state and rebuilds"),
            () -> assertEquals(BlockType.CANNON, restored, "the cannon is back"),
            () -> assertEquals(0, k.badKernel(), "nothing malformed"));
    }

    /** The frame clock runs this far ahead of the net tick in the PREDICT tests. */
    static final int OFFSET = 500;

    /** A snapshot payload (header + sections) with PREDICT, and optionally PREDICT_RARE; kernel version 1. */
    static ByteBuffer predict(int tick, int inputTick, com.fulcrologic.jxpilot.common.sim.ShipBody b, long applied,
                              int version, boolean rare) {
        return predict(tick, inputTick, b, applied, version, rare, 1, 7, true);
    }

    /**
     * A snapshot payload: PREDICT (the core at frame {@code tick + OFFSET}), PREDICT_ROUND when {@code roundDelay > 0}
     * and {@code withRound}, PREDICT_RARE after them when {@code rare}, PREDICT_KEYS when a mask differs from
     * {@code applied}.
     */
    static ByteBuffer predict(int tick, int inputTick, com.fulcrologic.jxpilot.common.sim.ShipBody b, long applied,
                              int version, boolean rare, int kernel, int roundDelay, boolean withRound) {
        ByteBuffer out = ByteBuffer.allocate(1200);
        com.fulcrologic.jxpilot.common.net.SnapshotWriter w = new com.fulcrologic.jxpilot.common.net.SnapshotWriter();
        w.begin(out, tick, inputTick, 0, 1, 1, 0, 0);
        w.beginSection(com.fulcrologic.jxpilot.common.net.Section.SHOT, 0);
        w.endSection();
        w.beginSection(com.fulcrologic.jxpilot.common.net.Section.PREDICT);
        com.fulcrologic.jxpilot.common.net.Records.Predict.put(w, b, OFFSET + tick, roundDelay, applied, version,
            kernel);
        w.endSection();
        if (roundDelay > 0 && withRound) {
            w.beginSection(com.fulcrologic.jxpilot.common.net.Section.PREDICT_ROUND);
            com.fulcrologic.jxpilot.common.net.Records.PredictRound.put(w, roundDelay);
            w.endSection();
        }
        if (rare) {
            w.beginSection(com.fulcrologic.jxpilot.common.net.Section.PREDICT_RARE);
            com.fulcrologic.jxpilot.common.net.Records.PredictRare.put(w, b, version, OFFSET);
            w.endSection();
        }
        if (b.keysDiffer(applied)) {
            w.beginSection(com.fulcrologic.jxpilot.common.net.Section.PREDICT_KEYS);
            com.fulcrologic.jxpilot.common.net.Records.PredictKeys.put(w, b);
            w.endSection();
        }
        out.flip();
        return out;
    }

    /** A KernelSync with the kernel world of {@link #server()} built from blob version {@code version}. */
    static KernelSync withWorld(int version) {
        Options o = server();
        KernelSync k = new KernelSync();
        Options client = new Options();
        ConfigParser.parse(o.toConfigText(clientMap(o)), "c", OptionOrigin.MAP, client);
        k.onConfig(client);
        deliver(k, blob(o, version));
        return k;
    }

    @Test
    @DisplayName("KERNEL_STATE ticks: the newest message's tick, an empty message included; unknown before one and"
        + " after a reset; a truncated header is malformed")
    void stateTicks() {
        KernelSync k = new KernelSync();
        boolean before = k.stateKnown();
        deliver(k, stateAt(40, Msg.KernelState.CANNON, 0, 0, 1, 0));
        int first = k.stateTick();
        boolean known = k.stateKnown();
        ReliableChannel ch = new ReliableChannel();
        new Msg.KernelState().begin(ch, 55).end(ch, true);
        deliver(k, messages(ch));
        int empty = k.stateTick();
        k.onReliable(ReliableType.KERNEL_STATE, ByteBuffer.allocate(4), 7);
        long bad = k.badKernel();
        int afterBad = k.stateTick();
        long stateCount = k.stateMessages();
        k.reset();
        assertAll(
            () -> assertFalse(before, "unknown before any message"),
            () -> assertEquals(40, first, "the message's tick"),
            () -> assertTrue(known, "known after one"),
            () -> assertEquals(55, empty, "an empty message (the initial state of a fresh world) still sets it"),
            () -> assertEquals(1, bad, "a 4-byte payload is shorter than the header: malformed"),
            () -> assertEquals(55, afterBad, "and changes nothing"),
            () -> assertEquals(2, stateCount, "two well-formed messages counted"),
            () -> assertFalse(k.stateKnown(), "a reset forgets it"),
            () -> assertEquals(0, k.stateMessages(), "and the count"));
    }

    /** Part {@code part} of tick {@code tick} (of 2), with PREDICT only when {@code withPredict}. */
    static ByteBuffer part(int tick, int part, com.fulcrologic.jxpilot.common.sim.ShipBody b, boolean withPredict) {
        ByteBuffer out = ByteBuffer.allocate(1200);
        com.fulcrologic.jxpilot.common.net.SnapshotWriter w = new com.fulcrologic.jxpilot.common.net.SnapshotWriter();
        w.begin(out, tick, 40, part, 2, 0, 0, 0);
        if (withPredict) {
            w.beginSection(com.fulcrologic.jxpilot.common.net.Section.PREDICT);
            com.fulcrologic.jxpilot.common.net.Records.Predict.put(w, b, OFFSET + tick, 0, 6, 3, 1);
            w.endSection();
            w.beginSection(com.fulcrologic.jxpilot.common.net.Section.PREDICT_RARE);
            com.fulcrologic.jxpilot.common.net.Records.PredictRare.put(w, b, 3, OFFSET);
            w.endSection();
        }
        w.beginSection(com.fulcrologic.jxpilot.common.net.Section.SHOT, 0);
        w.endSection();
        out.flip();
        return out;
    }

    @Test
    @DisplayName("parts: PREDICT is read from any part of the newest tick, the part arriving first or not; a part"
        + " already read and a part of an older tick are ignored")
    void parts() {
        KernelSync k = withWorld(1);
        com.fulcrologic.jxpilot.common.net.InputHistory in = new com.fulcrologic.jxpilot.common.net.InputHistory();
        in.set(40, 6, 0, 0);
        com.fulcrologic.jxpilot.common.sim.ShipBody b = new com.fulcrologic.jxpilot.common.sim.ShipBody();
        b.prevKeys = 6;
        b.lastReceivedKeys = 6;
        b.vx = 1;
        k.onSnapshot(part(50, 1, b, false), in);
        k.onSnapshot(part(50, 0, b, true), in);
        long afterPart0 = k.predicts();
        float vx50 = k.body().vx;
        b.vx = 2;
        k.onSnapshot(part(51, 1, b, true), in);
        long fromPart1 = k.predicts();
        int tick = k.tick();
        b.vx = 3;
        k.onSnapshot(part(51, 1, b, true), in);
        float afterRepeat = k.body().vx;
        k.onSnapshot(part(50, 0, b, true), in);
        assertAll(
            () -> assertEquals(1, afterPart0, "part 0 after part 1 of the same tick: its PREDICT is read"),
            () -> assertEquals(1f, vx50, "the body of tick 50"),
            () -> assertEquals(2, fromPart1, "PREDICT in part 1 of a newer tick is read"),
            () -> assertEquals(51, tick, "for tick 51"),
            () -> assertEquals(2f, afterRepeat, "the same part again is ignored"),
            () -> assertEquals(2, k.predicts(), "and so is a part of an older tick"),
            () -> assertEquals(51, k.tick(), "the tick stays 51"));
    }

    @Test
    @DisplayName("PREDICT: the body, frames and roundDelay; valid only with the rare version it names and known keys")
    void snapshots() {
        KernelSync k = withWorld(1);
        com.fulcrologic.jxpilot.common.net.InputHistory in = new com.fulcrologic.jxpilot.common.net.InputHistory();
        in.set(40, 6, 0, 0);
        com.fulcrologic.jxpilot.common.sim.ShipBody b = new com.fulcrologic.jxpilot.common.sim.ShipBody();
        b.setClicks(6400, 3200);
        b.vx = 2.5f;
        b.power = 33;
        b.prevKeys = 6;
        b.lastReceivedKeys = 6;
        b.shotTime = OFFSET + 42 - 5;
        boolean before = k.have();
        k.onSnapshot(predict(41, 40, b, 6, 3, false), in);
        boolean noRare = k.valid();
        k.onSnapshot(predict(42, 40, b, 6, 3, true), in);
        boolean withRare = k.valid();
        float vx = k.body().vx;
        float power = k.body().power;
        long keysFromFlag = k.body().prevKeys;
        long shotTime = k.body().shotTime;
        long frames42 = k.frames();
        k.onSnapshot(predict(43, 99, b, 6, 3, false), in);
        boolean unknownKeys = k.valid();
        b.lastReceivedKeys = 9;
        k.onSnapshot(predict(44, 99, b, 6, 3, false), in);
        boolean keysSent = k.valid();
        long received = k.body().lastReceivedKeys;
        ByteBuffer empty = ByteBuffer.allocate(64);
        new com.fulcrologic.jxpilot.common.net.SnapshotWriter().begin(empty, 45, 40, 0, 1, 1, 0, 0);
        empty.flip();
        k.onSnapshot(empty, in);
        ByteBuffer shortOne = ByteBuffer.allocate(4);
        k.onSnapshot(shortOne, in);
        assertAll(
            () -> assertFalse(before, "nothing before a PREDICT"),
            () -> assertFalse(noRare, "a core naming rare version 3 before that rare block: not valid"),
            () -> assertTrue(withRare, "with it: valid"),
            () -> assertEquals(2.5f, vx, "the body's velocity"),
            () -> assertEquals(33f, power, "the rare block's power"),
            () -> assertEquals(6L, keysFromFlag, "prevKeys from the flag: the client's entry for the inputTick"),
            () -> assertEquals(OFFSET + 42, frames42, "the frame clock: the tick plus the rare block's offset"),
            () -> assertEquals(OFFSET + 42 - 5, shotTime,
                "the shot time against that clock, though the rare block came after the core"),
            () -> assertFalse(unknownKeys, "an inputTick the history does not hold: the keys are unknown"),
            () -> assertTrue(keysSent, "unless PREDICT_KEYS carries them"),
            () -> assertEquals(9L, received, "lastReceivedKeys from PREDICT_KEYS"),
            () -> assertEquals(44, k.tick(), "a snapshot without PREDICT (or a short one) changes nothing"),
            () -> assertEquals(OFFSET + 44, k.frames(), "the frame clock of tick 44"),
            () -> assertEquals(7, k.roundDelay(), "roundDelay"),
            () -> assertEquals(4, k.predicts(), "four PREDICTs read"),
            () -> assertEquals(1, k.rareBlocks(), "one PREDICT_RARE"),
            () -> assertEquals(1, k.keyBlocks(), "one PREDICT_KEYS"),
            () -> assertTrue(k.have(), "have"));
    }

    @Test
    @DisplayName("PREDICT validity: the applied input's hash, the kernel version (mod 256), the world and roundDelay")
    void validity() {
        com.fulcrologic.jxpilot.common.net.InputHistory in = new com.fulcrologic.jxpilot.common.net.InputHistory();
        in.set(40, 6, 0, 0);
        com.fulcrologic.jxpilot.common.sim.ShipBody b = new com.fulcrologic.jxpilot.common.sim.ShipBody();
        b.prevKeys = 6;
        b.lastReceivedKeys = 6;
        KernelSync noWorld = new KernelSync();
        noWorld.onSnapshot(predict(41, 40, b, 6, 3, true), in);
        KernelSync k = withWorld(257);
        k.onSnapshot(predict(41, 40, b, 6, 3, true, 1, 0, false), in);
        boolean wrapped = k.valid();
        int roundOff = k.roundDelay();
        k.onSnapshot(predict(42, 40, b, 6, 3, true, 2, 0, false), in);
        boolean otherKernel = k.valid();
        com.fulcrologic.jxpilot.common.sim.ShipBody s = new com.fulcrologic.jxpilot.common.sim.ShipBody();
        s.prevKeys = 5;
        s.lastReceivedKeys = 5;
        k.onSnapshot(predict(43, 40, s, 5, 3, true, 1, 0, false), in);
        boolean mismatch = k.valid();
        long mismatches = k.keyMismatches();
        k.onSnapshot(predict(44, 40, b, 6, 3, true, 1, 9, false), in);
        boolean roundMissing = k.valid();
        k.onSnapshot(predict(45, 40, b, 6, 3, true, 1, 9, true), in);
        boolean roundSent = k.valid();
        int round = k.roundDelay();
        assertAll(
            () -> assertFalse(noWorld.valid(), "no kernel world yet: not valid"),
            () -> assertTrue(noWorld.have(), "though the PREDICT was read"),
            () -> assertTrue(wrapped, "kernel version 257 matches the PREDICT's 1 (mod 256)"),
            () -> assertEquals(0, roundOff, "no round-delay flag: roundDelay 0"),
            () -> assertFalse(otherKernel, "a PREDICT simulated with another kernel version: not valid"),
            () -> assertFalse(mismatch,
                "the server applied keys 5 where the client's entry has 6: the flagged masks are unknown"),
            () -> assertEquals(1, mismatches, "one hash mismatch counted"),
            () -> assertFalse(roundMissing, "the round-delay flag without PREDICT_ROUND in the part: not valid"),
            () -> assertTrue(roundSent, "with it: valid"),
            () -> assertEquals(9, round, "and roundDelay is its value"));
    }

    @Test
    @DisplayName("wormhole entries mirror into the world, before and after it is built; indices beyond the world"
        + " and empty messages are harmless; RESET's forgetting survives the next blob")
    void wormholesAndEdges() {
        Options o = server();
        KernelSync k = new KernelSync();
        k.onConfig(o);
        deliver(k, state(Msg.KernelState.WORM_COUNT, 0, 0, 0, 3, Msg.KernelState.WORM, 2, 4, 1, 2,
            Msg.KernelState.CANNON, 0, 0, 1, 0));
        deliver(k, blob(o, 1));
        int count = k.world().wormholeCount();
        int type = k.world().wormholeType(2);
        int bx = k.world().wormholeBx(2);
        com.fulcrologic.jxpilot.common.map.WorldMap m = k.world().map();
        int w = m.width();
        deliver(k, state(Msg.KernelState.WORM, 2, 5, 1, 1, Msg.KernelState.BLOCK, 4, 1, BlockType.FILLED & 0xFF, 9,
            Msg.KernelState.CANNON, k.world().cannonPhased.length, 0, 1, 0,
            Msg.KernelState.TARGET, k.world().targetTeam.length, 0, 0, 3,
            Msg.KernelState.BLOCK, w, 1, BlockType.FILLED & 0xFF, 0));
        int movedBx = k.world().wormholeBx(2);
        int item = k.world().map().itemId(4, 1);
        ReliableChannel ch = new ReliableChannel();
        Msg.KernelState writer = new Msg.KernelState().begin(ch, 0);
        writer.end(ch);
        ByteBuffer zero = ByteBuffer.allocate(Msg.KernelState.HEADER);
        k.onReliable(ReliableType.KERNEL_STATE, zero, 7);
        long bad = k.badKernel();
        deliver(k, state(Msg.KernelState.RESET, 0, 0, 0, 0));
        long rebuilt = k.rebuilds();
        deliver(k, blob(o, 2));
        boolean phasedAfter = k.world().cannonPhased[0];
        assertAll(
            () -> assertEquals(3, count, "the count from before the blob"),
            () -> assertEquals(2, type, "the temporary wormhole's type"),
            () -> assertEquals(4, bx, "its block"),
            () -> assertEquals(5, movedBx, "moved after the world exists"),
            () -> assertEquals(9, item, "a BLOCK entry's item id"),
            () -> assertEquals(0, bad, "an index beyond the world, a block outside the map and an empty message are no error"),
            () -> assertEquals(2, rebuilt, "RESET rebuilds the world"),
            () -> assertFalse(phasedAfter, "and what it forgot stays forgotten in the next blob's world"),
            () -> assertTrue(k.params() != null, "the parameters come with the world"),
            () -> assertNull(new KernelSync().params(), "none without a world"));
    }

    @Test
    @DisplayName("a failed PLAYER_JOIN in the middle of a kernel transfer does not abandon it")
    void joinFailureKeepsTransfer() {
        Options o = server();
        KernelSync k = new KernelSync();
        k.onConfig(o);
        List<ByteBuffer[]> b = blob(o, 1);
        deliver(k, b.subList(0, 1));
        k.onReliable(ReliableType.PLAYER_JOIN, ByteBuffer.allocate(1), 7);
        deliver(k, b.subList(1, b.size()));
        assertAll(
            () -> assertEquals(1, k.badKernel(), "the truncated join is counted"),
            () -> assertTrue(k.world() != null, "the blob still completes"));
    }

    @Test
    @DisplayName("PLAYER_JOIN of the own id sets the hull, others do not; the payload is left where it was")
    void hull() {
        KernelSync k = new KernelSync();
        Msg.PlayerJoin other = new Msg.PlayerJoin();
        other.id = 3;
        other.shape.append("(SH: 10,0 -5,5 -5,-5)");
        ByteBuffer po = ByteBuffer.allocate(512);
        other.write(po);
        po.flip();
        k.onReliable(ReliableType.PLAYER_JOIN, po, 7);
        boolean defaultKept = k.hull() == com.fulcrologic.jxpilot.common.sim.ShipGeom.defaultShip();
        Msg.PlayerJoin self = new Msg.PlayerJoin();
        self.id = 7;
        self.shape.append("(SH: 15,0 -8,8 -8,-8)(EN: -8,0)(MG: 15,0)");
        ByteBuffer ps = ByteBuffer.allocate(512);
        self.write(ps);
        ps.flip();
        k.onReliable(ReliableType.PLAYER_JOIN, ps, 7);
        assertAll(
            () -> assertTrue(defaultKept, "another player's join leaves the default hull"),
            () -> assertEquals(0, ps.position(), "the payload position is restored"),
            () -> assertFalse(k.hull() == com.fulcrologic.jxpilot.common.sim.ShipGeom.defaultShip(), "the own join sets it"),
            () -> assertSame(k.hull(), k.body().ship, "on the predicted body too"));
    }

    @Test
    @DisplayName("a corrupt blob, a truncated state message or an orphan chunk is counted and changes nothing; reset")
    void malformed() {
        KernelSync k = new KernelSync();
        k.onConfig(server());
        List<ByteBuffer[]> b = blob(server(), 1);
        ByteBuffer bad = ByteBuffer.allocate(b.get(1)[1].remaining());
        bad.put(b.get(1)[1].duplicate()).flip();
        bad.put(6, (byte) (bad.get(6) ^ 0x55));
        k.onReliable(ReliableType.KERNEL_CONFIG, b.get(0)[1].duplicate(), 7);
        k.onReliable(ReliableType.KERNEL_CHUNK, bad, 7);
        long afterBad = k.badKernel();
        k.onReliable(ReliableType.KERNEL_CHUNK, b.get(1)[1].duplicate(), 7);
        ByteBuffer cut = ByteBuffer.allocate(5).put((byte) 3);
        cut.flip();
        k.onReliable(ReliableType.KERNEL_STATE, cut, 7);
        k.onReliable(ReliableType.KERNEL_STATE, ByteBuffer.allocate(0), 7);
        deliver(k, b);
        boolean built = k.world() != null;
        k.reset();
        assertAll(
            () -> assertEquals(1, afterBad, "the corrupt blob is counted"),
            () -> assertEquals(3, k.badKernel() + 0, "two truncated state messages too (the orphan chunk is ignored)"),
            () -> assertTrue(built, "a good blob afterwards still builds"),
            () -> assertNull(k.world(), "reset forgets the world"),
            () -> assertEquals(0, k.kernelVersion(), "and the version"));
    }
}
