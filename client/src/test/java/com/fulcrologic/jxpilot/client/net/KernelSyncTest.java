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
        ReliableChannel ch = new ReliableChannel();
        Msg.KernelState w = new Msg.KernelState().begin(ch);
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
