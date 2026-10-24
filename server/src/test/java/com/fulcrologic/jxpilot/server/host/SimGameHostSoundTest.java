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

import com.fulcrologic.jxpilot.common.SoundEvent;
import com.fulcrologic.jxpilot.common.net.PacketHeader;
import com.fulcrologic.jxpilot.common.net.PacketType;
import com.fulcrologic.jxpilot.common.net.Protocol;
import com.fulcrologic.jxpilot.common.net.Records;
import com.fulcrologic.jxpilot.common.net.Section;
import com.fulcrologic.jxpilot.common.net.SnapshotReader;
import com.fulcrologic.jxpilot.common.net.SnapshotWriter;
import com.fulcrologic.jxpilot.server.sim.Simulation;
import com.fulcrologic.jxpilot.server.world.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static com.fulcrologic.jxpilot.server.host.HostFx.THREE;
import static com.fulcrologic.jxpilot.server.host.HostFx.enter;
import static com.fulcrologic.jxpilot.server.host.HostFx.sim;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("SimGameHost sounds (C sound_play_queued)")
class SimGameHostSoundTest {
    /** The SOUND records of a snapshot datagram, as "EVENT:volume". */
    static List<String> sounds(ByteBuffer datagram) {
        ByteBuffer b = datagram.duplicate();
        b.flip();
        b.position(PacketHeader.SIZE);
        SnapshotReader r = new SnapshotReader();
        Records.Sound s = new Records.Sound();
        List<String> out = new ArrayList<>();
        r.begin(b);
        while (r.nextSection()) {
            if (r.type() == Section.SOUND) {
                for (int i = 0; i < r.count(); i++) {
                    s.wrap(r, i);
                    out.add(SoundEvent.of(s.event()) + ":" + s.volume());
                }
            }
        }
        return out;
    }

    static ByteBuffer fill(SimGameHost h, int viewer) {
        SnapshotWriter wr = new SnapshotWriter();
        ByteBuffer b = ByteBuffer.allocate(Protocol.MAX_PACKET);
        PacketHeader.write(b, PacketType.SNAPSHOT, 77, 0);
        wr.begin(b, 5, 0, 0, 0, 0, 100, 100);
        h.fillSnapshot(viewer, 0, false, wr);
        return b;
    }

    @Test
    @DisplayName("part 0 sends the viewer's queued sounds once, then the queue is empty")
    void sentOnce() {
        Simulation s = sim("", THREE);
        SimGameHost h = new SimGameHost(s);
        int ann = enter(h, "Ann", 0);
        int bob = enter(h, "Bob", 0);
        Player a = s.world.byId(ann);
        a.audio.queue(SoundEvent.CANNON_FIRE.ordinal(), 37);
        ByteBuffer first = fill(h, ann);
        int left = a.audio.count();
        ByteBuffer second = fill(h, ann);
        ByteBuffer bobs = fill(h, bob);
        assertAll(
            () -> assertEquals(List.of("START:100", "CANNON_FIRE:37"), sounds(first),
                "the first snapshot carries START (both joins, deduplicated) and the queued cannon fire"),
            () -> assertEquals(0, left, "sending empties the viewer's queue"),
            () -> assertEquals(List.of(), sounds(second), "the next snapshot repeats nothing"),
            () -> assertEquals(List.of("START:100"), sounds(bobs), "each viewer's queue is its own"));
    }
}
