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

        @Override
        public void onReliableMessage(int type, ByteBuffer payload) {
            if (type == ReliableType.GAME_EVENT && payload.get(payload.position()) == Msg.GameEvent.KILL) {
                kills++;
            } else if (type == ReliableType.MESSAGE) {
                messages++;
            } else if (type == ReliableType.PLAYER_JOIN) {
                joins++;
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
        long now = 1_000_000_000L;
        int tick;
        int seq;
        long partsSent;
        long badTick = -1;
        String badPhase = "";
        long badBytes;

        Loop() throws IOException {
            Options o = doggy();
            host = (SimGameHost) new SimGameHostFactory(() -> 7).create(o, WorldMap.fromOptions(o));
            w = host.simulation().world;
            host.attach((pid, why) -> host.leave(pid));
            for (int i = 0; i < parts.length; i++) {
                parts[i] = ByteBuffer.allocateDirect(Protocol.MAX_PACKET);
            }
            id = host.join("Pilot", Protocol.NO_TEAM, "");
            host.initialState(id, server);
            host.clientMessage(id, ReliableType.READY, ByteBuffer.allocate(0));
        }

        void record(String phase, long bytes, boolean measured) {
            if (measured && bytes != 0 && badTick < 0) {
                badTick = tick;
                badPhase = phase;
                badBytes = bytes;
            }
        }

        void step(boolean measured) {
            tick++;
            long a = MX.getCurrentThreadAllocatedBytes();
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
                if (!host.fillSnapshot(id, part, radar, writer)) {
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

    @Test
    @DisplayName("one human (input every tick, chat now and then) and 8 robots on doggy.xp: 0 bytes per tick")
    void hostHotPath() throws IOException {
        Loop loop = new Loop();
        for (int i = 0; i < WARMUP; i++) {
            loop.step(false);
        }
        int robots = loop.host.simulation().robots.numRobots();
        long kills0 = loop.seen.kills;
        long messages0 = loop.seen.messages;
        long parts0 = loop.partsSent;
        long before = MX.getCurrentThreadAllocatedBytes();
        for (int i = 0; i < MEASURED; i++) {
            loop.step(true);
        }
        long allocated = MX.getCurrentThreadAllocatedBytes() - before;
        long kills = loop.seen.kills - kills0;
        long messages = loop.seen.messages - messages0;
        long parts = loop.partsSent - parts0;
        String firstBad = loop.badTick < 0 ? "none"
            : "tick " + loop.badTick + " phase " + loop.badPhase + ": " + loop.badBytes + " bytes";
        System.out.println("host allocation: " + allocated + " bytes over " + MEASURED + " ticks; robots " + robots
            + ", kills " + kills + ", messages " + messages + ", snapshot parts " + parts);
        assertAll(
            () -> assertEquals(0, allocated, MEASURED + " measured ticks allocate nothing (first: " + firstBad + ")"),
            () -> assertEquals(8, robots, "8 robots joined during the warm-up"),
            () -> assertTrue(kills > 0, "the robots fought: " + kills + " kills"),
            () -> assertTrue(messages > MEASURED / 97, "messages flowed (chat and game talk): " + messages),
            () -> assertTrue(parts >= MEASURED, "a snapshot every tick: " + parts + " parts"),
            () -> assertEquals(0, loop.server.unacked(), "the client acknowledged everything"),
            () -> assertTrue(loop.w.byId(loop.id) != null && loop.w.byId(loop.id).spawned, "the human is still in the game"));
    }
}
