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

import com.fulcrologic.jxpilot.common.net.Msg;
import com.fulcrologic.jxpilot.common.net.PacketHeader;
import com.fulcrologic.jxpilot.common.net.PacketType;
import com.fulcrologic.jxpilot.common.net.Protocol;
import com.fulcrologic.jxpilot.common.net.Records;
import com.fulcrologic.jxpilot.common.net.ReliableType;
import com.fulcrologic.jxpilot.common.net.Section;
import com.fulcrologic.jxpilot.common.net.SnapshotReader;
import com.fulcrologic.jxpilot.common.net.SnapshotWriter;
import com.fulcrologic.jxpilot.server.sim.Simulation;
import com.fulcrologic.jxpilot.common.sim.Obj;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static com.fulcrologic.jxpilot.server.host.HostFx.THREE;
import static com.fulcrologic.jxpilot.server.host.HostFx.empty;
import static com.fulcrologic.jxpilot.server.host.HostFx.enter;
import static com.fulcrologic.jxpilot.server.host.HostFx.sim;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** SimGameHost and the client's view size: DISPLAY (C {@code Receive_display}) and per-viewer culling. */
@DisplayName("SimGameHost: DISPLAY and the viewer's view size")
class SimGameHostDisplayTest {
    /** Three bases 12 blocks (420 px) apart on one row: bx 2, 14, 26 (1050 px wide); Ann, Bob, Cid get 26, 14, 2. */
    static final String[] WIDE = {
        "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
        "x                            x",
        "x _           _           _  x",
        "x                            x",
        "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
    };

    static ByteBuffer display(int width, int height) {
        Msg.Display d = new Msg.Display();
        d.viewWidth = width;
        d.viewHeight = height;
        ByteBuffer b = ByteBuffer.allocate(8);
        d.write(b);
        return b.flip();
    }

    static String size(Player p) {
        return p.viewWidth + "x" + p.viewHeight;
    }

    @Nested
    @DisplayName("clientMessage: DISPLAY (Receive_display)")
    class Receive {
        @Test
        @DisplayName("each axis is limited to [384, 1024]; values inside are kept; before READY too; a new login starts at 768x768")
        void limits() {
            Simulation s = sim("", THREE);
            World w = s.world;
            SimGameHost h = new SimGameHost(s);
            int ann = h.join("Ann", 0, "");
            Player p = w.byId(ann);
            String fresh = size(p);
            h.clientMessage(ann, ReliableType.DISPLAY, display(500, 700));
            String reserved = size(p);
            h.clientMessage(ann, ReliableType.READY, empty());
            String spawned = size(p);
            h.clientMessage(ann, ReliableType.DISPLAY, display(383, 1025));
            String outside = size(p);
            h.clientMessage(ann, ReliableType.DISPLAY, display(384, 1024));
            String bounds = size(p);
            h.clientMessage(ann, ReliableType.DISPLAY, display(1025, 383));
            String swapped = size(p);
            h.clientMessage(ann, ReliableType.DISPLAY, display(0, 65535));
            String extremes = size(p);
            h.leave(ann);
            int again = h.join("Bob", 0, "");
            String reused = size(w.byId(again));
            assertAll(
                () -> assertEquals("768x768", fresh, "a login starts with C DEF_VIEW_SIZE"),
                () -> assertEquals("500x700", reserved, "DISPLAY before READY is applied (C login_receive)"),
                () -> assertEquals("500x700", spawned, "and kept at the spawn"),
                () -> assertEquals("384x1024", outside, "383 is raised to 384, 1025 cut to 1024"),
                () -> assertEquals("384x1024", bounds, "the limits themselves are kept"),
                () -> assertEquals("1024x384", swapped, "each axis is limited on its own"),
                () -> assertEquals("384x1024", extremes, "0 and 65535 are limited too"),
                () -> assertEquals(ann, again, "Bob reuses Ann's id (and slot)"),
                () -> assertEquals("768x768", reused, "Bob starts at 768x768, not Ann's size"));
        }
    }

    @Nested
    @DisplayName("fillSnapshot culls with the viewer's view size")
    class Culling {
        /** Ids of the SHIP records the host writes for {@code viewer}, and the SELF id. */
        static List<Integer> ships(SimGameHost h, int viewer) {
            SnapshotWriter wr = new SnapshotWriter();
            ByteBuffer b = ByteBuffer.allocate(Protocol.MAX_PACKET);
            PacketHeader.write(b, PacketType.SNAPSHOT, 77, 0);
            wr.begin(b, 5, 0, 0, 0, 0, 100, 100);
            h.fillSnapshot(viewer, 0, false, wr);
            ByteBuffer in = b.duplicate().flip().position(PacketHeader.SIZE);
            SnapshotReader r = new SnapshotReader();
            r.setWorld(1050, 175, false);
            r.begin(in);
            List<Integer> ids = new ArrayList<>();
            Records.Ship ship = new Records.Ship();
            Records.Self self = new Records.Self();
            while (r.nextSection()) {
                for (int i = 0; i < r.count(); i++) {
                    if (r.type() == Section.SHIP) {
                        ship.wrap(r, i);
                        ids.add(ship.id());
                    } else if (r.type() == Section.SELF) {
                        self.wrap(r, i);
                        ids.add(-self.id());
                    }
                }
            }
            return ids;
        }

