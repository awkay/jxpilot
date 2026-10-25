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

import com.fulcrologic.jxpilot.common.config.ConfigParser;
import com.fulcrologic.jxpilot.common.config.Opt;
import com.fulcrologic.jxpilot.common.config.OptionOrigin;
import com.fulcrologic.jxpilot.common.config.Options;
import com.fulcrologic.jxpilot.common.map.WorldMap;
import com.fulcrologic.jxpilot.common.net.Msg;
import com.fulcrologic.jxpilot.common.net.PacketHeader;
import com.fulcrologic.jxpilot.common.net.PacketType;
import com.fulcrologic.jxpilot.common.net.Protocol;
import com.fulcrologic.jxpilot.common.net.ReliableChannel;
import com.fulcrologic.jxpilot.common.net.ReliableHandler;
import com.fulcrologic.jxpilot.common.net.ReliableType;
import com.fulcrologic.jxpilot.common.net.SnapshotWriter;
import com.fulcrologic.jxpilot.common.sim.Obj;
import com.fulcrologic.jxpilot.server.sim.RandomPilot;
import com.fulcrologic.jxpilot.server.world.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The host's hot path (GameHost: input, tick, viewX/Y, fillSnapshot, drainEvents) allocates nothing in steady state:
 * one human and 8 robots on doggy.xp, driven as ServerNet drives it, the reliable channel drained and acknowledged
 * like a client. Runs in the allocation-tests surefire execution (escape analysis off).
 */