        @Test
        @DisplayName("a ship 420 px away is sent iff the viewer's half width + 128 reaches it; through another's eyes the viewer's size counts")
        void perViewer() {
            Simulation s = sim("", WIDE);
            World w = s.world;
            SimGameHost h = new SimGameHost(s);
            int ann = enter(h, "Ann", 0);
            int bob = enter(h, "Bob", 0);
            int cid = enter(h, "Cid", 0);
            h.tick(1);
            Player a = w.byId(ann);
            Player b = w.byId(bob);
            Player c = w.byId(cid);
            List<Integer> byDefault = ships(h, ann);
            h.clientMessage(ann, ReliableType.DISPLAY, display(384, 1024));
            List<Integer> narrow = ships(h, ann);
            h.clientMessage(ann, ReliableType.DISPLAY, display(1024, 384));
            List<Integer> wide = ships(h, ann);
            h.clientMessage(cid, ReliableType.DISPLAY, display(1024, 1024));
            List<Integer> cidWide = ships(h, cid);
            h.clientMessage(ann, ReliableType.DISPLAY, display(384, 384));
            a.status |= Obj.GAME_OVER;
            a.lockTagged |= Player.LOCK_PLAYER;
            a.lockPlId = cid;
            List<Integer> throughCid = ships(h, ann);
            h.clientMessage(ann, ReliableType.DISPLAY, display(1024, 384));
            List<Integer> throughCidWide = ships(h, ann);
            assertAll(
                () -> assertEquals(420, Math.abs(b.px - a.px), "Bob is 420 px from Ann"),
                () -> assertEquals(420, Math.abs(c.px - b.px), "Cid is 420 px from Bob"),
                () -> assertEquals(840, Math.abs(c.px - a.px), "Cid is 840 px from Ann (never in Ann's view)"),
                () -> assertEquals(List.of(-ann, bob), byDefault, "768 wide (half 384 + 128 = 512): Bob is sent"),
                () -> assertEquals(List.of(-ann), narrow, "384 wide (half 192 + 128 = 320): Bob is culled; the height does not widen x"),
                () -> assertEquals(List.of(-ann, bob), wide, "1024 wide: Bob is sent again; the height does not narrow x"),
                () -> assertEquals(List.of(-cid, bob), cidWide, "Cid's own view (1024) sends Bob to Cid"),
                () -> assertEquals(List.of(-cid), throughCid, "Ann (384) looking through Cid's eyes: Cid's position, Ann's size, so no Bob"),
                () -> assertEquals(List.of(-cid, bob), throughCidWide, "Ann at 1024 through Cid's eyes: Bob is sent"));
        }

        /** C Frame_status's lock distance in the SELF record {@code viewer} receives (0 = not shown). */
        static int lockDist(SimGameHost h, int viewer) {
            SnapshotWriter wr = new SnapshotWriter();
            ByteBuffer b = ByteBuffer.allocate(Protocol.MAX_PACKET);
            PacketHeader.write(b, PacketType.SNAPSHOT, 77, 0);
            wr.begin(b, 5, 0, 0, 0, 0, 100, 100);
            h.fillSnapshot(viewer, 0, false, wr);
            ByteBuffer in = b.duplicate().flip().position(PacketHeader.SIZE);
            SnapshotReader r = new SnapshotReader();
            r.setWorld(1050, 175, false);
            r.begin(in);
            Records.Self self = new Records.Self();
            while (r.nextSection()) {
                if (r.type() == Section.SELF) {
                    return self.wrap(r, 0).lockDist();
                }
            }
            return -1;
        }

        @Test
        @DisplayName("the HUD lock is shown iff the target is in the receiving client's view (C Frame_status per frame)")
        void hudLock() {
            Simulation s = sim("playersOnRadar: no\n", WIDE);
            World w = s.world;
            SimGameHost h = new SimGameHost(s);
            int ann = enter(h, "Ann", 0);
            int bob = enter(h, "Bob", 0);
            h.tick(1);
            Player a = w.byId(ann);
            a.used |= com.fulcrologic.jxpilot.common.sim.Has.COMPASS;
            a.lockTagged |= Player.LOCK_PLAYER;
            a.lockPlId = bob;
            h.tick(1);
            int byDefault = lockDist(h, ann);
            h.clientMessage(ann, ReliableType.DISPLAY, display(1024, 384));
            int wide = lockDist(h, ann);
            assertAll(
                () -> assertEquals(0, byDefault, "768 wide: Bob, 420 px away, is outside C's view (-384, 384): no lock"),
                () -> assertEquals(420, wide, "1024 wide: inside (-512, 512): the lock is shown at 420 px"));
        }
    }
}