@DisplayName("SimGameHost hot path is allocation free")
class AllocationTest {
    private static final com.sun.management.ThreadMXBean MX =
        (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    // long enough for the C2 compiles of the once-per-tick item methods (a compile request shows up as a few
    // hundred thread-allocated bytes on the measured thread)
    static final int WARMUP = 20_000;
    static final int MEASURED = 5_000;

    /** What the client side of the reliable channel saw. */
    static final class Seen implements ReliableHandler {
        long kills;
        long messages;
        long joins;
        long kernelStates;
        long kernelEntries;

        @Override
        public void onReliableMessage(int type, ByteBuffer payload) {
            if (type == ReliableType.GAME_EVENT && payload.get(payload.position()) == Msg.GameEvent.KILL) {
                kills++;
            } else if (type == ReliableType.MESSAGE) {
                messages++;
            } else if (type == ReliableType.PLAYER_JOIN) {
                joins++;
            } else if (type == ReliableType.KERNEL_STATE) {
                kernelStates++;
                kernelEntries += Math.max(0, Msg.KernelState.count(payload));
            }
        }
    }

    static Options doggy() throws IOException {
        Options o = new Options();
        Path p = Path.of("..", "maps", "doggy.xp");
        if (!p.toFile().exists()) {
            p = Path.of("maps", "doggy.xp");
        }
        ConfigParser.parseFile(p, OptionOrigin.MAP, o);
        o.force(Opt.MAX_ROBOTS, "8");
        o.force(Opt.MIN_ROBOTS, "8");
        return o;
    }

    static ByteBuffer chat(String line) {
        ByteBuffer b = HostFx.chat(line);
        ByteBuffer d = ByteBuffer.allocateDirect(b.remaining());
        d.put(b).flip();
        return d;
    }

    /** One server tick of the host as ServerNet runs it, phase by phase, with per-phase allocation. */
    static final class Loop {
        final SimGameHost host;
        final World w;
        final int id;
        final RandomPilot pilot = new RandomPilot(5, 1);
        final ReliableChannel server = new ReliableChannel();
        final ReliableChannel client = new ReliableChannel();
        final ByteBuffer wire = ByteBuffer.allocateDirect(Protocol.MAX_PACKET);
        final ByteBuffer[] parts = new ByteBuffer[SimGameHost.SNAPSHOT_PARTS];
        final SnapshotWriter writer = new SnapshotWriter();
        final ByteBuffer[] chats = {chat("hello everyone"), chat("zzz:nobody here"), chat("3:team three"), chat("god:log")};
        final Seen seen = new Seen();
        final com.fulcrologic.jxpilot.common.net.SnapshotReader reader = new com.fulcrologic.jxpilot.common.net.SnapshotReader();
        /** PREDICT, PREDICT_RARE, PREDICT_KEYS sections in the snapshot parts written. */
        final long[] predictSections = new long[3];
        long now = 1_000_000_000L;
        int tick;
        int seq;
        long partsSent;
        long badTick = -1;
        String badPhase = "";
        long badBytes;

        Loop() throws IOException {
            Options o = doggy();
            // a no-op robot log: the real one is I/O (System.err) and allocates there, like the network log
            host = (SimGameHost) new SimGameHostFactory(() -> 7, line -> { }).create(o, WorldMap.fromOptions(o));
            w = host.simulation().world;
            host.attach((pid, why) -> host.leave(pid));
            for (int i = 0; i < parts.length; i++) {
                parts[i] = ByteBuffer.allocateDirect(Protocol.MAX_PACKET);
            }
            id = host.join("Pilot", Protocol.NO_TEAM, "");
            host.initialState(id, server);
            host.clientMessage(id, ReliableType.READY, ByteBuffer.allocate(0));
        }

        /** Measured ticks that allocated (counted without allocating). */
        int allocTicks;
        long lastAllocTick = -1;

        void record(String phase, long bytes, boolean measured) {
            if (measured && bytes != 0 && tick != lastAllocTick) {
                allocTicks++;
                lastAllocTick = tick;
            }
            if (measured && bytes != 0 && badTick < 0) {
                badTick = tick;
                badPhase = phase;
                badBytes = bytes;
            }
        }

        void step(boolean measured) {
            tick++;
            long a = MX.getCurrentThreadAllocatedBytes();
            if (measured && tick % 500 == 250) {
                // a cannon dies (its block is logged for KERNEL_STATE); it comes back cannonDeadTime later
                host.simulation().crashes.cannonDies((tick / 500) % w.map.cannons().size(), Obj.NO_ID);
            }
            if (measured && tick % 700 == 350) {
                // the human dies: after the respawn its key masks are cleared while the pilot holds keys, so
                // PREDICT_KEYS goes out
                w.byId(id).status |= Obj.KILLED;
            }
            record("scenario", MX.getCurrentThreadAllocatedBytes() - a, measured);
            a = MX.getCurrentThreadAllocatedBytes();
            host.input(id, ++seq, pilot.next()[0], 0);
            if (tick % 97 == 0) {
                ByteBuffer c = chats[(tick / 97) % chats.length];
                c.rewind();
                host.clientMessage(id, ReliableType.CHAT, c);
            }
            record("input/chat", MX.getCurrentThreadAllocatedBytes() - a, measured);
            a = MX.getCurrentThreadAllocatedBytes();
            host.tick(tick);
            record("tick", MX.getCurrentThreadAllocatedBytes() - a, measured);
            a = MX.getCurrentThreadAllocatedBytes();
            double vx = host.viewX(id);
            double vy = host.viewY(id);
            boolean radar = tick % 3 == 0;
            for (int part = 0; part < parts.length; part++) {
                ByteBuffer b = parts[part];
                b.clear();
                PacketHeader.write(b, PacketType.SNAPSHOT, 1, client.ack());
                writer.begin(b, tick, seq, part, 0, 0, vx, vy);
                partsSent++;
                boolean more = host.fillSnapshot(id, part, radar, writer);
                countInto(this, b);
                if (!more) {
                    break;
                }
            }
            record("view/snapshot", MX.getCurrentThreadAllocatedBytes() - a, measured);
            a = MX.getCurrentThreadAllocatedBytes();
            host.drainEvents(id, server);
            record("drainEvents", MX.getCurrentThreadAllocatedBytes() - a, measured);
            a = MX.getCurrentThreadAllocatedBytes();
            for (int i = 0; i < 64; i++) {
                wire.clear();
                if (server.write(wire, now) == 0) {
                    break;
                }
                wire.flip();
                client.read(wire, seen);
                server.onAck(client.ack(), now);
            }
            now += 1_000_000_000L / 14;
            record("reliable channel", MX.getCurrentThreadAllocatedBytes() - a, measured);
        }
    }

    /** Count the PREDICT sections of a written part (the reader is allocation free). */
    static void countInto(Loop l, ByteBuffer b) {
        int end = b.position();
        int limit = b.limit();
        b.limit(end).position(PacketHeader.SIZE);
        boolean ok = l.reader.begin(b);
        while (ok && l.reader.nextSection()) {
            if (l.reader.count() == 0) {
                continue;
            }
            switch (l.reader.type()) {
                case com.fulcrologic.jxpilot.common.net.Section.PREDICT -> l.predictSections[0]++;
                case com.fulcrologic.jxpilot.common.net.Section.PREDICT_RARE -> l.predictSections[1]++;
                case com.fulcrologic.jxpilot.common.net.Section.PREDICT_KEYS -> l.predictSections[2]++;
                default -> { }
            }
        }
        b.limit(limit).position(end);
    }

    @Test
    @DisplayName("one human (input every tick, chat now and then, killed now and then) and 8 robots on doggy.xp,"
        + " cannons destroyed: 0 bytes per tick, with every PREDICT section and KERNEL_STATE in the window")
    void hostHotPath() throws IOException {
        Loop loop = new Loop();
        for (int i = 0; i < WARMUP; i++) {
            loop.step(false);
        }
        // robots leave and are replaced ~28 ticks later; do not start measuring inside such a gap
        for (int i = 0; i < 200 && loop.host.simulation().robots.numRobots() < 8; i++) {
            loop.step(false);
        }
        int robots = loop.host.simulation().robots.numRobots();
        long kills0 = loop.seen.kills;
        long messages0 = loop.seen.messages;
        long parts0 = loop.partsSent;
        long[] predict0 = loop.predictSections.clone();
        long kstate0 = loop.seen.kernelStates;
        long kentries0 = loop.seen.kernelEntries;
        long before = MX.getCurrentThreadAllocatedBytes();
        for (int i = 0; i < MEASURED; i++) {
            loop.step(true);
        }
        long allocated = MX.getCurrentThreadAllocatedBytes() - before;
        long kills = loop.seen.kills - kills0;
        long messages = loop.seen.messages - messages0;
        long parts = loop.partsSent - parts0;
        long predicts = loop.predictSections[0] - predict0[0];
        long rares = loop.predictSections[1] - predict0[1];
        long keys = loop.predictSections[2] - predict0[2];
        long kstates = loop.seen.kernelStates - kstate0;
        long kentries = loop.seen.kernelEntries - kentries0;
        String firstBad = loop.badTick < 0 ? "none"
            : "tick " + loop.badTick + " phase " + loop.badPhase + ": " + loop.badBytes + " bytes";
        System.out.println("host allocation: " + allocated + " bytes over " + MEASURED + " ticks; robots " + robots
            + ", kills " + kills + ", messages " + messages + ", snapshot parts " + parts + "; PREDICT " + predicts
            + ", RARE " + rares + ", KEYS " + keys + "; KERNEL_STATE " + kstates + " (" + kentries + " entries)");
        assertAll(
            // one tick may allocate a little: a C2 compile request resolves constants and loads signature classes on
            // the requesting thread (see server.AllocationTest); anything more is a real allocation
            () -> assertTrue(loop.allocTicks <= 1 && allocated <= 4096, MEASURED + " measured ticks allocate nothing"
                + " beyond one one-off JIT request (" + loop.allocTicks + " ticks, " + allocated + " bytes; first: "
                + firstBad + ")"),
            () -> assertEquals(8, robots, "8 robots joined during the warm-up"),
            () -> assertTrue(kills > 0, "the robots fought: " + kills + " kills"),
            () -> assertTrue(messages > MEASURED / 97, "messages flowed (chat and game talk): " + messages),
            () -> assertTrue(parts >= MEASURED, "a snapshot every tick: " + parts + " parts"),
            () -> assertTrue(predicts > MEASURED / 2, "PREDICT was written in the window: " + predicts),
            () -> assertTrue(rares > 0, "PREDICT_RARE too: " + rares),
            () -> assertTrue(keys > 0, "and PREDICT_KEYS (keys differ after a respawn): " + keys),
            () -> assertTrue(kstates > 0 && kentries >= 2 * (MEASURED / 500),
                "KERNEL_STATE scanned, drained and sent in the window (the cannons' deaths and revivals): " + kstates
                    + " messages, " + kentries + " entries"),
            () -> assertEquals(0, loop.server.unacked(), "the client acknowledged everything"),
            () -> assertTrue(loop.w.byId(loop.id) != null && loop.w.byId(loop.id).spawned, "the human is still in the game"));
    }
}
